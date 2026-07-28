package com.nader.screenfinder.scan

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nader.screenfinder.data.Db
import com.nader.screenfinder.data.Shot
import com.nader.screenfinder.data.ShotDao
import com.nader.screenfinder.data.UserRule
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** how many screenshots are processed at the same time */
    private val lanes = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
    private val gate = Semaphore(lanes)

    override suspend fun doWork(): Result {
        val c = applicationContext
        if (c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val dao = Db.get(c).dao()
        note("בודק שינויים...")
        Scanner.diff(c, dao)

        val total = dao.countAll()
        val done = AtomicInteger(dao.countScanned())

        // ---- pass 1: quick sweep over everything, newest first ----
        val rules = dao.rules()
        while (true) {
            val batch = dao.unscanned(4 * lanes)
            if (batch.isEmpty()) break
            runParallel(batch) { s -> fastPass(c, dao, s, rules) }
            report(c, "סריקה מהירה: ${done.addAndGet(batch.size)} מתוך $total")
        }

        heal(c, dao)

        // ---- pass 2: deep understanding, in the background ----
        val deep = AtomicInteger(dao.countDeep())
        while (true) {
            val batch = dao.needDeep(2 * lanes)
            if (batch.isEmpty()) break
            runParallel(batch) { s -> deepPass(c, dao, s) }
            report(c, "הבנה מעמיקה: ${deep.addAndGet(batch.size)} מתוך $total")
        }

        try {
            Learner.run(dao)
        } catch (e: Exception) {
        }
        try {
            note("מארגן קבוצות...")
            Grouper.run(dao)
        } catch (e: Exception) {
        }
        autoOrganize(dao)
        brainPass(c, dao)
        return Result.success()
    }

    private suspend fun runParallel(batch: List<Shot>, block: suspend (Shot) -> Unit) = coroutineScope {
        batch.map { s ->
            async {
                try {
                    gate.withPermit { block(s) }
                } catch (e: Throwable) {
                }
            }
        }.forEach { it.await() }
    }

    /** latin OCR only - about 0.1s per screenshot */
    private suspend fun fastPass(c: Context, dao: ShotDao, s: Shot, rules: List<UserRule>) {
        val bmp = Meter.time(Meter.decode) { Scanner.load(c, s.id, 1200) }
        if (bmp == null) {
            dao.update(s.copy(scanned = true, deepDone = true))
            return
        }
        val text = try {
            Meter.time(Meter.latin) { Ocr.latin(bmp) }
        } finally {
            bmp.recycle()
        }
        val (cat, src) = Categorizer.categorize(s.sourceApp, text, emptyList(), rules)
        Meter.time(Meter.db) {
            dao.update(
                s.copy(
                    text = text,
                    norm = Ocr.norm(text),
                    category = s.userCat ?: cat,
                    source = src,
                    scanned = true
                )
            )
        }
    }

    /** hebrew/arabic OCR when needed + visual fingerprint */
    private suspend fun deepPass(c: Context, dao: ShotDao, s: Shot) {
        var text = s.text ?: ""
        val needHeavy = !s.heavyOcr && text.length < Ocr.LATIN_THRESHOLD
        // when only the visual model is left there is no reason to decode a big bitmap
        val bmp = Meter.time(Meter.decode) { Scanner.load(c, s.id, if (needHeavy) 1200 else 480) }
        if (bmp == null) {
            dao.update(s.copy(deepDone = true))
            return
        }
        var heavy = s.heavyOcr
        try {
            if (needHeavy) {
                val rtl = Meter.time(Meter.heavy) { Ocr.heavy(c, bmp) }
                if (rtl.isNotBlank()) text = (text + "\n" + rtl).trim()
                heavy = true
            }
            val emb = Meter.time(Meter.clip) { Clip.embed(c, bmp) }
            val visual = emb?.let { Clip.tagsFrom(it) }
            val mlLabels = if (text.length < 80) Ocr.labels(bmp) else emptyList()

            var labels = (mlLabels.joinToString(" ") + " " + (visual?.words ?: "")).trim()
            // a screenshot with almost no text borrows context from shots taken beside it
            if (text.length < 80 && emb != null) {
                labels = (labels + " " + borrowContext(dao, s, emb)).trim()
            }
            var (cat, src) = Categorizer.categorize(s.sourceApp, text, mlLabels, dao.rules())
            if (cat == "לא מסווג") Brain.classify(c, text)?.let { cat = it }
            if (cat == "לא מסווג" && visual?.cat != null) cat = visual.cat

            dao.update(
                s.copy(
                    text = text,
                    norm = Ocr.norm(text),
                    labels = labels,
                    category = s.userCat ?: cat,
                    source = src ?: s.source,
                    heavyOcr = heavy,
                    emb = emb?.let { Vec.pack(it) },
                    clipDone = emb != null,
                    deepDone = true
                )
            )
        } finally {
            bmp.recycle()
        }
    }

    /** shots taken within a couple of minutes AND visually alike are the same subject */
    private suspend fun borrowContext(dao: ShotDao, s: Shot, emb: FloatArray): String {
        return try {
            val packed = Vec.pack(emb)
            dao.neighbors(s.id, s.date - 120, s.date + 120)
                .filter { n -> n.emb != null && Vec.cos(packed, n.emb!!) > 0.72f }
                .joinToString(" ") { (it.text ?: "").take(120) }
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun brainPass(c: Context, dao: ShotDao) {
        if (!Brain.available(c)) return
        var n = 0
        while (true) {
            val batch = dao.needBrain(10)
            if (batch.isEmpty()) break
            for (s in batch) {
                try {
                    val cat = Brain.classify(c, s.text ?: "")
                    dao.update(
                        s.copy(
                            category = s.userCat ?: cat ?: s.category,
                            labels = ((s.labels ?: "") + " 🧠").trim()
                        )
                    )
                } catch (e: Exception) {
                    try {
                        dao.update(s.copy(labels = ((s.labels ?: "") + " 🧠").trim()))
                    } catch (e2: Exception) {
                    }
                }
                n++
                if (n % 10 == 0) note("סיווג חכם: $n")
            }
        }
        autoOrganize(dao)
    }

    /** undo damage done by older buggy rules */
    private suspend fun heal(c: Context, dao: ShotDao) {
        try {
            val rules = dao.rules()
            for (r in rules) {
                val kws = r.keywords.split(",").map { Ocr.norm(it.trim()) }.filter { it.isNotBlank() }
                for (s in dao.allInCategory(r.name)) {
                    if (s.userCat != null) continue
                    if (kws.none { Categorizer.wordMatch(s.norm ?: "", it) }) {
                        val (c2, src2) = Categorizer.categorize(
                            s.sourceApp, s.text ?: "", (s.labels ?: "").split(" "), rules
                        )
                        dao.update(s.copy(category = c2, source = src2))
                    }
                }
            }
            for (s in dao.allInCategory("קבלות וקניות")) {
                if (s.userCat != null) continue
                val (c2, src2) = Categorizer.categorize(
                    s.sourceApp, s.text ?: "", (s.labels ?: "").split(" "), dao.rules()
                )
                if (c2 != s.category) dao.update(s.copy(category = c2, source = src2))
            }
        } catch (e: Exception) {
        }
    }

    private suspend fun autoOrganize(dao: ShotDao) {
        try {
            for (src in dao.bigSources(20)) {
                dao.refineArticles(src)
                dao.adoptSource(src)
            }
            val counts = HashMap<String, Int>()
            for (l in dao.unclassifiedLabels()) {
                l.split(" ")
                    .filter { w -> w.isNotBlank() && w.any { it in 'א'..'ת' } }
                    .distinct()
                    .forEach { counts.merge(it, 1, Int::plus) }
            }
            counts.filterValues { it >= 15 }.keys.forEach { dao.adoptTag(it) }
        } catch (e: Exception) {
        }
    }

    private suspend fun note(msg: String) {
        try {
            setForeground(info(msg))
        } catch (e: Throwable) {
        }
    }

    /** progress + where the time actually goes, visible in the app and in the notification */
    private suspend fun report(c: Context, msg: String) {
        try {
            c.getSharedPreferences("sf", Context.MODE_PRIVATE).edit()
                .putString("progress", msg).putString("speed", Meter.summary()).apply()
        } catch (e: Throwable) {
        }
        note("$msg · ${Meter.summary()}")
    }

    private fun info(msg: String): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, "scan")
            .setContentTitle("Screenote")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(1, n)
        }
    }

    companion object {
        val perm: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun enqueue(c: Context) {
            WorkManager.getInstance(c).enqueueUniqueWork(
                "scan", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScanWorker>().build()
            )
        }
    }
}

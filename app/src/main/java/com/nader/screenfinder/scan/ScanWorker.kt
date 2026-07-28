package com.nader.screenfinder.scan

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /**
     * How many screenshots are processed at the same time.
     * Default stays low and at background priority so the phone keeps feeling responsive.
     */
    private val turbo by lazy {
        applicationContext.getSharedPreferences("sf", Context.MODE_PRIVATE)
            .getBoolean("turbo", false)
    }
    private val lanes by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        if (turbo) (cores - 2).coerceIn(2, 4) else (cores / 4).coerceIn(1, 2)
    }
    private val gate by lazy { Semaphore(lanes) }

    /** low priority threads: the system hands the screen the cpu before it hands it to us */
    private val pool by lazy {
        Executors.newFixedThreadPool(lanes) { r ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }.apply { priority = Thread.MIN_PRIORITY }
        }.asCoroutineDispatcher()
    }

    override suspend fun doWork(): Result {
        val c = applicationContext
        if (c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val dao = Db.get(c).dao()
        note("בודק שינויים...")
        Scanner.diff(c, dao)

        val total = dao.countAll()
        val done = AtomicInteger(dao.countScanned())

        // the vision pipeline changed: fingerprints taken from whole screenshots must be redone
        val prefs = c.getSharedPreferences("sf", Context.MODE_PRIVATE)
        if (prefs.getInt("visionVer", 1) < 2) {
            try {
                dao.resetDeep()
                prefs.edit().putInt("visionVer", 2).apply()
            } catch (e: Exception) {
            }
        }

        // shots that still miss vision or hebrew - newest first
        val deep = AtomicInteger(dao.countDeep())
        val rules0 = dao.rules()
        while (true) {
            val batch = dao.needDeep(2 * lanes)
            if (batch.isEmpty()) break
            runParallel(batch) { s -> fullPass(c, dao, s, rules0) }
            report(c, "משלים הבנה: ${deep.addAndGet(batch.size)} מתוך $total")
        }

        // full processing, newest first: hebrew screenshots are worthless without the heavy engine
        var sinceOrganize = 0
        while (true) {
            val batch = dao.unscanned(2 * lanes)
            if (batch.isEmpty()) break
            val rules = dao.rules()
            runParallel(batch) { s -> fullPass(c, dao, s, rules) }
            report(c, "נסרקו ${done.addAndGet(batch.size)} מתוך $total")
            sinceOrganize += batch.size
            if (sinceOrganize >= 800) {
                sinceOrganize = 0
                organize(dao)
            }
        }

        heal(c, dao)

        organize(dao)
        brainPass(c, dao)
        return Result.success()
    }

    private suspend fun organize(dao: ShotDao) {
        try {
            Learner.run(dao)
        } catch (e: Exception) {
        }
        try {
            Grouper.run(dao)
        } catch (e: Exception) {
        }
        autoOrganize(dao)
    }

    /** everything a screenshot needs, in one go: latin + hebrew/arabic text, vision, category */
    private suspend fun fullPass(c: Context, dao: ShotDao, s: Shot, rules: List<UserRule>) {
        val bmp = Meter.time(Meter.decode) { Scanner.load(c, s.id, 1200) }
        if (bmp == null) {
            dao.update(s.copy(scanned = true, deepDone = true))
            return
        }
        try {
            val (latinText, blocks) = Meter.time(Meter.latin) { Ocr.latinBlocks(bmp) }
            var text = latinText
            if (s.heavyOcr && !s.text.isNullOrBlank()) {
                text = s.text!!   // hebrew was already read for this shot, do not pay for it twice
            } else if (text.length < Ocr.LATIN_THRESHOLD) {
                val rtl = Meter.time(Meter.heavy) { Ocr.heavy(c, bmp) }
                if (rtl.isNotBlank()) text = (text + "\n" + rtl).trim()
            }

            // what is this screenshot actually about
            val focus = Focus.analyze(bmp.width, bmp.height, blocks)
            val subject = if (focus.focusText.length > 25) focus.focusText else text

            // the visual model looks at the picture, not at the surrounding interface
            val view = focus.crop?.let { r ->
                try {
                    val top = r.top.coerceIn(0, bmp.height - 8)
                    val h = r.height.coerceIn(8, bmp.height - top)
                    Bitmap.createBitmap(bmp, 0, top, bmp.width, h)
                } catch (e: Throwable) {
                    null
                }
            } ?: bmp
            val emb = Meter.time(Meter.clip) { Clip.embed(c, view) }
            val mlLabels = if (text.length < 80) Ocr.labels(view) else emptyList()
            if (view !== bmp) view.recycle()

            val visual = emb?.let { Clip.tagsFrom(it) }
            var labels = (mlLabels.joinToString(" ") + " " + (visual?.words ?: "")).trim()
            if (text.length < 80 && emb != null) {
                labels = (labels + " " + borrowContext(dao, s, emb)).trim()
            }
            var (cat, src) = Categorizer.categorize(s.sourceApp, subject, mlLabels, rules)
            if (cat == "לא מסווג") {
                // the periphery may still identify the source even when it is not the subject
                val (c2, src2) = Categorizer.categorize(s.sourceApp, text, mlLabels, rules)
                cat = c2
                if (src == null) src = src2
            }
            if (cat == "לא מסווג" && visual?.cat != null) cat = visual.cat

            Meter.time(Meter.db) {
                dao.update(
                    s.copy(
                        text = text,
                        norm = Ocr.norm(text),
                        labels = labels,
                        category = s.userCat ?: cat,
                        source = src,
                        scanned = true,
                        heavyOcr = true,
                        emb = emb?.let { Vec.pack(it) },
                        clipDone = emb != null,
                        deepDone = true
                    )
                )
            }
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun runParallel(batch: List<Shot>, block: suspend (Shot) -> Unit) = coroutineScope {
        batch.map { s ->
            async(pool) {
                try {
                    gate.withPermit { block(s) }
                } catch (e: Throwable) {
                }
            }
        }.forEach { it.await() }
        // a short breath between batches keeps the phone usable while scanning
        delay(if (turbo) 15 else 120)
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
            .setSmallIcon(com.nader.screenfinder.R.drawable.ic_logo_fg)
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

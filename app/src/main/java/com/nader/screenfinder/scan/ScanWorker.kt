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

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        if (c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val dao = Db.get(c).dao()
        try {
            setForeground(info("בודק שינויים..."))
        } catch (e: Exception) {
        }
        Scanner.diff(c, dao)
        val total = dao.countAll()
        var done = dao.countScanned()
        while (true) {
            val batch = dao.unscanned(10)
            if (batch.isEmpty()) break
            for (s in batch) {
                try {
                    val bmp = Scanner.load(c, s.id, 1600)
                    if (bmp == null) {
                        dao.update(s.copy(scanned = true))
                    } else {
                        val r = Ocr.process(c, bmp)
                        val clip = Clip.tags(c, bmp)
                        bmp.recycle()
                        var (cat, src) = Categorizer.categorize(s.sourceApp, r.text, r.labels, dao.rules())
                        if (cat == "לא מסווג") Brain.classify(c, r.text)?.let { cat = it }
                        if (cat == "לא מסווג" && clip?.cat != null) cat = clip.cat
                        val labels = (r.labels.joinToString(" ") + " " + (clip?.words ?: "")).trim()
                        dao.update(
                            s.copy(
                                text = r.text,
                                norm = Ocr.norm(r.text),
                                labels = labels,
                                category = cat,
                                source = src,
                                scanned = true,
                                clipDone = clip != null
                            )
                        )
                    }
                } catch (e: Exception) {
                    try {
                        dao.update(s.copy(scanned = true))
                    } catch (e2: Exception) {
                    }
                }
                done++
                if (done % 10 == 0) try {
                    setForeground(info("נסרקו $done מתוך $total"))
                } catch (e: Exception) {
                }
            }
        }
        // expanded CLIP concepts -> re-tag everything once
        val prefs = c.getSharedPreferences("sf", Context.MODE_PRIVATE)
        if (prefs.getInt("conceptsVer", 1) < 2) {
            try {
                dao.resetClip()
                prefs.edit().putInt("conceptsVer", 2).apply()
            } catch (e: Exception) {
            }
        }
        // heal user categories polluted by old substring matching (e.g. פצי inside פציעה)
        try {
            val rules = dao.rules()
            for (r in rules) {
                val kws = r.keywords.split(",").map { Ocr.norm(it.trim()) }.filter { it.isNotBlank() }
                for (s in dao.allInCategory(r.name)) {
                    if (kws.none { Categorizer.wordMatch(s.norm ?: "", it) }) {
                        val (c2, src2) = Categorizer.categorize(
                            s.sourceApp, s.text ?: "", (s.labels ?: "").split(" "), rules
                        )
                        dao.update(s.copy(category = c2, source = src2))
                    }
                }
            }
        } catch (e: Exception) {
        }
        // re-check categories that had buggy rules in earlier versions
        try {
            val rules = dao.rules()
            for (s in dao.allInCategory("קבלות וקניות")) {
                val (c2, src2) = Categorizer.categorize(
                    s.sourceApp, s.text ?: "", (s.labels ?: "").split(" "), rules
                )
                if (c2 != s.category) dao.update(s.copy(category = c2, source = src2))
            }
        } catch (e: Exception) {
        }
        autoOrganize(dao)
        // backfill CLIP tags for shots scanned by older versions
        var clipDone = 0
        while (true) {
            val batch = dao.needClip(10)
            if (batch.isEmpty()) break
            for (s in batch) {
                try {
                    val bmp = Scanner.load(c, s.id, 512)
                    if (bmp == null) {
                        dao.update(s.copy(clipDone = true))
                    } else {
                        val clip = Clip.tags(c, bmp)
                        bmp.recycle()
                        if (clip == null) {
                            dao.update(s.copy(clipDone = true))
                        } else {
                            var cat = s.category
                            if ((cat == null || cat == "לא מסווג") && clip.cat != null) cat = clip.cat
                            dao.update(
                                s.copy(
                                    labels = ((s.labels ?: "") + " " + clip.words).trim(),
                                    category = cat,
                                    clipDone = true
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    try {
                        dao.update(s.copy(clipDone = true))
                    } catch (e2: Exception) {
                    }
                }
                clipDone++
                if (clipDone % 20 == 0) try {
                    setForeground(info("זיהוי תמונות: $clipDone"))
                } catch (e: Exception) {
                }
            }
        }
        // brain pass: let the on-device LLM read texts the rules could not classify
        if (Brain.available(c)) {
            var brained = 0
            while (true) {
                val batch = dao.needBrain(10)
                if (batch.isEmpty()) break
                for (s in batch) {
                    try {
                        val cat = Brain.classify(c, s.text ?: "")
                        dao.update(
                            s.copy(
                                category = cat ?: s.category,
                                labels = ((s.labels ?: "") + " 🧠").trim()
                            )
                        )
                    } catch (e: Exception) {
                        try {
                            dao.update(s.copy(labels = ((s.labels ?: "") + " 🧠").trim()))
                        } catch (e2: Exception) {
                        }
                    }
                    brained++
                    if (brained % 10 == 0) try {
                        setForeground(info("סיווג חכם: $brained"))
                    } catch (e: Exception) {
                    }
                }
            }
            autoOrganize(dao)
        }
        return Result.success()
    }

    // self-organizing categories: promote recurring sources and visual tags
    private suspend fun autoOrganize(dao: com.nader.screenfinder.data.ShotDao) {
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

    private fun info(msg: String): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, "scan")
            .setContentTitle("סריקת סקרינשוטים")
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

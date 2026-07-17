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
        return Result.success()
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

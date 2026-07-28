package com.nader.screenfinder.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves the work, not the pictures: extracted text, categories and fingerprints.
 * Restoring after a reinstall skips hours of scanning.
 */
object Backup {

    private fun dbFile(c: Context): File = c.getDatabasePath("shots.db")

    suspend fun export(c: Context, target: Uri): String = withContext(Dispatchers.IO) {
        try {
            val db = Db.get(c)
            // fold the write ahead log into the main file so one file holds everything
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val src = dbFile(c)
            if (!src.exists()) return@withContext "אין עדיין מה לגבות"
            c.contentResolver.openOutputStream(target)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: return@withContext "לא ניתן לכתוב לקובץ שנבחר"
            "הגיבוי נשמר (${src.length() / (1024 * 1024)}MB)"
        } catch (e: Throwable) {
            "הגיבוי נכשל: ${e.message?.take(60)}"
        }
    }

    suspend fun import(c: Context, source: Uri): String = withContext(Dispatchers.IO) {
        try {
            val dst = dbFile(c)
            val tmp = File(c.cacheDir, "restore.db")
            c.contentResolver.openInputStream(source)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return@withContext "לא ניתן לקרוא את הקובץ"
            if (tmp.length() < 4096) {
                tmp.delete()
                return@withContext "הקובץ אינו גיבוי תקין"
            }
            Db.close()
            dst.parentFile?.mkdirs()
            File("${dst.path}-wal").delete()
            File("${dst.path}-shm").delete()
            tmp.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
            tmp.delete()
            "השחזור הושלם. סגור ופתח מחדש את האפליקציה"
        } catch (e: Throwable) {
            "השחזור נכשל: ${e.message?.take(60)}"
        }
    }
}

package com.nader.screenfinder.scan

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.nader.screenfinder.data.Shot
import com.nader.screenfinder.data.ShotDao

object Scanner {
    data class Meta(val id: Long, val name: String, val date: Long)

    fun list(c: Context): List<Meta> {
        val out = ArrayList<Meta>()
        try {
            c.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                ),
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}=?",
                arrayOf("Screenshots"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cur ->
                val iId = cur.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iDate = cur.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cur.moveToNext()) {
                    out.add(Meta(cur.getLong(iId), cur.getString(iName) ?: "", cur.getLong(iDate)))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    // Screenshot_20240115_143022_Chrome.jpg -> Chrome
    fun sourceApp(name: String): String? {
        val base = name.substringBeforeLast('.')
        val parts = base.split('_')
        if (parts.size >= 4 && parts[0].startsWith("Screenshot", true)) {
            val app = parts.subList(3, parts.size).joinToString("_")
            if (app.isNotBlank() && !app[0].isDigit()) return app
        }
        return null
    }

    suspend fun diff(c: Context, dao: ShotDao) {
        val cur = list(c)
        val known = dao.allIds().toHashSet()
        val newOnes = cur.filter { it.id !in known }
            .map { Shot(id = it.id, name = it.name, date = it.date, sourceApp = sourceApp(it.name)) }
        if (newOnes.isNotEmpty()) dao.insertAll(newOnes)
        val curIds = cur.map { it.id }.toHashSet()
        val gone = known.filter { it !in curIds }
        if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.delete(it) }
    }

    fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    fun load(c: Context, id: Long, maxDim: Int): Bitmap? {
        return try {
            val u = uri(id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }
}

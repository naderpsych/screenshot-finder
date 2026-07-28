package com.nader.screenfinder

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nader.screenfinder.data.Db
import com.nader.screenfinder.scan.Categorizer
import com.nader.screenfinder.scan.Clip
import com.nader.screenfinder.scan.Ocr
import com.nader.screenfinder.scan.Scanner
import com.nader.screenfinder.scan.Vec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanFlowTest {

    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    )

    @Test
    fun fullScanFlow(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // create a fake screenshot with English recipe text
        val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 56f
            isAntiAlias = true
        }
        canvas.drawText("Chocolate cake recipe", 40f, 200f, paint)
        canvas.drawText("ingredients flour sugar eggs", 40f, 300f, paint)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_20240101_120000_Chrome.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }

        val dao = Db.get(ctx).dao()
        Scanner.diff(ctx, dao)
        val shot = (dao.unscanned(2000) + dao.allScanned())
            .first { it.name == "Screenshot_20240101_120000_Chrome.jpg" }

        // OCR + visual fingerprint + categorize
        val loaded = Scanner.load(ctx, shot.id, 1600)!!
        val text = Ocr.latin(loaded)
        val emb = Clip.embed(ctx, loaded)
        loaded.recycle()
        assertTrue("OCR should read 'recipe', got: $text", text.lowercase().contains("recipe"))
        assertTrue("CLIP should produce a fingerprint", emb != null && emb.size > 100)

        val (cat, _) = Categorizer.categorize(shot.sourceApp, text, emptyList(), emptyList())
        assertTrue("category was $cat", cat == "מתכונים")

        dao.update(
            shot.copy(
                text = text, norm = Ocr.norm(text),
                category = cat, scanned = true, deepDone = true,
                emb = emb?.let { Vec.pack(it) }
            )
        )

        // FTS search finds it
        val found = dao.search("recipe*")
        assertTrue(found.any { it.id == shot.id })

        // deletion detection
        ctx.contentResolver.delete(uri, null, null)
        Scanner.diff(ctx, dao)
        assertFalse(dao.allIds().contains(shot.id))
    }
}

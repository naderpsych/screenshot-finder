package com.nader.screenfinder.scan

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.tasks.await
import java.io.File

object Ocr {
    data class Res(val text: String, val labels: List<String>)

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private var tessInstance: TessBaseAPI? = null
    private var tessFailed = false

    @Synchronized
    private fun tess(c: Context): TessBaseAPI? {
        if (tessFailed) return null
        tessInstance?.let { return it }
        return try {
            val dir = File(c.filesDir, "tess")
            val data = File(dir, "tessdata")
            data.mkdirs()
            for (lang in listOf("heb", "ara")) {
                val f = File(data, "$lang.traineddata")
                if (!f.exists()) c.assets.open("tessdata/$lang.traineddata").use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
            val t = TessBaseAPI()
            if (t.init(dir.absolutePath, "heb+ara")) {
                tessInstance = t
                t
            } else {
                tessFailed = true
                null
            }
        } catch (e: Exception) {
            tessFailed = true
            null
        }
    }

    suspend fun process(c: Context, bmp: Bitmap): Res {
        val latin = try {
            recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text
        } catch (e: Exception) {
            ""
        }
        var text = latin
        // Latin OCR found little text -> likely Hebrew/Arabic content, run Tesseract
        if (latin.length < 250) {
            val t = tess(c)
            if (t != null) try {
                val small = if (maxOf(bmp.width, bmp.height) > 1200) {
                    val s = 1200f / maxOf(bmp.width, bmp.height)
                    Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                } else bmp
                val rtl = synchronized(t) {
                    t.setImage(small)
                    t.utF8Text ?: ""
                }
                if (small !== bmp) small.recycle()
                text = (latin + "\n" + rtl).trim()
            } catch (e: Exception) {
            }
        }
        val labels = try {
            labeler.process(InputImage.fromBitmap(bmp, 0)).await()
                .filter { it.confidence > 0.6f }
                .map { it.text }
        } catch (e: Exception) {
            emptyList()
        }
        return Res(text.trim(), labels)
    }

    fun norm(s: String): String = s.lowercase()
        .replace(Regex("[\\u0591-\\u05C7\\u064B-\\u065F\\u0670\"'׳״]"), "")
}

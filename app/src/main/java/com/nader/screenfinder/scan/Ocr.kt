package com.nader.screenfinder.scan

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

object Ocr {
    /** below this many characters we assume the screenshot is mostly non-latin */
    const val LATIN_THRESHOLD = 250

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    // small pool so several images can go through the slow engine at once
    private const val TESS_INSTANCES = 3
    private val tessPool = ConcurrentLinkedQueue<TessBaseAPI>()
    private val tessGate = Semaphore(TESS_INSTANCES)
    private var tessReady = false
    private var tessFailed = false

    @Synchronized
    private fun initTess(c: Context): Boolean {
        if (tessFailed) return false
        if (tessReady) return true
        return try {
            val dir = File(c.filesDir, "tess")
            val data = File(dir, "tessdata")
            data.mkdirs()
            for (lang in listOf("heb", "ara")) {
                val f = File(data, "$lang.traineddata")
                if (!f.exists()) c.assets.open("tessdata/$lang.traineddata").use { i ->
                    f.outputStream().use { i.copyTo(it) }
                }
            }
            repeat(TESS_INSTANCES) {
                val t = TessBaseAPI()
                if (t.init(dir.absolutePath, "heb+ara")) tessPool.add(t)
            }
            tessReady = tessPool.isNotEmpty()
            if (!tessReady) tessFailed = true
            tessReady
        } catch (e: Throwable) {
            tessFailed = true
            false
        }
    }

    data class Block(val text: String, val top: Int, val bottom: Int)

    /** fast pass: latin script only, ~0.1s */
    suspend fun latin(bmp: Bitmap): String = try {
        recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text.trim()
    } catch (e: Exception) {
        ""
    }

    /** same pass, but keeping where each piece of text sits on the screen */
    suspend fun latinBlocks(bmp: Bitmap): Pair<String, List<Block>> = try {
        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val blocks = res.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            Block(b.text.trim(), r.top, r.bottom)
        }
        res.text.trim() to blocks
    } catch (e: Exception) {
        "" to emptyList()
    }

    /** deep pass: hebrew + arabic, ~0.8s */
    suspend fun heavy(c: Context, bmp: Bitmap): String {
        if (!initTess(c)) return ""
        return try {
            tessGate.withPermit {
                val t = tessPool.poll() ?: return@withPermit ""
                try {
                    val small = if (maxOf(bmp.width, bmp.height) > 1200) {
                        val s = 1200f / maxOf(bmp.width, bmp.height)
                        Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                    } else bmp
                    t.setImage(small)
                    val out = t.utF8Text ?: ""
                    if (small !== bmp) small.recycle()
                    out.trim()
                } finally {
                    tessPool.add(t)
                }
            }
        } catch (e: Throwable) {
            ""
        }
    }

    suspend fun labels(bmp: Bitmap): List<String> = try {
        labeler.process(InputImage.fromBitmap(bmp, 0)).await()
            .filter { it.confidence > 0.6f }
            .map { it.text }
    } catch (e: Exception) {
        emptyList()
    }

    fun norm(s: String): String = s.lowercase()
        .replace(Regex("[\\u0591-\\u05C7\\u064B-\\u065F\\u0670\"'׳״]"), "")
}

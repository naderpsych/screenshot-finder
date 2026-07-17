package com.nader.screenfinder.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** On-device zero-shot image tagging with CLIP. Nothing leaves the phone. */
object Clip {
    private class Concept(val en: String, val he: String, val cat: String?, val v: FloatArray)

    data class Tags(val words: String, val cat: String?)

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var concepts: List<Concept> = emptyList()
    private var failed = false

    @Synchronized
    fun init(c: Context): Boolean {
        if (failed) return false
        if (session != null) return true
        return try {
            val f = File(c.filesDir, "vision.onnx")
            if (!f.exists()) c.assets.open("clip/vision.onnx").use { i ->
                f.outputStream().use { i.copyTo(it) }
            }
            env = OrtEnvironment.getEnvironment()
            session = env!!.createSession(f.absolutePath)
            val json = JSONObject(c.assets.open("clip/concepts.json").bufferedReader().readText())
            val arr = json.getJSONArray("concepts")
            concepts = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val va = o.getJSONArray("v")
                Concept(
                    o.getString("en"), o.getString("he"),
                    o.optString("cat").ifBlank { null },
                    FloatArray(va.length()) { j -> va.getDouble(j).toFloat() }
                )
            }
            true
        } catch (e: Throwable) {
            failed = true
            false
        }
    }

    fun tags(c: Context, bmp: Bitmap): Tags? {
        if (!init(c)) return null
        return try {
            val input = preprocess(bmp)
            val ses = session!!
            OnnxTensor.createTensor(env!!, FloatBuffer.wrap(input), longArrayOf(1, 3, 224, 224)).use { t ->
                ses.run(mapOf(ses.inputNames.first() to t)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val emb = (out[0].value as Array<FloatArray>)[0]
                    var n = 0f
                    for (x in emb) n += x * x
                    n = sqrt(n)
                    val scored = concepts.map { con ->
                        var d = 0f
                        for (i in emb.indices) d += (emb[i] / n) * con.v[i]
                        con to d
                    }.sortedByDescending { it.second }
                    val top = scored.filter { it.second > 0.22f }.take(3)
                        .ifEmpty { if (scored.first().second > 0.18f) listOf(scored.first()) else emptyList() }
                    if (top.isEmpty()) Tags("", null)
                    else Tags(
                        top.joinToString(" ") { "${it.first.he} ${it.first.en}" },
                        top.first().first.cat
                    )
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun preprocess(src: Bitmap): FloatArray {
        val scale = 224f / minOf(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(224)
        val h = (src.height * scale).roundToInt().coerceAtLeast(224)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val crop = Bitmap.createBitmap(scaled, (w - 224) / 2, (h - 224) / 2, 224, 224)
        val px = IntArray(224 * 224)
        crop.getPixels(px, 0, 224, 0, 0, 224, 224)
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val out = FloatArray(3 * 224 * 224)
        for (i in px.indices) {
            val p = px[i]
            out[i] = (((p shr 16 and 0xFF) / 255f) - mean[0]) / std[0]
            out[224 * 224 + i] = (((p shr 8 and 0xFF) / 255f) - mean[1]) / std[1]
            out[2 * 224 * 224 + i] = (((p and 0xFF) / 255f) - mean[2]) / std[2]
        }
        if (crop !== scaled) crop.recycle()
        if (scaled !== src) scaled.recycle()
        return out
    }
}

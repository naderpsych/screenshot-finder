package com.nader.screenfinder.scan

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Turns a free text query into the same kind of fingerprint CLIP produces for images,
 * so "french food" can find a tartiflette that has no text on it at all.
 * Word fingerprints are precomputed at build time - no language model on the phone.
 */
object TextVec {
    private const val DIM = 512

    private var index: HashMap<String, Int>? = null
    private var bin: File? = null
    private var he2en: Map<String, String> = emptyMap()
    private var failed = false

    @Synchronized
    fun init(c: Context): Boolean {
        if (failed) return false
        if (index != null) return true
        return try {
            val f = File(c.filesDir, "words.bin")
            if (f.length() < 1000) c.assets.open("clip/words.bin").use { i ->
                f.outputStream().use { i.copyTo(it) }
            }
            val map = HashMap<String, Int>(40000)
            c.assets.open("clip/words.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { i, w -> if (w.isNotBlank()) map[w.trim()] = i }
            }
            // hebrew queries ride on the bilingual concept list we already ship
            val he = HashMap<String, String>()
            try {
                val arr = JSONObject(c.assets.open("clip/concepts.json").bufferedReader().readText())
                    .getJSONArray("concepts")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    he[Ocr.norm(o.getString("he"))] = o.getString("en")
                }
            } catch (e: Throwable) {
            }
            he2en = he
            bin = f
            index = map
            true
        } catch (e: Throwable) {
            failed = true
            false
        }
    }

    private fun vectorAt(pos: Int): FloatArray? = try {
        RandomAccessFile(bin!!, "r").use { raf ->
            raf.seek(pos.toLong() * DIM)
            val b = ByteArray(DIM)
            raf.readFully(b)
            FloatArray(DIM) { b[it].toFloat() }
        }
    } catch (e: Throwable) {
        null
    }

    private fun lookup(term: String): FloatArray? = index?.get(term)?.let { vectorAt(it) }

    /** null when nothing in the query is understood visually */
    fun embed(c: Context, query: String): FloatArray? {
        if (!init(c)) return null
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return null

        // hebrew query -> english term through the concept list
        val translated = he2en[Ocr.norm(raw)]
        if (translated != null) lookup(translated)?.let { return Vec.normalize(it) }

        lookup(raw)?.let { return Vec.normalize(it) }

        val sum = FloatArray(DIM)
        var found = 0
        for (w in raw.split(Regex("[^\\p{L}]+"))) {
            if (w.length < 3) continue
            val term = he2en[Ocr.norm(w)] ?: w
            val v = lookup(term) ?: lookup(w) ?: continue
            for (i in 0 until DIM) sum[i] += v[i]
            found++
        }
        return if (found == 0) null else Vec.normalize(sum)
    }
}

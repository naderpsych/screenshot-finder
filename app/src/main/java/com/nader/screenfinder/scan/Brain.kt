package com.nader.screenfinder.scan

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small on-device LLM (Qwen 0.5B). Downloaded once from our GitHub release; runs fully offline. */
object Brain {
    private const val FILE = "brain.task"
    private const val URL_STR =
        "https://github.com/naderpsych/screenshot-finder/releases/latest/download/brain.task"

    private var llm: LlmInference? = null
    private var failed = false

    fun available(c: Context): Boolean = File(c.filesDir, FILE).length() > 1_000_000

    @Synchronized
    private fun get(c: Context): LlmInference? {
        if (failed) return null
        llm?.let { return it }
        val f = File(c.filesDir, FILE)
        if (f.length() < 1_000_000) return null
        return try {
            val o = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(f.absolutePath)
                .setMaxTokens(512)
                .build()
            LlmInference.createFromOptions(c, o).also { llm = it }
        } catch (t: Throwable) {
            failed = true
            null
        }
    }

    private val cats = listOf(
        "מתכונים", "קבלות וקניות", "לימודים", "כתבות", "שיחות", "אוכל", "טיולים", "אחר"
    )

    /** returns a category name, or null when brain unavailable/unsure */
    fun classify(c: Context, text: String): String? {
        if (text.isBlank()) return null
        val l = get(c) ?: return null
        val prompt = "סווג את הטקסט הבא, שחולץ מצילום מסך, לקטגוריה המתאימה ביותר מהרשימה: " +
                cats.joinToString(", ") +
                ".\nענה רק בשם הקטגוריה, בלי הסברים.\n\nטקסט:\n" + text.take(700) + "\n\nקטגוריה:"
        return try {
            val out = l.generateResponse(prompt).trim()
            cats.firstOrNull { out.contains(it) }?.takeIf { it != "אחר" }
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun download(c: Context, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val f = File(c.filesDir, FILE)
            val tmp = File(c.filesDir, "$FILE.tmp")
            var conn = URL(URL_STR).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            // github release downloads redirect cross-host; follow manually if needed
            var redirects = 0
            while (conn.responseCode in 301..308 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                redirects++
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            if (tmp.length() < 1_000_000) return@withContext false
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (t: Throwable) {
            return@withContext false
        }
    }
}

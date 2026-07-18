package com.nader.screenfinder.scan

import com.nader.screenfinder.data.UserRule

object Categorizer {
    private val sites = mapOf(
        "haaretz" to "הארץ", "ynet" to "ynet", "mako" to "מאקו", "walla" to "וואלה",
        "timeout" to "טיים אאוט", "n12" to "N12", "globes" to "גלובס",
        "calcalist" to "כלכליסט", "themarker" to "דה מרקר", "wikipedia" to "ויקיפדיה",
        "srugim" to "סרוגים", "kikar" to "כיכר השבת", "marmiton" to "Marmiton",
        "alarabiya" to "אל-ערביה", "aljazeera" to "אל-ג'זירה"
    )
    private val chatApps = setOf("whatsapp", "telegram", "messenger", "messages")
    private val foodLabels = setOf("food", "dessert", "cake", "dish", "cuisine", "baked goods", "fast food", "snack")

    fun source(text: String): String? {
        val lower = text.lowercase()
        for ((k, v) in sites) if (lower.contains(k)) return v
        val m = Regex("([a-z0-9-]{3,})\\.(co\\.il|com|net|org|fr)").find(lower)
        return m?.value
    }

    // evidence weights per category: strong signals worth more, weak ambiguous words worth little
    private val evidence = mapOf(
        "לימודים" to listOf(
            3 to listOf("אוניברסיטה", "סילבוס", "סמסטר", "התמחות"),
            2 to listOf("תואר", "פסיכולוג", "סטודנט", "מטלה", "קורס", "מרצה", "בחינה")
        ),
        "מתכונים" to listOf(
            3 to listOf("מתכון", "אופן הכנה", "מצרכים", "recette", "ingredients"),
            1 to listOf("כפית", "כוס קמח", "מחממים תנור", "preheat", "רכיבים", "אופים", "מערבבים")
        ),
        "קבלות וקניות" to listOf(
            3 to listOf("חשבונית", "סהכ לתשלום", "אישור תשלום", "אישור הזמנה", "receipt", "invoice"),
            1 to listOf("קבלה", "שח", "₪", "לתשלום", "משלוח")
        ),
        "כתבות" to listOf(
            2 to listOf("כתבה", "דקות קריאה"),
            1 to listOf("חדשות", "עיתון")
        )
    )

    // whole-word match: "פצי" must not match inside "פציעה"
    fun wordMatch(text: String, kw: String): Boolean =
        Regex("(?<!\\p{L})" + Regex.escape(kw) + "(?!\\p{L})").containsMatchIn(text)

    // returns (category, detected source)
    fun categorize(fileApp: String?, text: String, labels: List<String>, rules: List<UserRule>): Pair<String, String?> {
        val n = Ocr.norm(text)
        val src = source(text)
        for (r in rules) {
            if (r.keywords.split(",").any {
                    val k = Ocr.norm(it.trim())
                    k.isNotEmpty() && wordMatch(n, k)
                }) {
                return r.name to src
            }
        }
        // mini-brain: every category collects weighted evidence, best supported wins
        val score = HashMap<String, Int>()
        fun add(cat: String, pts: Int) {
            score.merge(cat, pts, Int::plus)
        }
        if (fileApp != null && chatApps.any { fileApp.lowercase().contains(it) }) add("שיחות", 4)
        for ((cat, groups) in evidence) {
            for ((w, words) in groups) {
                for (word in words) if (n.contains(word)) add(cat, w)
            }
        }
        if (src != null && sites.containsValue(src)) add("כתבות", 3)
        val lbl = labels.map { it.lowercase() }
        if (n.length < 60 && lbl.any { it in foodLabels }) add("אוכל", 3)

        val best = score.entries.maxByOrNull { it.value }
        if (best != null && best.value >= 3) return best.key to src
        return "לא מסווג" to src
    }
}

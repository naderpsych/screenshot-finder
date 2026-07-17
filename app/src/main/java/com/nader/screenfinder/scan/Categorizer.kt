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
    private val recipeWords = listOf(
        "מתכון", "מצרכים", "רכיבים", "אופן הכנה", "כוס קמח", "כפית", "מחממים תנור",
        "ingredients", "recette", "preparation", "préparation", "preheat"
    )
    // "קבלה" alone is ambiguous in Hebrew (receipt / acceptance) - require real payment evidence
    private val receiptWords = listOf(
        "סהכ לתשלום", "חשבונית מס", "חשבונית", "אישור הזמנה", "אישור תשלום",
        "receipt", "invoice", "order confirmation"
    )
    private val studyWords = listOf(
        "אוניברסיטה", "סמסטר", "תואר", "פסיכולוג", "סילבוס", "מטלה", "התמחות", "סטודנט"
    )
    private val newsWords = listOf("כתבה", "חדשות", "דקות קריאה")
    private val foodLabels = setOf("food", "dessert", "cake", "dish", "cuisine", "baked goods", "fast food", "snack")

    fun source(text: String): String? {
        val lower = text.lowercase()
        for ((k, v) in sites) if (lower.contains(k)) return v
        val m = Regex("([a-z0-9-]{3,})\\.(co\\.il|com|net|org|fr)").find(lower)
        return m?.value
    }

    // returns (category, detected source)
    fun categorize(fileApp: String?, text: String, labels: List<String>, rules: List<UserRule>): Pair<String, String?> {
        val n = Ocr.norm(text)
        val src = source(text)
        for (r in rules) {
            if (r.keywords.split(",").any { it.trim().isNotEmpty() && n.contains(it.trim().lowercase()) }) {
                return r.name to src
            }
        }
        if (fileApp != null && chatApps.any { fileApp.lowercase().contains(it) }) return "שיחות" to src
        if (studyWords.any { n.contains(it) }) return "לימודים" to src
        if (recipeWords.any { n.contains(it) }) return "מתכונים" to src
        if (receiptWords.any { n.contains(it) } ||
            (n.contains("קבלה") && (n.contains("₪") || n.contains("שח") || n.contains("סהכ")))
        ) return "קבלות וקניות" to src
        val lbl = labels.map { it.lowercase() }
        if (n.length < 60 && lbl.any { it in foodLabels }) return "אוכל" to src
        if (src != null && sites.containsValue(src)) return "כתבות" to src
        if (newsWords.any { n.contains(it) }) return "כתבות" to src
        return "לא מסווג" to src
    }
}

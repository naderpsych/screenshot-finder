package com.nader.screenfinder.scan

import com.nader.screenfinder.data.Shot
import com.nader.screenfinder.data.ShotDao

/**
 * Spreads the user's manual category choices to visually similar screenshots.
 * One correction teaches the app about a whole family of shots.
 */
object Learner {
    private const val STRONG = 0.90f   // confident enough to override an automatic guess
    private const val WEAK = 0.82f     // enough to fill in an unclassified shot

    suspend fun run(dao: ShotDao): Int {
        val teachers = dao.teachers().filter { it.emb != null && it.category != null }
        if (teachers.isEmpty()) return 0
        var applied = 0
        for (s in dao.students()) {
            val e = s.emb ?: continue
            var bestCat: String? = null
            var best = -1f
            for (t in teachers) {
                val sim = Vec.cos(e, t.emb!!)
                if (sim > best) {
                    best = sim
                    bestCat = t.category
                }
            }
            val cat = bestCat ?: continue
            if (cat == s.category) continue
            val unclassified = s.category == null || s.category == "לא מסווג"
            if (best >= STRONG || (unclassified && best >= WEAK)) {
                dao.setAutoCat(s.id, cat)
                applied++
            }
        }
        return applied
    }
}

/**
 * Finds families of look-alike screenshots on its own and names each family
 * after the word its members share. No user input, no hand written rules.
 */
object Grouper {
    private const val SIMILAR = 0.86f
    private const val MIN_FAMILY = 8
    private const val MAX_FAMILIES = 300
    private const val MAX_SHOTS = 6000

    private val stop = setOf(
        "של", "את", "על", "לא", "זה", "יש", "אני", "הוא", "היא", "אם", "כי", "גם", "עם",
        "מה", "כל", "רק", "אז", "כמו", "הם", "היה", "אחרי", "לפני", "יותר", "אבל", "או",
        "כדי", "אשר", "היום", "שלי", "שלו", "שלא", "הזה", "כמה", "עוד", "אנחנו", "אתם",
        "תגובות", "שיתופים", "שיתוף", "לייק", "אהבתי", "הצג", "עוקבים", "עקוב", "פוסט",
        "שתף", "הוסף", "כתוב", "תגובה", "צפיות", "דקות", "שעות", "אתמול", "היסטוריה",
        "ממומן", "מודעה", "קבוצה", "חברים", "פרופיל", "הודעה", "חיפוש", "בית", "עמוד"
    )

    private fun words(s: Shot): Set<String> =
        ((s.norm ?: "") + " " + (s.labels ?: ""))
            .split(Regex("[^\\p{L}\"']+"))
            .filter { it.length >= 3 && it !in stop && it.any { ch -> ch in 'א'..'ת' } }
            .toSet()

    suspend fun run(dao: ShotDao): Int {
        val pool = dao.unclassifiedWithEmb(MAX_SHOTS).filter { it.emb != null }
        if (pool.size < MIN_FAMILY) return 0

        // leader clustering: cheap enough to run on the phone
        val leaders = ArrayList<ByteArray>()
        val members = ArrayList<ArrayList<Shot>>()
        for (s in pool) {
            val e = s.emb ?: continue
            var bi = -1
            var best = SIMILAR
            for (i in leaders.indices) {
                val sim = Vec.cos(e, leaders[i])
                if (sim > best) {
                    best = sim
                    bi = i
                }
            }
            if (bi >= 0) members[bi].add(s)
            else if (leaders.size < MAX_FAMILIES) {
                leaders.add(e)
                members.add(arrayListOf(s))
            }
        }

        val taken = HashSet(dao.cats().mapNotNull { it.category })
        var named = 0
        for (family in members) {
            if (family.size < MIN_FAMILY) continue
            val df = HashMap<String, Int>()
            for (s in family) for (w in words(s)) df.merge(w, 1, Int::plus)
            val name = df.entries
                .filter { it.value >= family.size * 0.6 }
                .maxByOrNull { it.value * 1000 + it.key.length }
                ?.key ?: continue
            if (name in taken) continue
            taken.add(name)
            for (s in family) dao.setAutoCat(s.id, name)
            named++
        }
        return named
    }
}

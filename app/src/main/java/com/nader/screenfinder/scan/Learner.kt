package com.nader.screenfinder.scan

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

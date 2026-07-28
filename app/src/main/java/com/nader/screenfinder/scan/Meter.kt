package com.nader.screenfinder.scan

import java.util.concurrent.atomic.AtomicLong

/** Measures where scanning time actually goes, so slowness can be diagnosed instead of guessed. */
object Meter {
    class Stat {
        private val sum = AtomicLong()
        private val n = AtomicLong()
        fun add(ms: Long) {
            sum.addAndGet(ms)
            n.incrementAndGet()
        }

        fun avg(): Long = n.get().let { if (it == 0L) 0 else sum.get() / it }
        fun count(): Long = n.get()
    }

    val decode = Stat()
    val latin = Stat()
    val heavy = Stat()
    val clip = Stat()
    val db = Stat()

    inline fun <T> time(s: Stat, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        try {
            return block()
        } finally {
            s.add(System.currentTimeMillis() - t0)
        }
    }

    fun summary(): String = buildString {
        append("פענוח ${decode.avg()} · טקסט ${latin.avg()}")
        if (heavy.count() > 0) append(" · עברית ${heavy.avg()}")
        if (clip.count() > 0) append(" · ראייה ${clip.avg()}")
        append(" · שמירה ${db.avg()} (מילישניות)")
    }
}

package com.nader.screenfinder.scan

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Compact storage + comparison of CLIP image fingerprints (int8, 512 bytes per image). */
object Vec {
    fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        if (n <= 0f) return v
        return FloatArray(v.size) { v[it] / n }
    }

    fun pack(v: FloatArray): ByteArray {
        val u = normalize(v)
        return ByteArray(u.size) { (u[it] * 127f).coerceIn(-127f, 127f).roundToInt().toByte() }
    }

    fun unpack(b: ByteArray): FloatArray {
        val f = FloatArray(b.size) { b[it].toFloat() / 127f }
        return normalize(f)
    }

    /** cosine similarity of two packed vectors, in [-1,1] */
    fun cos(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            val x = a[i].toFloat()
            val y = b[i].toFloat()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 0f || nb <= 0f) return -1f
        return dot / (sqrt(na) * sqrt(nb))
    }
}

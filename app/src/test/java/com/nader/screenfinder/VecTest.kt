package com.nader.screenfinder

import com.nader.screenfinder.scan.Vec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VecTest {
    private fun rnd(seed: Int) = Random(seed).let { r -> FloatArray(512) { r.nextFloat() - 0.5f } }

    @Test
    fun packRoundTripKeepsDirection() {
        val v = rnd(1)
        val back = Vec.unpack(Vec.pack(v))
        val n = Vec.normalize(v)
        var dot = 0f
        for (i in v.indices) dot += n[i] * back[i]
        assertTrue("quantization lost too much: $dot", dot > 0.999f)
    }

    @Test
    fun identicalVectorsAreSimilar() {
        val a = Vec.pack(rnd(2))
        assertEquals(1f, Vec.cos(a, a), 0.001f)
    }

    @Test
    fun randomVectorsAreNotSimilar() {
        val a = Vec.pack(rnd(3))
        val b = Vec.pack(rnd(4))
        assertTrue(Vec.cos(a, b) < 0.3f)
    }

    @Test
    fun packedSizeIsCompact() {
        assertEquals(512, Vec.pack(rnd(5)).size)
    }
}

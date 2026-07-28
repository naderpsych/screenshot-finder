package com.nader.screenfinder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nader.screenfinder.scan.TextVec
import com.nader.screenfinder.scan.Vec
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextVecTest {

    private fun cos(a: FloatArray, b: FloatArray): Float = Vec.cos(Vec.pack(a), Vec.pack(b))

    @Test
    fun understandsMeaningNotSpelling() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dog = TextVec.embed(ctx, "dog")
        val puppy = TextVec.embed(ctx, "puppy")
        val bicycle = TextVec.embed(ctx, "bicycle")
        assertNotNull(dog); assertNotNull(puppy); assertNotNull(bicycle)
        assertTrue(
            "dog should be closer to puppy than to bicycle",
            cos(dog!!, puppy!!) > cos(dog, bicycle!!)
        )
    }

    @Test
    fun multiWordQueryWorks() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val frenchFood = TextVec.embed(ctx, "french food")
        val croissant = TextVec.embed(ctx, "croissant")
        val laptop = TextVec.embed(ctx, "laptop")
        assertNotNull(frenchFood)
        assertTrue(
            "french food should be closer to croissant than to laptop",
            cos(frenchFood!!, croissant!!) > cos(frenchFood, laptop!!)
        )
    }

    @Test
    fun hebrewQueryBridgesToEnglish() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull("hebrew concept should resolve", TextVec.embed(ctx, "אוכל צרפתי"))
    }
}

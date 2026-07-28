package com.nader.screenfinder

import com.nader.screenfinder.scan.Focus
import com.nader.screenfinder.scan.Ocr
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTest {
    // a facebook feed screenshot: leftovers on top, the real post in the middle, next post cut off at the bottom
    private val feed = listOf(
        Ocr.Block("178 people follow this", 60, 130),
        Ocr.Block("Follow Preview See all", 150, 340),
        Ocr.Block("Ran Vardi Hungry Paris", 430, 520),
        Ocr.Block("ואם אתם בארץ ולא מתכננים צרפת בקרוב אני מצרף המלצה חמה לביסטרו אמיתי בלב תלביב", 560, 790),
        // 830..1500 is the food photo - no text
        Ocr.Block("67 24 1", 1520, 1600),
        Ocr.Block("לקראת תואר שני בפסיכולוגיה", 1660, 1740)
    )

    @Test
    fun subjectIsTheCenteredPostNotTheEdges() {
        val r = Focus.analyze(900, 1800, feed)
        assertTrue("should keep the bistro post", r.focusText.contains("ביסטרו"))
        assertFalse("should drop the cut off post at the bottom", r.focusText.contains("פסיכולוגיה"))
        assertFalse("should drop the leftovers on top", r.focusText.contains("178 people"))
    }

    @Test
    fun findsThePictureInsideTheScreenshot() {
        val r = Focus.analyze(900, 1800, feed)
        val crop = r.crop!!
        assertTrue("crop should cover the photo band, was $crop", crop.top >= 780 && crop.bottom <= 1540)
        assertTrue("crop should be a real region", crop.height() > 400)
    }

    @Test
    fun screenshotWithoutTextStillGivesAnArea() {
        val r = Focus.analyze(900, 1800, emptyList())
        assertTrue(r.crop != null && r.crop!!.height() > 1000)
    }
}

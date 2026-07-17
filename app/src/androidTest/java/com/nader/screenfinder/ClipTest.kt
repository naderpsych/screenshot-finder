package com.nader.screenfinder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nader.screenfinder.scan.Clip
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipTest {
    @Test
    fun clipInitsAndTags() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("CLIP init failed", Clip.init(ctx))
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(200, 30, 30))
        val tags = Clip.tags(ctx, bmp)
        assertNotNull("CLIP inference failed", tags)
    }
}

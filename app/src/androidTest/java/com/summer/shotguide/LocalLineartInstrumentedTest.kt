package com.summer.shotguide

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLineartInstrumentedTest {
    @Test fun bundledModelsGenerateAlignedTransparentGuide() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = context.assets.open("bench-photo.jpg").use(BitmapFactory::decodeStream)
        val guide = LocalLineart(context).generate(source)
        try {
            assertEquals("guide must use the original horizontal canvas", source.width, guide.width)
            assertEquals("guide must use the original vertical canvas", source.height, guide.height)
            val pixels = IntArray(guide.width * guide.height)
            guide.getPixels(pixels, 0, guide.width, 0, 0, guide.width, guide.height)
            val alpha = pixels.map { it ushr 24 }
            assertTrue("guide must contain visible ink", alpha.max() > 70)
            assertTrue("guide must retain faint background", alpha.count { it in 5..100 } > pixels.size / 100)
            assertTrue("guide must have transparent areas", alpha.count { it < 5 } > pixels.size / 100)
            File(context.cacheDir, "local-lineart-test.png").outputStream().use { guide.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            guide.recycle(); source.recycle()
        }
    }
}

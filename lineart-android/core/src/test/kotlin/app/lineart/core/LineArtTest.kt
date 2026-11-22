package app.lineart.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineArtTest {
    private val w = 64
    private val h = 64

    private fun stepImage() = IntArray(w * h) { i -> if (i % w < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }

    @Test fun gaussianKernelIsNormalized() {
        assertEquals(1f, LineArt.gaussianKernel(2.5f).sum(), 1e-4f)
    }

    @Test fun flatImageStaysWhiteInAllModes() {
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        for (m in Mode.values()) {
            val out = LineArt.processGray(LineArt.toGray(flat), w, h, LineArtParams(mode = m, pencilWhite = 0.9f))
            assertTrue(out.all { it > 0.99f }, "mode $m should give white paper on a flat image")
        }
    }

    @Test fun edgeProducesDarkLineNearBoundaryOnly() {
        for (m in listOf(Mode.XDOG, Mode.SOBEL)) {
            val out = LineArt.processGray(LineArt.toGray(stepImage()), w, h, LineArtParams(mode = m, despeckle = 0))
            val row = 32 * w
            val minNearEdge = (w / 2 - 3..w / 2 + 2).minOf { out[row + it] }
            assertTrue(minNearEdge < 0.3f, "mode $m: expected a dark line at the edge, got $minNearEdge")
            assertTrue(out[row + 5] > 0.99f && out[row + w - 5] > 0.99f, "mode $m: far from edge should be white")
        }
    }

    @Test fun thicknessMakesLinesWider() {
        val g = LineArt.toGray(stepImage())
        val thin = LineArt.processGray(g, w, h, LineArtParams(thickness = 0, despeckle = 0)).count { it < 0.5f }
        val thick = LineArt.processGray(g, w, h, LineArtParams(thickness = 2, despeckle = 0)).count { it < 0.5f }
        assertTrue(thick > thin)
    }

    @Test fun despeckleRemovesIsolatedDots() {
        val img = FloatArray(w * h) { 1f }
        img[10 * w + 10] = 0f
        for (x in 5..50) img[40 * w + x] = 0f
        val out = LineArt.despeckle(img, w, h, 5)
        assertEquals(1f, out[10 * w + 10])
        assertEquals(0f, out[40 * w + 20])
    }

    @Test fun scaledParamsGrowSpatialValues() {
        val p = LineArtParams(xdogSigma = 1f, thickness = 1, despeckle = 10).scaled(2f)
        assertEquals(2f, p.xdogSigma); assertEquals(2, p.thickness); assertEquals(40, p.despeckle)
    }
}

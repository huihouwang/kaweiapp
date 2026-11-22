package app.lineart.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiLineTest {
    /** Fake "network": dark where the red channel is dark. */
    private val fake = SketchModel { chw, w, h -> FloatArray(w * h) { chw[it] } }

    private fun img(w: Int, h: Int) = IntArray(w * h) { i -> if (i % w < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }

    @Test fun inputSizeIsMultipleOfFour() {
        val (a, b) = AiLine.inputSize(1260, 1678, 640)
        assertTrue(a % 4 == 0 && b % 4 == 0 && maxOf(a, b) in 636..644, "$a x $b")
    }

    @Test fun resizePreservesFlatColor() {
        val out = AiLine.resizePlane(FloatArray(100 * 80) { 0.4f }, 100, 80, 33, 27)
        assertTrue(out.all { kotlin.math.abs(it - 0.4f) < 1e-4f })
        val up = AiLine.resizePlane(FloatArray(10 * 8) { 0.7f }, 10, 8, 33, 27)
        assertTrue(up.all { kotlin.math.abs(it - 0.7f) < 1e-4f })
    }

    @Test fun modelPaddingDoesNotCropOrShiftTheComposition() {
        val params = AiParams.compositionGuide().copy(modelSize = 65, preRadius = 0)
        val raw = AiLine.infer(img(35, 65), 35, 65, params, fake)
        assertEquals(35, raw.w)
        assertEquals(65, raw.h)
        assertEquals(35 * 65, raw.structure?.size)
        for (y in 0 until raw.h) {
            assertEquals(0f, raw.y[y * raw.w + 16])
            assertEquals(1f, raw.y[y * raw.w + 17])
            assertEquals(1f, raw.y[(y + 1) * raw.w - 1])
        }
    }

    @Test fun pipelineRunsAndInvertSwapsPolarity() {
        val p = AiParams(modelSize = 64, preRadius = 0, grid = false, simplify = 0f, minComponent = 0)
        val raw = AiLine.infer(img(80, 80), 80, 80, p, fake)
        val l = AiLine.lineMap(raw, p)
        val normal = AiLine.render(l, raw.w, raw.h, raw.w, raw.h, p)
        val inv = AiLine.render(l, raw.w, raw.h, raw.w, raw.h, p.copy(invert = true))
        assertEquals(1f, normal.last()) // white paper
        assertEquals(0f, inv.last())    // black background
        assertTrue(normal.first() < 0.3f && inv.first() > 0.7f)
    }

    @Test fun gridDrawsLightLinesOnlyOnPaper() {
        val img = FloatArray(90 * 90) { 1f }
        AiLine.drawGrid(img, 90, 90, 9, invert = false)
        assertEquals(0.86f, img[45 * 90 + 10], 1e-6f.toFloat().coerceAtLeast(1e-5f))
        val dark = FloatArray(90 * 90) { 0f }.also { AiLine.drawGrid(it, 90, 90, 9, invert = true) }
        assertTrue(dark[10 * 90 + 10 * 5] > 0.3f)
    }

    @Test fun thicknessIncreasesInk() {
        val p = AiParams(modelSize = 64, preRadius = 0, grid = false, simplify = 0f, minComponent = 0)
        val raw = AiLine.infer(img(80, 80), 80, 80, p, fake)
        val l = AiLine.lineMap(raw, p)
        val a = AiLine.render(l, raw.w, raw.h, raw.w, raw.h, p).count { it < 0.5f }
        val b = AiLine.render(l, raw.w, raw.h, raw.w, raw.h, p.copy(thickness = 2)).count { it < 0.5f }
        assertTrue(b > a)
    }
}

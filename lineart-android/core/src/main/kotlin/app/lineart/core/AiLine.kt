package app.lineart.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * "AI 线稿": photo -> clean, sparse contour drawing.
 *
 * A small fully-convolutional generator (Informative Drawings, anime style, MIT) does the heavy lifting
 * (see [SketchModel]); everything around it (resize, edge-preserving pre-smoothing, levels, clean-up,
 * background simplification, thickness, line colour, invert, tracing grid) is plain Kotlin here, so it is
 * identical on Android and on the JVM sample harness.
 */
fun interface SketchModel {
    /** [chw]: RGB planes in [0,1], layout 3 x h x w (w,h multiples of 4). Returns h*w values, ~1 = paper, ~0 = line. */
    fun infer(chw: FloatArray, w: Int, h: Int): FloatArray
}

data class AiParams(
    // ---- inference-affecting (changing these re-runs the network) ----
    /** Long side of the network input. 640 is a good speed / detail trade-off. */
    val modelSize: Int = 640,
    /** RGB bilateral pre-smoothing radius (removes texture before the network sees it). 0 = off. */
    val preRadius: Int = 0,
    val preRange: Float = 0.08f,

    // ---- cheap post-processing (re-computed instantly) ----
    /** Levels: network "darkness" below [levelLo] is dropped (removes faint lines), above [levelHi] is full line. */
    val levelLo: Float = 0.05f,
    val levelHi: Float = 0.60f,
    val gamma: Float = 1f,
    /** Remove line fragments smaller than this many pixels (at 640px scale). */
    val minComponent: Int = 10,
    /** Gaussian softening of strokes (px at 640 scale): joins broken pieces into smooth continuous lines. 0 = off. */
    val lineSmooth: Float = 0f,
    /** 0..1: fade lines in areas that are densely scribbled (stone walls, leaves, foliage). */
    val simplify: Float = 0f,
    val simplifyLo: Float = 0.10f,
    val simplifyHi: Float = 0.30f,
    /** Line ink strength: 1 = black, 0.8 = dark grey (pencil-like). */
    val darkness: Float = 1f,
    /** Line thickening radius, in network-resolution pixels. 0 = as drawn by the network. */
    val thickness: Int = 0,
    /** White lines on black. */
    val invert: Boolean = false,
    /** Light tracing grid overlay. */
    val grid: Boolean = true,
    /** Number of grid cells across the image width. */
    val gridCells: Int = 9,
    /** Protect strokes that the network also detects at a coarser scale. */
    val keepStructure: Boolean = false,
) {
    companion object {
        /** A lighter composition guide for the bundled fine-tuned model. */
        fun compositionGuide() = AiParams(
            preRadius = 2, levelLo = 0.10f, levelHi = 0.64f, gamma = 1.30f,
            minComponent = 14, lineSmooth = 0.20f,
            simplify = 0.78f, simplifyLo = 0.045f, simplifyHi = 0.20f,
            darkness = 0.76f, grid = false, keepStructure = true,
        )

        /** Settings tuned for the original pretrained Informative-Drawings weights (light-grey, sparse look). */
        fun pretrainedAnime() = AiParams(
            preRadius = 2, preRange = 0.08f, levelLo = 0.08f, levelHi = 0.60f, minComponent = 25,
            lineSmooth = 0.8f, simplify = 0.65f, simplifyLo = 0.10f, simplifyHi = 0.30f, darkness = 0.8f,
        )
    }

    /** Params that require a new network run when changed. */
    val inferenceKey: String get() = "$modelSize/$preRadius/$preRange/$keepStructure"
}

/** Raw network result: [y] has [w]*[h] values, ~1 = paper. */
class AiRaw(val y: FloatArray, val w: Int, val h: Int, val structure: FloatArray? = null)

object AiLine {

    /** Content size before padding for the network's multiple-of-four input. */
    private fun contentSize(w: Int, h: Int, size: Int): Pair<Int, Int> {
        val s = size.toFloat() / max(w, h)
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    fun inputSize(w: Int, h: Int, size: Int): Pair<Int, Int> {
        val (width, height) = contentSize(w, h, size)
        return roundUpToFour(width) to roundUpToFour(height)
    }

    private fun roundUpToFour(value: Int) = (value + 3) / 4 * 4

    /** Full network pass: resize -> pre-smooth -> model. Expensive; cache the result per [AiParams.inferenceKey]. */
    fun infer(argb: IntArray, w: Int, h: Int, p: AiParams, model: SketchModel): AiRaw {
        val fine = inferAtScale(argb, w, h, p.modelSize, p.preRadius, p.preRange, model)
        if (!p.keepStructure) return fine
        val coarse = inferAtScale(argb, w, h, (p.modelSize * 0.60f).roundToInt(), max(3, p.preRadius), 0.12f, model)
        val strength = FloatArray(coarse.y.size) { clamp01((1f - coarse.y[it] - 0.10f) / 0.54f) }
        val aligned = resizePlane(strength, coarse.w, coarse.h, fine.w, fine.h)
        val structure = LineArt.gaussianBlur(aligned, fine.w, fine.h, 2.2f * p.modelSize / 640)
        return AiRaw(fine.y, fine.w, fine.h, structure)
    }

    private fun inferAtScale(
        argb: IntArray, w: Int, h: Int, size: Int, radius: Int, range: Float, model: SketchModel
    ): AiRaw {
        val (mw, mh) = contentSize(w, h, size)
        var chw = resizeArgbToChw(argb, w, h, mw, mh)
        if (radius > 0) chw = bilateralRgb(chw, mw, mh, radius, range)
        val paddedWidth = roundUpToFour(mw)
        val paddedHeight = roundUpToFour(mh)
        val needsPadding = paddedWidth != mw || paddedHeight != mh
        if (needsPadding) {
            val source = chw
            val paddedPlaneSize = paddedWidth * paddedHeight
            chw = FloatArray(3 * paddedPlaneSize) { i ->
                val plane = i / paddedPlaneSize
                val y = min(i % paddedPlaneSize / paddedWidth, mh - 1)
                val x = min(i % paddedWidth, mw - 1)
                source[(plane * mh + y) * mw + x]
            }
        }
        val result = model.infer(chw, paddedWidth, paddedHeight)
        require(result.size == paddedWidth * paddedHeight) {
            "model returned ${result.size} values, expected ${paddedWidth * paddedHeight}"
        }
        val content = if (needsPadding) FloatArray(mw * mh) { i -> result[i / mw * paddedWidth + i % mw] } else result
        return AiRaw(content, mw, mh)
    }

    /** Network output -> line map L in [0,1] (1 = full line) at network resolution. */
    fun lineMap(raw: AiRaw, p: AiParams): FloatArray {
        val w = raw.w; val h = raw.h
        val range = max(1e-3f, p.levelHi - p.levelLo)
        var l = FloatArray(raw.y.size) {
            val v = clamp01((1f - raw.y[it] - p.levelLo) / range)
            if (p.gamma == 1f) v else v.toDouble().pow(p.gamma.toDouble()).toFloat()
        }
        val scale = max(w, h) / 640f
        val minPx = (p.minComponent * scale * scale).roundToInt()
        if (minPx > 1) {
            // despeckle works on "paper = 1" planes; lines are L > 0.3, i.e. paper < 0.7
            val paper = FloatArray(l.size) { 1f - l[it] }
            val cleaned = LineArt.despeckle(paper, w, h, minPx, threshold = 0.7f)
            l = FloatArray(l.size) { 1f - cleaned[it] }
        }
        if (p.lineSmooth > 0.05f) {
            val sm = LineArt.gaussianBlur(l, w, h, p.lineSmooth * scale)
            val gain = 1f + 0.6f * p.lineSmooth
            for (i in l.indices) l[i] = clamp01(sm[i] * gain)
        }
        if (p.simplify > 0f) {
            val density = LineArt.gaussianBlur(l, w, h, (if (p.keepStructure) 7f else 6f) * scale)
            val span = max(1e-3f, p.simplifyHi - p.simplifyLo)
            for (i in l.indices) {
                val s = clamp01((density[i] - p.simplifyLo) / span)
                val support = raw.structure?.let { clamp01((it[i] - 0.04f) * 4.5f) } ?: 0f
                l[i] *= 1f - p.simplify * s * (1f - support)
            }
        }
        return l
    }

    /**
     * Line map -> final gray picture (0 black .. 1 white) at [outW] x [outH]
     * (upsampled smoothly when larger than the network resolution), with thickness, colour, invert and grid.
     */
    fun render(l: FloatArray, w: Int, h: Int, outW: Int, outH: Int, p: AiParams): FloatArray {
        var lines = if (outW == w && outH == h) l else resizePlane(l, w, h, outW, outH)
        if (p.thickness > 0) {
            val r = max(1, (p.thickness * max(outW, outH).toFloat() / max(w, h)).roundToInt())
            val inv = FloatArray(lines.size) { 1f - lines[it] }
            val th = LineArt.erode(inv, outW, outH, r)
            lines = FloatArray(lines.size) { 1f - th[it] }
        }
        val out = FloatArray(lines.size)
        if (p.invert) {
            val ink = min(1f, p.darkness + 0.15f)
            for (i in out.indices) out[i] = clamp01(lines[i]) * ink
        } else {
            for (i in out.indices) out[i] = 1f - clamp01(lines[i]) * p.darkness
        }
        if (p.grid && p.gridCells >= 2) drawGrid(out, outW, outH, p.gridCells, p.invert)
        return out
    }

    /** Light-grey square tracing grid, [cells] across the width. */
    fun drawGrid(img: FloatArray, w: Int, h: Int, cells: Int, invert: Boolean) {
        val cs = w.toFloat() / cells
        val lw = max(1, (w / 700f).roundToInt())
        val level = if (invert) 0.32f else 0.86f
        fun mark(i: Int) { img[i] = if (invert) max(img[i], level) else min(img[i], level) }
        for (c in 1 until cells) {
            val x0 = (c * cs).roundToInt()
            for (x in x0 until min(w, x0 + lw)) for (y in 0 until h) mark(y * w + x)
        }
        var r = 1
        while (r * cs < h) {
            val y0 = (r * cs).roundToInt()
            for (y in y0 until min(h, y0 + lw)) for (x in 0 until w) mark(y * w + x)
            r++
        }
    }

    // ------------------------------------------------------------------ resizing

    /** ARGB -> CHW float planes in [0,1], resized to [nw] x [nh] (area average when shrinking, linear when enlarging). */
    fun resizeArgbToChw(argb: IntArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val n = w * h
        val out = FloatArray(3 * nw * nh)
        val plane = FloatArray(n)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in 0 until n) {
                val px = argb[i]
                val a = (px ushr 24 and 0xFF) / 255f
                plane[i] = ((px shr shift and 0xFF) / 255f) * a + (1f - a)
            }
            val r = if (nw == w && nh == h) plane else resizePlane(plane, w, h, nw, nh)
            System.arraycopy(r, 0, out, c * nw * nh, nw * nh)
        }
        return out
    }

    /** Separable resize of one plane: area average per axis when shrinking, centre-aligned linear when enlarging. */
    fun resizePlane(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val tmp = FloatArray(nw * h)
        val xs = axisWeights(w, nw)
        for (y in 0 until h) for (x in 0 until nw) {
            val (i0, wt) = xs[x]
            var acc = 0f
            for (k in wt.indices) acc += src[y * w + i0 + k] * wt[k]
            tmp[y * nw + x] = acc
        }
        val out = FloatArray(nw * nh)
        val ys = axisWeights(h, nh)
        for (y in 0 until nh) {
            val (j0, wt) = ys[y]
            for (x in 0 until nw) {
                var acc = 0f
                for (k in wt.indices) acc += tmp[(j0 + k) * nw + x] * wt[k]
                out[y * nw + x] = acc
            }
        }
        return out
    }

    private fun axisWeights(n: Int, m: Int): Array<Pair<Int, FloatArray>> = Array(m) { d ->
        if (m < n) { // shrink: exact area weights
            val s = n.toFloat() / m
            val a = d * s; val b = (d + 1) * s
            val i0 = a.toInt(); val i1 = min(n - 1, (b - 1e-4f).toInt())
            val wts = FloatArray(i1 - i0 + 1) { k ->
                val lo = max(a, (i0 + k).toFloat()); val hi = min(b, (i0 + k + 1).toFloat()); max(0f, hi - lo)
            }
            val sum = wts.sum(); for (k in wts.indices) wts[k] /= sum
            i0 to wts
        } else { // enlarge: bilinear, pixel centres aligned
            val c = (d + 0.5f) * n / m - 0.5f
            val i0 = min(n - 1, max(0, c.toInt().let { if (c < 0) 0 else it }))
            val i1 = min(n - 1, i0 + 1)
            val f = clamp01(c - i0)
            if (i1 == i0) i0 to floatArrayOf(1f) else i0 to floatArrayOf(1f - f, f)
        }
    }

    // ------------------------------------------------------------------ pre-smoothing

    /** Edge-preserving smoothing on CHW RGB planes (colour-distance bilateral filter, disk of [radius]). */
    fun bilateralRgb(chw: FloatArray, w: Int, h: Int, radius: Int, rangeSigma: Float, passes: Int = 1): FloatArray {
        if (radius <= 0) return chw
        val n = w * h
        val ss = max(0.5f, radius / 2f)
        val offs = ArrayList<IntArray>(); val sw = ArrayList<Float>()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val r2 = dx * dx + dy * dy
            if (r2 <= radius * radius) { offs.add(intArrayOf(dx, dy)); sw.add(exp(-r2 / (2.0 * ss * ss)).toFloat()) }
        }
        val lutN = 1024; val maxD2 = 3f
        val rs = max(1e-3f, rangeSigma)
        val lut = FloatArray(lutN + 1) { exp(-(it / lutN.toFloat() * maxD2) / (2.0 * rs * rs)).toFloat() }
        var cur = chw
        repeat(passes) {
            val out = FloatArray(cur.size)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val r0 = cur[i]; val g0 = cur[n + i]; val b0 = cur[2 * n + i]
                var ar = 0f; var ag = 0f; var ab = 0f; var ws = 0f
                for (k in offs.indices) {
                    val xx = min(w - 1, max(0, x + offs[k][0])); val yy = min(h - 1, max(0, y + offs[k][1]))
                    val j = yy * w + xx
                    val dr = cur[j] - r0; val dg = cur[n + j] - g0; val db = cur[2 * n + j] - b0
                    val d2 = dr * dr + dg * dg + db * db
                    val wt = sw[k] * lut[min(lutN, (d2 / maxD2 * lutN).toInt())]
                    ar += cur[j] * wt; ag += cur[n + j] * wt; ab += cur[2 * n + j] * wt; ws += wt
                }
                out[i] = ar / ws; out[n + i] = ag / ws; out[2 * n + i] = ab / ws
            }
            cur = out
        }
        return cur
    }

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
}

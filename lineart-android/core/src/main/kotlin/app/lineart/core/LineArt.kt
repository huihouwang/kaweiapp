package app.lineart.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Platform-independent classic line-art (线稿) filters.
 *
 * Works on packed ARGB pixels (IntArray, same layout as android.graphics.Bitmap.getPixels)
 * and on grayscale FloatArray planes with values in [0, 1] (0 = black, 1 = white).
 * No Android dependencies, so everything here can be unit-tested on the JVM.
 */
enum class Mode { PENCIL, XDOG, SOBEL }

data class LineArtParams(
    val mode: Mode = Mode.XDOG,

    // ---- Pre-smoothing (edge-preserving bilateral filter), all modes ----
    /** Bilateral spatial radius in px. 0 = off. */
    val smoothRadius: Int = 2,
    /** Bilateral range sigma (gray units 0..1). Smaller = keeps more edges. */
    val smoothRange: Float = 0.12f,

    // ---- Mode 1: pencil sketch (grayscale -> invert -> blur -> color dodge -> levels) ----
    val pencilSigma: Float = 8f,
    val pencilBlack: Float = 0.0f,
    val pencilWhite: Float = 0.92f,
    val pencilGamma: Float = 2.0f,

    // ---- Mode 2: XDoG clean line art ----
    val xdogSigma: Float = 1.2f,
    val xdogK: Float = 1.6f,
    val xdogStrength: Float = 30f,
    val xdogThreshold: Float = 0.25f,
    val xdogSharpness: Float = 4f,
    val xdogTone: Float = 0f,

    // ---- Mode 3: Sobel edges ----
    val sobelSigma: Float = 1.2f,
    val sobelThreshold: Float = 0.08f,
    val sobelSoftness: Float = 0.12f,

    // ---- Post-processing, all modes ----
    /** Line thickening radius in px (grayscale erosion with a disk). 0 = none. */
    val thickness: Int = 0,
    /** Remove isolated dark specks smaller than this many pixels. 0 = off. */
    val despeckle: Int = 20,
) {
    companion object {
        /** Recommended starting values for each mode (tuned on ~1000px previews). */
        fun defaultsFor(mode: Mode): LineArtParams = when (mode) {
            Mode.PENCIL -> LineArtParams(mode = mode, smoothRadius = 3, smoothRange = 0.08f, despeckle = 12)
            Mode.XDOG -> LineArtParams(mode = mode, smoothRadius = 2, smoothRange = 0.12f, despeckle = 30)
            Mode.SOBEL -> LineArtParams(mode = mode, smoothRadius = 2, smoothRange = 0.12f, despeckle = 30)
        }
    }

    /** Params adapted to an image [factor] times larger than the one they were tuned on. */
    fun scaled(factor: Float): LineArtParams {
        if (factor == 1f) return this
        return copy(
            smoothRadius = (smoothRadius * factor).roundToInt(),
            pencilSigma = pencilSigma * factor,
            xdogSigma = xdogSigma * factor,
            sobelSigma = sobelSigma * factor,
            thickness = (thickness * factor).roundToInt(),
            despeckle = (despeckle * factor * factor).roundToInt(),
        )
    }
}

object LineArt {

    /** Full pipeline: ARGB in, ARGB (opaque grayscale) out. */
    fun process(argb: IntArray, width: Int, height: Int, p: LineArtParams): IntArray {
        val gray = toGray(argb)
        val result = processGray(gray, width, height, p)
        return grayToArgb(result)
    }

    /** Full pipeline on a gray plane in [0,1]. */
    fun processGray(gray: FloatArray, w: Int, h: Int, p: LineArtParams): FloatArray {
        val src = if (p.smoothRadius > 0) bilateral(gray, w, h, p.smoothRadius, p.smoothRange) else gray
        var out = when (p.mode) {
            Mode.PENCIL -> pencilSketch(src, w, h, p.pencilSigma, p.pencilBlack, p.pencilWhite, p.pencilGamma)
            Mode.XDOG -> xdog(src, w, h, p.xdogSigma, p.xdogK, p.xdogStrength, p.xdogThreshold, p.xdogSharpness, p.xdogTone)
            Mode.SOBEL -> sobelLines(src, w, h, p.sobelSigma, p.sobelThreshold, p.sobelSoftness)
        }
        if (p.despeckle > 0) out = despeckle(out, w, h, p.despeckle)
        if (p.thickness > 0) out = erode(out, w, h, p.thickness)
        return out
    }

    // ------------------------------------------------------------------ conversions

    /** ARGB -> luma in [0,1] (Rec.601), transparent pixels composited on white. */
    fun toGray(argb: IntArray): FloatArray {
        val out = FloatArray(argb.size)
        for (i in argb.indices) {
            val c = argb[i]
            val a = (c ushr 24 and 0xFF) / 255f
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            out[i] = y * a + (1f - a)
        }
        return out
    }

    fun grayToArgb(gray: FloatArray): IntArray {
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            val v = (clamp01(gray[i]) * 255f).roundToInt()
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    // ------------------------------------------------------------------ mode 1: pencil

    /** Classic pencil sketch: dodge = gray / (1 - blur(1 - gray)), then levels + gamma. */
    fun pencilSketch(
        gray: FloatArray, w: Int, h: Int,
        sigma: Float, black: Float, white: Float, gamma: Float,
    ): FloatArray {
        val inv = FloatArray(gray.size) { 1f - gray[it] }
        val blurInv = gaussianBlur(inv, w, h, sigma)
        val range = max(1e-4f, white - black)
        val out = FloatArray(gray.size)
        for (i in gray.indices) {
            val denom = 1f - blurInv[i]
            val dodge = if (denom <= 1e-4f) 1f else min(1f, gray[i] / denom)
            val lv = clamp01((dodge - black) / range)
            out[i] = lv.toDouble().pow(gamma.toDouble()).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------------ mode 2: XDoG

    /**
     * eXtended Difference of Gaussians (Winnemöller et al. 2012), line-art variant.
     *   u = p * (G(sigma) - G(k*sigma)) + tone * (G(sigma) - 1)
     *   out = 1 if u >= -eps else 1 + tanh(phi * (u + eps))
     */
    fun xdog(
        gray: FloatArray, w: Int, h: Int,
        sigma: Float, k: Float, p: Float, eps: Float, phi: Float, tone: Float,
    ): FloatArray {
        val g1 = gaussianBlur(gray, w, h, sigma)
        val g2 = gaussianBlur(gray, w, h, sigma * k)
        val out = FloatArray(gray.size)
        for (i in gray.indices) {
            val u = p * (g1[i] - g2[i]) + tone * (g1[i] - 1f)
            out[i] = if (u >= -eps) 1f else clamp01(1f + tanh(phi * (u + eps)))
        }
        return out
    }

    // ------------------------------------------------------------------ mode 3: Sobel

    fun sobelLines(gray: FloatArray, w: Int, h: Int, sigma: Float, threshold: Float, softness: Float): FloatArray {
        val g = gaussianBlur(gray, w, h, sigma)
        val out = FloatArray(g.size)
        val soft = max(1e-4f, softness)
        for (y in 0 until h) {
            val ym = max(0, y - 1) * w; val y0 = y * w; val yp = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
                val gx = (g[ym + xp] + 2 * g[y0 + xp] + g[yp + xp]) - (g[ym + xm] + 2 * g[y0 + xm] + g[yp + xm])
                val gy = (g[yp + xm] + 2 * g[yp + x] + g[yp + xp]) - (g[ym + xm] + 2 * g[ym + x] + g[ym + xp])
                val mag = sqrt(gx * gx + gy * gy) / 4f
                out[y0 + x] = 1f - clamp01((mag - threshold) / soft)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ building blocks

    fun gaussianKernel(sigma: Float): FloatArray {
        val r = max(1, ceil(3.0 * sigma).toInt())
        val k = FloatArray(2 * r + 1)
        var sum = 0f
        for (i in -r..r) {
            val v = exp(-(i * i) / (2.0 * sigma * sigma)).toFloat()
            k[i + r] = v; sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Separable Gaussian blur, clamp-to-edge. sigma <= 0 returns a copy. */
    fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Float): FloatArray {
        if (sigma <= 0.01f) return src.copyOf()
        val k = gaussianKernel(sigma)
        val r = k.size / 2
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (i in -r..r) {
                    val xx = min(w - 1, max(0, x + i))
                    acc += src[row + xx] * k[i + r]
                }
                tmp[row + x] = acc
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (i in -r..r) {
                    val yy = min(h - 1, max(0, y + i))
                    acc += tmp[yy * w + x] * k[i + r]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    /** Brute-force bilateral filter (edge-preserving smoothing). Spatial sigma = radius / 2. */
    fun bilateral(src: FloatArray, w: Int, h: Int, radius: Int, rangeSigma: Float): FloatArray {
        if (radius <= 0) return src.copyOf()
        val ss = max(0.5f, radius / 2f)
        val d = 2 * radius + 1
        val spatial = FloatArray(d * d)
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val r2 = dx * dx + dy * dy
            spatial[(dy + radius) * d + dx + radius] =
                if (r2 > radius * radius) 0f else exp(-r2 / (2.0 * ss * ss)).toFloat()
        }
        val lutSize = 1024
        val rs = max(1e-3f, rangeSigma)
        val lut = FloatArray(lutSize + 1) { val v = it / lutSize.toFloat(); exp(-(v * v) / (2.0 * rs * rs)).toFloat() }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src[y * w + x]
                var acc = 0f; var wsum = 0f
                for (dy in -radius..radius) {
                    val yy = min(h - 1, max(0, y + dy)) * w
                    val sRow = (dy + radius) * d + radius
                    for (dx in -radius..radius) {
                        val sw = spatial[sRow + dx]
                        if (sw == 0f) continue
                        val v = src[yy + min(w - 1, max(0, x + dx))]
                        val wt = sw * lut[min(lutSize, (abs(v - c) * lutSize).toInt())]
                        acc += v * wt; wsum += wt
                    }
                }
                out[y * w + x] = acc / wsum
            }
        }
        return out
    }

    /** Grayscale erosion (min filter) with a disk: makes dark lines thicker. */
    fun erode(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val out = FloatArray(src.size)
        val r2 = radius * radius
        for (y in 0 until h) for (x in 0 until w) {
            var m = 1f
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -radius..radius) {
                    if (dx * dx + dy * dy > r2) continue
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    val v = src[yy * w + xx]
                    if (v < m) m = v
                }
            }
            out[y * w + x] = m
        }
        return out
    }

    /**
     * Removes 8-connected dark components (pixels < [threshold]) with fewer than [minSize] pixels
     * by painting them (and a 1px halo) white.
     */
    fun despeckle(src: FloatArray, w: Int, h: Int, minSize: Int, threshold: Float = 0.5f): FloatArray {
        val out = src.copyOf()
        if (minSize <= 1) return out
        val visited = BooleanArray(src.size)
        val stack = IntArray(src.size)
        val comp = IntArray(src.size)
        for (start in src.indices) {
            if (visited[start] || src[start] >= threshold) continue
            var sp = 0; var n = 0
            stack[sp++] = start; visited[start] = true
            while (sp > 0) {
                val idx = stack[--sp]
                comp[n++] = idx
                val x = idx % w; val y = idx / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (!visited[j] && src[j] < threshold) { visited[j] = true; stack[sp++] = j }
                    }
                }
            }
            if (n < minSize) {
                for (t in 0 until n) {
                    val idx = comp[t]; val x = idx % w; val y = idx / w
                    for (dy in -1..1) for (dx in -1..1) {
                        val xx = x + dx; val yy = y + dy
                        if (xx in 0 until w && yy in 0 until h) out[yy * w + xx] = 1f
                    }
                }
            }
        }
        return out
    }

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
}

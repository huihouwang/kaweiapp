package com.summer.shotguide

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import app.lineart.core.AiLine
import app.lineart.core.AiParams
import app.lineart.core.SketchModel
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** The tested composition-guide preset, rendered as ink on a transparent canvas for CameraX. */
class LocalLineart(private val context: Context) {
    fun generate(original: Bitmap, progress: (Int, String) -> Unit = { _, _ -> }): Bitmap {
        val width = original.width
        val height = original.height
        val scale = minOf(1f, 1024f / maxOf(width, height))
        val input = Bitmap.createScaledBitmap(
            original, (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1), true,
        )
        val inputWidth = input.width
        val inputHeight = input.height
        val pixels = IntArray(inputWidth * inputHeight)
        try {
            input.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        } finally {
            if (input !== original) input.recycle()
        }

        progress(25, "Drawing fine lines on this device…")
        val params = AiParams.compositionGuide()
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open("lineart_ft.onnx").use { it.readBytes() }
        val raw = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(modelBytes, options).use { session ->
                val model = SketchModel { rgb, w, h ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(rgb), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
                        session.run(mapOf("input" to tensor)).use { outputs ->
                            val buffer = (outputs[0] as OnnxTensor).floatBuffer
                            FloatArray(buffer.remaining()).also { buffer.get(it) }
                        }
                    }
                }
                AiLine.infer(pixels, inputWidth, inputHeight, params, model)
            }
        }
        progress(78, "Finishing the line guide…")
        val lines = AiLine.lineMap(raw, params)
        val gray = AiLine.render(lines, raw.w, raw.h, width, height, params)
        val ink = IntArray(gray.size) { (255 - (gray[it] * 255f).roundToInt().coerceIn(0, 255)) shl 24 }
        return Bitmap.createBitmap(ink, width, height, Bitmap.Config.ARGB_8888)
    }
}

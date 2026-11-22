package com.summer.shotguide

import androidx.camera.core.CameraSelector

internal fun oppositeLensFacing(facing: Int): Int =
    if (facing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK
    else CameraSelector.LENS_FACING_FRONT

internal fun lensFacingName(facing: Int): String =
    if (facing == CameraSelector.LENS_FACING_FRONT) "front" else "rear"

internal fun shouldMirrorCapture(facing: Int, mirrorFrontCapture: Boolean): Boolean =
    facing == CameraSelector.LENS_FACING_FRONT && mirrorFrontCapture

internal fun resolveLensFacing(requested: Int, requestedAvailable: Boolean, fallbackAvailable: Boolean): Int? = when {
    requestedAvailable -> requested
    fallbackAvailable -> oppositeLensFacing(requested)
    else -> null
}

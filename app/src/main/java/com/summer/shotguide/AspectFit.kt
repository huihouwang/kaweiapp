package com.summer.shotguide

data class FitBox(val left: Float, val top: Float, val width: Float, val height: Float)

/** A single scale factor preserves proportions; extra space becomes letterboxing. */
fun fitInside(containerWidth: Float, containerHeight: Float, imageWidth: Float, imageHeight: Float): FitBox {
    require(containerWidth >= 0 && containerHeight >= 0 && imageWidth > 0 && imageHeight > 0)
    val scale = minOf(containerWidth / imageWidth, containerHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FitBox((containerWidth - width) / 2, (containerHeight - height) / 2, width, height)
}

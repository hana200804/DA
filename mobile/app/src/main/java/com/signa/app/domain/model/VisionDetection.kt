package com.signa.app.domain.model

data class VisionBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width * height

    fun intersection(other: VisionBox): Float =
        (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f) *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)

    fun iou(other: VisionBox): Float {
        val overlap = intersection(other)
        val union = area + other.area - overlap
        return if (union > 0f) overlap / union else 0f
    }
}

data class VisionLabel(val id: Int, val code: String, val name: String)

data class VisionDetection(val box: VisionBox, val label: VisionLabel, val confidence: Float)

data class VisionModel(val name: String, val version: String, val labelMapVersion: Int)

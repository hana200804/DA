package com.signa.app.vision.analyzer

import com.signa.app.domain.model.VisionBox
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

internal data class Letterbox(
    val size: Int,
    val width: Int,
    val height: Int,
    val resizedWidth: Int,
    val resizedHeight: Int,
    val left: Int,
    val top: Int
) {
    fun toOriginal(box: VisionBox): VisionBox {
        val scaleX = width.toDouble() / resizedWidth
        val scaleY = height.toDouble() / resizedHeight
        fun x(value: Float) = ((value.toDouble() - left) * scaleX).coerceIn(0.0, width.toDouble()).toFloat()
        fun y(value: Float) = ((value.toDouble() - top) * scaleY).coerceIn(0.0, height.toDouble()).toFloat()
        return VisionBox(x(box.left), y(box.top), x(box.right), y(box.bottom))
    }

    companion object {
        fun fit(width: Int, height: Int, size: Int): Letterbox {
            require(minOf(width, height, size) > 0)
            val scale = size.toDouble() / maxOf(width, height)
            val resizedWidth = round(width * scale).toInt().coerceAtLeast(1)
            val resizedHeight = round(height * scale).toInt().coerceAtLeast(1)
            return Letterbox(size, width, height, resizedWidth, resizedHeight,
                (size - resizedWidth) / 2, (size - resizedHeight) / 2)
        }
    }
}

/** Nội suy FP32 theo tâm pixel và làm tròn về số chẵn như NumPy. */
internal class FramePreprocessor(private val size: Int) {
    private val buffer = ByteBuffer.allocateDirect(size * size * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val tensor = buffer.asFloatBuffer()
    private val normalized = FloatArray(256) { it / 255f }
    private val padding = FloatArray(size * 3) { normalized[114] }
    private val row = FloatArray(size * 3)
    private var transform: Letterbox? = null
    private lateinit var horizontal: Axis
    private lateinit var vertical: Axis

    fun prepare(pixels: IntArray, width: Int, height: Int): PreparedFrame {
        require(pixels.size >= width * height)
        if (transform?.width != width || transform?.height != height) {
            val fit = Letterbox.fit(width, height, size)
            horizontal = Axis(width, fit.resizedWidth)
            vertical = Axis(height, fit.resizedHeight)
            transform = fit
        }
        val fit = checkNotNull(transform)
        tensor.clear()
        repeat(fit.top) { tensor.put(padding) }
        for (y in 0 until fit.resizedHeight) {
            val top = vertical.low[y] * width
            val bottom = vertical.high[y] * width
            val weightY = vertical.weight[y]
            var index = 0
            for (x in 0 until fit.resizedWidth) {
                val left = horizontal.low[x]
                val right = horizontal.high[x]
                val weightX = horizontal.weight[x]
                val topLeft = pixels[top + left]
                val topRight = pixels[top + right]
                val bottomLeft = pixels[bottom + left]
                val bottomRight = pixels[bottom + right]
                for (shift in 16 downTo 0 step 8) {
                    val upper = ((topLeft shr shift) and 255) * (1f - weightX) +
                        ((topRight shr shift) and 255) * weightX
                    val lower = ((bottomLeft shr shift) and 255) * (1f - weightX) +
                        ((bottomRight shr shift) and 255) * weightX
                    row[index++] = normalized[round(upper * (1f - weightY) + lower * weightY).toInt()]
                }
            }
            tensor.put(padding, 0, fit.left * 3)
            tensor.put(row, 0, index)
            tensor.put(padding, 0, (size - fit.left - fit.resizedWidth) * 3)
        }
        repeat(size - fit.top - fit.resizedHeight) { tensor.put(padding) }
        return PreparedFrame(buffer.rewind(), fit)
    }

    private class Axis(length: Int, resized: Int) {
        val low = IntArray(resized)
        val high = IntArray(resized)
        val weight = FloatArray(resized)

        init {
            val scale = (length.toDouble() / resized).toFloat()
            for (index in 0 until resized) {
                val position = ((index + .5f) * scale - .5f).coerceIn(0f, (length - 1).toFloat())
                low[index] = position.toInt()
                high[index] = minOf(low[index] + 1, length - 1)
                weight[index] = position - low[index]
            }
        }
    }
}

internal data class PreparedFrame(val tensor: ByteBuffer, val transform: Letterbox)

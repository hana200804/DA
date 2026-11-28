package com.signa.app.vision.analyzer

import android.graphics.Bitmap
import android.os.SystemClock
import com.signa.app.domain.model.Session
import com.signa.app.domain.model.VisionBox
import com.signa.app.domain.model.VisionDetection
import com.signa.app.domain.model.VisionModel
import com.signa.app.vision.detector.TfliteSignDetector
import com.signa.app.vision.geo.ObservationGeo
import com.signa.app.vision.tracker.FinishedTrack
import com.signa.app.vision.tracker.SignTracker
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class CaptureContext(val owner: Session, val geo: ObservationGeo, val capturedAt: Instant)
internal data class TrackEvidence(val context: CaptureContext, val jpeg: ByteArray)
internal data class VisionFrame(
    val detections: List<VisionDetection>,
    val fps: Float,
    val timing: FrameTiming
)

internal data class FrameTiming(
    val preprocessNs: Long,
    val inferenceNs: Long,
    val postprocessNs: Long,
    val trackerNs: Long
) {
    val totalNs get() = preprocessNs + inferenceNs + postprocessNs + trackerNs
}

/** Used in order on one worker by each frame source. The caller owns the source bitmap. */
internal class VisionPipeline(
    private val detector: TfliteSignDetector,
    private val onTrack: (VisionModel, FinishedTrack<TrackEvidence>) -> Unit = { _, _ -> }
) : AutoCloseable {
    private val preprocessor = FramePreprocessor(detector.inputSize)
    private val tracker = SignTracker<TrackEvidence>()
    private var pixels = IntArray(0)
    private var dimensions = 0 to 0
    private val completions = ArrayDeque<Long>(FPS_WINDOW)

    fun process(
        bitmap: Bitmap,
        captured: CaptureContext? = null,
        started: Long = SystemClock.elapsedRealtimeNanos()
    ): VisionFrame {
        val width = bitmap.width
        val height = bitmap.height
        if (dimensions != (width to height)) {
            finishTracks()
            dimensions = width to height
        }
        if (pixels.size < width * height) pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val frame = preprocessor.prepare(pixels, width, height)
        val prepared = SystemClock.elapsedRealtimeNanos()
        detector.infer(frame)
        val inferred = SystemClock.elapsedRealtimeNanos()
        val detections = detector.decode(frame)
        val decoded = SystemClock.elapsedRealtimeNanos()
        val update = tracker.update(detections) { detection ->
            captured?.let { TrackEvidence(it, crop(bitmap, detection.box)) }
        }
        update.finished.forEach { onTrack(detector.model, it) }
        val tracked = SystemClock.elapsedRealtimeNanos()
        if (completions.size == FPS_WINDOW) completions.removeFirst()
        completions.add(tracked)
        val span = tracked - completions.first()
        val fps = if (span > 0) (completions.size - 1) * 1e9f / span else 0f
        return VisionFrame(update.visible, fps, FrameTiming(prepared - started, inferred - prepared,
            decoded - inferred, tracked - decoded))
    }

    fun finishTracks() = tracker.finish().forEach { onTrack(detector.model, it) }

    override fun close() {
        try {
            finishTracks()
        } finally {
            detector.close()
        }
    }

    private fun crop(bitmap: Bitmap, box: VisionBox): ByteArray {
        val xMargin = .1f * box.width
        val yMargin = .1f * box.height
        val left = floor(box.left - xMargin).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(box.top - yMargin).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(box.right + xMargin).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(box.bottom + yMargin).toInt().coerceIn(top + 1, bitmap.height)
        val region = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scale = minOf(1.0, 256.0 / maxOf(region.width, region.height))
        var resized = region
        return try {
            if (scale < 1) resized = Bitmap.createScaledBitmap(region,
                (region.width * scale).roundToInt().coerceAtLeast(1),
                (region.height * scale).roundToInt().coerceAtLeast(1), true)
            ByteArrayOutputStream().use {
                check(resized.compress(Bitmap.CompressFormat.JPEG, 85, it))
                it.toByteArray().also { jpeg -> check(jpeg.size in 1..200 * 1024) }
            }
        } finally {
            if (resized !== region) resized.recycle()
            if (region !== bitmap) region.recycle()
        }
    }

    private companion object { const val FPS_WINDOW = 12 }
}

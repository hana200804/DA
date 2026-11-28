package com.signa.app.vision.analyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import com.signa.app.domain.model.VisionModel
import com.signa.app.vision.detector.ModelHashException
import com.signa.app.vision.detector.TfliteSignDetector
import com.signa.app.vision.tracker.FinishedTrack
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(TransformExperimental::class)
internal class CameraFrameAnalyzer(
    context: Context,
    private val captureContext: (Long) -> CaptureContext?,
    private val onTrack: (VisionModel, FinishedTrack<TrackEvidence>) -> Unit,
    private val onFrame: (VisionFrame, OutputTransform) -> Unit,
    private val onError: (checksumError: Boolean) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private val assets = context.assets
    private val power = context.getSystemService(PowerManager::class.java)
    private val closed = AtomicBoolean()
    val worker = Executors.newSingleThreadExecutor()
    private val transforms = ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }
    private var pipeline: VisionPipeline? = null
    private var failed = false
    private var skipNextHotFrame = false

    override fun analyze(image: ImageProxy) {
        val started = SystemClock.elapsedRealtimeNanos()
        var bitmap: Bitmap? = null
        var oriented: Bitmap? = null
        try {
            if (closed.get() || failed || shouldSkip()) return
            val transform = transforms.getOutputTransform(image)
            val capture = captureContext(started)
            bitmap = image.toBitmap()
            oriented = bitmap.rotate(image.imageInfo.rotationDegrees)
            val active = pipeline ?: VisionPipeline(TfliteSignDetector.load(assets), onTrack).also { pipeline = it }
            val result = active.process(oriented, capture, started)
            main.execute { if (!closed.get()) onFrame(result, transform) }
        } catch (failure: Exception) {
            fail(failure)
        } catch (failure: LinkageError) {
            fail(failure)
        } finally {
            if (oriented !== bitmap) oriented?.recycle()
            bitmap?.recycle()
            image.close()
        }
    }

    private fun shouldSkip(): Boolean {
        val hot = Build.VERSION.SDK_INT >= 29 &&
            (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE
        val skip = hot && skipNextHotFrame
        skipNextHotFrame = hot && !skipNextHotFrame
        return skip
    }

    private fun fail(failure: Throwable) {
        failed = true
        Log.e("SignaVision", "Không thể phân tích khung camera", failure)
        main.execute { if (!closed.get()) onError(failure is ModelHashException) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute { pipeline?.close() }
        worker.shutdown()
    }
}

internal fun Bitmap.rotate(degrees: Int): Bitmap = if (degrees == 0) this else
    Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { setRotate(degrees.toFloat()) }, false)

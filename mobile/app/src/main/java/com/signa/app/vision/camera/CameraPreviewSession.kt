package com.signa.app.vision.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.annotation.MainThread
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.signa.app.vision.analyzer.CameraFrameAnalyzer

/**
 * Owns this screen's Preview and optional ImageAnalysis use cases.
 * Unbind on PAUSE as well as disposal: a navigation entry can remain STARTED
 * during transitions, but camera access is restricted to the foreground screen.
 * All methods/callbacks run on the main thread.
 */
@MainThread
internal class CameraPreviewSession(
    context: Context,
    private val view: PreviewView,
    private val owner: LifecycleOwner,
    private val createAnalyzer: (() -> CameraFrameAnalyzer)? = null,
    private val onStatus: (CameraPreviewStatus) -> Unit
) : DefaultLifecycleObserver, AutoCloseable {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var provider: ProcessCameraProvider? = null
    private var providerPending = false
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var analyzer: CameraFrameAnalyzer? = null
    private var cameraInfo: CameraInfo? = null
    private var closed = false
    private var hasError = false
    private var streaming = false

    private val openingTimeout = Runnable {
        if (preview != null && !streaming && isForeground()) {
            hasError = true
            onStatus(CameraPreviewStatus.Unavailable)
        }
    }
    private val streamObserver = Observer<PreviewView.StreamState> { state ->
        if (preview != null && isForeground() && !hasError) {
            streaming = state == PreviewView.StreamState.STREAMING
            view.keepScreenOn = streaming
            if (streaming) {
                handler.removeCallbacks(openingTimeout)
                onStatus(CameraPreviewStatus.Streaming)
            } else {
                onStatus(CameraPreviewStatus.Opening)
                handler.removeCallbacks(openingTimeout)
                handler.postDelayed(openingTimeout, OPEN_TIMEOUT_MS)
            }
        }
    }
    private val cameraObserver = Observer<CameraState> { state ->
        if (preview != null && isForeground()) {
            val error = state.error
            if (error != null) {
                hasError = true
                view.keepScreenOn = false
                handler.removeCallbacks(openingTimeout)
                onStatus(when (error.code) {
                    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraPreviewStatus.Busy
                    CameraState.ERROR_CAMERA_DISABLED -> CameraPreviewStatus.Disabled
                    else -> CameraPreviewStatus.Unavailable
                })
            } else if (state.type == CameraState.Type.OPEN && hasError) {
                // Recoverable errors (e.g. another app releasing the camera) can clear.
                hasError = false
                onStatus(CameraPreviewStatus.Opening)
                handler.postDelayed(openingTimeout, OPEN_TIMEOUT_MS)
                streamObserver.onChanged(view.previewStreamState.value ?: PreviewView.StreamState.IDLE)
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (view.display?.displayId == displayId) {
                val rotation = view.display.rotation
                preview?.targetRotation = rotation
                analysis?.targetRotation = rotation
            }
        }
    }
    private val layoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> start() }

    init {
        view.previewStreamState.observe(owner, streamObserver)
        view.addOnLayoutChangeListener(layoutListener)
        displayManager.registerDisplayListener(displayListener, handler)
        owner.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) = start()
    override fun onPause(owner: LifecycleOwner) {
        releasePreview()
        if (!closed) onStatus(CameraPreviewStatus.Paused)
    }
    override fun onDestroy(owner: LifecycleOwner) = close()

    private fun isForeground() = !closed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun start() {
        if (!isForeground() || preview != null || (createAnalyzer != null && view.viewPort == null)) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus(CameraPreviewStatus.PermissionRequired)
            return
        }
        onStatus(CameraPreviewStatus.Opening)
        provider?.let { bind(it); return }
        if (providerPending) return
        providerPending = true
        try {
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                providerPending = false
                if (!closed) {
                    try {
                        provider = future.get()
                        if (isForeground()) bind(requireNotNull(provider))
                    } catch (_: Exception) {
                        if (isForeground()) onStatus(CameraPreviewStatus.Unavailable)
                    }
                }
            }, ContextCompat.getMainExecutor(appContext))
        } catch (_: Exception) {
            providerPending = false
            onStatus(CameraPreviewStatus.Unavailable)
        }
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        if (!isForeground() || preview != null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus(CameraPreviewStatus.PermissionRequired)
            return
        }
        try {
            if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                onStatus(CameraPreviewStatus.NoRearCamera)
                return
            }
            hasError = false
            streaming = false
            val useCase = Preview.Builder()
                .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build())
                .build()
            preview = useCase
            useCase.setSurfaceProvider(view.surfaceProvider)
            val camera = if (createAnalyzer == null) {
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
            } else {
                val frameAnalyzer = createAnalyzer().also { analyzer = it }
                val frames = ImageAnalysis.Builder()
                    .setTargetRotation(useCase.targetRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build())
                    .build().also { analysis = it }
                frames.setAnalyzer(frameAnalyzer.worker, frameAnalyzer)
                val group = UseCaseGroup.Builder().setViewPort(checkNotNull(view.viewPort))
                    .addUseCase(useCase).addUseCase(frames).build()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            }
            cameraInfo = camera.cameraInfo
            camera.cameraInfo.cameraState.observe(owner, cameraObserver)
            handler.postDelayed(openingTimeout, OPEN_TIMEOUT_MS)
        } catch (_: SecurityException) {
            releasePreview()
            onStatus(CameraPreviewStatus.PermissionRequired)
        } catch (_: Exception) {
            releasePreview()
            onStatus(CameraPreviewStatus.Unavailable)
        }
    }

    private fun releasePreview() {
        handler.removeCallbacks(openingTimeout)
        cameraInfo?.cameraState?.removeObserver(cameraObserver)
        cameraInfo = null
        val ownedPreview = preview
        analysis?.let { frames ->
            frames.clearAnalyzer()
            if (ownedPreview != null) provider?.unbind(ownedPreview, frames)
        }
        analyzer?.close()
        analyzer = null
        preview = null
        streaming = false
        view.keepScreenOn = false
        if (ownedPreview != null) {
            if (analysis == null) provider?.unbind(ownedPreview)
            ownedPreview.setSurfaceProvider(null)
        }
        analysis = null
    }

    override fun close() {
        if (closed) return
        closed = true
        owner.lifecycle.removeObserver(this)
        view.removeOnLayoutChangeListener(layoutListener)
        view.previewStreamState.removeObserver(streamObserver)
        displayManager.unregisterDisplayListener(displayListener)
        releasePreview()
    }

    private companion object { const val OPEN_TIMEOUT_MS = 15_000L }
}

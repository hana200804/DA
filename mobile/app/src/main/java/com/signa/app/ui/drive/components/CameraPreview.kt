package com.signa.app.ui.drive.components

import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.signa.app.R
import com.signa.app.domain.model.VisionModel
import com.signa.app.vision.analyzer.CameraFrameAnalyzer
import com.signa.app.vision.analyzer.CaptureContext
import com.signa.app.vision.analyzer.TrackEvidence
import com.signa.app.vision.analyzer.VisionFrame
import com.signa.app.vision.camera.CameraPreviewSession
import com.signa.app.vision.camera.CameraPreviewStatus
import com.signa.app.vision.tracker.FinishedTrack

/** Compose adapter for the camera surface and the shared bounding box overlay. */
@androidx.annotation.OptIn(TransformExperimental::class)
@Composable
internal fun CameraPreview(
    enabled: Boolean,
    attempt: Int,
    onStatus: (CameraPreviewStatus) -> Unit,
    captureContext: (Long) -> CaptureContext?,
    onTrack: (VisionModel, FinishedTrack<TrackEvidence>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestStatus by rememberUpdatedState(onStatus)
    val latestCapture by rememberUpdatedState(captureContext)
    val latestTrack by rememberUpdatedState(onTrack)
    val description = stringResource(R.string.camera_live_description)
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var result by remember(view, enabled, attempt) { mutableStateOf<Pair<VisionFrame, CoordinateTransform>?>(null) }
    Box(modifier) {
        AndroidView(factory = { view }, update = { it.contentDescription = description }, modifier = Modifier.matchParentSize())
        result?.let { (frame, transform) ->
            VisionOverlay(frame, { rect, _ -> transform.mapRect(rect) }, Modifier.matchParentSize())
        }
    }
    DisposableEffect(view, owner, enabled, attempt) {
        var analysisError: CameraPreviewStatus? = null
        val session = if (enabled) CameraPreviewSession(
            context,
            view,
            owner,
            createAnalyzer = {
                analysisError = null
                CameraFrameAnalyzer(
                    context,
                    captureContext = { latestCapture(it) },
                    onTrack = { model, track -> latestTrack(model, track) },
                    onFrame = { frame, source ->
                        result = view.outputTransform?.let { frame to CoordinateTransform(source, it) }
                    },
                    onError = { checksumError ->
                        result = null
                        val error = if (checksumError) CameraPreviewStatus.ModelHashError else CameraPreviewStatus.AnalysisError
                        analysisError = error
                        latestStatus(error)
                    }
                )
            },
            onStatus = {
                if (it != CameraPreviewStatus.Streaming) result = null
                latestStatus(if (it == CameraPreviewStatus.Streaming) analysisError ?: it else it)
            }
        ) else null
        onDispose { session?.close() }
    }
}

package com.signa.app.ui.drive

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.signa.app.R
import com.signa.app.ui.components.SignaPrimaryButton
import com.signa.app.ui.drive.components.VisionOverlay
import com.signa.app.vision.analyzer.VisionFrame
import com.signa.app.vision.detector.ModelHashException
import com.signa.app.vision.replay.replayVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@RequiresApi(28)
@Composable
internal fun VideoReplayRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var uri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var complete by remember(attempt) { mutableIntStateOf(0) }
    var error by remember(attempt) { mutableIntStateOf(0) }
    val preview = remember(attempt) { MutableStateFlow<Pair<Bitmap, VisionFrame>?>(null) }
    val currentFrame by preview.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        if (selected != null) {
            uri = selected
            attempt++
        }
    }
    LaunchedEffect(attempt) {
        val selected = uri ?: return@LaunchedEffect
        try {
            complete = replayVideo(context, selected) { bitmap, frame -> preview.value = bitmap to frame }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelHashException) {
            error = R.string.camera_model_integrity_error
            Log.e("SignaReplay", "SHA-256 mô hình không hợp lệ", failure)
        } catch (failure: Exception) {
            error = R.string.video_open_failed
            Log.e("SignaReplay", "Phát lại video thất bại", failure)
        } catch (failure: LinkageError) {
            error = R.string.camera_analysis_error
            Log.e("SignaReplay", "Không nạp được LiteRT", failure)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = (maxHeight * .62f).coerceIn(200.dp, 480.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.video_replay), style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.fillMaxWidth().height(previewHeight).clip(RoundedCornerShape(20.dp)).background(Color(0xFF101820)),
                contentAlignment = Alignment.Center) {
                val result = currentFrame
                when {
                    result != null -> {
                        val (bitmap, frame) = result
                        Image(bitmap.asImageBitmap(), contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
                        VisionOverlay(frame, { box, size ->
                            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                            val x = (size.width - bitmap.width * scale) / 2
                            val y = (size.height - bitmap.height * scale) / 2
                            box.set(box.left * scale + x, box.top * scale + y, box.right * scale + x, box.bottom * scale + y)
                        }, Modifier.matchParentSize())
                    }
                    error == 0 && uri != null -> CircularProgressIndicator(color = Color.White)
                }
            }
            when {
                error != 0 -> Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                complete > 0 -> Text(stringResource(R.string.video_frame_count, complete))
                uri != null -> Text(stringResource(R.string.video_processing))
            }
            SignaPrimaryButton(stringResource(R.string.video_pick), { picker.launch("video/*") }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
        }
    }
}

package com.signa.app.ui.recognition

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.signa.app.data.remote.backend.dto.DetectedPhoto
import com.signa.app.ui.components.SignaPage
import kotlin.math.roundToInt

@Composable
internal fun PhotoRecognitionScreen(
    state: PhotoRecognitionState,
    signedIn: Boolean,
    onBack: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onRefreshModels: () -> Unit,
    onModel: (String?) -> Unit,
    onDetect: () -> Unit,
    onSignIn: () -> Unit,
    onSubmit: (Int, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var signCode by rememberSaveable { mutableStateOf("") }
    SignaPage("Nhận diện ảnh", onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTakePhoto, enabled = !state.busy) { Text("Chụp ảnh") }
            OutlinedButton(onClick = onChoosePhoto, enabled = !state.busy) { Text("Chọn ảnh") }
        }
        state.bitmap?.let { MarkedPhoto(it, state.result) }
        val defaultModel = state.models?.defaultModel
        val defaultLabel = if (defaultModel == null) "Mặc định" else "Mặc định (${modelLabel(defaultModel)})"
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !state.busy && state.models != null) {
                Text(state.model?.let(::modelLabel) ?: defaultLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { onModel(null); expanded = false })
                state.models?.models?.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(modelLabel(model.modelName)) },
                        onClick = { onModel(model.modelName); expanded = false }
                    )
                }
            }
        }
        if (state.modelsLoading) Text("Đang tải mô hình…")
        state.modelsError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefreshModels) { Text("Tải lại mô hình") }
        }
        Button(onClick = onDetect, enabled = !state.busy && state.bitmap != null) { Text("Nhận diện") }
        if (state.busy) CircularProgressIndicator()
        state.message?.let { Text(it) }
        state.result?.let { result ->
            Text("${modelLabel(result.model.name)} · ${result.inferenceMs} ms", style = MaterialTheme.typography.titleMedium)
            if (result.detections.isEmpty()) Text("Không tìm thấy biển báo")
            result.detections.forEachIndexed { index, detection ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            listOfNotNull(detection.signTypeCode, detection.name).joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium
                        )
                        detection.description?.let { Text(it) }
                        Text("Độ tin cậy ${(detection.confidence * 100).roundToInt()}%")
                        when {
                            index in state.queued -> Text("Đã lưu đề xuất")
                            signedIn -> TextButton(
                                enabled = !state.busy,
                                onClick = { editing = index; signCode = detection.signTypeCode }
                            ) { Text("Gửi lên bản đồ") }
                            else -> TextButton(onClick = onSignIn) { Text("Đăng nhập để gửi đề xuất") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { index ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Gửi đề xuất") },
            text = {
                OutlinedTextField(
                    value = signCode,
                    onValueChange = { signCode = it },
                    label = { Text("Mã biển") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = signCode.isNotBlank() && signCode.trim().length <= 30, onClick = {
                    editing = null
                    onSubmit(index, signCode)
                }) { Text("Gửi") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Hủy") } }
        )
    }
}

@Composable
private fun MarkedPhoto(bitmap: Bitmap, result: DetectedPhoto?) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(Modifier.fillMaxWidth().aspectRatio((bitmap.width.toFloat() / bitmap.height).coerceIn(.4f, 3f))) {
        Image(image, "Ảnh nhận diện", Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        if (result == null || result.image.width <= 0 || result.image.height <= 0) return@Box
        Canvas(Modifier.matchParentSize()) {
            val fit = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val drawnWidth = bitmap.width * fit
            val drawnHeight = bitmap.height * fit
            val scaleX = drawnWidth / result.image.width
            val scaleY = drawnHeight / result.image.height
            val insetX = (size.width - drawnWidth) / 2
            val insetY = (size.height - drawnHeight) / 2
            result.detections.forEach { detection ->
                val box = detection.box
                val left = (box.x * scaleX).toFloat().coerceIn(0f, drawnWidth)
                val top = (box.y * scaleY).toFloat().coerceIn(0f, drawnHeight)
                val right = ((box.x + box.width) * scaleX).toFloat().coerceIn(left, drawnWidth)
                val bottom = ((box.y + box.height) * scaleY).toFloat().coerceIn(top, drawnHeight)
                drawRect(Color.Black, Offset(insetX + left, insetY + top), Size(right - left, bottom - top), style = Stroke(5.dp.toPx()))
                drawRect(Color.Yellow, Offset(insetX + left, insetY + top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private fun modelLabel(model: String): String = when (model) {
    "yolov5" -> "YOLOv5"
    "yolov8" -> "YOLOv8"
    "detr" -> "DETR"
    else -> model
}

package com.signa.app.ui.drive.components

import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signa.app.R
import com.signa.app.vision.analyzer.VisionFrame
import kotlin.math.roundToInt

/** mapBox projects the original frame's coordinates into this viewport. */
@Composable
internal fun VisionOverlay(
    frame: VisionFrame,
    mapBox: (RectF, Size) -> Unit,
    modifier: Modifier = Modifier
) {
    val textSize = with(LocalDensity.current) { 12.sp.toPx() }
    val text = remember(textSize) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = android.graphics.Color.WHITE
        }
    }
    val background = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6101820.toInt() }
    }
    val labels = frame.detections.map {
        "${it.label.code} · ${it.label.name} · ${(it.confidence * 100).roundToInt()}%"
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().semantics { if (labels.isNotEmpty()) contentDescription = labels.joinToString("\n") }) {
            val inset = 5.dp.toPx()
            val lineHeight = text.descent() - text.ascent()
            val box = RectF()
            frame.detections.forEachIndexed { index, detection ->
                val bounds = detection.box
                box.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
                mapBox(box, size)
                if (!box.intersect(0f, 0f, size.width, size.height)) return@forEachIndexed
                drawRect(Color(0xFF4EF8AC), Offset(box.left, box.top), Size(box.width(), box.height()),
                    style = Stroke(2.dp.toPx()))
                val available = size.width - 2 * inset
                if (available <= 0) return@forEachIndexed
                val label = TextUtils.ellipsize(labels[index], text, available, TextUtils.TruncateAt.END).toString()
                val labelWidth = (text.measureText(label) + 2 * inset).coerceAtMost(size.width)
                val x = box.left.coerceAtMost(size.width - labelWidth)
                val y = (box.top - lineHeight - inset).coerceAtLeast(0f)
                drawContext.canvas.nativeCanvas.apply {
                    drawRect(x, y, x + labelWidth, y + lineHeight + inset, background)
                    drawText(label, x + inset, y + inset / 2 - text.ascent(), text)
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), color = Color(0xE6101820),
            shape = RoundedCornerShape(6.dp)) {
            Text(stringResource(R.string.vision_fps, frame.fps), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

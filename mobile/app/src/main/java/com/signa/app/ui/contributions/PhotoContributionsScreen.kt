package com.signa.app.ui.contributions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.signa.app.ui.components.SignaBackHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PhotoContributionsScreen(
    state: PhotoContributionsState,
    onBack: () -> Unit,
    onOpenSign: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoad: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { SignaBackHeader("Đề xuất của tôi", onBack) }
        if (!state.signedIn && !state.loading) {
            item { Text("Bạn cần đăng nhập để xem đề xuất.") }
        } else {
            if (state.signedIn) {
                item { TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Làm mới") } }
            }
            items(state.local, key = { "local:${it.clientId}" }) {
                PhotoContributionCard(it.signTypeCode, it.state, it.capturedAt, it.error)
            }
            items(state.items, key = { "server:${it.id}" }) { item ->
                PhotoContributionCard(item.code, item.status, item.date, item.reason, item.signId?.let { { onOpenSign(it) } })
            }
            item {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.failed -> {
                        Text("Không tải được đề xuất. Vui lòng thử lại.")
                        TextButton(onClick = onLoad) { Text("Thử lại") }
                    }
                    state.items.isEmpty() && state.local.isEmpty() -> Text("Bạn chưa có đề xuất nào.")
                    state.hasMore -> TextButton(onClick = onLoad) { Text("Tải thêm") }
                }
            }
        }
    }
}

@Composable
private fun PhotoContributionCard(
    code: String,
    status: String,
    date: String,
    reason: String?,
    onOpenSign: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(code, style = MaterialTheme.typography.titleMedium)
            Text(when (status) {
                "QUEUED" -> "Đang chờ gửi"
                "FAILED" -> "Gửi không thành công"
                "RECEIVED", "GROUPED" -> "Đang chờ duyệt"
                "ACCEPTED" -> "Đã duyệt"
                "REJECTED" -> "Đã từ chối"
                else -> status
            })
            val localDate = runCatching {
                Instant.parse(date).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            }.getOrDefault(date)
            Text(localDate, style = MaterialTheme.typography.bodySmall)
            reason?.takeIf(String::isNotBlank)?.let { Text(it) }
            onOpenSign?.let { TextButton(onClick = it) { Text("Xem biển trên bản đồ") } }
        }
    }
}

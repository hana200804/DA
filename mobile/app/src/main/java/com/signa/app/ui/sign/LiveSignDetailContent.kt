package com.signa.app.ui.sign

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signa.app.BuildConfig
import com.signa.app.R
import com.signa.app.data.remote.backend.dto.ReportReason
import com.signa.app.data.remote.backend.dto.SignRating
import com.signa.app.ui.components.SignaPage
import com.signa.app.ui.components.SignaStatusLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun LiveSignDetailContent(
    onBack: () -> Unit,
    onSignInRequired: () -> Unit,
    viewModel: LiveSignDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val catalog by viewModel.categories.collectAsStateWithLifecycle()
    var showReport by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.reportSubmitted) {
        if (state.reportSubmitted) showReport = false
    }
    SignaPage(stringResource(R.string.sign_detail), onBack = onBack) {
        val detail = state.detail
        if (state.loading) CircularProgressIndicator()
        if (state.loadFailed) {
            Text(stringResource(R.string.data_load_error))
            TextButton(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
        }
        if (detail == null) return@SignaPage
        detail.coverCropUrl?.let { url ->
            SignRemoteImage(url, stringResource(R.string.live_sign_cover), Modifier.fillMaxWidth().height(200.dp)) {
                Text(stringResource(R.string.live_sign_image_unavailable))
            }
        }
        val type = state.type
        val category = catalog?.categories?.firstOrNull { it.code == detail.categoryCode }?.name
        if (type == null) {
            Text(detail.signTypeCode, style = MaterialTheme.typography.titleLarge)
            Text(category ?: detail.categoryCode)
        } else {
            SignTypeDescription(type, category)
        }
        SignaStatusLabel(stringResource(when {
            BuildConfig.USE_FAKE_BACKEND -> R.string.sample_data_label
            detail.status == "UNDER_REVIEW" -> R.string.live_sign_under_review
            else -> R.string.live_sign_approved
        }))
        Text(stringResource(R.string.live_sign_confidence, (detail.confidence * 100).roundToInt()))
        Text(stringResource(R.string.live_sign_votes, detail.confirmCount, detail.denyCount))
        Text(stringResource(R.string.live_sign_updated, signDate(detail.updatedAt)))
        if (state.actionMessage != null && !showReport) Text(stringResource(requireNotNull(state.actionMessage)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignRating.entries.forEach { rating ->
                val title = if (rating == SignRating.CONFIRM) R.string.live_sign_confirm else R.string.live_sign_deny
                FilterChip(
                    selected = detail.myRating == rating,
                    onClick = { if (session == null) onSignInRequired() else viewModel.rate(rating) },
                    enabled = !state.busy && !state.loading,
                    label = { Text(stringResource(title)) }
                )
            }
        }
        OutlinedButton(
            enabled = !state.busy && !state.loading,
            onClick = {
                if (session == null) onSignInRequired() else {
                    viewModel.clearMessage()
                    showReport = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.report_sign)) }
    }
    if (showReport) {
        val resolver = LocalContext.current.contentResolver
        SignReportDialog(
            busy = state.busy,
            message = state.actionMessage,
            onDismiss = { viewModel.clearMessage(); showReport = false },
            onSubmit = { reason, description, uri ->
                viewModel.report(reason, description) { loadReportPhoto(resolver, uri) }
            }
        )
    }
}

@Composable
private fun SignReportDialog(
    busy: Boolean,
    message: Int?,
    onDismiss: () -> Unit,
    onSubmit: (ReportReason, String, Uri?) -> Unit
) {
    var reason by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = uri
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sign_report_dialog)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportReason.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().height(40.dp).selectable(
                        selected = reason == option, enabled = !busy, role = Role.RadioButton, onClick = { reason = option }
                    ), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(reason == option, onClick = null, enabled = !busy)
                        Text(stringResource(option.title()))
                    }
                }
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(stringResource(R.string.sign_report_description)) }, maxLines = 4)
                TextButton(
                    enabled = !busy,
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                ) { Text(stringResource(if (photo == null) R.string.sign_report_add_photo else R.string.sign_report_change_photo)) }
                if (photo != null) TextButton(enabled = !busy, onClick = { photo = null }) {
                    Text(stringResource(R.string.sign_report_remove_photo))
                }
                if (message != null) Text(stringResource(message), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = reason != null && !busy, onClick = {
                reason?.let { onSubmit(it, description, photo) }
            }) { Text(stringResource(R.string.sign_report_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.close)) } }
    )
}

internal fun ReportReason.title(): Int = when (this) {
    ReportReason.NOT_EXIST -> R.string.sign_report_missing
    ReportReason.WRONG_TYPE -> R.string.sign_report_wrong_type
    ReportReason.WRONG_LOCATION -> R.string.sign_report_wrong_location
    ReportReason.OTHER -> R.string.sign_report_other
}

internal fun signDate(value: String): String = try {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
} catch (_: java.time.DateTimeException) {
    value
}

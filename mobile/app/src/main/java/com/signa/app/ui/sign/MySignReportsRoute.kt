package com.signa.app.ui.sign

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signa.app.R
import com.signa.app.data.remote.backend.dto.ReportResolution
import com.signa.app.data.remote.backend.dto.ReportStatus
import com.signa.app.ui.components.SignaPage

@Composable
internal fun MySignReportsRoute(
    onBack: () -> Unit,
    onSignInRequired: () -> Unit,
    onOpenSign: (String) -> Unit,
    viewModel: MySignReportsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SignaPage(stringResource(R.string.my_sign_reports), onBack = onBack) {
        when (state.signedIn) {
            null -> CircularProgressIndicator()
            false -> TextButton(onClick = onSignInRequired) { Text(stringResource(R.string.my_sign_reports_sign_in)) }
            true -> {
                state.reports.forEach { report ->
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(14.dp)) {
                            Text(stringResource(report.reason.title()), style = MaterialTheme.typography.titleMedium)
                            Text("${stringResource(report.status.title())} · ${signDate(report.createdAt)}")
                            report.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            when (report.resolution) {
                                ReportResolution.EDITED -> Text(stringResource(R.string.sign_report_edited))
                                ReportResolution.REMOVED -> Text(stringResource(R.string.sign_report_removed))
                                null -> Unit
                            }
                            report.resolutionNote?.let { Text(it) }
                            report.resolvedAt?.let { Text(signDate(it)) }
                            if (report.resolution != ReportResolution.REMOVED) {
                                TextButton(onClick = { onOpenSign(report.signId) }) { Text(stringResource(R.string.view_detail)) }
                            }
                        }
                    }
                }
                when {
                    state.loading -> CircularProgressIndicator()
                    state.failed -> {
                        Text(stringResource(R.string.data_load_error))
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                    }
                    state.hasMore -> TextButton(onClick = viewModel::more) { Text(stringResource(R.string.my_sign_reports_more)) }
                    state.reports.isEmpty() -> Text(stringResource(R.string.my_sign_reports_empty))
                }
            }
        }
    }
}

private fun ReportStatus.title(): Int = when (this) {
    ReportStatus.PENDING -> R.string.sign_report_pending
    ReportStatus.ACCEPTED -> R.string.sign_report_accepted
    ReportStatus.DISMISSED -> R.string.sign_report_dismissed
}

package com.signa.app.ui.contributions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ContributionsRoute(
    onBack: () -> Unit,
    onOpenSign: (String) -> Unit,
    viewModel: PhotoContributionsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PhotoContributionsScreen(
        state = state,
        onBack = onBack,
        onOpenSign = onOpenSign,
        onRefresh = viewModel::refresh,
        onLoad = viewModel::load
    )
}

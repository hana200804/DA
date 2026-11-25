package com.signa.app.ui.sign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SignDetailRoute(
    onBack: () -> Unit,
    onReport: () -> Unit,
    signId: String? = null,
    onSignInRequired: () -> Unit = {},
    onShowOnMap: (String) -> Unit = {}
) {
    when {
        signId == null -> {
            val viewModel: SignDetailViewModel = hiltViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            SignDetailScreen(state = state, onBack = onBack, onReport = onReport)
        }
        signId.startsWith(CATALOG_TYPE_PREFIX) ->
            SignCatalogTypeDetail(signId.removePrefix(CATALOG_TYPE_PREFIX), onBack, onShowOnMap)
        else -> LiveSignDetailContent(onBack, onSignInRequired)
    }
}

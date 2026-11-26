package com.signa.app.ui.sign

import androidx.compose.runtime.Composable

@Composable
fun SignLookupRoute(
    onAction: () -> Unit,
    onOpenSign: (String) -> Unit
) {
    SignLookupScreen(state = SignLookupUiState(), onAction = onAction) {
        SignCatalogContent(onOpenType = onOpenSign)
    }
}

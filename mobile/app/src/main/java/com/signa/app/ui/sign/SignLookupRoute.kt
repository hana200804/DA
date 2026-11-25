package com.signa.app.ui.sign

import androidx.compose.runtime.Composable
import com.signa.app.ui.recognition.PhotoRecognitionRoute

@Composable
fun SignLookupRoute(
    onAction: () -> Unit,
    onOpenSign: (String) -> Unit
) {
    PhotoRecognitionRoute(onSignIn = onAction) { takePhoto, choosePhoto ->
        SignLookupScreen(
            state = SignLookupUiState(),
            onAction = takePhoto,
            onChoosePhoto = choosePhoto
        ) {
            SignCatalogContent(onOpenType = onOpenSign)
        }
    }
}

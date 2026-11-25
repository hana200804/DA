package com.signa.app.ui.sign

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signa.app.ui.recognition.PhotoRecognitionRoute

@Composable
fun SignLookupRoute(
    onAction: () -> Unit,
    onOpenSign: (String) -> Unit,
    viewModel: SignLookupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PhotoRecognitionRoute(onSignIn = onAction) { takePhoto, choosePhoto ->
        SignLookupScreen(state = state, onAction = takePhoto, onChoosePhoto = choosePhoto, onOpenSign = onOpenSign)
    }
}

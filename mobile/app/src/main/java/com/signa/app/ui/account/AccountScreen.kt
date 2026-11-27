package com.signa.app.ui.account

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.signa.app.R
import com.signa.app.ui.components.SignaPage
import com.signa.app.ui.components.SignaPrimaryButton
import com.signa.app.ui.auth.authErrorText

@Composable
fun AccountScreen(
    state: AccountUiState,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onContributions: () -> Unit,
    onChangePassword: () -> Unit,
    onOpenProfile: () -> Unit,
    onUpdateProfile: (String) -> Unit
) {
    val user = state.user
    if (!state.isLoading && user != null) {
        ProfileScreen(
            user, onLogout, onContributions, onChangePassword,
            errorMessage = state.operationError?.let { authErrorText(it, state.retryAfterSeconds) },
            isSigningOut = state.isSigningOut,
            vehicleType = state.vehicleType,
            onOpenProfile = onOpenProfile,
            onUpdateProfile = onUpdateProfile,
            isUpdatingProfile = state.isUpdatingProfile
        )
        return
    }
    SignaPage(stringResource(R.string.nav_account)) {
        when {
            state.isLoading -> CircularProgressIndicator()
            state.hasError -> Text(stringResource(R.string.data_load_error))
            else -> {
                AccountHeader("S", stringResource(R.string.guest_name), stringResource(R.string.guest_profile_hint))
                Text(stringResource(R.string.guest_account_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                SignaPrimaryButton(stringResource(R.string.login), onLogin, Modifier.fillMaxWidth())
            }
        }
    }
}

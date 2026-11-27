package com.signa.app.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AccountRoute(
    onLogin: () -> Unit,
    onContributions: () -> Unit,
    onChangePassword: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountScreen(
        state = state,
        onLogin = onLogin,
        onLogout = viewModel::logout,
        onContributions = onContributions,
        onChangePassword = onChangePassword,
        onOpenProfile = viewModel::refreshProfile,
        onUpdateProfile = viewModel::updateProfile
    )
}

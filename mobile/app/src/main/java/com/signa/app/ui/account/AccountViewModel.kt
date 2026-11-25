package com.signa.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.domain.model.AuthError
import com.signa.app.domain.model.AuthException
import com.signa.app.domain.repository.AuthRepository
import com.signa.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: AuthRepository, settings: SettingsRepository
) : ViewModel() {
    private val operationError = MutableStateFlow<AuthError?>(null)
    private val signingOut = MutableStateFlow(false)
    private val updatingProfile = MutableStateFlow(false)
    val state = combine(
        repository.session, operationError, signingOut, updatingProfile,
        settings.settings.catch { emit(com.signa.app.domain.model.AppSettings()) }
    ) { session, error, signingOut, updatingProfile, preferences ->
        AccountUiState(
            user = session?.user, vehicleType = preferences.vehicleType, isLoading = false,
            operationError = error, isSigningOut = signingOut, isUpdatingProfile = updatingProfile
        )
    }.catch { emit(AccountUiState(isLoading = false, hasError = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    fun refreshProfile() = perform(updatingProfile, repository::refreshProfile)

    fun updateProfile(displayName: String) = perform(updatingProfile) {
        repository.updateProfile(displayName)
    }

    fun logout() = perform(signingOut, repository::logout)

    private fun perform(busy: MutableStateFlow<Boolean>, action: suspend () -> Unit) {
        if (signingOut.value || updatingProfile.value) return
        busy.value = true
        operationError.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: AuthException) {
                operationError.value = error.error
            } catch (_: IOException) {
                operationError.value = AuthError.Storage
            } finally {
                busy.value = false
            }
        }
    }
}

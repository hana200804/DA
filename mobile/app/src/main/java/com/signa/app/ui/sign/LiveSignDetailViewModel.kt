package com.signa.app.ui.sign

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.R
import com.signa.app.data.remote.ApiException
import com.signa.app.data.remote.dto.ReportReason
import com.signa.app.data.remote.dto.SignDetailDto
import com.signa.app.data.remote.dto.SignRating
import com.signa.app.data.remote.dto.SignReportDto
import com.signa.app.data.remote.dto.SignTypeDto
import com.signa.app.data.repository.ReportPhoto
import com.signa.app.data.repository.SignCatalogRepository
import com.signa.app.data.repository.SignMapRepository
import com.signa.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveSignDetailState(
    val detail: SignDetailDto? = null,
    val type: SignTypeDto? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val loadFailed: Boolean = false,
    val actionMessage: Int? = null,
    val reportSubmitted: Boolean = false
)

@HiltViewModel
class LiveSignDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SignMapRepository,
    private val catalog: SignCatalogRepository,
    private val auth: AuthRepository
) : ViewModel() {
    private val id = requireNotNull(savedState.get<String>(SIGN_ID_ARGUMENT))
    private val mutableState = MutableStateFlow(LiveSignDetailState())
    val state = mutableState.asStateFlow()
    val session = auth.session.catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val categories = catalog.snapshots.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        load()
    }

    fun load() {
        if (state.value.busy) return
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            signDataCall { repository.detail(id) }
                .onSuccess { detail ->
                    val cached = signDataCall { catalog.snapshots.first() }.getOrNull()
                        ?.types?.items?.firstOrNull { it.code == detail.signTypeCode }
                    mutableState.update { it.copy(detail = detail, type = cached, loading = false) }
                    signDataCall { catalog.type(detail.signTypeCode) }.getOrNull()?.let { type ->
                        mutableState.update { it.copy(type = type) }
                    }
                }
                .onFailure { mutableState.update { it.copy(loadFailed = true, loading = false) } }
        }
    }

    fun rate(rating: SignRating) = submit {
        repository.rate(id, rating)
        mutableState.update { state ->
            state.copy(detail = state.detail?.withRating(rating), actionMessage = R.string.live_sign_rating_saved)
        }
        signDataCall { repository.detail(id) }.getOrNull()?.let { updated ->
            mutableState.update { it.copy(detail = updated) }
        }
    }

    fun report(reason: ReportReason, description: String, photo: suspend () -> ReportPhoto?) {
        if (state.value.busy) return
        val text = description.trim()
        val validationError = when {
            reason == ReportReason.OTHER && text.isBlank() -> R.string.sign_report_description_required
            text.codePointCount(0, text.length) > 1000 -> R.string.sign_report_description_limit
            else -> null
        }
        if (validationError != null) {
            mutableState.update { it.copy(actionMessage = validationError) }
            return
        }
        submit {
            repository.report(id, SignReportDto(reason, text.ifBlank { null }), photo())
            mutableState.update { it.copy(reportSubmitted = true, actionMessage = R.string.sign_report_sent) }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(actionMessage = null, reportSubmitted = false) }
    }

    private fun submit(action: suspend () -> Unit) {
        if (state.value.busy || state.value.loading) return
        if (session.value == null) return
        mutableState.update { it.copy(busy = true, actionMessage = null) }
        viewModelScope.launch {
            signDataCall { action() }.onFailure { failure ->
                val message = when {
                    failure is SignReportPhotoException -> R.string.sign_report_bad_photo
                    failure is ApiException && failure.status == 409 -> R.string.sign_feedback_conflict
                    failure is ApiException && failure.status == 403 -> R.string.sign_feedback_forbidden
                    failure is ApiException && failure.status == 422 -> R.string.sign_own_submission_rating
                    failure is ApiException && failure.status in setOf(413, 415) -> R.string.sign_report_bad_photo
                    else -> R.string.sign_feedback_failed
                }
                mutableState.update { it.copy(actionMessage = message) }
            }
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun SignDetailDto.withRating(rating: SignRating): SignDetailDto {
        if (myRating == rating) return this
        return copy(
            confirmCount = (confirmCount + delta(SignRating.CONFIRM, myRating, rating)).coerceAtLeast(0),
            denyCount = (denyCount + delta(SignRating.DENY, myRating, rating)).coerceAtLeast(0),
            myRating = rating
        )
    }

    private fun delta(value: SignRating, old: SignRating?, new: SignRating): Int = when (value) {
        new -> 1
        old -> -1
        else -> 0
    }
}

internal class SignReportPhotoException : IllegalArgumentException()

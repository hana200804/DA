package com.signa.app.ui.sign

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.data.local.database.SignCatalogSnapshot
import com.signa.app.data.remote.dto.SignTypeDto
import com.signa.app.data.repository.SignCatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal const val CATALOG_TYPE_PREFIX = "catalog:"
internal const val CATALOG_MAP_ARGUMENT = "catalogMapCode"

internal fun normalizeSignSearch(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").replace('đ', 'd').replace('Đ', 'D').lowercase(Locale.ROOT)

internal suspend fun <T> signDataCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}

@HiltViewModel
class SignCatalogViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SignCatalogRepository
) : ViewModel() {
    private val code = savedState.get<String>(SIGN_ID_ARGUMENT)?.removePrefix(CATALOG_TYPE_PREFIX)

    private val mutableType = MutableStateFlow<SignTypeDto?>(null)
    val type = mutableType.asStateFlow()

    private val mutableLoading = MutableStateFlow(true)
    val loading = mutableLoading.asStateFlow()

    private val mutableFailed = MutableStateFlow(false)
    val failed = mutableFailed.asStateFlow()
    val catalog: StateFlow<SignCatalogSnapshot?> = repository.snapshots
        .catch { mutableFailed.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutableLoading.value = true
            mutableFailed.value = false
            val result = if (code == null) signDataCall { repository.refresh() }
            else signDataCall { repository.type(code) }.onSuccess { mutableType.value = it }
            mutableFailed.value = result.isFailure
            mutableLoading.value = false
        }
    }

}

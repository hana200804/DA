package com.signa.app.ui.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.data.local.database.SignCatalogSnapshot
import com.signa.app.data.local.preferences.SignMapFilters
import com.signa.app.data.repository.SignCatalogRepository
import com.signa.app.data.repository.SignMapRepository
import com.signa.app.data.repository.SignViewport
import com.signa.app.data.repository.VisibleSigns
import com.signa.app.ui.sign.CATALOG_MAP_ARGUMENT
import com.signa.app.ui.sign.signDataCall
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignViewportUiState(
    val visible: VisibleSigns = VisibleSigns(),
    val selectedId: String? = null,
    val loading: Boolean = true,
    val hasError: Boolean = false
)

private data class ViewportQuery(
    val viewport: SignViewport?,
    val categories: Set<String>,
    val signType: String?,
    val retry: Int
)

@HiltViewModel
class SignViewportViewModel(
    private val savedState: SavedStateHandle,
    repository: SignMapRepository,
    catalogRepository: SignCatalogRepository,
    categories: Flow<Set<String>>,
    private val toggle: suspend (String) -> Unit,
    private val clear: suspend () -> Unit
) : ViewModel() {
    @Inject constructor(
        savedState: SavedStateHandle,
        repository: SignMapRepository,
        catalogRepository: SignCatalogRepository,
        filters: SignMapFilters
    ) : this(savedState, repository, catalogRepository, filters.categories, filters::toggle, filters::clear)

    val activeCategories = categories.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val signType = savedState.getStateFlow<String?>(CATALOG_MAP_ARGUMENT, null)
    val catalog = catalogRepository.snapshots.catch { }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null as SignCatalogSnapshot?)

    private val viewport = MutableStateFlow<SignViewport?>(null)
    private val retryCount = MutableStateFlow(0)
    private val mutableState = MutableStateFlow(SignViewportUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch { signDataCall { catalogRepository.refresh() } }
        viewModelScope.launch {
            combine(viewport, categories, signType, retryCount, ::ViewportQuery).collectLatest { query ->
                val currentViewport = query.viewport ?: return@collectLatest
                mutableState.update { it.copy(loading = true, hasError = false) }
                delay(300)
                signDataCall { repository.visible(currentViewport, query.categories, setOfNotNull(query.signType)) }
                    .onSuccess { result -> mutableState.update { it.copy(visible = result, loading = false) } }
                    .onFailure { mutableState.update { it.copy(visible = VisibleSigns(), loading = false, hasError = true) } }
            }
        }
    }

    fun cameraIdle(value: SignViewport) {
        viewport.value = value
    }

    fun select(id: String) {
        mutableState.update { it.copy(selectedId = id) }
    }

    fun removeSignTypeFilter() {
        savedState[CATALOG_MAP_ARGUMENT] = null as String?
    }

    fun toggleCategory(code: String) {
        editFilters { toggle(code) }
    }

    fun clearCategories() {
        editFilters { clear() }
    }

    fun retry() {
        retryCount.update { it + 1 }
    }

    private fun editFilters(block: suspend () -> Unit) {
        viewModelScope.launch {
            signDataCall { block() }.onFailure { mutableState.update { it.copy(hasError = true) } }
        }
    }
}

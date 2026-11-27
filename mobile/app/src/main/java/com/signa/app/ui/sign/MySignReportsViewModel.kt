package com.signa.app.ui.sign

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.data.remote.backend.dto.MySignReportDto
import com.signa.app.data.repository.SignMapRepository
import com.signa.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MySignReportsState(
    val signedIn: Boolean? = null,
    val reports: List<MySignReportDto> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false
)

@HiltViewModel
class MySignReportsViewModel @Inject constructor(
    private val repository: SignMapRepository,
    auth: AuthRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(MySignReportsState())
    val state = mutableState.asStateFlow()
    private val request = MutableStateFlow(PageRequest())

    init {
        viewModelScope.launch {
            auth.session.catch { emit(null) }.collectLatest { session ->
                mutableState.value = MySignReportsState(signedIn = session != null)
                request.value = PageRequest()
                if (session != null) request.collectLatest { load(it.page) }
            }
        }
    }

    fun more() {
        if (state.value.hasMore && !state.value.loading) request.value = PageRequest(state.value.page + 1)
    }

    fun retry() {
        if (state.value.signedIn == true && !state.value.loading) {
            request.update { it.copy(attempt = it.attempt + 1) }
        }
    }

    private suspend fun load(page: Int) {
        mutableState.update { it.copy(loading = true, failed = false) }
        signDataCall { repository.myReports(page) }.onSuccess { result ->
            mutableState.update {
                val reports = if (page == 0) result.items else (it.reports + result.items).distinctBy { report -> report.id }
                it.copy(reports = reports, page = result.page, loading = false,
                    hasMore = result.items.isNotEmpty() && (result.page.toLong() + 1) * result.size < result.total)
            }
        }.onFailure {
            mutableState.update { it.copy(loading = false, failed = true) }
        }
    }

    private data class PageRequest(val page: Int = 0, val attempt: Int = 0)
}

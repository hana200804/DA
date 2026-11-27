package com.signa.app.ui.contributions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.data.fake.FakeContributionRepository
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.local.submissions.LocalPhotoStatus
import com.signa.app.data.local.submissions.PhotoSubmissionQueue
import com.signa.app.data.remote.api.SubmissionOwner
import com.signa.app.data.remote.dto.SubmissionItem
import com.signa.app.data.repository.PhotoSubmissionRepository
import com.signa.app.domain.model.ContributionStatus
import com.signa.app.domain.model.Session
import com.signa.app.domain.model.SessionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoContributionItem(
    val id: String,
    val code: String,
    val status: String,
    val date: String,
    val reason: String?,
    val signId: String?
)

data class PhotoContributionsState(
    val signedIn: Boolean = false,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val hasMore: Boolean = false,
    val local: List<LocalPhotoStatus> = emptyList(),
    val items: List<PhotoContributionItem> = emptyList()
)

@HiltViewModel
class PhotoContributionsViewModel @Inject constructor(
    sessions: SessionManager,
    private val queue: PhotoSubmissionQueue,
    private val remote: PhotoSubmissionRepository,
    private val fake: FakeContributionRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhotoContributionsState())
    val state = mutableState.asStateFlow()
    private var active: Session? = null
    private var loadJob: Job? = null
    private var nextPage = 0

    init {
        viewModelScope.launch {
            sessions.session.collectLatest { session ->
                loadJob?.cancel()
                active = session
                nextPage = 0
                mutableState.value = PhotoContributionsState(signedIn = session != null, loading = false)
                if (session == null) return@collectLatest
                load()
                queue.observe(session).collect { local ->
                    mutableState.update { it.copy(local = local) }
                }
            }
        }
    }

    fun refresh() {
        if (state.value.loading) return
        nextPage = 0
        load()
    }

    fun load() {
        val session = active ?: return
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, failed = false) }
        val pageNumber = nextPage
        loadJob = viewModelScope.launch {
            try {
                if (session.source == SessionSource.FAKE) {
                    val samples = fake.observeSummary().first().recentObservations.map {
                        PhotoContributionItem(
                            it.sign.id,
                            it.sign.code,
                            if (it.status == ContributionStatus.VERIFIED) "ACCEPTED" else "RECEIVED",
                            Instant.ofEpochMilli(it.observedAtEpochMillis).toString(),
                            null,
                            it.sign.id
                        )
                    }
                    mutableState.update { it.copy(items = samples, loading = false, hasMore = false) }
                } else {
                    val page = remote.list(SubmissionOwner(session.user.id), pageNumber, PAGE_SIZE)
                    nextPage = page.page + 1
                    mutableState.update {
                        val items = if (pageNumber == 0) page.items.map(SubmissionItem::toItem)
                        else (it.items + page.items.map(SubmissionItem::toItem)).distinctBy(PhotoContributionItem::id)
                        it.copy(items = items, loading = false, hasMore = page.items.isNotEmpty() && nextPage.toLong() * page.size < page.total)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

private fun SubmissionItem.toItem() = PhotoContributionItem(id, signTypeCode, status, capturedAt, rejectReason, signId)

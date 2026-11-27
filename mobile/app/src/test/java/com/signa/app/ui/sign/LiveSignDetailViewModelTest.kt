package com.signa.app.ui.sign

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.signa.app.R
import com.signa.app.data.fake.FakeSignCatalogRepository
import com.signa.app.data.fake.FakeSignMapRepository
import com.signa.app.data.remote.backend.dto.ReportReason
import com.signa.app.data.remote.backend.dto.SignRating
import com.signa.app.data.remote.backend.dto.SignReportDto
import com.signa.app.data.repository.ReportPhoto
import com.signa.app.data.repository.SignMapRepository
import com.signa.app.domain.model.Session
import com.signa.app.domain.model.SessionSource
import com.signa.app.domain.model.User
import com.signa.app.support.TestAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSignDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = TestAuthRepository()
    private val repository = FeedbackRepository()
    private lateinit var model: LiveSignDetailViewModel

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        model = LiveSignDetailViewModel(
            SavedStateHandle(mapOf(SIGN_ID_ARGUMENT to "sample-speed-50")),
            repository, FakeSignCatalogRepository(), auth
        )
    }

    @After fun cleanUp() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun ratingRequiresSessionAndChangingItUpdatesCounts() = runTest(dispatcher) {
        advanceUntilIdle()
        assertEquals("P.127_50", model.state.value.type?.code)
        model.rate(SignRating.CONFIRM)
        advanceUntilIdle()
        assertEquals(0, repository.ratingCount)

        signIn()
        advanceUntilIdle()
        model.rate(SignRating.CONFIRM)
        advanceUntilIdle()
        assertEquals(SignRating.CONFIRM, model.state.value.detail?.myRating)
        assertEquals(1, model.state.value.detail?.confirmCount)
        model.rate(SignRating.DENY)
        advanceUntilIdle()
        assertEquals(SignRating.DENY, model.state.value.detail?.myRating)
        assertEquals(0, model.state.value.detail?.confirmCount)
        assertEquals(1, model.state.value.detail?.denyCount)
        assertEquals(2, repository.ratingCount)
    }

    @Test fun otherReportValidatesDescriptionBeforeReadingPhotoAndSending() = runTest(dispatcher) {
        signIn()
        advanceUntilIdle()
        model.report(ReportReason.OTHER, "  ") { error("Không được đọc ảnh khi mô tả trống") }
        advanceUntilIdle()
        assertEquals(R.string.sign_report_description_required, model.state.value.actionMessage)
        assertNull(repository.reported)
        val photo = ReportPhoto(byteArrayOf(1, 2), "image/jpeg", "report.jpg")
        model.report(ReportReason.OTHER, "  Biển bị che  ") { photo }
        advanceUntilIdle()
        assertEquals(SignReportDto(ReportReason.OTHER, "Biển bị che") to photo, repository.reported)
        assertTrue(model.state.value.reportSubmitted)
    }

    private fun signIn() {
        auth.session.value = Session("test", User("u", "An", "an@example.com"), Long.MAX_VALUE, SessionSource.FAKE)
    }

    private class FeedbackRepository(private val fake: FakeSignMapRepository = FakeSignMapRepository()) : SignMapRepository by fake {
        var ratingCount = 0
        var reported: Pair<SignReportDto, ReportPhoto?>? = null

        override suspend fun rate(id: String, rating: SignRating) {
            ratingCount++
            fake.rate(id, rating)
        }

        override suspend fun report(id: String, metadata: SignReportDto, photo: ReportPhoto?) =
            fake.report(id, metadata, photo).also { reported = metadata to photo }
    }
}

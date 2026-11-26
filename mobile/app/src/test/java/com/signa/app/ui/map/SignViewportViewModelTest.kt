package com.signa.app.ui.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.signa.app.data.fake.FakeSignCatalogRepository
import com.signa.app.data.fake.FakeSignMapRepository
import com.signa.app.data.repository.SignMapRepository
import com.signa.app.data.repository.SignViewport
import com.signa.app.data.repository.VisibleSigns
import com.signa.app.ui.sign.CATALOG_MAP_ARGUMENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignViewportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val categories = MutableStateFlow(setOf("PROHIBITORY", "WARNING"))
    private val repository = ViewportRepository()
    private lateinit var model: SignViewportViewModel

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        model = SignViewportViewModel(
            SavedStateHandle(mapOf(CATALOG_MAP_ARGUMENT to "P.127_50")),
            repository, FakeSignCatalogRepository(), categories,
            { code -> categories.value = categories.value - code },
            { categories.value = emptySet() }
        )
    }

    @After fun cleanUp() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun debounceUsesLatestViewportAndRestoredFilters() = runTest(dispatcher) {
        val first = SignViewport(106.5, 10.6, 106.9, 11.0, 12)
        val second = SignViewport(106.6, 10.7, 106.8, 10.9, 15)
        model.cameraIdle(first)
        advanceTimeBy(200)
        model.cameraIdle(second)
        advanceTimeBy(299)
        assertTrue(repository.requests.isEmpty())
        advanceUntilIdle()
        assertEquals(Request(second, setOf("PROHIBITORY", "WARNING"), setOf("P.127_50")), repository.requests.single())
        assertFalse(model.state.value.loading)

        model.toggleCategory("WARNING")
        model.removeSignTypeFilter()
        advanceUntilIdle()
        assertEquals(Request(second, setOf("PROHIBITORY"), emptySet()), repository.requests.last())
    }

    @Test fun newCameraIdleCancelsStaleRequestAndErrorCanBeRetried() = runTest(dispatcher) {
        val first = SignViewport(106.5, 10.6, 106.9, 11.0, 12)
        val second = first.copy(zoom = 15)
        model.cameraIdle(first)
        advanceTimeBy(301)
        assertEquals(1, repository.requests.size)
        repository.fail = true
        model.cameraIdle(second)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(15), repository.completed)
        assertTrue(model.state.value.hasError)
        repository.fail = false
        model.retry()
        advanceUntilIdle()
        assertEquals(second, repository.requests.last().viewport)
        assertFalse(model.state.value.hasError)
    }

    private data class Request(val viewport: SignViewport, val categories: Set<String>, val typeCodes: Set<String>)

    private class ViewportRepository : SignMapRepository by FakeSignMapRepository() {
        val requests = mutableListOf<Request>()
        val completed = mutableListOf<Int>()
        var fail = false
        override suspend fun visible(viewport: SignViewport, categories: Set<String>, signTypeCodes: Set<String>): VisibleSigns {
            requests += Request(viewport, categories, signTypeCodes)
            delay(80)
            completed += viewport.zoom
            if (fail) throw java.io.IOException()
            return VisibleSigns()
        }
    }
}

package com.signa.app.ui.drive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.local.submissions.PhotoSubmissionQueue
import com.signa.app.data.remote.backend.dto.PhotoModel
import com.signa.app.data.remote.backend.dto.SubmissionLocation
import com.signa.app.data.remote.backend.dto.SubmissionMetadata
import com.signa.app.di.ApplicationScope
import com.signa.app.domain.model.DeviceLocation
import com.signa.app.domain.model.VisionModel
import com.signa.app.domain.repository.LocationRepository
import com.signa.app.domain.repository.SettingsRepository
import com.signa.app.vision.analyzer.CaptureContext
import com.signa.app.vision.analyzer.TrackEvidence
import com.signa.app.vision.geo.forObservation
import com.signa.app.vision.tracker.FinishedTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
internal class ActiveDriveViewModel @Inject constructor(
    private val sessions: SessionManager,
    settings: SettingsRepository,
    private val locations: LocationRepository,
    private val submissions: PhotoSubmissionQueue,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {
    private val latestFix = AtomicReference<DeviceLocation?>()
    val canContribute = combine(sessions.session, settings.settings) { session, preferences ->
        session != null && preferences.contributionEnabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    suspend fun observeLocation() {
        try {
            locations.observeLocations().collect { latestFix.set(it) }
        } finally {
            latestFix.set(null)
        }
    }

    fun captureContext(frameTimeNanos: Long): CaptureContext? {
        if (!canContribute.value) return null
        val owner = sessions.current ?: return null
        val geo = latestFix.get()?.forObservation(frameTimeNanos) ?: return null
        return CaptureContext(owner, geo, clock.instant())
    }

    fun submitTrack(model: VisionModel, track: FinishedTrack<TrackEvidence>) {
        val evidence = track.evidence ?: return
        val (owner, geo, capturedAt) = evidence.context
        val metadata = SubmissionMetadata(
            clientId = UUID.randomUUID().toString(),
            signTypeCode = track.label.code,
            confidence = track.confidence,
            location = SubmissionLocation(geo.point.latitude, geo.point.longitude),
            headingDeg = geo.headingDeg,
            source = "CAMERA",
            flags = if (track.splitVote) listOf("SPLIT_VOTE") else emptyList(),
            model = PhotoModel(model.name, model.version, model.labelMapVersion),
            capturedAt = capturedAt.toString()
        )
        appScope.launch {
            try {
                submissions.enqueue(owner, metadata, evidence.jpeg)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("SignaVision", "Không thể lưu đề xuất từ camera", failure)
            }
        }
    }
}

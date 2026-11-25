package com.signa.app.ui.recognition

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.local.submissions.PhotoSubmissionQueue
import com.signa.app.data.remote.dto.DetectedPhoto
import com.signa.app.data.remote.dto.RecognitionModels
import com.signa.app.data.remote.dto.SubmissionLocation
import com.signa.app.data.remote.dto.SubmissionMetadata
import com.signa.app.data.repository.PhotoRecognitionRepository
import com.signa.app.data.repository.RecognitionRateLimited
import com.signa.app.data.repository.RecognitionUnavailable
import com.signa.app.domain.model.GeoPoint
import com.signa.app.domain.model.LocationResult
import com.signa.app.domain.repository.LocationRepository
import com.signa.app.domain.repository.SettingsRepository
import com.signa.app.vision.photo.PhotoTooLarge
import com.signa.app.vision.photo.cropPhoto
import com.signa.app.vision.photo.encodePhoto
import com.signa.app.vision.photo.loadPhoto
import com.signa.app.vision.photo.photoSize
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

data class PhotoRecognitionState(
    val open: Boolean = false,
    val bitmap: Bitmap? = null,
    val models: RecognitionModels? = null,
    val model: String? = null,
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val result: DetectedPhoto? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val queued: Set<Int> = emptySet()
)

@HiltViewModel
class PhotoRecognitionViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val recognition: PhotoRecognitionRepository,
    private val submissions: PhotoSubmissionQueue,
    private val locations: LocationRepository,
    private val settings: SettingsRepository,
    private val sessions: SessionManager,
    private val clock: Clock
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhotoRecognitionState())
    val state = mutableState.asStateFlow()
    val session = sessions.session
    private var capturedAt: String? = null
    private var location: GeoPoint? = null
    private var locationJob: Job? = null
    private var upload: ByteArray? = null
    private val draftMetadata = mutableMapOf<Int, SubmissionMetadata>()

    fun load(uri: Uri, temporaryFile: File?) {
        locationJob?.cancel()
        location = null
        upload = null
        capturedAt = clock.instant().toString()
        draftMetadata.clear()
        mutableState.update {
            it.copy(open = true, bitmap = null, result = null, busy = true, message = null, queued = emptySet())
        }
        if (sessions.current != null) {
            locationJob = viewModelScope.launch { location = currentPosition() }
        }
        if (state.value.models == null) refreshModels()
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { loadPhoto(context.contentResolver, uri) }
                mutableState.update { it.copy(bitmap = bitmap) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage(if (error is PhotoTooLarge) "Ảnh vượt quá 10 MB." else "Không đọc được ảnh đã chọn.")
            } finally {
                temporaryFile?.delete()
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun refreshModels() {
        if (state.value.modelsLoading) return
        mutableState.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            try {
                val models = recognition.models()
                mutableState.update { it.copy(models = models) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(modelsError = recognitionMessage(error)) }
            } finally {
                mutableState.update { it.copy(modelsLoading = false) }
            }
        }
    }

    fun chooseModel(model: String?) {
        if (state.value.busy) return
        mutableState.update { it.copy(model = model) }
    }

    fun detect() {
        val snapshot = state.value
        val bitmap = snapshot.bitmap ?: return
        if (snapshot.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val bytes = upload ?: withContext(Dispatchers.Default) { bitmap.encodePhoto() }.also { upload = it }
                val result = recognition.detect(bytes, bitmap.photoSize(), snapshot.model)
                draftMetadata.clear()
                mutableState.update { it.copy(result = result, queued = emptySet()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage(recognitionMessage(error))
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun submit(index: Int, code: String) {
        val snapshot = state.value
        val bitmap = snapshot.bitmap ?: return
        val result = snapshot.result ?: return
        val detection = result.detections.getOrNull(index) ?: return
        val time = capturedAt ?: return
        if (snapshot.busy || index in snapshot.queued) return
        val owner = sessions.current
        if (owner == null) {
            showMessage("Bạn cần đăng nhập để gửi đề xuất.")
            return
        }
        val typeCode = code.trim()
        if (typeCode.isEmpty() || typeCode.length > 30) {
            showMessage("Mã biển phải có từ 1 đến 30 ký tự.")
            return
        }

        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                if (!settings.settings.first().contributionEnabled) {
                    showMessage("Hãy bật đóng góp dữ liệu trong Cài đặt để gửi đề xuất.")
                    return@launch
                }
                locationJob?.join()
                val point = location ?: currentPosition()?.also { location = it }
                if (point == null) {
                    showMessage("Chưa lấy được vị trí. Hãy cấp quyền vị trí, bật GPS và thử lại.")
                    return@launch
                }
                val jpeg = withContext(Dispatchers.Default) { cropPhoto(bitmap, result.image, detection.box) }
                val flags = if (typeCode == detection.signTypeCode) emptyList() else listOf("USER_EDITED")
                val metadata = draftMetadata[index]?.copy(signTypeCode = typeCode, flags = flags) ?: SubmissionMetadata(
                    clientId = UUID.randomUUID().toString(),
                    signTypeCode = typeCode,
                    confidence = detection.confidence,
                    location = SubmissionLocation(point.latitude, point.longitude),
                    headingDeg = null,
                    source = "PHOTO",
                    flags = flags,
                    model = result.model,
                    capturedAt = time
                )
                draftMetadata[index] = metadata
                submissions.enqueue(owner, metadata, jpeg)
                mutableState.update {
                    it.copy(queued = it.queued + index, message = "Đã lưu đề xuất để gửi. Xem trạng thái trong Đề xuất của tôi.")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage("Không lưu được đề xuất. Vui lòng thử lại.")
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    fun show() {
        mutableState.update { it.copy(open = true, message = null) }
    }

    fun close() {
        if (state.value.busy) return
        locationJob?.cancel()
        location = null
        upload = null
        capturedAt = null
        draftMetadata.clear()
        mutableState.update { it.copy(open = false, bitmap = null, result = null, message = null, queued = emptySet()) }
    }

    private suspend fun currentPosition(): GeoPoint? =
        (locations.currentLocation() as? LocationResult.Available)?.location?.point

    private fun recognitionMessage(error: Exception): String = when (error) {
        is RecognitionUnavailable -> "Dịch vụ nhận diện tạm thời không khả dụng. Vui lòng thử lại sau."
        is RecognitionRateLimited -> if (error.retryAfterSeconds != null && error.retryAfterSeconds > 0) {
            "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau ${error.retryAfterSeconds} giây."
        } else {
            "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau."
        }
        is PhotoTooLarge -> "Ảnh vượt quá 10 MB."
        is HttpException -> if (error.code() == 413) "Ảnh vượt quá giới hạn tải lên." else "Không nhận diện được ảnh. Vui lòng thử lại."
        else -> "Không kết nối được dịch vụ nhận diện. Vui lòng thử lại."
    }
}

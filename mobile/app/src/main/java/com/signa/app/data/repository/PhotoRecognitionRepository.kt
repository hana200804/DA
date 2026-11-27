package com.signa.app.data.repository

import com.signa.app.BuildConfig
import com.signa.app.data.remote.backend.api.RecognitionApi
import com.signa.app.data.remote.backend.dto.DetectedPhoto
import com.signa.app.data.remote.backend.dto.PhotoBox
import com.signa.app.data.remote.backend.dto.PhotoDetection
import com.signa.app.data.remote.backend.dto.PhotoModel
import com.signa.app.data.remote.backend.dto.PhotoSize
import com.signa.app.data.remote.backend.dto.RecognitionModel
import com.signa.app.data.remote.backend.dto.RecognitionModels
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

internal val jpegMediaType = "image/jpeg".toMediaType()
internal const val MAX_CROP_BYTES = 200 * 1024

class RecognitionUnavailable : Exception()
class RecognitionRateLimited(val retryAfterSeconds: Long?) : Exception()

class PhotoRecognitionRepository(
    private val api: RecognitionApi,
    private val clock: Clock,
    private val fake: Boolean
) {
    @Inject constructor(api: RecognitionApi, clock: Clock) : this(api, clock, BuildConfig.USE_FAKE_BACKEND)

    suspend fun models(): RecognitionModels = if (fake) fakeModels else request { api.models() }

    suspend fun detect(jpeg: ByteArray, size: PhotoSize, model: String?): DetectedPhoto {
        if (fake) {
            return DetectedPhoto(
                PhotoModel(model ?: fakeModels.defaultModel, "demo", 1), size, 18,
                listOf(PhotoDetection(
                    PhotoBox(size.width * .25, size.height * .25, size.width * .5, size.height * .5),
                    "P.123a", .93, "Cấm rẽ trái", "Cấm các loại xe rẽ trái."
                ))
            )
        }
        return request {
            api.detect(
                MultipartBody.Part.createFormData("image", "photo.jpg", jpeg.toRequestBody(jpegMediaType)),
                model?.let { MultipartBody.Part.createFormData("model", it) }
            )
        }
    }

    private suspend fun <T> request(block: suspend () -> T): T = try {
        block()
    } catch (failure: HttpException) {
        val response = failure.response()
        val code = runCatching {
            response?.errorBody()?.use { body ->
                Json.parseToJsonElement(body.string()).jsonObject["code"]?.jsonPrimitive?.content
            }
        }.getOrNull()
        throw when {
            failure.code() == 503 && code == "RECOGNITION_UNAVAILABLE" -> RecognitionUnavailable()
            failure.code() == 429 && code == "RATE_LIMITED" ->
                RecognitionRateLimited(retryAfterSeconds(response?.headers()?.get("Retry-After")))
            else -> failure
        }
    }

    private fun retryAfterSeconds(value: String?): Long? {
        if (value == null) return null
        return value.toLongOrNull()?.coerceAtLeast(0) ?: runCatching {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            (at.toEpochSecond() - clock.instant().epochSecond).coerceAtLeast(0)
        }.getOrNull()
    }

    private companion object {
        val fakeModels = RecognitionModels("yolov8", listOf("yolov5", "yolov8", "detr").map {
            RecognitionModel(it, "demo", 1)
        })
    }
}

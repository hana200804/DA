package com.signa.app.data.repository

import com.signa.app.data.remote.backend.api.PhotoSubmissionApi
import com.signa.app.data.remote.backend.api.SubmissionOwner
import com.signa.app.data.remote.backend.dto.SubmissionMetadata
import com.signa.app.data.remote.backend.dto.SubmissionPage
import com.signa.app.data.remote.backend.dto.SubmissionReceipt
import javax.inject.Inject
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

data class PhotoSubmissionResult(val receipt: SubmissionReceipt, val created: Boolean)

class PhotoSubmissionRepository @Inject constructor(
    private val api: PhotoSubmissionApi,
    private val json: Json
) {
    fun encode(metadata: SubmissionMetadata): String = json.encodeToString(metadata)

    suspend fun submit(owner: SubmissionOwner, metadata: String, jpeg: ByteArray): PhotoSubmissionResult {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_CROP_BYTES)
        val response = api.submit(
            owner,
            metadata.toRequestBody("application/json".toMediaType()),
            MultipartBody.Part.createFormData("crop", "crop.jpg", jpeg.toRequestBody(jpegMediaType))
        )
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw HttpException(response)
        }
        check(response.code() == 200 || response.code() == 201)
        return PhotoSubmissionResult(checkNotNull(response.body()), created = response.code() == 201)
    }

    suspend fun list(owner: SubmissionOwner, page: Int, size: Int): SubmissionPage = api.list(owner, page, size)
}

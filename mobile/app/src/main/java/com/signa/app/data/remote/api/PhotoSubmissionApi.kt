package com.signa.app.data.remote.api

import com.signa.app.data.remote.dto.SubmissionPage
import com.signa.app.data.remote.dto.SubmissionReceipt
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.Tag

data class SubmissionOwner(val userId: String)

interface PhotoSubmissionApi {
    @Multipart
    @POST("submissions")
    suspend fun submit(
        @Tag owner: SubmissionOwner,
        @Part("metadata") metadata: RequestBody,
        @Part crop: MultipartBody.Part
    ): Response<SubmissionReceipt>

    @GET("me/submissions")
    suspend fun list(
        @Tag owner: SubmissionOwner,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): SubmissionPage
}

package com.signa.app.data.remote.backend.api

import com.signa.app.data.remote.backend.dto.DetectedPhoto
import com.signa.app.data.remote.backend.dto.RecognitionModels
import okhttp3.MultipartBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface RecognitionApi {
    @GET("recognition/models")
    suspend fun models(): RecognitionModels

    @Multipart
    @POST("recognition/detect")
    suspend fun detect(@Part image: MultipartBody.Part, @Part model: MultipartBody.Part?): DetectedPhoto
}

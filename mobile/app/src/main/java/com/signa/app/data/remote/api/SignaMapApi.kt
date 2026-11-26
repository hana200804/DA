package com.signa.app.data.remote.api

import com.signa.app.data.remote.dto.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface SignaMapApi {
    @GET("sign-categories")
    suspend fun categories(): SignCategoriesDto

    @GET("sign-types")
    suspend fun signTypes(@Header("If-None-Match") etag: String?): Response<SignTypesDto>

    @GET("sign-types/{code}")
    suspend fun signType(@Path("code") code: String): SignTypeDto

    @GET("signs")
    suspend fun signs(
        @Query("bbox") bbox: String,
        @Query("zoom") zoom: Int,
        @Query("categories") categories: String?,
        @Query("signTypeCodes") signTypeCodes: String?
    ): SignMapDto

    @GET("signs/{id}")
    suspend fun detail(@Path("id") id: String): SignDetailDto

    @PUT("signs/{id}/rating")
    suspend fun rate(@Path("id") id: String, @Body rating: RatingRequestDto)

    @POST("signs/{id}/reports")
    suspend fun report(@Path("id") id: String, @Body metadata: SignReportDto): SignReportReceiptDto

    @Multipart
    @POST("signs/{id}/reports")
    suspend fun reportWithPhoto(
        @Path("id") id: String,
        @Part metadata: MultipartBody.Part,
        @Part photo: MultipartBody.Part
    ): SignReportReceiptDto

    @GET("me/reports")
    suspend fun myReports(@Query("page") page: Int, @Query("size") size: Int): MySignReportsDto
}

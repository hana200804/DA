package com.signa.app.data.remote.backend.api

import com.signa.app.data.remote.backend.dto.ChangePasswordRequest
import com.signa.app.data.remote.backend.dto.ForgotPasswordRequest
import com.signa.app.data.remote.backend.dto.LoginRequest
import com.signa.app.data.remote.backend.dto.LoginResponse
import com.signa.app.data.remote.backend.dto.RegisterRequest
import com.signa.app.data.remote.backend.dto.ResetPasswordRequest
import com.signa.app.data.remote.backend.dto.UpdateProfileRequest
import com.signa.app.data.remote.backend.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

interface AuthApi {
    @POST("auth/login") suspend fun login(@Body request: LoginRequest): LoginResponse
    @POST("auth/register") suspend fun register(@Body request: RegisterRequest)
    @POST("auth/logout") suspend fun logout()
    @POST("auth/password/forgot") suspend fun forgotPassword(@Body request: ForgotPasswordRequest)
    @POST("auth/password/reset") suspend fun resetPassword(@Body request: ResetPasswordRequest)
    @PUT("me/password") suspend fun changePassword(@Body request: ChangePasswordRequest)
    @GET("me") suspend fun currentUser(): UserDto
    @PATCH("me") suspend fun updateProfile(@Body request: UpdateProfileRequest): UserDto
}

package com.signa.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String, val client: String)

@Serializable
data class RegisterRequest(val displayName: String, val email: String, val password: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val email: String, val code: String, val newPassword: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class UpdateProfileRequest(val displayName: String)

@Serializable
data class UserDto(
    val id: String,
    val displayName: String,
    val email: String,
    val role: String,
    val permissions: List<String>
)

@Serializable
data class LoginUserDto(
    val id: String,
    val displayName: String,
    val role: String,
    val permissions: List<String>
)

@Serializable
data class LoginResponse(val token: String, val expiresAt: String, val user: LoginUserDto)

@Serializable
data class ApiFieldError(val field: String, val issue: String)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val details: List<ApiFieldError> = emptyList(),
    val requestId: String? = null
)

package com.signa.app.data.repository

import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.remote.backend.error.ApiException
import com.signa.app.data.remote.backend.api.AuthApi
import com.signa.app.data.remote.backend.error.apiCall
import com.signa.app.data.remote.backend.dto.ChangePasswordRequest
import com.signa.app.data.remote.backend.dto.ForgotPasswordRequest
import com.signa.app.data.remote.backend.dto.LoginRequest
import com.signa.app.data.remote.backend.dto.RegisterRequest
import com.signa.app.data.remote.backend.dto.ResetPasswordRequest
import com.signa.app.data.remote.backend.dto.UpdateProfileRequest
import com.signa.app.data.remote.backend.dto.UserDto
import com.signa.app.domain.model.AuthError
import com.signa.app.domain.model.AuthException
import com.signa.app.domain.model.PasswordResetChallenge
import com.signa.app.domain.model.Session
import com.signa.app.domain.model.SessionSource
import com.signa.app.domain.model.User
import com.signa.app.domain.repository.AuthRepository
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class RemoteAuthRepository @Inject constructor(
    private val api: AuthApi,
    private val sessions: SessionManager,
    private val json: Json,
    private val clock: Clock
) : AuthRepository {
    override val session = sessions.session

    override suspend fun register(displayName: String, email: String, password: String) = request {
        api.register(RegisterRequest(displayName.trim(), email.trim(), password))
    }

    override suspend fun login(email: String, password: String) {
        val response = request(unauthenticated = AuthError.InvalidCredentials) {
            api.login(LoginRequest(email.trim(), password, client = "MOBILE"))
        }
        val expires = try {
            Instant.parse(response.expiresAt).toEpochMilli()
        } catch (error: java.time.format.DateTimeParseException) {
            throw AuthException(AuthError.InvalidResponse, error)
        }
        if (response.token.isBlank() || response.user.id.isBlank() || expires <= clock.millis()) {
            throw AuthException(AuthError.InvalidResponse)
        }
        val user = User(
            response.user.id, response.user.displayName, email.trim(),
            role = response.user.role,
            permissions = response.user.permissions
        )
        sessions.save(Session(response.token, user, expires, SessionSource.REMOTE))
    }

    override suspend fun refreshProfile() = syncProfile { api.currentUser() }

    override suspend fun updateProfile(displayName: String) = syncProfile {
        api.updateProfile(UpdateProfileRequest(displayName.trim()))
    }

    private suspend fun syncProfile(call: suspend () -> UserDto) {
        val current = sessions.current ?: throw AuthException(AuthError.NotSignedIn)
        val profile = request(call = call)
        if (profile.id != current.user.id) throw AuthException(AuthError.InvalidResponse)
        sessions.updateProfile(current.copy(user = current.user.copy(
            displayName = profile.displayName,
            email = profile.email,
            role = profile.role,
            permissions = profile.permissions
        )))
    }

    override suspend fun logout() {
        val current = sessions.current
        try {
            if (current != null) request { api.logout() }
        } finally {
            if (current != null) sessions.clear(current.id)
        }
    }

    override suspend fun requestPasswordReset(email: String): PasswordResetChallenge {
        request { api.forgotPassword(ForgotPasswordRequest(email.trim())) }
        return PasswordResetChallenge(email.trim(), expiresAtEpochMillis = 0)
    }

    override suspend fun resetPassword(email: String, otp: String, newPassword: String) = request(businessRule = AuthError.InvalidOtp) {
        api.resetPassword(ResetPasswordRequest(email.trim(), code = otp, newPassword = newPassword))
    }

    override suspend fun changePassword(oldPassword: String, newPassword: String) {
        if (sessions.current == null) throw AuthException(AuthError.NotSignedIn)
        request(businessRule = AuthError.WrongPassword) {
            api.changePassword(ChangePasswordRequest(currentPassword = oldPassword, newPassword = newPassword))
        }
    }

    private suspend fun <T> request(
        unauthenticated: AuthError = AuthError.Unauthorized,
        businessRule: AuthError = AuthError.Validation,
        call: suspend () -> T
    ): T {
        try {
            return apiCall(json, call)
        } catch (error: ApiException) {
            val code = when (error.error?.code) {
                "INVALID_CREDENTIALS" -> AuthError.InvalidCredentials
                "UNAUTHENTICATED" -> unauthenticated
                "FORBIDDEN" -> AuthError.Forbidden
                "VALIDATION_ERROR" -> AuthError.Validation
                "BUSINESS_RULE" -> businessRule
                "NOT_FOUND" -> AuthError.AccountMissing
                "CONFLICT" -> AuthError.EmailExists
                "RATE_LIMITED" -> AuthError.RateLimited
                "INTERNAL_ERROR" -> AuthError.Server
                else -> when (error.status) {
                    400, 422 -> AuthError.Validation
                    401 -> unauthenticated
                    403 -> AuthError.Forbidden
                    404 -> AuthError.AccountMissing
                    409 -> AuthError.EmailExists
                    429 -> AuthError.RateLimited
                    else -> AuthError.Server
                }
            }
            val retryAfter = error.retryAfterSeconds.takeIf { code == AuthError.RateLimited }
            throw AuthException(code, error, retryAfter)
        } catch (error: SerializationException) {
            throw AuthException(AuthError.InvalidResponse, error)
        } catch (error: IOException) {
            throw AuthException(AuthError.Network, error)
        }
    }
}

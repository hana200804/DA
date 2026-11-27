package com.signa.app.data.remote.backend.error

import com.signa.app.data.remote.backend.dto.ApiErrorDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

suspend fun <T> apiCall(json: Json, call: suspend () -> T): T {
    try {
        return call()
    } catch (failure: HttpException) {
        val body = failure.response()?.errorBody()?.use { it.string() }
        val error = if (body.isNullOrBlank()) null else try {
            json.decodeFromString<ApiErrorDto>(body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val retryAfter = failure.response()?.headers()?.get("Retry-After")?.trim()?.toIntOrNull()
        throw ApiException(failure.code(), error, failure, retryAfter?.takeIf { it > 0 })
    }
}

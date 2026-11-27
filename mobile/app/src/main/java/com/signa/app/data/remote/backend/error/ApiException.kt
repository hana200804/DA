package com.signa.app.data.remote.backend.error

import com.signa.app.data.remote.backend.dto.ApiErrorDto
import java.io.IOException

class ApiException(
    val status: Int,
    val error: ApiErrorDto?,
    cause: Throwable,
    val retryAfterSeconds: Int? = null
) : IOException("HTTP $status", cause)

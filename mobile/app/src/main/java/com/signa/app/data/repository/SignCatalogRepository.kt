package com.signa.app.data.repository

import com.signa.app.data.local.database.SignCatalogSnapshot
import com.signa.app.data.local.database.SignCatalogStore
import com.signa.app.data.remote.ApiConfig
import com.signa.app.data.remote.ApiException
import com.signa.app.data.remote.api.SignaMapApi
import com.signa.app.data.remote.apiCall
import com.signa.app.data.remote.dto.SignTypeDto
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import retrofit2.HttpException

interface SignCatalogRepository {
    val snapshots: Flow<SignCatalogSnapshot>
    suspend fun refresh()
    suspend fun type(code: String): SignTypeDto?
}

class RemoteSignCatalogRepository(
    private val api: SignaMapApi,
    private val store: SignCatalogStore,
    private val config: ApiConfig,
    private val json: Json
) : SignCatalogRepository {
    private val refreshMutex = Mutex()
    override val snapshots = store.snapshots

    override suspend fun refresh() = refreshMutex.withLock {
        apiCall(json) {
            val cached = snapshots.first()
            val response = api.signTypes(cached.etag)
            val categories = when {
                response.code() == 304 || response.isSuccessful -> api.categories().items
                else -> throw HttpException(response)
            }
            val updated = if (response.code() == 304) {
                response.errorBody()?.close()
                cached.copy(categories = categories)
            } else {
                val types = requireNotNull(response.body())
                SignCatalogSnapshot(
                    categories,
                    types.copy(items = types.items.map { it.withResolvedIcon() }),
                    response.headers()["ETag"]
                )
            }
            if (updated != cached) store.save(updated)
        }
    }

    override suspend fun type(code: String): SignTypeDto? {
        return try {
            apiCall(json) { api.signType(code).withResolvedIcon() }
        } catch (failure: IOException) {
            if (failure is ApiException && failure.status < 500) throw failure
            snapshots.first().types.items.firstOrNull { it.code == code } ?: throw failure
        }
    }

    private fun SignTypeDto.withResolvedIcon() = copy(iconUrl = iconUrl?.let(::publicUrl))

    private fun publicUrl(value: String): String? = config.baseUrl.resolve(value)
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
        ?.toString()
}

package com.signa.app.data.repository

import com.signa.app.data.remote.api.SignaMapApi
import com.signa.app.data.remote.apiCall
import com.signa.app.data.remote.dto.*
import com.signa.app.domain.model.GeoPoint
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class SignViewport(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val zoom: Int
) {
    fun bounds(): List<String> {
        if (south >= north || west == east) return emptyList()
        val ranges = if (west < east) listOf(west to east) else listOf(west to 180.0, -180.0 to east)
        return ranges.filter { (left, right) -> left < right }
            .map { (left, right) -> listOf(left, south, right, north).joinToString(",") }
    }
}

data class VisibleSigns(
    val signs: List<MapSignDto> = emptyList(),
    val clusters: List<MapClusterDto> = emptyList()
)

data class ReportPhoto(val bytes: ByteArray, val mimeType: String, val filename: String) {
    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}

fun SignLocationDto.point(): GeoPoint? = GeoPoint.fromOrNull(lat, lng)

interface SignMapRepository {
    suspend fun visible(viewport: SignViewport, categories: Set<String>, signTypeCodes: Set<String>): VisibleSigns
    suspend fun detail(id: String): SignDetailDto
    suspend fun rate(id: String, rating: SignRating)
    suspend fun report(id: String, metadata: SignReportDto, photo: ReportPhoto?): SignReportReceiptDto
    suspend fun myReports(page: Int): MySignReportsDto
}

class RemoteSignMapRepository(
    private val api: SignaMapApi,
    private val json: Json
) : SignMapRepository {
    override suspend fun visible(viewport: SignViewport, categories: Set<String>, signTypeCodes: Set<String>) =
        apiCall(json) {
            val signs = mutableListOf<MapSignDto>()
            val clusters = mutableListOf<MapClusterDto>()
            for (bounds in viewport.bounds()) {
                val result = api.signs(bounds, viewport.zoom, categories.query(), signTypeCodes.query())
                when (result.mode) {
                    MapMode.SIGNS -> signs += result.items.map { json.decodeFromJsonElement(MapSignDto.serializer(), it) }
                    MapMode.CLUSTERS -> clusters += result.items.map { json.decodeFromJsonElement(MapClusterDto.serializer(), it) }
                }
            }
            VisibleSigns(signs.distinctBy { it.id }, clusters)
        }

    override suspend fun detail(id: String) = apiCall(json) { api.detail(id) }

    override suspend fun rate(id: String, rating: SignRating) =
        apiCall(json) { api.rate(id, RatingRequestDto(rating)) }

    override suspend fun report(id: String, metadata: SignReportDto, photo: ReportPhoto?) = apiCall(json) {
        if (photo == null) api.report(id, metadata)
        else {
            val metadataBody = json.encodeToString(metadata).toRequestBody("application/json".toMediaType())
            api.reportWithPhoto(
                id,
                MultipartBody.Part.createFormData("metadata", "metadata.json", metadataBody),
                MultipartBody.Part.createFormData("photo", photo.filename, photo.bytes.toRequestBody(photo.mimeType.toMediaType()))
            )
        }
    }

    override suspend fun myReports(page: Int) = apiCall(json) { api.myReports(page, SIGN_REPORT_PAGE_SIZE) }

    private fun Set<String>.query(): String? = sorted().joinToString(",").ifEmpty { null }
}

internal const val SIGN_REPORT_PAGE_SIZE = 20

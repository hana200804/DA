package com.signa.app.data.remote.backend.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SignCategoryDto(
    val code: String,
    val name: String,
    val description: String? = null,
    val sortOrder: Int
)

@Serializable
data class SignCategoriesDto(val items: List<SignCategoryDto>)

@Serializable
data class SignTypeDto(
    val code: String,
    val categoryCode: String,
    val name: String,
    val description: String? = null,
    val speedKmh: Int? = null,
    val iconUrl: String? = null,
    val recognizable: Boolean
)

@Serializable
data class SignTypesDto(val labelMapVersion: Int? = null, val items: List<SignTypeDto>)

@Serializable
data class SignLocationDto(val lat: Double, val lng: Double)

@Serializable
enum class MapMode { SIGNS, CLUSTERS }

@Serializable
data class SignMapDto(val mode: MapMode, val items: List<JsonElement>)

@Serializable
data class MapSignDto(
    val id: String,
    val signTypeCode: String,
    val categoryCode: String,
    val location: SignLocationDto,
    val headingDeg: Int? = null,
    val status: String,
    val confidence: Double
)

@Serializable
data class MapClusterDto(val location: SignLocationDto, val count: Long)

@Serializable
data class SignDetailDto(
    val id: String,
    val signTypeCode: String,
    val categoryCode: String,
    val location: SignLocationDto,
    val headingDeg: Int? = null,
    val status: String,
    val confidence: Double,
    val confirmCount: Int,
    val denyCount: Int,
    val updatedAt: String,
    val coverCropUrl: String? = null,
    val myRating: SignRating? = null
)

@Serializable
enum class SignRating { CONFIRM, DENY }

@Serializable
data class RatingRequestDto(val value: SignRating)

@Serializable
enum class ReportReason { NOT_EXIST, WRONG_TYPE, WRONG_LOCATION, OTHER }

@Serializable
enum class ReportStatus { PENDING, ACCEPTED, DISMISSED }

@Serializable
enum class ReportResolution { EDITED, REMOVED }

@Serializable
data class SignReportDto(val reason: ReportReason, val description: String? = null)

@Serializable
data class SignReportReceiptDto(val id: String, val status: ReportStatus)

@Serializable
data class MySignReportDto(
    val id: String,
    val signId: String,
    val reason: ReportReason,
    val description: String? = null,
    val status: ReportStatus,
    val resolution: ReportResolution? = null,
    val resolutionNote: String? = null,
    val createdAt: String,
    val resolvedAt: String? = null
)

@Serializable
data class MySignReportsDto(val items: List<MySignReportDto>, val page: Int, val size: Int, val total: Long)

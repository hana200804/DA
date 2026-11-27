package com.signa.app.data.remote.backend.dto

import kotlinx.serialization.Serializable

@Serializable
data class RecognitionModels(val defaultModel: String, val models: List<RecognitionModel>)

@Serializable
data class RecognitionModel(val modelName: String, val modelVersion: String, val labelMapVersion: Int)

@Serializable
data class DetectedPhoto(
    val model: PhotoModel,
    val image: PhotoSize,
    val inferenceMs: Long,
    val detections: List<PhotoDetection>
)

@Serializable
data class PhotoModel(val name: String, val version: String, val labelMapVersion: Int)

@Serializable
data class PhotoSize(val width: Int, val height: Int)

@Serializable
data class PhotoDetection(
    val box: PhotoBox,
    val signTypeCode: String,
    val confidence: Double,
    val name: String? = null,
    val description: String? = null,
    val iconUrl: String? = null
)

@Serializable
data class PhotoBox(val x: Double, val y: Double, val width: Double, val height: Double)

@Serializable
data class SubmissionMetadata(
    val clientId: String,
    val signTypeCode: String,
    val confidence: Double,
    val location: SubmissionLocation,
    val headingDeg: Int?,
    val source: String,
    val flags: List<String>,
    val model: PhotoModel,
    val capturedAt: String
)

@Serializable
data class SubmissionLocation(val lat: Double, val lng: Double)

@Serializable
data class SubmissionReceipt(val id: String, val status: String)

@Serializable
data class SubmissionPage(val items: List<SubmissionItem>, val page: Int, val size: Int, val total: Long)

@Serializable
data class SubmissionItem(
    val id: String,
    val signTypeCode: String,
    val status: String,
    val rejectReason: String? = null,
    val signId: String? = null,
    val location: SubmissionLocation,
    val receivedAt: String,
    val capturedAt: String
)

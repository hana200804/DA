package com.signa.app.data.submissions

import com.signa.app.data.remote.ApiClientFactory
import com.signa.app.data.remote.ApiConfig
import com.signa.app.data.remote.api.PhotoSubmissionApi
import com.signa.app.data.remote.api.RecognitionApi
import com.signa.app.data.remote.api.SubmissionOwner
import com.signa.app.data.remote.dto.PhotoBox
import com.signa.app.data.remote.dto.PhotoModel
import com.signa.app.data.remote.dto.PhotoSize
import com.signa.app.data.remote.dto.SubmissionLocation
import com.signa.app.data.remote.dto.SubmissionMetadata
import com.signa.app.data.repository.MAX_CROP_BYTES
import com.signa.app.data.repository.PhotoRecognitionRepository
import com.signa.app.data.repository.PhotoSubmissionRepository
import com.signa.app.data.repository.RecognitionRateLimited
import com.signa.app.data.repository.RecognitionUnavailable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PhotoApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val clock = Clock.fixed(Instant.parse("2026-01-15T08:30:12Z"), ZoneOffset.UTC)
    private lateinit var server: MockWebServer
    private lateinit var recognitionApi: RecognitionApi
    private lateinit var recognition: PhotoRecognitionRepository
    private lateinit var submissions: PhotoSubmissionRepository

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val retrofit = ApiClientFactory.retrofit(ApiConfig(server.url("/api/v1/").toString()), OkHttpClient(), json)
        recognitionApi = retrofit.create(RecognitionApi::class.java)
        recognition = PhotoRecognitionRepository(recognitionApi, clock, fake = false)
        submissions = PhotoSubmissionRepository(retrofit.create(PhotoSubmissionApi::class.java), json)
    }

    @After
    fun close() {
        server.shutdown()
    }

    @Test
    fun `che do fake dung cung luong mo hinh ma khong goi HTTP`() = runTest {
        val fake = PhotoRecognitionRepository(recognitionApi, clock, fake = true)
        val size = PhotoSize(320, 240)
        val models = fake.models()
        val result = fake.detect(byteArrayOf(1), size, model = null)
        assertEquals(models.defaultModel, result.model.name)
        assertEquals(size, result.image)
        assertTrue(result.detections.isNotEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `doc danh sach mo hinh dung record ModelList`() = runTest {
        response(200, """
            {"defaultModel":"yolov8","models":[
              {"modelName":"yolov8","modelVersion":"yolov8s-v1.0","labelMapVersion":1,"modelCard":{"test":{}}},
              {"modelName":"detr","modelVersion":"detr-r50-v1.0","labelMapVersion":1,"modelCard":{}}
            ]}
        """)
        val models = recognition.models()
        assertEquals("/api/v1/recognition/models", server.takeRequest().path)
        assertEquals("yolov8", models.defaultModel)
        assertEquals(listOf("yolov8", "detr"), models.models.map { it.modelName })
    }

    @Test
    fun `nhan dien gui image model va doc box so thuc`() = runTest {
        response(200, DETECT_RESULT)
        val result = recognition.detect(byteArrayOf(1, 2, 3), PhotoSize(1280, 960), "yolov8")
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/recognition/detect", request.path)
        assertTrue(body.contains("name=\"image\"; filename=\"photo.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains("name=\"model\"\r\nContent-Length: 6\r\n\r\nyolov8") ||
            body.contains("name=\"model\"\r\n\r\nyolov8"))
        assertEquals(142L, result.inferenceMs)
        assertEquals(PhotoSize(1280, 960), result.image)
        val detection = result.detections.single()
        assertEquals(PhotoBox(812.25, 233.0, 64.5, 66.0), detection.box)
        assertEquals("P.123a", detection.signTypeCode)
        assertEquals("Cấm rẽ trái", detection.name)
    }

    @Test
    fun `bo part model neu dung mac dinh`() = runTest {
        response(200, DETECT_RESULT)
        recognition.detect(byteArrayOf(1), PhotoSize(1280, 960), model = null)
        assertFalse(server.takeRequest().body.readUtf8().contains("name=\"model\""))
    }

    @Test
    fun `phan biet 503 tu dich vu nhan dien`() = runTest {
        response(503, """{"code":"RECOGNITION_UNAVAILABLE","message":"Dịch vụ nhận diện tạm thời không phản hồi","details":[],"requestId":"test"}""")
        try {
            recognition.detect(byteArrayOf(1), PhotoSize(10, 10), null)
            fail("Cần báo lỗi dịch vụ nhận diện")
        } catch (_: RecognitionUnavailable) {
            assertEquals("/api/v1/recognition/detect", server.takeRequest().path)
        }
    }

    @Test
    fun `doc Retry After theo so giay va ngay HTTP khi 429`() = runTest {
        for ((header, seconds) in listOf("17" to 17L, "Thu, 15 Jan 2026 08:30:42 GMT" to 30L)) {
            response(429, """{"code":"RATE_LIMITED","message":"Quá nhiều yêu cầu","details":[],"requestId":"test"}""", header)
            try {
                recognition.detect(byteArrayOf(1), PhotoSize(10, 10), null)
                fail("Cần báo giới hạn tần suất")
            } catch (error: RecognitionRateLimited) {
                assertEquals(seconds, error.retryAfterSeconds)
            }
        }
    }

    @Test
    fun `gui metadata va crop va giu clientId khi server tra 201 roi 200`() = runTest {
        val clientId = UUID.randomUUID().toString()
        val metadata = submissions.encode(metadata(clientId))
        val obj = json.parseToJsonElement(metadata).jsonObject
        assertEquals(
            setOf("clientId", "signTypeCode", "confidence", "location", "headingDeg", "source", "flags", "model", "capturedAt"),
            obj.keys
        )
        assertEquals(clientId, obj.getValue("clientId").jsonPrimitive.content)
        assertEquals(setOf("lat", "lng"), obj.getValue("location").jsonObject.keys)
        assertEquals(setOf("name", "version", "labelMapVersion"), obj.getValue("model").jsonObject.keys)
        val receipt = """{"id":"1add4743-5ede-4c02-820b-da25e77b15d5","status":"RECEIVED"}"""
        val crop = ByteArray(MAX_CROP_BYTES) { 1 }
        var previous = ""
        for (status in listOf(201, 200)) {
            response(status, receipt)
            val result = submissions.submit(SubmissionOwner("user"), metadata, crop)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals(status == 201, result.created)
            assertEquals("RECEIVED", result.receipt.status)
            assertEquals("/api/v1/submissions", request.path)
            assertTrue(body.contains("name=\"metadata\""))
            assertTrue(body.contains("Content-Type: application/json"))
            assertTrue(body.contains("name=\"crop\"; filename=\"crop.jpg\""))
            assertTrue(body.contains("Content-Type: image/jpeg"))
            val payload = body.substringAfter("\r\n\r\n").substringBefore("\r\n")
            assertEquals(metadata, payload)
            if (status == 200) assertEquals(previous, payload)
            previous = payload
        }
    }

    @Test
    fun `tu choi anh cat qua 200 KiB truoc khi goi API`() = runTest {
        try {
            submissions.submit(SubmissionOwner("user"), submissions.encode(metadata()), ByteArray(MAX_CROP_BYTES + 1))
            fail("Cần kiểm tra kích thước trước khi tải lên")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `doc trang de xuat cung ly do tu choi`() = runTest {
        response(200, """
            {"items":[{"id":"submission-1","signTypeCode":"P.123a","status":"REJECTED","rejectReason":"Ảnh không rõ",
              "signId":null,"location":{"lat":21.0285,"lng":105.8048},"receivedAt":"2026-01-15T08:30:13Z",
              "capturedAt":"2026-01-15T08:30:12Z"}],"page":1,"size":20,"total":21}
        """)
        val page = submissions.list(SubmissionOwner("user"), page = 1, size = 20)
        assertEquals("/api/v1/me/submissions?page=1&size=20", server.takeRequest().path)
        assertEquals(21L, page.total)
        assertEquals("REJECTED", page.items.single().status)
        assertEquals("Ảnh không rõ", page.items.single().rejectReason)
    }

    private fun metadata(clientId: String = UUID.randomUUID().toString()) = SubmissionMetadata(
        clientId, "P.123a", .93, SubmissionLocation(21.0285, 105.8048), 359, "PHOTO",
        listOf("USER_EDITED"), PhotoModel("yolov8", "yolov8s-v1.0", 1), clock.instant().toString()
    )

    private fun response(status: Int, json: String, retryAfter: String? = null) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody(json.trimIndent()).apply { retryAfter?.let { setHeader("Retry-After", it) } })
    }

    private companion object {
        const val DETECT_RESULT = """
            {"model":{"name":"yolov8","version":"yolov8s-v1.0","labelMapVersion":1},
             "image":{"width":1280,"height":960},"inferenceMs":142,"detections":[
              {"box":{"x":812.25,"y":233.0,"width":64.5,"height":66.0},"signTypeCode":"P.123a",
               "confidence":0.93,"name":"Cấm rẽ trái","description":"Cấm các loại xe rẽ trái.","iconUrl":"/icons/P.123a.png"}
             ]}
        """
    }
}

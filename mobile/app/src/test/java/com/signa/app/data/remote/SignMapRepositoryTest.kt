package com.signa.app.data.remote

import com.signa.app.data.local.database.SignCatalogSnapshot
import com.signa.app.data.local.database.SignCatalogStore
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.remote.api.SignaMapApi
import com.signa.app.data.remote.dto.ReportReason
import com.signa.app.data.remote.dto.ReportResolution
import com.signa.app.data.remote.dto.ReportStatus
import com.signa.app.data.remote.dto.SignReportDto
import com.signa.app.data.remote.dto.SignTypesDto
import com.signa.app.data.remote.dto.SignRating
import com.signa.app.data.remote.interceptor.SessionInterceptor
import com.signa.app.data.repository.RemoteSignCatalogRepository
import com.signa.app.data.repository.RemoteSignMapRepository
import com.signa.app.data.repository.ReportPhoto
import com.signa.app.data.repository.SignViewport
import com.signa.app.domain.model.Session
import com.signa.app.domain.model.SessionSource
import com.signa.app.domain.model.User
import com.signa.app.support.MemorySessionStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SignMapRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private lateinit var config: ApiConfig
    private lateinit var api: SignaMapApi
    private lateinit var map: RemoteSignMapRepository

    @Before fun setUp() {
        server.start()
        config = ApiConfig(server.url("/").toString())
        val retrofit = ApiClientFactory.retrofit(config, OkHttpClient(), json)
        api = retrofit.create(SignaMapApi::class.java)
        map = RemoteSignMapRepository(api, json)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun catalogSendsPersistedEtagAndKeepsTypesOn304() = runTest {
        val store = MemoryCatalog()
        val repository = RemoteSignCatalogRepository(api, store, config, json)
        respond("""{"labelMapVersion":1,"items":[$TYPE]}""", etag = "\"catalog-v1\"")
        respond(CATEGORIES)
        repository.refresh()
        val firstTypes = request()
        assertEquals("/api/v1/sign-types", firstTypes.path)
        assertNull(firstTypes.getHeader("If-None-Match"))
        assertEquals("/api/v1/sign-categories", request().path)
        assertEquals(1, store.snapshots.value.types.labelMapVersion)
        assertEquals("\"catalog-v1\"", store.snapshots.value.etag)
        assertEquals("P.127_50", store.snapshots.value.types.items.single().code)
        assertEquals(server.url("/icons/P.127_50.png").toString(), store.snapshots.value.types.items.single().iconUrl)
        val saved = store.snapshots.value

        respond("", status = 304)
        respond(CATEGORIES)
        RemoteSignCatalogRepository(api, store, config, json).refresh()
        assertEquals("\"catalog-v1\"", request().getHeader("If-None-Match"))
        assertEquals("/api/v1/sign-categories", request().path)
        assertEquals(saved, store.snapshots.value)
        assertEquals(1, store.writes)
    }

    @Test fun catalogReplacesSnapshotOn200AndUsesCatalogForOfflineDetail() = runTest {
        val store = MemoryCatalog()
        val repository = RemoteSignCatalogRepository(api, store, config, json)
        respond("""{"labelMapVersion":1,"items":[$TYPE]}""", etag = "\"old\"")
        respond(CATEGORIES)
        repository.refresh()

        respond("""{"labelMapVersion":2,"items":[${TYPE.replace("P.127_50", "P.127_60")}]}""", etag = "\"new\"")
        respond(CATEGORIES)
        repository.refresh()
        assertEquals("P.127_60", store.snapshots.value.types.items.single().code)
        assertEquals(2, store.snapshots.value.types.labelMapVersion)
        assertEquals("\"new\"", store.snapshots.value.etag)

        respond(TYPE)
        val online = repository.type("P.127_50")
        assertEquals("Tốc độ tối đa cho phép 50 km/h", online?.name)
        repeat(4) { request() }
        assertEquals("/api/v1/sign-types/P.127_50", request().path)

        val unavailableClient = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        val offlineApi = ApiClientFactory.retrofit(config, unavailableClient, json).create(SignaMapApi::class.java)
        val offline = RemoteSignCatalogRepository(offlineApi, store, config, json)
        assertEquals("P.127_60", offline.type("P.127_60")?.code)
    }

    @Test fun viewportReadsBothJavaMapResultVariantsAndSendsFilters() = runTest {
        respond("""{"mode":"SIGNS","items":[{"id":"s1","signTypeCode":"P.127_50","categoryCode":"PROHIBITORY","location":{"lat":10.8,"lng":106.7},"headingDeg":null,"status":"UNDER_REVIEW","confidence":0.82}]}""")
        val viewport = SignViewport(106.6, 10.7, 106.8, 10.9, 14)
        val visible = map.visible(viewport, setOf("WARNING", "PROHIBITORY"), setOf("P.127_50"))
        val query = requireNotNull(request().requestUrl)
        assertEquals("/api/v1/signs", query.encodedPath)
        assertEquals("106.6,10.7,106.8,10.9", query.queryParameter("bbox"))
        assertEquals("14", query.queryParameter("zoom"))
        assertEquals("PROHIBITORY,WARNING", query.queryParameter("categories"))
        assertEquals("P.127_50", query.queryParameter("signTypeCodes"))
        assertEquals("UNDER_REVIEW", visible.signs.single().status)
        assertTrue(visible.clusters.isEmpty())

        respond("""{"mode":"CLUSTERS","items":[{"location":{"lat":10.8,"lng":106.7},"count":540}]}""")
        val clustered = map.visible(viewport, emptySet(), emptySet())
        assertEquals(540L, clustered.clusters.single().count)
        assertTrue(clustered.signs.isEmpty())
        assertNull(request().requestUrl?.queryParameter("categories"))
    }

    @Test fun detailAndRatingUseFlatRecordAndNoContent() = runTest {
        respond("""{"id":"s1","signTypeCode":"P.127_50","categoryCode":"PROHIBITORY","location":{"lat":10.8,"lng":106.7},"headingDeg":null,"status":"APPROVED","confidence":0.91,"confirmCount":7,"denyCount":2,"updatedAt":"2026-11-25T10:00:00Z","coverCropUrl":"https://storage.example/signed","myRating":"CONFIRM"}""")
        val detail = map.detail("s1")
        assertEquals("P.127_50", detail.signTypeCode)
        assertEquals(0.91, detail.confidence, 0.001)
        assertEquals(7, detail.confirmCount)
        assertEquals(2, detail.denyCount)
        assertEquals("2026-11-25T10:00:00Z", detail.updatedAt)
        assertEquals("https://storage.example/signed", detail.coverCropUrl)
        assertEquals(SignRating.CONFIRM, detail.myRating)
        assertEquals("/api/v1/signs/s1", request().path)

        respond("", status = 204)
        map.rate("s1", SignRating.DENY)
        val rating = request()
        assertEquals("/api/v1/signs/s1/rating", rating.path)
        assertEquals("PUT", rating.method)
        assertEquals("""{"value":"DENY"}""", rating.body.readUtf8())
    }

    @Test fun createsJsonAndMultipartReportsAndReadsHistoryRecord() = runTest {
        val metadata = SignReportDto(ReportReason.OTHER, "Thông tin chưa đúng")
        respond("""{"id":"r1","status":"PENDING"}""", status = 201)
        assertEquals(ReportStatus.PENDING, map.report("s1", metadata, null).status)
        val plain = request()
        assertEquals("/api/v1/signs/s1/reports", plain.path)
        assertEquals("POST", plain.method)
        assertEquals(metadata, json.decodeFromString<SignReportDto>(plain.body.readUtf8()))

        respond("""{"id":"r2","status":"PENDING"}""", status = 201)
        map.report("s1", SignReportDto(ReportReason.WRONG_LOCATION), ReportPhoto("photo-bytes".toByteArray(), "image/jpeg", "test.jpg"))
        val multipart = request()
        assertTrue(multipart.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        val parts = multipart.body.readUtf8()
        assertTrue(parts.contains("name=\"metadata\""))
        assertTrue(parts.contains("application/json"))
        assertTrue(parts.contains("""{"reason":"WRONG_LOCATION"}"""))
        assertTrue(parts.contains("name=\"photo\"; filename=\"test.jpg\""))
        assertTrue(parts.contains("photo-bytes"))

        respond("""{"items":[{"id":"r1","signId":"s1","reason":"WRONG_LOCATION","description":null,"status":"ACCEPTED","resolution":"EDITED","resolutionNote":"Đã chỉnh vị trí","createdAt":"2026-11-25T10:00:00Z","resolvedAt":"2026-11-25T11:00:00Z"}],"page":1,"size":20,"total":21}""")
        val page = map.myReports(1)
        assertEquals(ReportResolution.EDITED, page.items.single().resolution)
        assertEquals("Đã chỉnh vị trí", page.items.single().resolutionNote)
        assertEquals(21L, page.total)
        assertEquals("/api/v1/me/reports?page=1&size=20", request().path)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun feedbackUsesTheExistingBearerInterceptor() = runTest {
        val instant = Instant.parse("2026-11-25T10:00:00Z")
        val session = Session("session-token", User("u", "An", "an@example.com"),
            instant.plusSeconds(60).toEpochMilli(), SessionSource.REMOTE)
        val sessions = SessionManager(MemorySessionStore(session), Clock.fixed(instant, ZoneOffset.UTC),
            SessionSource.REMOTE, backgroundScope)
        runCurrent()
        val client = ApiClientFactory.client(SessionInterceptor(config, sessions))
        val signedApi = ApiClientFactory.retrofit(config, client, json).create(SignaMapApi::class.java)
        respond("", status = 204)
        RemoteSignMapRepository(signedApi, json).rate("s1", SignRating.CONFIRM)
        assertEquals("Bearer session-token", request().getHeader("Authorization"))
    }

    private fun request() = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    private fun respond(body: String, status: Int = 200, etag: String? = null) {
        val response = MockResponse().setResponseCode(status).setBody(body).addHeader("Content-Type", "application/json")
        if (etag != null) response.addHeader("ETag", etag)
        server.enqueue(response)
    }

    private class MemoryCatalog : SignCatalogStore {
        override val snapshots = MutableStateFlow(SignCatalogSnapshot(emptyList(), SignTypesDto(null, emptyList())))
        var writes = 0
        override suspend fun save(snapshot: SignCatalogSnapshot) {
            writes++
            snapshots.value = snapshot
        }
    }

    private companion object {
        const val CATEGORIES = """{"items":[{"code":"PROHIBITORY","name":"Biển cấm","description":null,"sortOrder":1}]}"""
        const val TYPE = """{"code":"P.127_50","categoryCode":"PROHIBITORY","name":"Tốc độ tối đa cho phép 50 km/h","description":null,"speedKmh":50,"iconUrl":"/icons/P.127_50.png","recognizable":true}"""
    }
}

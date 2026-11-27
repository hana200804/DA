package com.signa.app.data.remote.backend

import com.signa.app.data.remote.backend.error.apiCall
import com.signa.app.data.remote.backend.error.ApiException
import com.signa.app.data.remote.backend.client.ApiClientFactory
import com.signa.app.data.remote.backend.client.ApiConfig
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.remote.backend.api.AuthApi
import com.signa.app.data.remote.backend.dto.LoginRequest
import com.signa.app.data.remote.backend.interceptor.SessionInterceptor
import com.signa.app.data.repository.RemoteAuthRepository
import com.signa.app.domain.model.AuthError
import com.signa.app.domain.model.AuthException
import com.signa.app.domain.model.Session
import com.signa.app.domain.model.SessionSource
import com.signa.app.domain.model.User
import com.signa.app.support.MemorySessionStore
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val token = "signa_Test-Token_0123456789abcdef0123456789A"
    private val permissions = listOf("report:create", "sign:rate", "submission:create")
    private val profileId = "f39794ed-2de9-42a1-83f3-9b57b5c12a4f"

    private fun backendResponse(status: Int, fixture: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(requireNotNull(javaClass.getResource("/signa-auth/$fixture.json")).readText())

    private fun session(id: String, source: SessionSource = SessionSource.REMOTE) =
        Session(id, User("u", "An", "an@example.com"), now + 60_000, source)

    @Test fun normalizesBaseUrlAndSerializesLoginDto() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val config = ApiConfig(server.url("/").toString())
            val manager = SessionManager(MemorySessionStore(), clock, SessionSource.REMOTE, backgroundScope)
            runCurrent()
            val client = ApiClientFactory.client(SessionInterceptor(config, manager))
            val api = ApiClientFactory.retrofit(config, client, json).create(AuthApi::class.java)
            server.enqueue(backendResponse(200, "login"))
            val response = api.login(LoginRequest("an@example.com", "example-password", client = "MOBILE"))
            assertEquals(token, response.token)
            assertEquals(permissions, response.user.permissions)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/auth/login", request.path)
            assertEquals("POST", request.method)
            assertNull(request.getHeader("Authorization"))
            assertNotNull(request.getHeader("X-Request-Id"))
            val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("an@example.com", body.getValue("email").jsonPrimitive.content)
            assertEquals("MOBILE", body.getValue("client").jsonPrimitive.content)
        } finally { server.shutdown() }
    }

    @Test fun parsesStructuredHttpErrorAndMalformedErrorBody() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val config = ApiConfig(server.url("/api/v1/").toString())
            val manager = SessionManager(MemorySessionStore(), clock, SessionSource.REMOTE, backgroundScope)
            runCurrent()
            val api = ApiClientFactory.retrofit(config, ApiClientFactory.client(SessionInterceptor(config, manager)), json).create(AuthApi::class.java)
            server.enqueue(backendResponse(400, "me-invalid"))
            try {
                apiCall(json) { api.updateProfile(com.signa.app.data.remote.backend.dto.UpdateProfileRequest("")) }
                fail("Expected HTTP error")
            } catch (error: ApiException) {
                assertEquals(400, error.status)
                assertEquals("VALIDATION_ERROR", error.error?.code)
                assertEquals("0123456789abcdef0123456789abcdef", error.error?.requestId)
                assertEquals(listOf("displayName", "displayName"), error.error?.details?.map { it.field })
            }
            server.enqueue(MockResponse().setResponseCode(503).setBody("not json"))
            try {
                apiCall(json) { api.currentUser() }
                fail("Expected server error")
            } catch (error: ApiException) {
                assertEquals(503, error.status)
                assertNull(error.error)
            }
        } finally { server.shutdown() }
    }

    @Test fun authenticated401ClearsSessionButAnonymous401DoesNot() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val store = MemorySessionStore(session("token-a"))
            val manager = SessionManager(store, clock, SessionSource.REMOTE, backgroundScope)
            runCurrent()
            val config = ApiConfig(server.url("/api/v1/").toString())
            val client = ApiClientFactory.client(SessionInterceptor(config, manager))
            server.enqueue(backendResponse(401, "login-invalid"))
            client.newCall(Request.Builder().url(server.url("/api/v1/auth/login")).build()).execute().use { assertEquals(401, it.code) }
            assertEquals("token-a", store.current?.id)
            assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(backendResponse(401, "me-after-logout"))
            client.newCall(Request.Builder().url(server.url("/api/v1/me")).build()).execute().use { assertEquals(401, it.code) }
            assertEquals("Bearer token-a", server.takeRequest().getHeader("Authorization"))
            assertNull(store.current)
        } finally { server.shutdown() }
    }

    @Test fun neverAddsFakeTokenOrTokenForAnotherOrigin() = runTest {
        val server = MockWebServer()
        val external = MockWebServer()
        server.start()
        external.start()
        try {
            val store = MemorySessionStore(session("fake-token", SessionSource.FAKE))
            val manager = SessionManager(store, clock, SessionSource.FAKE, backgroundScope)
            runCurrent()
            val config = ApiConfig(server.url("/api/v1/").toString())
            val client = ApiClientFactory.client(SessionInterceptor(config, manager))
            server.enqueue(MockResponse().setBody("ok"))
            client.newCall(Request.Builder().url(server.url("/api/v1/me")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
            external.enqueue(MockResponse().setBody("ok"))
            client.newCall(Request.Builder().url(external.url("/storage/upload")).build()).execute().close()
            val request = external.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Request-Id"))
        } finally { server.shutdown(); external.shutdown() }
    }

    @Test fun delayed401CannotEraseReplacementSession() = runTest {
        val server = MockWebServer()
        val store = MemorySessionStore(session("old"))
        val manager = SessionManager(store, clock, SessionSource.REMOTE, backgroundScope)
        runCurrent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { manager.save(session("new")) }
                return MockResponse().setResponseCode(401)
            }
        }
        server.start()
        try {
            val config = ApiConfig(server.url("/api/v1/").toString())
            val client = ApiClientFactory.client(SessionInterceptor(config, manager))
            client.newCall(Request.Builder().url(server.url("/api/v1/me")).build()).execute().close()
            runCurrent()
            assertEquals("new", store.current?.id)
        } finally { server.shutdown() }
    }

    @Test fun timeoutSurfacesAsIoFailure() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val config = ApiConfig(server.url("/api/v1/").toString())
            val manager = SessionManager(MemorySessionStore(), clock, SessionSource.REMOTE, backgroundScope)
            runCurrent()
            val client = ApiClientFactory.client(SessionInterceptor(config, manager)).newBuilder()
                .callTimeout(300, TimeUnit.MILLISECONDS).build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            try {
                client.newCall(Request.Builder().url(server.url("/api/v1/me")).build()).execute().close()
                fail("Expected timeout")
            } catch (_: IOException) { }
        } finally { server.shutdown() }
    }

    @Test fun remoteRepositoryRestoresServerExpiryAndMapsInvalidLogin() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val store = MemorySessionStore()
            val manager = SessionManager(store, clock, SessionSource.REMOTE, backgroundScope)
            runCurrent()
            val config = ApiConfig(server.url("/api/v1/").toString())
            val api = ApiClientFactory.retrofit(config, ApiClientFactory.client(SessionInterceptor(config, manager)), json).create(AuthApi::class.java)
            val repository = RemoteAuthRepository(api, manager, json, clock)
            server.enqueue(backendResponse(200, "login"))
            repository.login("an@example.com", "password")
            assertEquals(token, store.current?.id)
            assertEquals(Instant.parse("2026-12-25T16:18:06.845Z").toEpochMilli(), store.current?.expiresAtEpochMillis)
            assertEquals(SessionSource.REMOTE, store.current?.source)
            assertEquals("USER", store.current?.user?.role)
            assertEquals(permissions, store.current?.user?.permissions)
            server.enqueue(backendResponse(401, "login-invalid"))
            try {
                repository.login("an@example.com", "incorrect")
                fail("Expected bad credentials")
            } catch (error: AuthException) {
                assertEquals(AuthError.InvalidCredentials, error.error)
            }
            assertEquals(token, store.current?.id)
        } finally { server.shutdown() }
    }

    @Test fun remoteRepositoryUpdatesProfileAndKeepsTokenAfterChangingPassword() = runTest {
        MockWebServer().use { server ->
            val store = MemorySessionStore(session(token).let { it.copy(user = it.user.copy(id = profileId)) })
            val manager = SessionManager(store, clock, SessionSource.REMOTE, backgroundScope)
            runCurrent()
            val config = ApiConfig(server.url("/").toString())
            val client = ApiClientFactory.client(SessionInterceptor(config, manager))
            val repository = RemoteAuthRepository(ApiClientFactory.retrofit(config, client, json).create(AuthApi::class.java), manager, json, clock)

            server.enqueue(backendResponse(200, "me"))
            repository.refreshProfile()
            assertEquals("Tài khoản Android", store.current?.user?.displayName)
            assertEquals(permissions, store.current?.user?.permissions)
            val get = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", get.method)
            assertEquals("/api/v1/me", get.path)
            assertEquals("Bearer $token", get.getHeader("Authorization"))

            server.enqueue(backendResponse(200, "me-patch"))
            repository.updateProfile(" Tên Android đã sửa ")
            assertEquals("Tên Android đã sửa", store.current?.user?.displayName)
            val patch = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PATCH", patch.method)
            assertEquals("/api/v1/me", patch.path)
            assertEquals("Bearer $token", patch.getHeader("Authorization"))
            val renameBody = json.parseToJsonElement(patch.body.readUtf8()).jsonObject
            assertEquals(setOf("displayName"), renameBody.keys)
            assertEquals("Tên Android đã sửa", renameBody.getValue("displayName").jsonPrimitive.content)

            server.enqueue(backendResponse(422, "password-wrong"))
            val error = runCatching { repository.changePassword("wrong", "password-new") }.exceptionOrNull() as AuthException
            assertEquals(AuthError.WrongPassword, error.error)
            assertEquals(token, store.current?.id)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            server.enqueue(MockResponse().setResponseCode(204))
            repository.changePassword("password-old", "password-new")
            assertEquals(token, store.current?.id)
            val change = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PUT", change.method)
            assertEquals("/api/v1/me/password", change.path)
            assertEquals("Bearer $token", change.getHeader("Authorization"))
            val passwordBody = json.parseToJsonElement(change.body.readUtf8()).jsonObject
            assertEquals(setOf("currentPassword", "newPassword"), passwordBody.keys)
            assertEquals("password-old", passwordBody.getValue("currentPassword").jsonPrimitive.content)
        }
    }

    @Test fun remoteRepositoryRegistersAndSendsResetCodeWithRateLimitFeedback() = runTest {
        MockWebServer().use { server ->
            val manager = SessionManager(MemorySessionStore(), clock, SessionSource.REMOTE, backgroundScope)
            runCurrent()
            val config = ApiConfig(server.url("/").toString())
            val client = ApiClientFactory.client(SessionInterceptor(config, manager))
            val repository = RemoteAuthRepository(ApiClientFactory.retrofit(config, client, json).create(AuthApi::class.java), manager, json, clock)

            server.enqueue(backendResponse(201, "register"))
            repository.register("Tài khoản Android", "an@example.com", "example-password")
            assertEquals("/api/v1/auth/register", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            server.enqueue(backendResponse(409, "register-conflict"))
            val duplicate = runCatching {
                repository.register("Tài khoản Android", "an@example.com", "example-password")
            }.exceptionOrNull() as AuthException
            assertEquals(AuthError.EmailExists, duplicate.error)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            server.enqueue(MockResponse().setResponseCode(202))
            assertEquals("an@example.com", repository.requestPasswordReset("an@example.com").email)
            assertEquals("/api/v1/auth/password/forgot", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            server.enqueue(backendResponse(429, "forgot-rate").setHeader("Retry-After", "900"))
            val limited = runCatching { repository.requestPasswordReset("an@example.com") }.exceptionOrNull() as AuthException
            assertEquals(AuthError.RateLimited, limited.error)
            assertEquals(900, limited.retryAfterSeconds)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            server.enqueue(backendResponse(422, "reset-invalid"))
            val wrongOtp = runCatching {
                repository.resetPassword("an@example.com", "000000", "password-new")
            }.exceptionOrNull() as AuthException
            assertEquals(AuthError.InvalidOtp, wrongOtp.error)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setResponseCode(204))
            repository.resetPassword("an@example.com", "123456", "password-new")
            val reset = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/auth/password/reset", reset.path)
            assertEquals("POST", reset.method)
            assertNull(reset.getHeader("Authorization"))
            val resetBody = json.parseToJsonElement(reset.body.readUtf8()).jsonObject
            assertEquals(setOf("email", "code", "newPassword"), resetBody.keys)
            assertEquals("123456", resetBody.getValue("code").jsonPrimitive.content)
        }
    }
}

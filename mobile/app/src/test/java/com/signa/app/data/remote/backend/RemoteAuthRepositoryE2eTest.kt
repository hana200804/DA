package com.signa.app.data.remote.backend

import com.signa.app.data.remote.backend.client.ApiClientFactory
import com.signa.app.data.remote.backend.client.ApiConfig
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.remote.backend.api.AuthApi
import com.signa.app.data.remote.backend.interceptor.SessionInterceptor
import com.signa.app.data.repository.RemoteAuthRepository
import com.signa.app.domain.model.SessionSource
import com.signa.app.support.MemorySessionStore
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RemoteAuthRepositoryE2eTest {
    @Test fun accountLifecycleAgainstSigna(): Unit = runBlocking {
        val baseUrl = System.getenv("SIGNA_E2E_BASE_URL")
        assumeTrue("Cần SIGNA_E2E_BASE_URL trỏ tới backend dev", !baseUrl.isNullOrBlank())
        val config = ApiConfig(requireNotNull(baseUrl))
        val clock = Clock.systemUTC()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val manager = SessionManager(MemorySessionStore(), clock, SessionSource.REMOTE, scope)
        val json = Json { ignoreUnknownKeys = true }
        val client = ApiClientFactory.client(SessionInterceptor(config, manager))
        val api = ApiClientFactory.retrofit(config, client, json).create(AuthApi::class.java)
        val repository = RemoteAuthRepository(api, manager, json, clock)
        val email = "android-e2e-${UUID.randomUUID()}@example.com"
        val password = "Signa-mobile-test-2026!"
        val newPassword = "Signa-mobile-test-changed-2026!"

        try {
            repository.register("Tài khoản Android", email, password)
            repository.login(email, password)
            val session = withTimeout(15_000) { repository.session.first { it != null } }!!
            assertEquals("USER", session.user.role)
            assertTrue(session.user.permissions.contains("submission:create"))

            repository.refreshProfile()
            assertEquals(email, api.currentUser().email)
            repository.updateProfile("Tên Android đã sửa")
            val renamed = withTimeout(15_000) {
                repository.session.first { it?.user?.displayName == "Tên Android đã sửa" }
            }
            assertEquals(session.id, renamed?.id)

            repository.changePassword(password, newPassword)
            repository.refreshProfile()
            assertEquals(session.id, manager.current?.id)
            assertEquals("Tên Android đã sửa", api.currentUser().displayName)

            repository.logout()
            withTimeout(15_000) { repository.session.first { it == null } }
            repository.login(email, newPassword)
            withTimeout(15_000) { repository.session.first { it != null } }
            repository.logout()
            withTimeout(15_000) { repository.session.first { it == null } }
        } finally {
            scope.cancel()
        }
    }
}

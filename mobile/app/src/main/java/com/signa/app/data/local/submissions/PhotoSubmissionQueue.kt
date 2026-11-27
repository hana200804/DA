package com.signa.app.data.local.submissions

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.remote.backend.api.SubmissionOwner
import com.signa.app.data.remote.backend.dto.SubmissionMetadata
import com.signa.app.data.repository.MAX_CROP_BYTES
import com.signa.app.data.repository.PhotoSubmissionRepository
import com.signa.app.di.ApplicationScope
import com.signa.app.domain.model.Session
import com.signa.app.domain.model.SessionSource
import com.signa.app.domain.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

@Singleton
class PhotoSubmissionQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
    database: PhotoQueueDatabase,
    private val repository: PhotoSubmissionRepository,
    private val settings: SettingsRepository,
    private val sessions: SessionManager,
    @param:ApplicationScope private val scope: CoroutineScope
) {
    private val dao = database.queue()
    private val started = AtomicBoolean()
    private val drainLock = Mutex()
    private val workManager get() = WorkManager.getInstance(context)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val recovery = PeriodicWorkRequestBuilder<PhotoSubmissionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(wifiOnly = false))
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(RECOVERY_WORK, ExistingPeriodicWorkPolicy.KEEP, recovery)
        scope.launch {
            combine(sessions.session, settings.settings) { session, preferences ->
                (session?.takeIf { preferences.contributionEnabled }?.queueOwner) to preferences.wifiOnly
            }.distinctUntilChanged().collect { (owner, wifiOnly) ->
                if (owner != null && dao.hasPending(owner)) schedule(wifiOnly, ExistingWorkPolicy.REPLACE)
            }
        }
    }

    fun observe(session: Session) = dao.observe(session.queueOwner)

    suspend fun enqueue(session: Session, metadata: SubmissionMetadata, jpeg: ByteArray) {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_CROP_BYTES)
        require(sessions.current?.queueOwner == session.queueOwner)
        val preferences = settings.settings.first()
        require(preferences.contributionEnabled)
        dao.enqueue(QueuedPhoto(
            clientId = metadata.clientId,
            owner = session.queueOwner,
            signTypeCode = metadata.signTypeCode,
            capturedAt = metadata.capturedAt,
            metadataJson = repository.encode(metadata),
            jpeg = jpeg
        ))
        schedule(preferences.wifiOnly, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    internal suspend fun drain(): Boolean = drainLock.withLock {
        val session = sessions.session.first() ?: return@withLock true
        while (true) {
            val photo = dao.next(session.queueOwner) ?: break
            val preferences = settings.settings.first()
            if (!preferences.contributionEnabled || sessions.current?.queueOwner != session.queueOwner) break
            if (preferences.wifiOnly && !isWifiConnected()) return@withLock false

            try {
                if (session.source == SessionSource.FAKE) {
                    dao.acknowledgeFake(photo.orderId)
                } else {
                    repository.submit(SubmissionOwner(session.user.id), photo.metadataJson, photo.jpeg)
                    dao.remove(photo.orderId)
                }
            } catch (_: IOException) {
                return@withLock false
            } catch (failure: HttpException) {
                when (failure.code()) {
                    401 -> return@withLock false
                    400, 403, 404, 413, 415, 422 -> dao.fail(
                        photo.orderId,
                        "Máy chủ từ chối đề xuất (mã HTTP ${failure.code()})."
                    )
                    else -> return@withLock false
                }
            } catch (_: IllegalArgumentException) {
                dao.fail(photo.orderId, "Ảnh hoặc dữ liệu đề xuất không hợp lệ.")
            } catch (_: IllegalStateException) {
                return@withLock false
            }
        }
        true
    }

    private fun schedule(wifiOnly: Boolean, policy: ExistingWorkPolicy) {
        val work = OneTimeWorkRequestBuilder<PhotoSubmissionWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SEND_WORK, policy, work)
    }

    private fun constraints(wifiOnly: Boolean): Constraints {
        val builder = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
        if (wifiOnly) {
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            builder.setRequiredNetworkRequest(network, NetworkType.CONNECTED)
        }
        return builder.build()
    }

    private fun isWifiConnected(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private val Session.queueOwner get() = "${source.name}:${user.id}"

    private companion object {
        const val SEND_WORK = "signa-photo-submissions"
        const val RECOVERY_WORK = "signa-photo-submissions-recovery"
    }
}

class PhotoSubmissionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun photoSubmissionQueue(): PhotoSubmissionQueue
    }

    override suspend fun doWork(): Result {
        val queue = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
            .photoSubmissionQueue()
        return if (queue.drain()) Result.success() else Result.retry()
    }
}

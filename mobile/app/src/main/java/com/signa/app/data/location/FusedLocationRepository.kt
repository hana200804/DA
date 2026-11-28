package com.signa.app.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.signa.app.domain.model.DeviceLocation
import com.signa.app.domain.model.GeoPoint
import com.signa.app.domain.model.LocationProblem
import com.signa.app.domain.model.LocationResult
import com.signa.app.domain.repository.LocationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class FusedLocationRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock
) : LocationRepository {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Both fine/coarse are checked immediately before the request; revocation is handled below.
    override suspend fun currentLocation(): LocationResult {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return failure(LocationProblem.PermissionDenied)
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !LocationManagerCompat.isLocationEnabled(manager)) return failure(LocationProblem.Disabled)
        val cancellation = CancellationTokenSource()
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(10_000)
                .setDurationMillis(15_000)
                .build()
            val fix = withTimeout(20_000) { client.getCurrentLocation(request, cancellation.token).await() }
            val location = fix?.toDeviceLocation(!fine)
            if (location == null || clock.millis() - location.capturedAtEpochMillis !in -5_000..30_000) {
                failure(LocationProblem.Unavailable)
            } else LocationResult.Available(location)
        } catch (_: TimeoutCancellationException) {
            failure(LocationProblem.TimedOut)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            failure(LocationProblem.PermissionDenied)
        } catch (_: com.google.android.gms.common.api.ApiException) {
            failure(LocationProblem.Unavailable)
        } catch (_: IllegalStateException) {
            failure(LocationProblem.Unavailable)
        } finally {
            cancellation.cancel()
        }
    }

    @SuppressLint("MissingPermission") // Checked for every new foreground subscription.
    override fun observeLocations() = callbackFlow {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            close()
            return@callbackFlow
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.toDeviceLocation(approximate = false)?.let { trySend(it) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMaxUpdateAgeMillis(0)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close() }
        } catch (_: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toDeviceLocation(approximate: Boolean): DeviceLocation? {
        val point = GeoPoint.fromOrNull(latitude, longitude) ?: return null
        return DeviceLocation(point, accuracy, time, approximate,
            speed.takeIf { hasSpeed() }, bearing.takeIf { hasBearing() }, elapsedRealtimeNanos)
    }

    private fun failure(problem: LocationProblem) = LocationResult.Unavailable(problem)
}

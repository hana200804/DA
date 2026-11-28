package com.signa.app.domain.repository

import com.signa.app.domain.model.DeviceLocation
import com.signa.app.domain.model.LocationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface LocationRepository {
    /** Foreground, cancellable one-shot location request. Never substitutes sample coordinates. */
    suspend fun currentLocation(): LocationResult

    /** Updates while a foreground collector needs a fix for a camera frame. */
    fun observeLocations(): Flow<DeviceLocation> = emptyFlow()
}

package com.signa.app.vision.geo

import com.signa.app.domain.model.DeviceLocation
import com.signa.app.domain.model.GeoPoint
import kotlin.math.roundToInt

internal data class ObservationGeo(val point: GeoPoint, val headingDeg: Int?)

/** Ghép fix chính xác và còn mới với khung hình, theo đồng hồ đơn điệu của thiết bị. */
internal fun DeviceLocation.forObservation(frameNanos: Long): ObservationGeo? {
    val fixNanos = elapsedRealtimeNanos ?: return null
    if (isApproximate || accuracyMeters !in 0f..15f || frameNanos - fixNanos !in 0L..2_000_000_000L) return null
    val heading = bearingDegrees?.takeIf {
        it.isFinite() && (speedMetersPerSecond ?: 0f) >= 5f / 3.6f
    }?.roundToInt()?.mod(360)
    return ObservationGeo(point, heading)
}

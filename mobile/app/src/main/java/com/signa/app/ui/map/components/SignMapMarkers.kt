package com.signa.app.ui.map.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.signa.app.data.repository.point
import com.signa.app.domain.model.GeoPoint
import com.signa.app.ui.map.MapUiState
import vn.vietmap.vietmapsdk.maps.Style

internal class SignMapMarkers(private val density: Float) {
    private var displayedImages = emptySet<String>()

    fun features(state: MapUiState, style: Style): FeatureCollection {
        val icons = linkedMapOf<String, () -> Bitmap>()
        val features = state.positionedSigns.map { sign ->
            val label = sign.speedLimitKmh?.toString() ?: sign.code
            val reviewing = state.signStatuses[sign.id] == "UNDER_REVIEW"
            val icon = "signa-sign-${sign.speedLimitKmh != null}-$label-$reviewing"
            icons[icon] = { badge(label, isCluster = false, reviewing = reviewing) }
            marker(requireNotNull(sign.position), icon).apply { addStringProperty("signId", sign.id) }
        } + state.clusters.mapNotNull { cluster ->
            val point = cluster.location.point() ?: return@mapNotNull null
            val label = cluster.count.toString()
            val icon = "signa-cluster-$label"
            icons[icon] = { badge(label, isCluster = true, reviewing = false) }
            marker(point, icon).apply { addBooleanProperty("cluster", true) }
        }
        icons.forEach { (id, draw) -> if (id !in displayedImages) style.addImage(id, draw()) }
        (displayedImages - icons.keys).forEach { style.removeImage(it) }
        displayedImages = icons.keys.toSet()
        return FeatureCollection.fromFeatures(features)
    }

    private fun marker(point: GeoPoint, icon: String): Feature =
        Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty("icon", icon)
        }

    private fun badge(label: String, isCluster: Boolean, reviewing: Boolean): Bitmap {
        val size = (48 * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, center - density, paint)
        paint.color = when {
            isCluster -> Color.rgb(7, 86, 214)
            reviewing -> Color.rgb(180, 97, 0)
            else -> Color.rgb(204, 30, 40)
        }
        if (!isCluster) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * density
        }
        canvas.drawCircle(center, center, center - 3 * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (isCluster) Color.WHITE else Color.rgb(16, 35, 63)
        paint.textSize = 18 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val maxWidth = size - 12 * density
        val textWidth = paint.measureText(label)
        if (textWidth > maxWidth) paint.textSize *= maxWidth / textWidth
        canvas.drawText(label, center, center - (paint.ascent() + paint.descent()) / 2, paint)
        bitmap.density = (density * 160).toInt()
        return bitmap
    }
}

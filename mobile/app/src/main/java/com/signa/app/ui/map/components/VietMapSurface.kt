package com.signa.app.ui.map.components

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.signa.app.domain.model.DeviceLocation
import com.signa.app.domain.model.TrafficSign
import com.signa.app.data.remote.backend.dto.MapClusterDto
import com.signa.app.data.repository.SignViewport
import com.signa.app.ui.map.MapCameraTarget
import com.signa.app.ui.map.DirectionsUiState
import com.signa.app.ui.map.MapViewportInsets
import com.signa.app.domain.model.GeoPoint
import com.signa.app.domain.model.MapDestination
import com.signa.app.domain.model.RoadRoute
import com.mapbox.geojson.LineString
import vn.vietmap.vietmapsdk.style.layers.LineLayer
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.lineColor
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.lineWidth
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.lineCap
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.lineJoin
import vn.vietmap.vietmapsdk.geometry.LatLngBounds
import com.signa.app.ui.map.MapLoadStatus
import com.signa.app.ui.map.MapUiState
import vn.vietmap.vietmapsdk.Vietmap
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import vn.vietmap.vietmapsdk.style.sources.GeoJsonSource
import vn.vietmap.vietmapsdk.style.layers.SymbolLayer
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.iconImage
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.iconAllowOverlap
import vn.vietmap.vietmapsdk.style.layers.PropertyFactory.iconIgnorePlacement
import vn.vietmap.vietmapsdk.style.expressions.Expression.get as featureProperty
import vn.vietmap.vietmapsdk.camera.CameraPosition
import vn.vietmap.vietmapsdk.camera.CameraUpdateFactory
import vn.vietmap.vietmapsdk.geometry.LatLng
import vn.vietmap.vietmapsdk.log.Logger
import vn.vietmap.vietmapsdk.maps.MapView
import vn.vietmap.vietmapsdk.maps.Style
import vn.vietmap.vietmapsdk.maps.VietMapGL
import kotlin.math.floor

@Composable
internal fun VietMapSurface(
    state: MapUiState,
    configuration: VietMapConfiguration,
    onSelectSign: (String) -> Unit,
    onStatus: (MapLoadStatus) -> Unit,
    modifier: Modifier = Modifier,
    directions: DirectionsUiState = DirectionsUiState(),
    viewportInsets: MapViewportInsets = MapViewportInsets(),
    onChoosePoint: (GeoPoint) -> Unit = {},
    onCameraIdle: (SignViewport) -> Unit = {},
    session: VietMapSession? = null
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestSelection = rememberUpdatedState(onSelectSign)
    val latestStatus = rememberUpdatedState(onStatus)
    val latestPoint = rememberUpdatedState(onChoosePoint)
    val latestViewport = rememberUpdatedState(onCameraIdle)
    val result = remember(context, configuration, state.retryMapId, session) {
        val create = {
            try {
                Result.success(VietMapController(context, configuration, { latestSelection.value(it) },
                    { latestStatus.value(it) }, { latestPoint.value(it) }, { latestViewport.value(it) }))
            } catch (failure: RuntimeException) {
                Result.failure(failure)
            } catch (failure: LinkageError) {
                Result.failure(failure)
            }
        }
        session?.acquire(configuration, state.retryMapId, create) ?: create()
    }
    val controller = result.getOrNull()
    if (controller == null) {
        LaunchedEffect(result) { onStatus(MapLoadStatus.Failed) }
        Box(modifier)
        return
    }
    controller.bind({ latestSelection.value(it) }, { latestStatus.value(it) },
        { latestPoint.value(it) }, { latestViewport.value(it) })
    DisposableEffect(controller, lifecycle, session) {
        context.registerComponentCallbacks(controller)
        lifecycle.addObserver(controller)
        onDispose {
            lifecycle.removeObserver(controller)
            context.unregisterComponentCallbacks(controller)
            controller.detach()
            if (session == null) controller.close()
        }
    }
    LaunchedEffect(controller, lifecycle) { controller.publishRetainedState() }
    AndroidView(factory = {
        (controller.view.parent as? ViewGroup)?.removeView(controller.view)
        controller.view
    }, modifier = modifier, update = { controller.render(state, directions, viewportInsets) })
}

/** Retains one native map within the main navigation UI, never in a ViewModel. */
internal class VietMapSession {
    private var controller: VietMapController? = null
    private var key: Pair<VietMapConfiguration, Int>? = null

    fun acquire(configuration: VietMapConfiguration, retry: Int,
        create: () -> Result<VietMapController>): Result<VietMapController> {
        val next = configuration to retry
        controller?.takeIf { key == next && !it.isClosed }?.let { return Result.success(it) }
        close()
        return create().onSuccess { controller = it; key = next }
    }

    fun close() {
        controller?.close()
        controller = null
        key = null
    }
}

@Composable
internal fun rememberVietMapSession(): VietMapSession {
    val session = remember { VietMapSession() }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}

/** Only this UI adapter owns SDK objects; repositories and ViewModels never hold a MapView. */
internal class VietMapController(
    context: Context,
    private val configuration: VietMapConfiguration,
    private var onSelectSign: (String) -> Unit,
    private var onStatus: (MapLoadStatus) -> Unit,
    private var onChoosePoint: (GeoPoint) -> Unit,
    private var onCameraIdle: (SignViewport) -> Unit
) : DefaultLifecycleObserver, ComponentCallbacks {
    val view: MapView
    val isClosed: Boolean get() = closed

    fun bind(select: (String) -> Unit, status: (MapLoadStatus) -> Unit,
        point: (GeoPoint) -> Unit, viewport: (SignViewport) -> Unit) {
        onSelectSign = select
        onStatus = status
        onChoosePoint = point
        onCameraIdle = viewport
    }

    fun publishRetainedState() {
        if (ready && !closed) {
            onStatus(if (configuration.isConfigured) MapLoadStatus.Ready else MapLoadStatus.MissingKey)
            view.post { map?.let(::publishViewport) }
        }
    }

    fun detach() {
        stop()
        // Retained native views must not keep callbacks into a disposed destination.
        bind({}, {}, {}, {})
    }
    private val density = context.resources.displayMetrics.density
    private val markers = SignMapMarkers(density)
    private var signSource: GeoJsonSource? = null
    private var locationSource: GeoJsonSource? = null
    private var routeSource: GeoJsonSource? = null
    private var destinationSource: GeoJsonSource? = null
    private var renderedRoute: RoadRoute? = null
    private var renderedDestination: MapDestination? = null
    private var lastDirectionCamera: MapCameraTarget? = null
    private var lastFitRoute = 0L
    private var lastViewWasRoute = false
    private var latestDirections = DirectionsUiState()
    private var latestInsets = MapViewportInsets()
    private var renderedInsets: MapViewportInsets? = null
    private var map: VietMapGL? = null
    private var mapStyle: Style? = null
    private var latestState = MapUiState()
    private var renderedSigns: List<TrafficSign>? = null
    private var renderedStatuses: Map<String, String>? = null
    private var renderedClusters: List<MapClusterDto>? = null
    private var renderedLocation: DeviceLocation? = null
    private var lastCamera: MapCameraTarget? = null
    private var ready = false
    private var started = false
    private var resumed = false
    private var closed = false

    init {
        Vietmap.getInstance(context)
        // Third-party request logs can include a key in tile/style query parameters.
        Logger.setVerbosity(Logger.NONE)
        view = MapView(context)
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener { if (!closed) onStatus(MapLoadStatus.Failed) }
        view.getMapAsync { sdk ->
            if (!closed) {
                map = sdk
                sdk.uiSettings.apply {
                    isLogoEnabled = true
                    isAttributionEnabled = true
                    setLogoGravity(Gravity.BOTTOM or Gravity.START)
                    setLogoMargins(px(12), 0, 0, px(185))
                    setAttributionGravity(Gravity.BOTTOM or Gravity.END)
                    setAttributionMargins(0, 0, px(12), px(185))
                    setCompassMargins(0, px(200), px(16), 0)
                }
                sdk.addOnMapClickListener { coordinate ->
                    val feature = sdk.queryRenderedFeatures(sdk.projection.toScreenLocation(coordinate), SIGN_LAYER).firstOrNull()
                    if (feature?.hasProperty("cluster") == true) {
                        val center = feature.geometry() as? Point
                        center?.let {
                            sdk.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                LatLng(it.latitude(), it.longitude()), (sdk.cameraPosition.zoom + 2).coerceAtMost(sdk.maxZoomLevel)
                            ), 400)
                        }
                        true
                    } else {
                        val signId = feature?.getStringProperty("signId")
                        if (signId == null) false else { onSelectSign(signId); true }
                    }
                }
                sdk.addOnCameraIdleListener { publishViewport(sdk) }
                sdk.addOnMapLongClickListener { coordinate ->
                    GeoPoint.fromOrNull(coordinate.latitude, coordinate.longitude)?.let(onChoosePoint)
                    true
                }
                val style = if (configuration.isConfigured) Style.Builder().fromUri(configuration.styleUrl)
                    else Style.Builder().fromJson(VietMapConfiguration.EMPTY_STYLE)
                sdk.setStyle(style) { loadedStyle ->
                    if (!closed) {
                        mapStyle = loadedStyle
                        val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
                        routeSource = GeoJsonSource(ROUTE_SOURCE, empty).also { loadedStyle.addSource(it) }
                        destinationSource = GeoJsonSource(DESTINATION_SOURCE, empty).also { loadedStyle.addSource(it) }
                        loadedStyle.addLayer(LineLayer("signa-route-casing", ROUTE_SOURCE).withProperties(
                            lineColor("#FFFFFF"), lineWidth(10f), lineCap("round"), lineJoin("round")))
                        loadedStyle.addLayer(LineLayer("signa-route-line", ROUTE_SOURCE).withProperties(
                            lineColor("#0756D6"), lineWidth(6f), lineCap("round"), lineJoin("round")))
                        signSource = GeoJsonSource(SIGN_SOURCE, empty).also { loadedStyle.addSource(it) }
                        locationSource = GeoJsonSource(LOCATION_SOURCE, empty).also { loadedStyle.addSource(it) }
                        loadedStyle.addLayer(SymbolLayer(SIGN_LAYER, SIGN_SOURCE).withProperties(
                            iconImage(featureProperty("icon")), iconAllowOverlap(true), iconIgnorePlacement(true)
                        ))
                        loadedStyle.addImage(LOCATION_ICON, locationIcon())
                        loadedStyle.addLayer(SymbolLayer("signa-location-layer", LOCATION_SOURCE).withProperties(
                            iconImage(LOCATION_ICON), iconAllowOverlap(true), iconIgnorePlacement(true)
                        ))
                        loadedStyle.addImage(DESTINATION_ICON, destinationIcon())
                        loadedStyle.addLayer(SymbolLayer("signa-destination-layer", DESTINATION_SOURCE).withProperties(
                            iconImage(DESTINATION_ICON), iconAllowOverlap(true), iconIgnorePlacement(true)))
                        ready = true
                        onStatus(if (configuration.isConfigured) MapLoadStatus.Ready else MapLoadStatus.MissingKey)
                        render(latestState, latestDirections, latestInsets)
                        view.post { publishViewport(sdk) }
                    }
                }
            }
        }
    }

    fun render(state: MapUiState, directions: DirectionsUiState, insets: MapViewportInsets) {
        latestState = state
        latestDirections = directions
        latestInsets = insets
        val sdk = map ?: return
        val style = mapStyle ?: return
        if (!ready || closed) return
        if (renderedSigns != state.positionedSigns || renderedStatuses != state.signStatuses || renderedClusters != state.clusters) {
            signSource?.setGeoJson(markers.features(state, style))
            renderedSigns = state.positionedSigns
            renderedStatuses = state.signStatuses
            renderedClusters = state.clusters
        }
        if (state.userLocation != renderedLocation) {
            val features = state.userLocation?.let {
                listOf(Feature.fromGeometry(Point.fromLngLat(it.point.longitude, it.point.latitude)))
            }.orEmpty()
            locationSource?.setGeoJson(FeatureCollection.fromFeatures(features))
            renderedLocation = state.userLocation
        }
        if (renderedRoute != directions.route) {
            val features = directions.route?.let { road ->
                listOf(Feature.fromGeometry(LineString.fromLngLats(road.points.map { Point.fromLngLat(it.longitude, it.latitude) })))
            }.orEmpty()
            routeSource?.setGeoJson(FeatureCollection.fromFeatures(features))
            renderedRoute = directions.route
        }
        if (renderedDestination != directions.destination) {
            val features = directions.destination?.let { place -> listOf(Feature.fromGeometry(Point.fromLngLat(place.point.longitude, place.point.latitude))) }.orEmpty()
            destinationSource?.setGeoJson(FeatureCollection.fromFeatures(features))
            renderedDestination = directions.destination
        }
        val insetsChanged = renderedInsets != insets
        if (insetsChanged) {
            val bottom = insets.bottom.coerceAtLeast(px(80)) + px(8)
            sdk.uiSettings.setLogoMargins(px(12), 0, 0, bottom)
            sdk.uiSettings.setAttributionMargins(0, 0, px(12), bottom)
            sdk.uiSettings.setCompassMargins(0, insets.top + px(16), px(16), 0)
            renderedInsets = insets
        }
        var cameraMoved = false
        state.cameraTarget?.let { target ->
            if (target != lastCamera) {
                val update = CameraUpdateFactory.newLatLngZoom(LatLng(target.point.latitude, target.point.longitude), 14.5)
                if (lastCamera == null) sdk.moveCamera(update) else sdk.animateCamera(update, 500)
                lastCamera = target
                cameraMoved = true
                lastViewWasRoute = false
            }
        }
        directions.cameraTarget?.let { target ->
            if (target != lastDirectionCamera) {
                sdk.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.point.latitude, target.point.longitude), 15.5), 500)
                lastDirectionCamera = target
                cameraMoved = true
                lastViewWasRoute = false
            }
        }
        if (directions.cameraTarget == null) lastDirectionCamera = null
        val road = directions.route
        val newFit = directions.fitRouteRequest != lastFitRoute
        if (road != null && (newFit || (insetsChanged && lastViewWasRoute && !cameraMoved))) {
            val bounds = LatLngBounds.Builder().includes(
                (road.points + road.origin + road.destination).map { LatLng(it.latitude, it.longitude) }
            ).build()
            val totalPadding = (insets.top + insets.bottom).coerceAtLeast(1)
            val scale = minOf(1f, (view.height - px(96)).coerceAtLeast(1).toFloat() / totalPadding)
            val padding = intArrayOf(px(28), (insets.top * scale).toInt() + px(12),
                px(28), (insets.bottom * scale).toInt() + px(12))
            // The native bounds result does not retain the UI's asymmetric padding.
            // Keep it in the camera position so the route fits above the bottom card.
            sdk.getCameraForLatLngBounds(bounds, padding, 0.0, 0.0)?.let { camera ->
                val position = CameraPosition.Builder(camera).padding(
                    padding[0].toDouble(), padding[1].toDouble(), padding[2].toDouble(), padding[3].toDouble()
                ).build()
                sdk.animateCamera(CameraUpdateFactory.newCameraPosition(position), 600)
            }
            lastViewWasRoute = true
        }
        lastFitRoute = directions.fitRouteRequest
    }

    private fun publishViewport(sdk: VietMapGL) {
        if (closed || !ready || view.width <= 0 || view.height <= 0) return
        val bounds = sdk.projection.visibleRegion.latLngBounds
        val world = bounds.longitudeSpan >= 360
        val west = if (world) -180.0 else wrapLongitude(bounds.longitudeWest)
        val east = if (world) 180.0 else wrapLongitude(bounds.longitudeEast)
        val south = bounds.latitudeSouth.coerceIn(-90.0, 90.0)
        val north = bounds.latitudeNorth.coerceIn(-90.0, 90.0)
        if (west.isFinite() && east.isFinite() && west != east && south < north) {
            onCameraIdle(SignViewport(west, south, east, north, floor(sdk.cameraPosition.zoom).toInt().coerceIn(0, 22)))
        }
    }

    private fun wrapLongitude(value: Double): Double =
        if (value in -180.0..180.0) value else ((value + 180) % 360 + 360) % 360 - 180

    private fun locationIcon(): Bitmap {
        val size = px(28)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - density, paint)
        paint.color = Color.rgb(10, 95, 220)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4 * density, paint)
        bitmap.density = (density * 160).toInt()
        return bitmap
    }

    private fun destinationIcon(): Bitmap {
        val size = px(36)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - density, paint)
        paint.color = Color.rgb(220, 40, 50)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3 * density, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, 5 * density, paint)
        bitmap.density = (density * 160).toInt()
        return bitmap
    }

    private fun px(value: Int) = (value * density).toInt()
    override fun onStart(owner: LifecycleOwner) { if (!closed && !started) { view.onStart(); started = true } }
    override fun onResume(owner: LifecycleOwner) { if (!closed && !resumed) { view.onResume(); resumed = true } }
    override fun onPause(owner: LifecycleOwner) = pause()
    override fun onStop(owner: LifecycleOwner) = stop()
    override fun onDestroy(owner: LifecycleOwner) = close()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() { if (!closed) view.onLowMemory() }
    private fun pause() { if (resumed) { view.onPause(); resumed = false } }
    private fun stop() { pause(); if (started) { view.onStop(); started = false } }
    fun close() {
        if (closed) return
        stop()
        closed = true
        view.onDestroy()
        map = null
        mapStyle = null
        signSource = null
        locationSource = null
        routeSource = null
        destinationSource = null
    }

    private companion object {
        const val ROUTE_SOURCE = "signa-route-source"
        const val DESTINATION_SOURCE = "signa-destination-source"
        const val DESTINATION_ICON = "signa-destination-icon"
        const val SIGN_SOURCE = "signa-sign-source"
        const val SIGN_LAYER = "signa-sign-layer"
        const val LOCATION_SOURCE = "signa-location-source"
        const val LOCATION_ICON = "signa-location-icon"
    }
}

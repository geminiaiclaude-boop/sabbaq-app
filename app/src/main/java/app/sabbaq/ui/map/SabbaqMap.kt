package app.sabbaq.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.sabbaq.LocaleManager
import app.sabbaq.core.engine.zones.GeoBox
import app.sabbaq.service.maps.MapPackStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File

/** A filled zone on the map; colours as `#rrggbb`. */
data class MapArea(val points: List<GeoPoint>, val fill: String, val line: String, val opacity: Double = 0.28, val dashed: Boolean = false)

/**
 * The map style: the driver's offline city (a PMTiles archive, Protomaps' OpenStreetMap layers,
 * labels from fonts and icons bundled in the app) when one is stored, else OpenStreetMap's own
 * tiles online. The zone overlays sit on top of either.
 */
object MapStyles {
    const val ATTRIBUTION = "© OpenStreetMap contributors"

    fun build(context: Context, pack: File?, arabic: Boolean): String {
        val base: JsonArray
        val sources = buildJsonObject {
            if (pack != null) {
                putJsonObject("protomaps") {
                    put("type", "vector"); put("url", "pmtiles://file://${pack.absolutePath}"); put("attribution", "$ATTRIBUTION · Protomaps")
                }
            } else {
                putJsonObject("osm") {
                    put("type", "raster"); put("tileSize", 256); put("maxzoom", 19); put("attribution", ATTRIBUTION)
                    putJsonArray("tiles") { add(JsonPrimitive("https://tile.openstreetmap.org/{z}/{x}/{y}.png")) }
                }
            }
            for (id in listOf(ZONES, DRAFT, CORNERS, PINS)) putJsonObject(id) { put("type", "geojson"); put("data", EMPTY) }
        }
        base = if (pack != null) {
            val text = context.assets.open(if (arabic) "map/layers_ar.json" else "map/layers_en.json").bufferedReader().use { it.readText() }
            kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonArray
        } else buildJsonArray {
            add(buildJsonObject { put("id", "osm"); put("type", "raster"); put("source", "osm") })
        }
        val overlays = buildJsonArray {
            add(layer("zones-fill", "fill", ZONES) {
                putJsonObject("paint") { put("fill-color", get("fill")); put("fill-opacity", get("opacity")) }
            })
            add(layer("zones-line", "line", ZONES) {
                putJsonObject("paint") { put("line-color", get("line")); put("line-width", 3) }
            })
            add(layer("draft-fill", "fill", DRAFT) {
                put("filter", buildJsonArray { add(JsonPrimitive("==")); add(buildJsonArray { add(JsonPrimitive("geometry-type")) }); add(JsonPrimitive("Polygon")) })
                putJsonObject("paint") { put("fill-color", get("fill")); put("fill-opacity", 0.3) }
            })
            add(layer("draft-line", "line", DRAFT) {
                putJsonObject("paint") { put("line-color", get("line")); put("line-width", 4) }
            })
            add(layer("corners", "circle", CORNERS) {
                putJsonObject("paint") {
                    put("circle-radius", 9); put("circle-color", "#ffffff"); put("circle-stroke-width", 3); put("circle-stroke-color", get("line"))
                }
            })
            add(layer("pins", "circle", PINS) {
                putJsonObject("paint") {
                    put("circle-radius", 8); put("circle-color", get("color")); put("circle-stroke-width", 3); put("circle-stroke-color", "#ffffff")
                }
            })
        }
        return buildJsonObject {
            put("version", 8)
            put("glyphs", "asset://map/fonts/{fontstack}/{range}.pbf")
            put("sprite", "asset://map/sprites/light")
            put("sources", sources)
            put("layers", JsonArray(base + overlays))
        }.toString()
    }

    private fun get(prop: String) = buildJsonArray { add(JsonPrimitive("get")); add(JsonPrimitive(prop)) }

    private fun layer(id: String, type: String, source: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", id); put("type", type); put("source", source); extra()
    }

    const val ZONES = "sabbaq-zones"
    const val DRAFT = "sabbaq-draft"
    const val CORNERS = "sabbaq-corners"
    const val PINS = "sabbaq-pins"
    private val EMPTY = buildJsonObject { put("type", "FeatureCollection"); putJsonArray("features") { } }

    // ---- GeoJSON for the overlays ---------------------------------------------------------------

    fun areas(list: List<MapArea>): String = collection(list.filter { it.points.size >= 3 }.map { a ->
        feature(polygon(a.points)) { put("fill", a.fill); put("line", a.line); put("opacity", a.opacity) }
    })

    /** The zone being drawn: its outline (closed from three corners) and its colour. */
    fun draft(points: List<GeoPoint>, fill: String, line: String): String = collection(when {
        points.size >= 3 -> listOf(feature(polygon(points)) { put("fill", fill); put("line", line) })
        points.size == 2 -> listOf(feature(lineString(points)) { put("fill", fill); put("line", line) })
        else -> emptyList()
    })

    fun corners(points: List<GeoPoint>, line: String): String = collection(points.mapIndexed { i, p ->
        feature(point(p)) { put("line", line); put("i", i) }
    })

    fun pins(points: List<Pair<GeoPoint, String>>): String = collection(points.map { (p, color) -> feature(point(p)) { put("color", color) } })

    private fun collection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection"); put("features", JsonArray(features))
    }.toString()

    private fun feature(geometry: JsonObject, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "Feature"); put("geometry", geometry); putJsonObject("properties") { props() }
    }

    private fun coord(p: GeoPoint) = buildJsonArray { add(JsonPrimitive(p.longitude)); add(JsonPrimitive(p.latitude)) }
    private fun point(p: GeoPoint) = buildJsonObject { put("type", "Point"); put("coordinates", coord(p)) }
    private fun lineString(ps: List<GeoPoint>) = buildJsonObject { put("type", "LineString"); put("coordinates", JsonArray(ps.map(::coord))) }
    private fun polygon(ps: List<GeoPoint>) = buildJsonObject {
        put("type", "Polygon"); put("coordinates", buildJsonArray { add(JsonArray((ps + ps.first()).map(::coord))) })
    }
}

/**
 * One MapLibre map and what Sabbaq draws on it. Calls made before the map is ready are kept and
 * applied once its style has loaded. With [editable], a press on a corner drags it (a press that
 * does not move removes it) and a tap elsewhere is reported to [onTap].
 */
class SabbaqMapState(context: Context) {
    init { MapLibre.getInstance(context.applicationContext) }

    val view: MapView = MapView(context)
    private var map: MapLibreMap? = null
    private var styleReady = false
    private val pending = ArrayList<(MapLibreMap) -> Unit>()

    var editable = false
    var onTap: ((GeoPoint) -> Unit)? = null
    var onCornerMoved: ((Int, GeoPoint) -> Unit)? = null
    var onCornerRemoved: ((Int) -> Unit)? = null

    private var areas: List<MapArea> = emptyList()
    private var draft: List<GeoPoint> = emptyList()
    private var draftFill = "#1e9650"
    private var draftLine = "#148246"
    private var pins: List<Pair<GeoPoint, String>> = emptyList()
    private var loadedPack: String? = null

    init {
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.addOnMapClickListener { ll -> onTap?.invoke(GeoPoint(ll.latitude, ll.longitude)); onTap != null }
            loadStyle(context)
        }
        installDrag()
    }

    /** (Re)loads the style when the stored city archive changed (a download just finished). */
    fun loadStyle(context: Context) {
        val m = map ?: return
        val pack = MapPackStore.stored(context)?.second
        if (styleReady && pack?.absolutePath == loadedPack) return
        loadedPack = pack?.absolutePath
        styleReady = false
        m.setStyle(MapStyles.build(context, pack, LocaleManager.isArabic())) {
            styleReady = true
            pushOverlays()
            val queued = pending.toList(); pending.clear()
            queued.forEach { it(m) }
        }
    }

    private fun whenReady(action: (MapLibreMap) -> Unit) {
        val m = map
        if (m != null && styleReady) action(m) else pending += action
    }

    fun setAreas(list: List<MapArea>) { areas = list; pushOverlays() }

    fun setDraft(points: List<GeoPoint>, fill: String, line: String) { draft = points; draftFill = fill; draftLine = line; pushOverlays() }

    fun setPins(list: List<Pair<GeoPoint, String>>) { pins = list; pushOverlays() }

    private fun pushOverlays() {
        val style = map?.style ?: return
        if (!styleReady) return
        style.getSourceAs<GeoJsonSource>(MapStyles.ZONES)?.setGeoJson(MapStyles.areas(areas))
        style.getSourceAs<GeoJsonSource>(MapStyles.DRAFT)?.setGeoJson(MapStyles.draft(draft, draftFill, draftLine))
        style.getSourceAs<GeoJsonSource>(MapStyles.CORNERS)?.setGeoJson(MapStyles.corners(if (editable) draft else emptyList(), draftLine))
        style.getSourceAs<GeoJsonSource>(MapStyles.PINS)?.setGeoJson(MapStyles.pins(pins))
    }

    fun moveTo(p: GeoPoint, zoom: Double, animate: Boolean = true) = whenReady { m ->
        val u = CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), zoom)
        if (animate) m.animateCamera(u, 600) else m.moveCamera(u)
    }

    fun fit(points: List<GeoPoint>, animate: Boolean = true) {
        if (points.isEmpty()) return
        if (points.size == 1) { moveTo(points[0], 15.0, animate); return }
        whenReady { m ->
            val b = LatLngBounds.from(points.maxOf { it.latitude }, points.maxOf { it.longitude }, points.minOf { it.latitude }, points.minOf { it.longitude })
            val u = CameraUpdateFactory.newLatLngBounds(b, 80)
            if (animate) m.animateCamera(u, 600) else m.moveCamera(u)
        }
    }

    /** Keeps the camera inside [box] (the driver's city) and no further out than a city view; null frees it. */
    fun restrictTo(box: GeoBox?) = whenReady { m ->
        if (box == null) {
            m.setLatLngBoundsForCameraTarget(null)
            m.setMinZoomPreference(2.0)
        } else {
            m.setLatLngBoundsForCameraTarget(LatLngBounds.from(box.north, box.east, box.south, box.west))
            m.setMinZoomPreference(9.0)
        }
    }

    fun center(): GeoPoint? = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        val hit = 28f * view.resources.displayMetrics.density
        var index = -1
        var downX = 0f
        var downY = 0f
        var moved = false
        view.setOnTouchListener { _, e ->
            val m = map
            if (!editable || m == null || !styleReady) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    index = draft.indices.minByOrNull { i -> distance(m, draft[i], e.x, e.y) }?.takeIf { distance(m, draft[it], e.x, e.y) <= hit } ?: -1
                    downX = e.x; downY = e.y; moved = false
                    index >= 0
                }
                MotionEvent.ACTION_MOVE -> {
                    if (index < 0) return@setOnTouchListener false
                    if (!moved && (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop)) moved = true
                    if (moved) {
                        val ll = m.projection.fromScreenLocation(PointF(e.x, e.y))
                        draft = draft.toMutableList().also { it[index] = GeoPoint(ll.latitude, ll.longitude) }
                        pushOverlays()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (index < 0) return@setOnTouchListener false
                    val i = index
                    index = -1
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (moved) onCornerMoved?.invoke(i, draft[i]) else onCornerRemoved?.invoke(i)
                    }
                    true
                }
                else -> index >= 0
            }
        }
    }

    private fun distance(m: MapLibreMap, p: GeoPoint, x: Float, y: Float): Float {
        val s = m.projection.toScreenLocation(LatLng(p.latitude, p.longitude))
        return kotlin.math.hypot(s.x - x, s.y - y)
    }

    fun destroy() { view.onDestroy() }
}

@Composable
fun rememberSabbaqMap(): SabbaqMapState {
    val context = LocalContext.current
    return remember { SabbaqMapState(context) }
}

/** Shows [state]'s map, following the screen's lifecycle and destroying the map with the screen. */
@Composable
fun SabbaqMap(state: SabbaqMapState, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.view.onStart()
                Lifecycle.Event.ON_RESUME -> state.view.onResume()
                Lifecycle.Event.ON_PAUSE -> state.view.onPause()
                Lifecycle.Event.ON_STOP -> state.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) state.view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) state.view.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            state.view.onPause(); state.view.onStop(); state.destroy()
        }
    }
    AndroidView(factory = { state.view }, modifier = modifier)
}

/** The city map the driver has (or the stored city box from the names index): what the editor stays inside. */
fun MapPackStore.cityBox(context: Context): GeoBox? = stored(context)?.first?.let { GeoBox(it.south, it.west, it.north, it.east) }

package app.sabbaq.ui

import android.Manifest
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sabbaq.R
import app.sabbaq.service.zones.DriverLocationTracker
import app.sabbaq.service.zones.ZoneStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/** One zone being edited: the reference engine's `{"name","active","points":[[lat,lng],…]}`. */
internal data class EditableZone(val name: String, val active: Boolean, val points: List<GeoPoint>)

internal enum class ZoneMapMode { INCLUDE, EXCLUDE, HOME }

internal object ZoneJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<EditableZone> = runCatching {
        json.parseToJsonElement(text).jsonArray.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val pts = (o["points"] as? JsonArray)?.mapNotNull { p ->
                val a = p as? JsonArray ?: return@mapNotNull null
                val lat = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                val lng = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                if (lat == null || lng == null) null else GeoPoint(lat, lng)
            }.orEmpty()
            if (pts.size < 3) null
            else EditableZone((o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["active"] as? JsonPrimitive)?.booleanOrNull ?: true, pts)
        }
    }.getOrDefault(emptyList())

    fun write(zones: List<EditableZone>): String = JsonArray(zones.map { z ->
        JsonObject(mapOf(
            "name" to JsonPrimitive(z.name),
            "active" to JsonPrimitive(z.active),
            "points" to JsonArray(z.points.map { JsonArray(listOf(JsonPrimitive(it.latitude), JsonPrimitive(it.longitude))) }),
        ))
    }).toString()

    fun activeCount(text: String): Int = parse(text).count { it.active }
}

/** Location permission state plus a request that refreshes it and restarts tracking once granted. */
private class LocationAccess(val granted: Boolean, val request: () -> Unit)

@Composable
private fun rememberLocationAccess(): LocationAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(DriverLocationTracker.hasPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = DriverLocationTracker.hasPermission(context)
        if (granted) app.sabbaq.service.SabbaqA11yService.instance?.onConfigSaved()
    }
    return LocationAccess(granted) {
        if (!DriverLocationTracker.hasPermission(context)) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

/**
 * The reference engine's geographic conditions: destination zones (with "on the way"), exclude zones and
 * the smart destination. Each needs the driver's position; the map edits the zone polygons.
 */
@Composable
internal fun ZoneConditions(draft: FilterDraft, errors: Map<String, String>, onDraftChange: (FilterDraft) -> Unit) {
    val location = rememberLocationAccess()
    val requestLocation = location.request
    var mapMode by rememberSaveable { mutableStateOf<ZoneMapMode?>(null) }
    val needsLocation = draft.zonesEnabled || draft.excludeZonesEnabled || draft.smartDestActive
    if (needsLocation && !location.granted) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.zone_location_needed), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = requestLocation, modifier = Modifier.testTag("zone-location-allow")) { Text(stringResource(R.string.zone_location_allow)) }
            }
        }
    }

    val fixAge = ZoneStore.fixAgeMs()
    if (needsLocation && location.granted && (fixAge == null || fixAge > 30 * 60_000L)) {
        Text(
            if (fixAge == null) stringResource(R.string.zone_location_none) else stringResource(R.string.zone_location_old, (fixAge / 60_000L).toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("zone-location-age"),
        )
    }

    ProConditionCard(
        title = stringResource(R.string.zone_include_title),
        subtitle = stringResource(R.string.zone_include_sub),
        enabled = draft.zonesEnabled,
        testKey = "zones-include",
        onEnabledChange = { onDraftChange(draft.copy(zonesEnabled = it)); if (it) requestLocation() },
        footer = {
            Column {
                SwitchLine(stringResource(R.string.zone_reject_unknown), draft.zonesRejectOnUnknown, "zones-reject-unknown") {
                    onDraftChange(draft.copy(zonesRejectOnUnknown = it))
                }
                SwitchLine(stringResource(R.string.zone_on_the_way), draft.zonesOnTheWay, "zones-on-the-way") {
                    onDraftChange(draft.copy(zonesOnTheWay = it))
                }
            }
        },
    ) { _ ->
        ZoneSummary(ZoneJson.activeCount(draft.zonesJson), errors["zones"], "zones-include-map") { mapMode = ZoneMapMode.INCLUDE }
    }

    ProConditionCard(
        title = stringResource(R.string.zone_exclude_title),
        subtitle = stringResource(R.string.zone_exclude_sub),
        enabled = draft.excludeZonesEnabled,
        testKey = "zones-exclude",
        onEnabledChange = { onDraftChange(draft.copy(excludeZonesEnabled = it)); if (it) requestLocation() },
        footer = {
            SwitchLine(stringResource(R.string.zone_exclude_reject_unknown), draft.excludeZonesRejectOnUnknown, "zones-exclude-reject-unknown") {
                onDraftChange(draft.copy(excludeZonesRejectOnUnknown = it))
            }
        },
    ) { _ ->
        ZoneSummary(ZoneJson.activeCount(draft.excludeZonesJson), errors["excludeZones"], "zones-exclude-map") { mapMode = ZoneMapMode.EXCLUDE }
    }

    ProConditionCard(
        title = stringResource(R.string.zone_home_title),
        subtitle = stringResource(R.string.zone_home_sub),
        enabled = draft.smartDestActive,
        testKey = "smart-dest",
        onEnabledChange = { onDraftChange(draft.copy(smartDestActive = it)); if (it) requestLocation() },
        footer = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (draft.homeSet) stringResource(R.string.zone_home_set) else stringResource(R.string.zone_home_not_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errors["smartDest"] != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { mapMode = ZoneMapMode.HOME }, modifier = Modifier.testTag("smart-dest-map")) {
                    Text(stringResource(R.string.zone_open_map))
                }
            }
        },
    ) { dimmed ->
        CompactStepperValue(
            stringResource(R.string.zone_home_angle), draft.smartDestAngle, "°", "5", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.copy(smartDestAngle = it)) }, testKey = "smart-dest-angle",
            modifier = Modifier.weight(1f), error = errors["smartDestAngle"], dimmed = dimmed,
        )
    }

    mapMode?.let { mode ->
        ZoneMapDialog(
            mode = mode,
            zonesJson = when (mode) { ZoneMapMode.INCLUDE -> draft.zonesJson; ZoneMapMode.EXCLUDE -> draft.excludeZonesJson; ZoneMapMode.HOME -> "[]" },
            home = if (draft.homeSet) GeoPoint(draft.smartDestLat, draft.smartDestLng) else null,
            onDismiss = { mapMode = null },
            onSave = { zones, home ->
                mapMode = null
                onDraftChange(when (mode) {
                    ZoneMapMode.INCLUDE -> draft.copy(zonesJson = zones)
                    ZoneMapMode.EXCLUDE -> draft.copy(excludeZonesJson = zones)
                    ZoneMapMode.HOME -> draft.copy(smartDestLat = home?.latitude ?: 0.0, smartDestLng = home?.longitude ?: 0.0)
                })
            },
        )
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ZoneSummary(count: Int, error: String?, tag: String, onOpen: () -> Unit) {
    Column(Modifier.weight(1f)) {
        Text(
            if (count == 0) stringResource(R.string.zone_none) else stringResource(R.string.zone_count, count),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = onOpen, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.zone_open_map)) }
}

/** Full-screen map: tap to add corners, finish a zone with 3+ corners; in home mode a tap sets the point. */
@Composable
internal fun ZoneMapDialog(
    mode: ZoneMapMode,
    zonesJson: String,
    home: GeoPoint?,
    onDismiss: () -> Unit,
    onSave: (zonesJson: String, home: GeoPoint?) -> Unit,
) {
    val context = LocalContext.current
    val zones = remember { mutableStateListOf<EditableZone>().apply { addAll(ZoneJson.parse(zonesJson)) } }
    val drawing = remember { mutableStateListOf<GeoPoint>() }
    var homePoint by remember { mutableStateOf(home) }
    val zoneWord = stringResource(R.string.zone_default_name)
    val mapView = remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            val loc = ZoneStore.driverLocation()
            val center = home ?: zones.firstOrNull()?.points?.firstOrNull()
                ?: loc?.let { GeoPoint(it[0], it[1]) } ?: GeoPoint(24.7136, 46.6753)
            controller.setZoom(12.0)
            controller.setCenter(center)
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    fun redraw() {
        mapView.overlays.removeAll { it !is MapEventsOverlay }
        val fill = if (mode == ZoneMapMode.EXCLUDE) AndroidColor.argb(70, 220, 40, 40) else AndroidColor.argb(70, 30, 160, 90)
        val stroke = if (mode == ZoneMapMode.EXCLUDE) AndroidColor.rgb(200, 30, 30) else AndroidColor.rgb(20, 130, 70)
        zones.forEach { z ->
            mapView.overlays += Polygon(mapView).apply {
                points = z.points
                fillPaint.color = if (z.active) fill else AndroidColor.argb(30, 120, 120, 120)
                outlinePaint.color = stroke
                outlinePaint.strokeWidth = 4f
                title = z.name
            }
        }
        if (drawing.isNotEmpty()) {
            mapView.overlays += Polyline(mapView).apply {
                setPoints(drawing + drawing.first())
                outlinePaint.color = AndroidColor.rgb(30, 90, 200)
                outlinePaint.strokeWidth = 5f
            }
        }
        homePoint?.let { p -> mapView.overlays += Marker(mapView).apply { position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }
        mapView.invalidate()
    }

    remember {
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (mode == ZoneMapMode.HOME) homePoint = p else drawing += p
                redraw()
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        redraw()
        true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(if (mode == ZoneMapMode.HOME) R.string.zone_map_hint_home else R.string.zone_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().testTag("zone-map"))
                }
                if (mode != ZoneMapMode.HOME) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { if (drawing.isNotEmpty()) drawing.removeAt(drawing.lastIndex); redraw() },
                            enabled = drawing.isNotEmpty(), modifier = Modifier.testTag("zone-undo")) { Text(stringResource(R.string.zone_undo)) }
                        Button(onClick = {
                            zones += EditableZone("$zoneWord ${zones.size + 1}", true, drawing.toList())
                            drawing.clear(); redraw()
                        }, enabled = drawing.size >= 3, modifier = Modifier.testTag("zone-finish")) { Text(stringResource(R.string.zone_finish)) }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 8.dp)) {
                        itemsIndexed(zones) { i, z ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(z.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = z.active, onCheckedChange = { zones[i] = z.copy(active = it); redraw() })
                                IconButton(onClick = { zones.removeAt(i); redraw() }, modifier = Modifier.testTag("zone-delete-$i")) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.zone_delete))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.zone_cancel)) }
                    Button(onClick = { onSave(ZoneJson.write(zones), homePoint) }, modifier = Modifier.testTag("zone-save")) {
                        Text(stringResource(R.string.zone_save))
                    }
                }
            }
        }
    }
}

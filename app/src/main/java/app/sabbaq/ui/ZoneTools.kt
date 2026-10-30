package app.sabbaq.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sabbaq.R
import app.sabbaq.Settings
import app.sabbaq.core.engine.zones.ZoneCacheWarm
import app.sabbaq.core.engine.zones.ZoneDiagnosis
import app.sabbaq.fleet.FleetRepository
import app.sabbaq.service.zones.GeocodeWorker
import app.sabbaq.service.zones.ZoneStore
import app.sabbaq.service.zones.ZoneWarmer
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** A zone's corners as `[lat, lng]` pairs, the form the core helpers take. */
internal fun EditableZone.corners(): List<DoubleArray> = points.map { doubleArrayOf(it.latitude, it.longitude) }

/** A zone shown on the read-only map: destination zones green, avoided zones red. */
internal data class MapZone(val zone: EditableZone, val exclude: Boolean)

/** A full-screen dialog with a title bar and a close button, shared by the zone tools. */
@Composable
private fun ZoneToolDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.zp_close)) }
                }
                content()
            }
        }
    }
}

/**
 * «عرض على الخريطة»: the zones on a read-only map, framed on [focus] (or on all of them), with
 * the driver's last position marked.
 */
@Composable
internal fun ZoneMapDialog(title: String, zones: List<MapZone>, focus: EditableZone?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            for (z in zones) {
                overlays += Polygon(this).apply {
                    points = z.zone.points + z.zone.points.first()
                    val on = z.zone.active && !z.zone.pending
                    fillPaint.color = when {
                        !on -> AndroidColor.argb(40, 120, 120, 120)
                        z.exclude -> AndroidColor.argb(70, 220, 40, 40)
                        else -> AndroidColor.argb(70, 30, 160, 90)
                    }
                    outlinePaint.color = if (z.exclude) AndroidColor.rgb(200, 30, 30) else AndroidColor.rgb(20, 130, 70)
                    outlinePaint.strokeWidth = 5f
                    this.title = z.zone.name
                }
            }
            ZoneStore.driverLocation()?.let { me ->
                overlays += Marker(this).apply { position = GeoPoint(me[0], me[1]); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
            }
            val frame = (focus?.points ?: zones.flatMap { it.zone.points })
            if (frame.isNotEmpty()) {
                val box = BoundingBox.fromGeoPoints(frame)
                controller.setZoom(14.0)
                controller.setCenter(box.centerWithDateLine)
                addOnFirstLayoutListener { _, _, _, _, _ -> zoomToBoundingBox(box.increaseByScale(1.3f), false) }
            } else {
                controller.setZoom(12.0)
                controller.setCenter(ZoneStore.driverLocation()?.let { GeoPoint(it[0], it[1]) } ?: GeoPoint(24.7136, 46.6753))
            }
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }
    ZoneToolDialog(title, onDismiss) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().testTag("zone-view-map"))
        }
    }
}

/**
 * The names cached inside a zone (the reference's zone cache view, searchable) and «اقتراح كلمات
 * لهذه المنطقة»: the names the zone's keyword list lacks, to add to the destination words (a
 * destination zone) or the excluded words (an avoided zone).
 */
@Composable
internal fun ZoneNamesDialog(
    zone: EditableZone,
    exclude: Boolean,
    draft: FilterDraft,
    onDraftChange: (FilterDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val inside = remember(zone) { ZoneCacheWarm.namesInside(ZoneStore.cache(), zone.corners()) }
    val keywords = if (exclude) draft.destExcludeKeywords else draft.destKeywords
    val suggestions = remember(inside, keywords) { ZoneCacheWarm.suggestions(inside, keywords) }
    val chosen = remember(suggestions) { mutableStateListOf<String>().apply { addAll(suggestions) } }
    var query by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(false) }
    val shown = inside.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    ZoneToolDialog(stringResource(R.string.zp_names_title, zone.name.ifBlank { stringResource(R.string.zp_unnamed) }), onDismiss) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(stringResource(R.string.zp_suggest_words), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (exclude) R.string.zp_suggest_warn_exclude else R.string.zp_suggest_note_include),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exclude) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (suggestions.isEmpty()) Text(stringResource(R.string.zp_suggest_none), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            items(suggestions, key = { "s:$it" }) { word ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Checkbox(checked = word in chosen, onCheckedChange = { if (it) chosen += word else chosen -= word })
                    Text(word, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (suggestions.isNotEmpty()) item {
                Button(enabled = chosen.isNotEmpty() && !added, modifier = Modifier.fillMaxWidth().testTag("zone-suggest-add"), onClick = {
                    val joined = (keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() } + chosen).joinToString(", ")
                    onDraftChange(if (exclude) draft.copy(destExcludeKeywords = joined) else draft.copy(destKeywords = joined))
                    added = true
                }) { Text(stringResource(if (exclude) R.string.zp_suggest_add_exclude else R.string.zp_suggest_add_include)) }
                if (added) Text(stringResource(R.string.zp_suggest_added), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedTextField(value = query, onValueChange = { query = it.take(60) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.zp_names_search)) }, modifier = Modifier.fillMaxWidth().testTag("zone-names-search"))
                if (inside.isEmpty()) Text(stringResource(R.string.zp_names_none), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            items(shown, key = { "n:${it.name}" }) { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(n.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(ArabicCount.points(n.points.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * «تشخيص المناطق الجغرافية»: a typed destination, where it lies, and what the zone stage does
 * with it now (from the cache, as an offer sees it); plus warming every active zone.
 */
@Composable
internal fun ZoneDiagnosisDialog(draft: FilterDraft, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ZoneDiagnosis.Report?>(null) }
    var liveAsked by remember { mutableStateOf(false) }
    var warmNote by remember { mutableStateOf<String?>(null) }
    val config = FleetRepository.effectiveConfig(draft.validate(Settings.engineConfig).config ?: Settings.engineConfig)
    val noneText = stringResource(R.string.zp_diag_warm_none)

    fun check() {
        val dest = text.trim()
        if (dest.isEmpty() || busy) return
        busy = true
        scope.launch {
            val cache = ZoneStore.poiIndex()
            val me = ZoneStore.driverLocation()
            val before = ZoneDiagnosis.diagnose(config, dest, cache, null, me)
            liveAsked = !before.fromCache && before.where != ZoneDiagnosis.Where.NO_ZONES
            report = before
            if (liveAsked) {
                val live = GeocodeWorker.resolveNow(context, dest)
                report = ZoneDiagnosis.diagnose(config, dest, cache, live, me)
            }
            busy = false
        }
    }

    ZoneToolDialog(stringResource(R.string.zp_diag_title), onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.zp_diag_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = text, onValueChange = { text = it.take(120) }, singleLine = true,
                label = { Text(stringResource(R.string.zp_diag_dest)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { check() }),
                modifier = Modifier.fillMaxWidth().testTag("zone-diag-input"))
            SabbaqButton(stringResource(R.string.zp_diag_check), onClick = { check() }, enabled = text.isNotBlank() && !busy, busy = busy,
                busyText = stringResource(R.string.zp_diag_live), modifier = Modifier.testTag("zone-diag-check"))
            report?.let { r -> DiagnosisLines(r, liveAsked, busy) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("zone-diag-warm-all"), onClick = {
                val polygons = (ZoneJson.parse(draft.zonesJson).filter { draft.zonesEnabled && it.active } +
                    ZoneJson.parse(draft.excludeZonesJson).filter { draft.excludeZonesEnabled && it.active } +
                    if (draft.excludeZonesEnabled && !draft.globalZonesOptOut) ZoneJson.parse(config.globalDestExcludeZonesJson) else emptyList())
                    .map { it.corners() }
                if (polygons.isEmpty()) { warmNote = noneText; return@OutlinedButton }
                busy = true
                scope.launch {
                    var added = 0
                    for (p in polygons) added += ZoneWarmer.warmAsync(p).await().added
                    warmNote = context.getString(R.string.zp_diag_warm_done, added)
                    busy = false
                }
            }) { Text(stringResource(R.string.zp_diag_warm_all)) }
            warmNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DiagnosisLines(r: ZoneDiagnosis.Report, liveAsked: Boolean, busy: Boolean) {
    val lines = buildList {
        when (r.where) {
            ZoneDiagnosis.Where.NO_ZONES -> add(stringResource(R.string.zp_diag_no_zones) to false)
            ZoneDiagnosis.Where.UNPLACED -> {
                if (liveAsked && !busy) add(stringResource(R.string.zp_diag_no_live) to false)
                add(stringResource(R.string.zp_diag_unplaced) to true)
            }
            else -> {
                add(stringResource(if (r.fromCache) R.string.zp_diag_from_cache else R.string.zp_diag_from_live) to false)
                add(when (r.where) {
                    ZoneDiagnosis.Where.INSIDE_EXCLUDE -> stringResource(R.string.zp_diag_in_exclude, r.zoneName.orEmpty())
                    ZoneDiagnosis.Where.INSIDE_INCLUDE -> stringResource(R.string.zp_diag_in_include, r.zoneName.orEmpty())
                    ZoneDiagnosis.Where.OUTSIDE_INCLUDE -> stringResource(R.string.zp_diag_out_include)
                    else -> stringResource(R.string.zp_diag_clear)
                } to (r.where == ZoneDiagnosis.Where.INSIDE_EXCLUDE || r.where == ZoneDiagnosis.Where.OUTSIDE_INCLUDE))
            }
        }
        if (r.where != ZoneDiagnosis.Where.NO_ZONES) {
            add(when {
                r.engineReject != null -> stringResource(R.string.zp_diag_engine_rejects, reasonLabel(r.engineReject))
                r.engineUnplaced -> stringResource(R.string.zp_diag_engine_unplaced)
                else -> stringResource(R.string.zp_diag_engine_accepts)
            } to (r.engineReject != null))
            if (r.rejectedForNoPoint) add(stringResource(R.string.zp_diag_no_point_reason) to true)
        }
    }
    Column(Modifier.fillMaxWidth().testTag("zone-diag-report"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { (line, warn) ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * The administration's default exclude zones for the driver's country: listed by name, each
 * viewable on the map, with the driver's own switch to apply them or not.
 */
@Composable
internal fun SharedZonesCard(zonesJson: String, draft: FilterDraft, onDraftChange: (FilterDraft) -> Unit, onShowMap: (EditableZone) -> Unit) {
    val zones = remember(zonesJson) { ZoneJson.parse(zonesJson) }
    if (zones.isEmpty()) return
    SabbaqCard(title = stringResource(R.string.zp_shared_title), modifier = Modifier.testTag("zones-shared")) {
        Text(stringResource(R.string.zp_shared_body, stringResource(R.string.zone_exclude_title)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchLine(stringResource(R.string.zp_shared_apply), !draft.globalZonesOptOut, "zones-shared-apply") { on ->
            onDraftChange(draft.copy(globalZonesOptOut = !on))
        }
        zones.forEach { z ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                Text(z.name.ifBlank { stringResource(R.string.zp_unnamed) }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onShowMap(z) }) { Text(stringResource(R.string.zp_show_map)) }
            }
        }
    }
}

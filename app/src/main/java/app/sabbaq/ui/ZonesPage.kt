package app.sabbaq.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sabbaq.R
import app.sabbaq.core.engine.zones.ZoneLimit
import app.sabbaq.fleet.ZonePolicyRepository
import app.sabbaq.service.zones.GeocodeWorker
import app.sabbaq.service.zones.ZoneStore
import app.sabbaq.service.zones.ZoneWarmer
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** Destination zones are green and avoided zones red, on the map, the cards and the dashboard. */
private val IncludeColor = Color(0xFF1E9E5A)
private val ExcludeColor = Color(0xFFD03030)

/** Which list a zone lives in and its place there; null index = a new zone. */
private data class ZoneRef(val exclude: Boolean, val index: Int?)

private fun FilterDraft.zones(exclude: Boolean): List<EditableZone> = ZoneJson.parse(if (exclude) excludeZonesJson else zonesJson)

private fun FilterDraft.withZones(exclude: Boolean, zones: List<EditableZone>): FilterDraft =
    if (exclude) copy(excludeZonesJson = ZoneJson.write(zones)) else copy(zonesJson = ZoneJson.write(zones))

/** Kilometres from the driver's last saved position to the zone's centre, or null when either is unknown. */
private fun distanceKm(zone: EditableZone): Double? {
    val me = ZoneStore.driverLocation() ?: return null
    if (zone.points.isEmpty()) return null
    val lat = zone.points.sumOf { it.latitude } / zone.points.size
    val lng = zone.points.sumOf { it.longitude } / zone.points.size
    val r = Math.toRadians(1.0)
    val dLat = (lat - me[0]) * r; val dLng = (lng - me[1]) * r
    val a = Math.sin(dLat / 2).let { it * it } + Math.cos(me[0] * r) * Math.cos(lat * r) * Math.sin(dLng / 2).let { it * it }
    return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/**
 * The reference's zones list screen: the zone settings, then every zone by name in its own card
 * (green destination zones, red avoided zones) with its switch, edit and delete, and «منطقة
 * جديدة» opening the editor. Everything edits the same draft as the conditions, saved at once.
 */
@Composable
internal fun ZonesPage(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<ZoneRef?>(null) }
    var deleting by remember { mutableStateOf<ZoneRef?>(null) }
    var viewing by remember { mutableStateOf<EditableZone?>(null) }
    var naming by remember { mutableStateOf<ZoneRef?>(null) }
    var diagnosing by remember { mutableStateOf(false) }
    var warming by remember { mutableStateOf<List<GeoPoint>?>(null) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val include = draft.zones(exclude = false)
    val exclude = draft.zones(exclude = true)
    val farLimit = draft.excludeProximityKm.toDoubleOrNull() ?: 60.0
    val policy by ZonePolicyRepository.policy.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The warming takes up to a minute; it must apply to the draft as it is then, not as it was.
    val latestDraft by rememberUpdatedState(draft)
    val latestChange by rememberUpdatedState(onDraftChange)

    fun limitText(): String = context.getString(R.string.zp_limit, policy.maxActiveZones)

    /** Switches a zone on or off; on only within the administration's limit. */
    fun setActive(isExclude: Boolean, index: Int, on: Boolean) {
        val d = latestDraft
        val list = d.zones(isExclude).toMutableList()
        val zone = list.getOrNull(index) ?: return
        if (on && !ZoneLimit.canActivate(ZoneLimit.activeCount(d.zonesJson, d.excludeZonesJson), policy.maxActiveZones)) {
            notice = limitText() to true
            return
        }
        list[index] = zone.copy(active = on)
        latestChange(d.withZones(isExclude, list))
    }

    /**
     * The reference's activation: the zone waits switched off while its streets and places are
     * cached, then switches on (within the limit) and says how many names it holds.
     */
    fun warmThenActivate(isExclude: Boolean, zone: EditableZone) {
        if (warming != null) return
        warming = zone.points
        notice = context.getString(R.string.zp_warming, zone.name) to false
        scope.launch {
            val result = ZoneWarmer.warmAsync(zone.corners()).await()
            warming = null
            val d = latestDraft
            val list = d.zones(isExclude).toMutableList()
            val i = list.indexOfFirst { it.points == zone.points }
            if (i < 0) { notice = null; return@launch }
            if (!result.ok) { notice = context.getString(R.string.zp_warm_failed, zone.name) to true; return@launch }
            val rest = ZoneJson.write(list.filterIndexed { k, _ -> k != i })
            val others = if (isExclude) ZoneLimit.activeCount(d.zonesJson, rest) else ZoneLimit.activeCount(rest, d.excludeZonesJson)
            val allowed = ZoneLimit.canActivate(others, policy.maxActiveZones)
            list[i] = list[i].copy(pending = false, active = allowed)
            latestChange(d.withZones(isExclude, list))
            notice = when {
                !allowed -> limitText() to true
                result.names > 0 -> context.getString(R.string.zp_warm_done, zone.name, result.names) to false
                else -> context.getString(R.string.zp_warm_empty, zone.name) to true
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.zp_back), onClick = onBack)
            .heightIn(min = 48.dp)
            .testTag("zones-back"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Auto-mirrored: points left in English, right in Arabic (the way back in each).
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
        Text(stringResource(R.string.zp_back), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.zone_group_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.zp_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ZoneLocationNotice(draft)

    SabbaqCard(title = stringResource(R.string.zp_settings), modifier = Modifier.testTag("zones-settings")) {
        SwitchLine(stringResource(R.string.zone_include_title) + " — " + stringResource(R.string.zone_include_sub), draft.zonesEnabled, "zones-include-switch") {
            onDraftChange(draft.copy(zonesEnabled = it))
        }
        if (draft.zonesEnabled) {
            Text(stringResource(R.string.zp_unknown_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = draft.zonesRejectOnUnknown, onClick = { onDraftChange(draft.copy(zonesRejectOnUnknown = true)) },
                    label = { Text(stringResource(R.string.zp_unknown_reject)) }, modifier = Modifier.weight(1f).testTag("zones-unknown-reject"))
                FilterChip(selected = !draft.zonesRejectOnUnknown, onClick = { onDraftChange(draft.copy(zonesRejectOnUnknown = false)) },
                    label = { Text(stringResource(R.string.zp_unknown_accept)) }, modifier = Modifier.weight(1f).testTag("zones-unknown-accept"))
            }
            Text(
                stringResource(if (draft.zonesRejectOnUnknown) R.string.zp_unknown_reject_b else R.string.zp_unknown_accept_b),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchLine(stringResource(R.string.zone_on_the_way), draft.zonesOnTheWay, "zones-on-the-way") { onDraftChange(draft.copy(zonesOnTheWay = it)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SwitchLine(stringResource(R.string.zone_exclude_title) + " — " + stringResource(R.string.zone_exclude_sub), draft.excludeZonesEnabled, "zones-exclude-switch") {
            onDraftChange(draft.copy(excludeZonesEnabled = it))
        }
        if (draft.excludeZonesEnabled) {
            Text(stringResource(R.string.zp_unknown_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = draft.excludeZonesRejectOnUnknown, onClick = { onDraftChange(draft.copy(excludeZonesRejectOnUnknown = true)) },
                    label = { Text(stringResource(R.string.zp_unknown_reject)) }, modifier = Modifier.weight(1f).testTag("zones-exclude-unknown-reject"))
                FilterChip(selected = !draft.excludeZonesRejectOnUnknown, onClick = { onDraftChange(draft.copy(excludeZonesRejectOnUnknown = false)) },
                    label = { Text(stringResource(R.string.zp_unknown_accept_alert)) }, modifier = Modifier.weight(1f).testTag("zones-exclude-unknown-accept"))
            }
            Text(
                stringResource(if (draft.excludeZonesRejectOnUnknown) R.string.zone_exclude_reject_unknown else R.string.zp_unknown_accept_alert_b),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.zonesEnabled || draft.excludeZonesEnabled) ZoneAdvancedSettings(draft, errors, onDraftChange)
    }

    SharedZonesCard(policy.zonesJson, draft, onDraftChange) { viewing = it }
    notice?.let { (text, warn) ->
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("zones-notice"),
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
    if (policy.maxActiveZones > 0) {
        Text(stringResource(R.string.zp_limit_note, policy.maxActiveZones), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(IncludeColor, stringResource(R.string.zp_legend_include))
        LegendDot(ExcludeColor, stringResource(R.string.zp_legend_exclude))
    }

    ZoneSection(
        title = stringResource(R.string.zone_include_title),
        zones = include, exclude = false, sectionOn = draft.zonesEnabled, farLimitKm = farLimit, error = errors["zones"], warming = warming,
        onToggle = { i, on -> if (on && include[i].pending) warmThenActivate(false, include[i]) else setActive(false, i, on) },
        onWarm = { warmThenActivate(false, include[it]) },
        onShowMap = { viewing = include[it] },
        onNames = { naming = ZoneRef(false, it) },
        onEdit = { editing = ZoneRef(false, it) },
        onDelete = { deleting = ZoneRef(false, it) },
    )
    ZoneSection(
        title = stringResource(R.string.zone_exclude_title),
        zones = exclude, exclude = true, sectionOn = draft.excludeZonesEnabled, farLimitKm = farLimit, error = errors["excludeZones"], warming = warming,
        onToggle = { i, on -> if (on && exclude[i].pending) warmThenActivate(true, exclude[i]) else setActive(true, i, on) },
        onWarm = { warmThenActivate(true, exclude[it]) },
        onShowMap = { viewing = exclude[it] },
        onNames = { naming = ZoneRef(true, it) },
        onEdit = { editing = ZoneRef(true, it) },
        onDelete = { deleting = ZoneRef(true, it) },
    )
    if (include.isEmpty() && exclude.isEmpty()) {
        Text(stringResource(R.string.zp_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SabbaqButton(
        stringResource(R.string.zp_new_zone),
        onClick = { editing = ZoneRef(exclude = draft.excludeZonesEnabled && !draft.zonesEnabled, index = null) },
        icon = Icons.Outlined.Add,
        modifier = Modifier.testTag("zones-new"),
    )
    SabbaqButton(
        stringResource(R.string.zp_diag_open),
        onClick = { diagnosing = true },
        style = SabbaqButtonStyle.Secondary,
        modifier = Modifier.testTag("zones-diagnose"),
    )
    Text(stringResource(R.string.zone_privacy_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    editing?.let { ref ->
        val list = if (ref.exclude) exclude else include
        ZoneEditorDialog(
            initial = ref.index?.let { list.getOrNull(it) },
            initialExclude = ref.exclude,
            onDismiss = { editing = null },
            onSave = { drawn, toExclude ->
                editing = null
                val before = ref.index?.let { list.getOrNull(it) }
                // A new or redrawn zone waits switched off until its streets and places are cached.
                val redrawn = before == null || before.points != drawn.points || before.pending
                val zone = if (redrawn) drawn.copy(active = false, pending = true) else drawn
                var next = draft
                val from = list.toMutableList()
                if (ref.index != null && toExclude == ref.exclude) {
                    from[ref.index] = zone
                    next = next.withZones(ref.exclude, from)
                } else {
                    if (ref.index != null) { from.removeAt(ref.index); next = next.withZones(ref.exclude, from) }
                    next = next.withZones(toExclude, next.zones(toExclude) + zone)
                }
                // A first zone switches its list on, as drawing it is the point of opening the editor.
                if (toExclude && !next.excludeZonesEnabled && next.zones(true).size == 1) next = next.copy(excludeZonesEnabled = true)
                if (!toExclude && !next.zonesEnabled && next.zones(false).size == 1) next = next.copy(zonesEnabled = true)
                onDraftChange(next)
                if (redrawn) warmThenActivate(toExclude, zone)
            },
        )
    }
    deleting?.let { ref ->
        val list = if (ref.exclude) exclude else include
        val zone = ref.index?.let { list.getOrNull(it) }
        if (zone == null) { deleting = null; return@let }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.zone_delete)) },
            text = { Text(stringResource(R.string.zp_delete_q, zone.name.ifBlank { stringResource(R.string.zp_unnamed) })) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    var next = draft.withZones(ref.exclude, list.toMutableList().also { it.removeAt(ref.index!!) })
                    // An empty list cannot stay on (it would fail validation and block every save).
                    if (ref.exclude && next.zones(true).isEmpty()) next = next.copy(excludeZonesEnabled = false)
                    if (!ref.exclude && next.zones(false).isEmpty()) next = next.copy(zonesEnabled = false)
                    onDraftChange(next)
                }, modifier = Modifier.testTag("zones-delete-confirm")) { Text(stringResource(R.string.zone_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.zone_cancel)) } },
        )
    }
    viewing?.let { zone ->
        val shared = ZoneJson.parse(policy.zonesJson).map { MapZone(it, true) }
        ZoneMapDialog(zone.name.ifBlank { stringResource(R.string.zp_unnamed) },
            include.map { MapZone(it, false) } + exclude.map { MapZone(it, true) } + shared, zone) { viewing = null }
    }
    naming?.let { ref ->
        val zone = (if (ref.exclude) exclude else include).getOrNull(ref.index ?: -1)
        if (zone == null) { naming = null; return@let }
        ZoneNamesDialog(zone, ref.exclude, draft, onDraftChange) { naming = null }
    }
    if (diagnosing) ZoneDiagnosisDialog(draft) { diagnosing = false }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ZoneSection(
    title: String,
    zones: List<EditableZone>,
    exclude: Boolean,
    sectionOn: Boolean,
    farLimitKm: Double,
    error: String?,
    warming: List<GeoPoint>?,
    onToggle: (Int, Boolean) -> Unit,
    onWarm: (Int) -> Unit,
    onShowMap: (Int) -> Unit,
    onNames: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            Text(
                if (sectionOn) stringResource(R.string.zp_on) else stringResource(R.string.zp_off),
                style = MaterialTheme.typography.labelMedium,
                color = if (sectionOn) (if (exclude) ExcludeColor else IncludeColor) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (zones.isEmpty()) {
            Text(stringResource(R.string.zone_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        zones.forEachIndexed { i, zone ->
            ZoneCard(zone, exclude, distanceKm(zone)?.takeIf { it > farLimitKm }, warming == zone.points,
                { onToggle(i, it) }, { onWarm(i) }, { onShowMap(i) }, { onNames(i) }, { onEdit(i) }, { onDelete(i) },
                tag = (if (exclude) "zone-exclude-" else "zone-include-") + i)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneCard(
    zone: EditableZone,
    exclude: Boolean,
    farKm: Double?,
    warmingNow: Boolean,
    onToggle: (Boolean) -> Unit,
    onWarm: () -> Unit,
    onShowMap: () -> Unit,
    onNames: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    tag: String,
) {
    val accent = if (exclude) ExcludeColor else IncludeColor
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (zone.active) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (zone.active) accent else MaterialTheme.colorScheme.outline))
                Column(Modifier.weight(1f)) {
                    Text(zone.name.ifBlank { stringResource(R.string.zp_unnamed) }, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(if (exclude) R.string.zp_kind_exclude else R.string.zp_kind_include) + " · " + ArabicCount.points(zone.points.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (farKm != null) Text(stringResource(R.string.zp_far, farKm.toInt()), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    if (warmingNow) Text(stringResource(R.string.zp_warming, zone.name), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    else if (zone.pending) Text(stringResource(R.string.zp_pending), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("$tag-pending"))
                }
                Switch(checked = zone.active, onCheckedChange = onToggle, enabled = !warmingNow, modifier = Modifier.testTag("$tag-switch"))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                if (zone.pending && !warmingNow) TextButton(onClick = onWarm, modifier = Modifier.testTag("$tag-warm")) {
                    Text(stringResource(R.string.zp_warm_now))
                }
                TextButton(onClick = onShowMap, modifier = Modifier.testTag("$tag-map")) {
                    Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" " + stringResource(R.string.zp_show_map))
                }
                TextButton(onClick = onNames, modifier = Modifier.testTag("$tag-names")) { Text(stringResource(R.string.zp_suggest_words)) }
                TextButton(onClick = onEdit, modifier = Modifier.testTag("$tag-edit")) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" " + stringResource(R.string.zp_edit))
                }
                TextButton(onClick = onDelete, modifier = Modifier.testTag("$tag-delete")) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text(" " + stringResource(R.string.zone_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * «شروط القيادة» on the dashboard, under «قنص الطلبات بدون وجهة»: the reference's destination
 * zones card. Both lists' switches and every zone by name with its own switch, and the button to
 * the zones page.
 */
@Composable
internal fun DashboardZonesBlock(draft: FilterDraft, onDraftChange: (FilterDraft) -> Unit, onOpenZones: () -> Unit) {
    val include = draft.zones(exclude = false)
    val exclude = draft.zones(exclude = true)
    val maxActive = ZonePolicyRepository.policy.collectAsState().value.maxActiveZones
    var limitHit by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("quick-zones"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.zone_group_title), style = MaterialTheme.typography.titleSmall)
        SwitchLine(stringResource(R.string.zone_include_title), draft.zonesEnabled, "quick-zones-include") { on ->
            onDraftChange(draft.copy(zonesEnabled = on))
            if (on && include.isEmpty()) onOpenZones()
        }
        SwitchLine(stringResource(R.string.zone_exclude_title), draft.excludeZonesEnabled, "quick-zones-exclude") { on ->
            onDraftChange(draft.copy(excludeZonesEnabled = on))
            if (on && exclude.isEmpty()) onOpenZones()
        }
        if (include.isEmpty() && exclude.isEmpty()) {
            Text(stringResource(R.string.zone_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (include.mapIndexed { i, z -> Triple(false, i, z) } + exclude.mapIndexed { i, z -> Triple(true, i, z) }).forEach { (isExclude, i, zone) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                val on = zone.active && (if (isExclude) draft.excludeZonesEnabled else draft.zonesEnabled)
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) (if (isExclude) ExcludeColor else IncludeColor) else MaterialTheme.colorScheme.outline))
                Text(zone.name.ifBlank { stringResource(R.string.zp_unnamed) }, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Switch(checked = zone.active, onCheckedChange = { active ->
                    when {
                        active && zone.pending -> onOpenZones()
                        active && !ZoneLimit.canActivate(ZoneLimit.activeCount(draft.zonesJson, draft.excludeZonesJson), maxActive) -> limitHit = true
                        else -> {
                            limitHit = false
                            val list = (if (isExclude) exclude else include).toMutableList().also { it[i] = it[i].copy(active = active) }
                            onDraftChange(draft.withZones(isExclude, list))
                        }
                    }
                }, modifier = Modifier.testTag((if (isExclude) "quick-zone-exclude-" else "quick-zone-include-") + i))
            }
        }
        if (limitHit) Text(stringResource(R.string.zp_limit, maxActive), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onOpenZones, modifier = Modifier.testTag("quick-zones-open")) {
            Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(" " + stringResource(R.string.zp_my_zones))
        }
    }
}

/**
 * The reference's zone editor: the zone's name (or one suggested from the map), its kind, a place
 * search, and the map. A tap adds a corner, a corner can be dragged, and a tap on a corner
 * removes it; undo, clear and «موقعي» sit above the map. Saved with at least three corners.
 */
@Composable
internal fun ZoneEditorDialog(
    initial: EditableZone?,
    initialExclude: Boolean,
    onDismiss: () -> Unit,
    onSave: (EditableZone, exclude: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var exclude by remember { mutableStateOf(initialExclude) }
    val points = remember { mutableStateListOf<GeoPoint>().apply { initial?.points?.let(::addAll) } }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, DoubleArray>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val nameNeeded = stringResource(R.string.zp_name_needed)
    val pointsNeeded = stringResource(R.string.zp_points_needed)
    val notFound = stringResource(R.string.zp_search_none)

    val mapView = remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            val loc = ZoneStore.driverLocation()
            val center = initial?.points?.let { p -> GeoPoint(p.sumOf { it.latitude } / p.size, p.sumOf { it.longitude } / p.size) }
                ?: loc?.let { GeoPoint(it[0], it[1]) } ?: GeoPoint(24.7136, 46.6753)
            controller.setZoom(if (initial != null) 14.0 else 13.0)
            controller.setCenter(center)
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    fun redraw() {
        mapView.overlays.removeAll { it !is MapEventsOverlay }
        val stroke = if (exclude) AndroidColor.rgb(200, 30, 30) else AndroidColor.rgb(20, 130, 70)
        if (points.size >= 2) {
            mapView.overlays += Polygon(mapView).apply {
                this.points = points.toList() + points.first()
                fillPaint.color = if (exclude) AndroidColor.argb(70, 220, 40, 40) else AndroidColor.argb(70, 30, 160, 90)
                outlinePaint.color = stroke
                outlinePaint.strokeWidth = 5f
            }
        }
        points.forEachIndexed { i, p ->
            mapView.overlays += Marker(mapView).apply {
                position = p
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) = Unit
                    override fun onMarkerDragStart(marker: Marker) = Unit
                    override fun onMarkerDragEnd(marker: Marker) { if (i < points.size) points[i] = marker.position; redraw() }
                })
                setOnMarkerClickListener { _, _ -> if (i < points.size) points.removeAt(i); redraw(); true }
            }
        }
        mapView.invalidate()
    }

    remember {
        mapView.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { points += p; error = null; redraw(); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        redraw()
        true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (initial == null) R.string.zp_new_zone else R.string.zp_edit_zone),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.zone_cancel)) }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(60); error = null },
                        label = { Text(stringResource(R.string.zp_name)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("zone-name"),
                        trailingIcon = {
                            TextButton(enabled = points.isNotEmpty(), onClick = {
                                val c = GeoPoint(points.sumOf { it.latitude } / points.size, points.sumOf { it.longitude } / points.size)
                                scope.launch { GeocodeWorker.placeName(context, c.latitude, c.longitude)?.let { name = it.take(60) } }
                            }) { Text(stringResource(R.string.zp_suggest_name)) }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !exclude, onClick = { exclude = false; redraw() }, label = { Text(stringResource(R.string.zp_kind_include)) },
                            modifier = Modifier.weight(1f).testTag("zone-kind-include"))
                        FilterChip(selected = exclude, onClick = { exclude = true; redraw() }, label = { Text(stringResource(R.string.zp_kind_exclude)) },
                            modifier = Modifier.weight(1f).testTag("zone-kind-exclude"))
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it.take(80) },
                        placeholder = { Text(stringResource(R.string.zp_search)) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (query.isBlank() || searching) return@KeyboardActions
                            searching = true
                            scope.launch {
                                results = GeocodeWorker.searchPlaces(context, query.trim())
                                searching = false
                                if (results.isEmpty()) error = notFound
                            }
                        }),
                        modifier = Modifier.fillMaxWidth().testTag("zone-search"),
                    )
                    results.take(5).forEach { (label, p) ->
                        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable {
                                mapView.controller.setZoom(15.0); mapView.controller.animateTo(GeoPoint(p[0], p[1])); results = emptyList()
                            }.padding(vertical = 6.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.zp_tap_hint, points.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { if (points.isNotEmpty()) points.removeAt(points.lastIndex); redraw() }, enabled = points.isNotEmpty(),
                        modifier = Modifier.testTag("zone-undo")) { Text(stringResource(R.string.zone_undo)) }
                    TextButton(onClick = { points.clear(); redraw() }, enabled = points.isNotEmpty(),
                        modifier = Modifier.testTag("zone-clear")) { Text(stringResource(R.string.zp_clear)) }
                    IconButton(onClick = {
                        ZoneStore.driverLocation()?.let { mapView.controller.animateTo(GeoPoint(it[0], it[1])) }
                    }) { Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.zp_my_location)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().testTag("zone-map"))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.zone_cancel)) }
                    Button(onClick = {
                        when {
                            name.isBlank() -> error = nameNeeded
                            points.size < 3 -> error = pointsNeeded
                            else -> onSave(EditableZone(name.trim(), initial?.active ?: true, points.toList()), exclude)
                        }
                    }, modifier = Modifier.testTag("zone-save")) { Text(stringResource(R.string.zp_save_zone)) }
                }
            }
        }
    }
}

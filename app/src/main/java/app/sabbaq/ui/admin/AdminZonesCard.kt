package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.admin.AdminZones
import app.sabbaq.ui.EditableZone
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.ZoneEditorDialog
import app.sabbaq.ui.ZoneJson
import kotlinx.coroutines.launch

/**
 * «استثناءات الوجهة الافتراضية»: the exclude zones every driver of a country gets by default (each
 * driver may switch them off for themselves), whether they apply to that country now, and the zone
 * limits — how many own zones a driver may have on, and how far a shared zone still applies.
 */
@Composable
internal fun AdminZonesCard(session: AdminSession) {
    val ar = LocaleManager.isArabic()
    val scope = rememberCoroutineScope()
    var policy by remember { mutableStateOf<AdminZones.Policy?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var country by remember { mutableStateOf(AdminZones.COUNTRIES.first()) }
    var enabled by remember { mutableStateOf(false) }
    var zones by remember { mutableStateOf<List<EditableZone>>(emptyList()) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    var maxZones by remember { mutableStateOf("") }
    var proximity by remember { mutableStateOf("") }

    fun show(p: AdminZones.Policy) {
        policy = p
        val c = p.countries[country] ?: AdminZones.Country()
        enabled = c.enabled; zones = ZoneJson.parse(c.zonesJson)
        maxZones = p.defaultMax.toString(); proximity = p.proximityKm.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    }

    LaunchedEffect(reload, country) {
        busy = true
        AdminZones.policy().fold({ show(it); message = null }, {
            message = session.failureOf(it, if (ar) "تعذر تحميل مناطق الاستثناء." else "Could not load the exclude zones.").text
        })
        busy = false
    }

    SabbaqCard(title = if (ar) "استثناءات الوجهة الافتراضية" else "Default destination exclusions", icon = Icons.Outlined.Place,
        modifier = Modifier.testTag("admin-zones")) {
        Text(
            if (ar) "هذه الوجهات تُطبَّق تلقائيًا على كل سائقي الدولة المختارة كاستثناء افتراضي (يستطيع السائق إيقافها لنفسه). تُنفَّذ بأمان: لا تُرفض الرحلة إلا إذا تأكّد أن وجهتها داخل المنطقة."
            else "These destinations apply automatically to every driver of the chosen country as a default exclusion (a driver can switch them off for themselves). They act safely: a ride is rejected only when its destination is known to be inside the zone.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminZones.COUNTRIES.forEach { c ->
                FilterChip(selected = country == c, onClick = { country = c }, enabled = !busy,
                    label = { Text(if (c == "SA") (if (ar) "السعودية" else "Saudi Arabia") else (if (ar) "الأردن" else "Jordan")) },
                    modifier = Modifier.testTag("admin-zones-country-$c"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ar) "تطبيقها على هذه الدولة" else "Apply them to this country", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = policy != null && !busy, modifier = Modifier.testTag("admin-zones-enabled"))
        }
        Text(
            if (enabled) (if (ar) "تُطبَّق المناطق أدناه على هذه الدولة." else "The zones below apply to this country.")
            else (if (ar) "لا يُطبَّق أي استثناء على هذه الدولة (المناطق محفوظة)." else "No exclusion applies to this country (the zones are kept)."),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (zones.isEmpty()) Text(if (ar) "لا توجد مناطق بعد" else "No zones yet", style = MaterialTheme.typography.bodySmall)
        zones.forEachIndexed { i, z ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(z.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = i }, enabled = !busy) { Text(if (ar) "تعديل" else "Edit") }
                TextButton(onClick = { deleting = i }, enabled = !busy) { Text(if (ar) "حذف" else "Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
        SabbaqButton(if (ar) "منطقة جديدة" else "New zone", onClick = { adding = true }, style = SabbaqButtonStyle.Secondary,
            enabled = policy != null && !busy && zones.size < 50, modifier = Modifier.testTag("admin-zones-new"))
        SabbaqButton(if (ar) "تحديث استثناءات الوجهة الافتراضية" else "Update the default destination exclusions",
            enabled = policy != null && !busy, busy = busy, busyText = if (ar) "جارٍ الحفظ…" else "Saving…",
            modifier = Modifier.testTag("admin-zones-save"), onClick = {
                scope.launch {
                    busy = true
                    AdminZones.saveCountry(country, enabled, ZoneJson.write(zones)).fold({
                        show(it)
                        message = if (ar) "حُفظت المناطق (الإصدار ${it.version}). يتلقاها السائقون مع إشعار عند التحديث التالي." else "Zones saved (version ${it.version}). Drivers get them, with a notification, on their next refresh."
                    }, { message = session.failureOf(it, if (ar) "لم نتأكد من الحفظ؛ أعد التحميل." else "We could not confirm the save; reload.").text })
                    busy = false
                }
            })

        Text(if (ar) "حدود المناطق" else "Zone limits", style = MaterialTheme.typography.titleSmall)
        AdminField(if (ar) "حدّ المناطق المفعّلة للمستخدمين (0 = بلا حد)" else "Zones a driver may have on (0 = no limit)", maxZones,
            { maxZones = it.filter(Char::isDigit).take(3) }, enabled = policy != null && !busy)
        AdminField(if (ar) "يتجاهل المحرك المناطق الافتراضية الأبعد من (كم، 0 = كلها)" else "The engine ignores default zones farther than (km, 0 = all)", proximity,
            { proximity = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, enabled = policy != null && !busy)
        SabbaqButton(if (ar) "حفظ حدود المناطق" else "Save zone limits", enabled = policy != null && !busy, style = SabbaqButtonStyle.Secondary,
            modifier = Modifier.testTag("admin-zones-limits-save"), onClick = {
                val m = maxZones.toIntOrNull()
                val km = proximity.toDoubleOrNull()
                if (m == null || m !in 0..100 || km == null || km !in 0.0..1000.0) {
                    message = if (ar) "تحقق من الأرقام: الحد من 0 إلى 100، والمسافة من 0 إلى 1000." else "Check the numbers: the limit is 0-100 and the distance 0-1000."
                    return@SabbaqButton
                }
                scope.launch {
                    busy = true
                    AdminZones.saveLimits(m, km).fold({ show(it); message = if (ar) "حُفظت حدود المناطق." else "Zone limits saved." }, {
                        message = session.failureOf(it, if (ar) "لم نتأكد من الحفظ؛ أعد التحميل." else "We could not confirm the save; reload.").text
                    })
                    busy = false
                }
            })
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        SabbaqButton(if (ar) "إعادة التحميل" else "Reload", onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !busy)
    }

    if (adding || editing != null) {
        val index = editing
        ZoneEditorDialog(
            initial = index?.let { zones.getOrNull(it) },
            initialExclude = true,
            onDismiss = { adding = false; editing = null },
            onSave = { zone, _ ->
                zones = if (index == null) zones + zone.copy(active = true, pending = false)
                    else zones.toMutableList().also { it[index] = zone.copy(pending = false) }
                adding = false; editing = null
            },
        )
    }
    deleting?.let { i ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (ar) "حذف المنطقة الافتراضية" else "Delete the default zone") },
            text = { Text(zones.getOrNull(i)?.name.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { zones = zones.filterIndexed { k, _ -> k != i }; deleting = null }) {
                    Text(if (ar) "حذف" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(if (ar) "إلغاء" else "Cancel") } },
        )
    }
}

/** One driver's own zone limit; empty returns them to the default. */
@Composable
internal fun AdminDriverZoneLimitDialog(session: AdminSession, driverId: String, driverName: String, onDismiss: () -> Unit) {
    val ar = LocaleManager.isArabic()
    val scope = rememberCoroutineScope()
    var limit by remember { mutableStateOf<AdminZones.DriverLimit?>(null) }
    var value by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(driverId) {
        AdminZones.driverLimit(driverId).fold({ limit = it; value = it.own?.toString().orEmpty() }, {
            message = session.failureOf(it, if (ar) "تعذر تحميل حد المناطق." else "Could not load the zone limit.").text
        })
        busy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ar) "حدّ مناطق المستخدم: $driverName" else "Driver zone limit: $driverName") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                limit?.let {
                    Text(if (ar) "الحد الافتراضي: ${it.default} (0 = بلا حد)" else "Default limit: ${it.default} (0 = no limit)", style = MaterialTheme.typography.bodySmall)
                }
                AdminField(if (ar) "حد هذا السائق (فارغ = الافتراضي)" else "This driver's limit (empty = default)", value,
                    { value = it.filter(Char::isDigit).take(3) }, enabled = !busy)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && limit != null, modifier = Modifier.testTag("admin-driver-zone-limit-save"), onClick = {
                val n = value.toIntOrNull()
                if (value.isNotEmpty() && (n == null || n !in 0..100)) {
                    message = if (ar) "الحد من 0 إلى 100." else "The limit is 0-100."
                    return@TextButton
                }
                scope.launch {
                    busy = true
                    AdminZones.setDriverLimit(driverId, n).fold({ onDismiss() }, {
                        message = session.failureOf(it, if (ar) "لم نتأكد من الحفظ." else "We could not confirm the save.").text
                    })
                    busy = false
                }
            }) { Text(if (ar) "حفظ" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (ar) "إلغاء" else "Cancel") } },
    )
}

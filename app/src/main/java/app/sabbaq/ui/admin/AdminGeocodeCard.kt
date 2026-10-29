package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.admin.AdminRepository
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import kotlinx.coroutines.launch

/**
 * «تحديد مواقع العناوين»: switch the server's Google geocoding on or off, set the API key
 * (write-only: only "a key is set" comes back) and the monthly and per-driver daily caps, and
 * read this month's calls and the shared cache size.
 */
@Composable
internal fun AdminGeocodeCard(session: AdminSession) {
    val ar = LocaleManager.isArabic()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AdminRepository.GeocodeSettings?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var monthly by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf("") }

    fun show(s: AdminRepository.GeocodeSettings) {
        state = s; enabled = s.enabled; monthly = s.monthlyCap.toString(); daily = s.driverDailyCap.toString(); key = ""
    }

    LaunchedEffect(reload) {
        busy = true
        AdminRepository.geocodeSettings().fold({ show(it); message = null }, {
            message = session.failureOf(it, if (ar) "تعذر تحميل إعدادات تحديد المواقع." else "Could not load the geocoding settings.").text
        })
        busy = false
    }

    SabbaqCard(title = if (ar) "تحديد مواقع العناوين (Google)" else "Address geocoding (Google)", icon = Icons.Outlined.Place,
        modifier = Modifier.testTag("admin-geocode")) {
        Text(
            if (ar) "يحدد خادم سبّاق مواقع عناوين الطلبات للمناطق مرة واحدة لكل عنوان ويشاركها بين السائقين. عند الإيقاف أو بلوغ الحد يستخدم التطبيق OpenStreetMap."
            else "Sabbaq's server places order addresses for zones once per address and shares them with every driver. When off or capped, the app uses OpenStreetMap.",
            style = MaterialTheme.typography.bodySmall,
        )
        state?.let { s ->
            Text(
                if (ar) "هذا الشهر: ${s.monthCalls} طلبًا إلى Google · ${s.cachedAddresses} عنوانًا محفوظًا · المفتاح ${if (s.hasKey) "محفوظ" else "غير مضبوط"}"
                else "This month: ${s.monthCalls} Google requests · ${s.cachedAddresses} cached addresses · key ${if (s.hasKey) "set" else "not set"}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ar) "تشغيل Google" else "Use Google", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = state != null && !busy,
                modifier = Modifier.testTag("admin-geocode-enabled"))
        }
        AdminField(if (ar) "مفتاح Google Geocoding (يُكتب فقط)" else "Google Geocoding key (write-only)", key, { key = it.trim() },
            hint = if (ar) "اتركه فارغًا للإبقاء على المفتاح الحالي" else "Leave empty to keep the current key", enabled = state != null && !busy)
        AdminField(if (ar) "الحد الشهري للطلبات" else "Monthly request cap", monthly, { monthly = it.filter(Char::isDigit) }, enabled = state != null && !busy)
        AdminField(if (ar) "الحد اليومي لكل سائق" else "Daily cap per driver", daily, { daily = it.filter(Char::isDigit) }, enabled = state != null && !busy)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        SabbaqButton(if (ar) "حفظ إعدادات تحديد المواقع" else "Save geocoding settings", enabled = state != null && !busy, busy = busy,
            busyText = if (ar) "جارٍ الحفظ…" else "Saving…", modifier = Modifier.testTag("admin-geocode-save"), onClick = {
                val m = monthly.toIntOrNull()
                val d = daily.toIntOrNull()
                if (m == null || d == null || (key.isNotEmpty() && !AdminRepository.GEOCODE_KEY.matches(key))) {
                    message = if (ar) "تحقق من المفتاح والأرقام." else "Check the key and the numbers."
                    return@SabbaqButton
                }
                scope.launch {
                    busy = true
                    AdminRepository.saveGeocodeSettings(enabled, key.ifEmpty { null }, m, d).fold({ show(it); message = null }, {
                        message = session.failureOf(it, if (ar) "لم نتأكد من الحفظ؛ أعد التحميل." else "We could not confirm the save; reload.").text
                    })
                    busy = false
                }
            })
        SabbaqButton(if (ar) "إعادة التحميل" else "Reload", onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !busy)
    }
}

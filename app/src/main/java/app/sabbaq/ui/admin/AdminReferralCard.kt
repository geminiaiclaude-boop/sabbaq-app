package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
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

/** «الإحالات والمكافآت»: on/off, reward days, claim window, totals and the top referrers. */
@Composable
internal fun AdminReferralCard(session: AdminSession) {
    val ar = LocaleManager.isArabic()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AdminRepository.ReferralSettings?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(true) }
    var days by remember { mutableStateOf("") }
    var window by remember { mutableStateOf("") }

    fun show(s: AdminRepository.ReferralSettings) { state = s; enabled = s.enabled; days = s.rewardDays.toString(); window = s.claimWindowDays.toString() }

    LaunchedEffect(reload) {
        busy = true
        AdminRepository.referralSettings().fold({ show(it); message = null }, {
            message = session.failureOf(it, if (ar) "تعذر تحميل إعدادات الإحالات." else "Could not load the referral settings.").text
        })
        busy = false
    }

    SabbaqCard(title = if (ar) "الإحالات والمكافآت" else "Referrals and rewards", icon = Icons.Outlined.CardGiftcard,
        modifier = Modifier.testTag("admin-referrals")) {
        state?.let { s ->
            Text(if (ar) "الإحالات: ${s.claims} · مكافآت صُرفت: ${s.rewarded} · رُفضت كاحتيال: ${s.rejected}"
                 else "Referrals: ${s.claims} · rewards paid: ${s.rewarded} · rejected as fraud: ${s.rejected}",
                style = MaterialTheme.typography.labelMedium)
            s.top.take(10).forEach { t ->
                Text("${t.name} (${t.code}): ${t.rewarded}/${t.invited}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ar) "استقبال إحالات جديدة" else "Accept new referrals", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = state != null && !busy, modifier = Modifier.testTag("admin-ref-enabled"))
        }
        AdminField(if (ar) "أيام المكافأة لكل إحالة مدفوعة (1–90)" else "Reward days per paid referral (1–90)", days, { days = it.filter(Char::isDigit) }, enabled = state != null && !busy)
        AdminField(if (ar) "مهلة إدخال الرمز بعد التسجيل بالأيام (1–30)" else "Days after sign-up to enter a code (1–30)", window, { window = it.filter(Char::isDigit) }, enabled = state != null && !busy)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        SabbaqButton(if (ar) "حفظ إعدادات الإحالات" else "Save referral settings", enabled = state != null && !busy, busy = busy,
            modifier = Modifier.testTag("admin-ref-save"), onClick = {
                val d = days.toIntOrNull()
                val w = window.toIntOrNull()
                if (d == null || d !in 1..90 || w == null || w !in 1..30) {
                    message = if (ar) "أيام المكافأة بين 1 و90، والمهلة بين 1 و30." else "Reward days 1–90, window 1–30."
                    return@SabbaqButton
                }
                scope.launch {
                    busy = true
                    AdminRepository.saveReferralSettings(enabled, d, w).fold({ show(it); message = null }, {
                        message = session.failureOf(it, if (ar) "لم نتأكد من الحفظ؛ أعد التحميل." else "We could not confirm the save; reload.").text
                    })
                    busy = false
                }
            })
        SabbaqButton(if (ar) "إعادة التحميل" else "Reload", onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !busy)
    }
}

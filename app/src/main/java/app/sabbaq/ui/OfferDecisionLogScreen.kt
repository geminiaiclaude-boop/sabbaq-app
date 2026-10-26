package app.sabbaq.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sabbaq.stats.DecisionLogOutcome
import app.sabbaq.stats.OfferDecisionLogEntry
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.ui.theme.sabbaqEntrance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «سجل القرارات»: the statistics first (today, week, month and top rejection reasons), then
 * the latest decisions as one list. Same page structure as «الإعدادات»: a back row, a heading
 * and cards, so every secondary page reads alike.
 */
@Composable
fun OfferDecisionLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(OfferDecisionLogStore.snapshot()) }
    var askClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<SabbaqStatus?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = "رجوع", role = Role.Button, onClick = onBack)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // RTL: «back» points to the right.
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            Text("رجوع", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text("سجل القرارات", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            "نشاط سبّاق على هذا الجهاز: ما رآه من طلبات، وما قبله أو رفضه ولماذا.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OfferStatsCard(Modifier.sabbaqEntrance(0))

        SabbaqCard(
            title = "آخر القرارات",
            subtitle = "آخر 7 أيام. لا يتضمن نص الطلب أو العناوين أو الأجرة، ولا يُرفع إلى الخادم.",
            icon = Icons.Outlined.History,
            modifier = Modifier.sabbaqEntrance(1),
        ) {
            if (rows.isEmpty()) {
                Text("لا توجد قرارات مسجلة بعد.", style = MaterialTheme.typography.titleSmall)
                Text(
                    "إن كانت الطلبات تصلك في تطبيق النقل ولا تظهر هنا، فسبّاق لا يراها: افتح «الإعدادات ← التشخيص والدعم» لمعرفة السبب وإرسال معرّف الدعم.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rows.take(MAX_VISIBLE_ROWS).forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DecisionRow(row)
                }
                if (rows.size > MAX_VISIBLE_ROWS) {
                    Text(
                        "يُعرض آخر $MAX_VISIBLE_ROWS قرارًا من ${rows.size}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SabbaqButton(
                    text = "مسح السجل",
                    onClick = { askClear = true },
                    style = SabbaqButtonStyle.Text,
                    icon = Icons.Outlined.DeleteOutline,
                )
            }
            SabbaqAnimatedMessage(message?.text, message?.tone ?: SabbaqTone.Info)
        }
    }

    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("مسح سجل القرارات؟") },
        text = { Text("سيُحذف السجل المحلي نهائيًا من هذا الجهاز. لن تتغير شروط الطلبات أو الإحصاءات.") },
        confirmButton = {
            TextButton(onClick = {
                askClear = false
                message = if (OfferDecisionLogStore.clear(context)) {
                    rows = emptyList()
                    SabbaqStatus("تم مسح سجل القرارات من هذا الجهاز.", SabbaqTone.Success)
                } else {
                    SabbaqStatus("تعذر مسح السجل؛ أعد المحاولة أو احذف بيانات التطبيق من إعدادات الجهاز.", SabbaqTone.Error)
                }
            }) { Text("مسح") }
        },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("إلغاء") } },
    )
}

private const val MAX_VISIBLE_ROWS = 60

private data class SabbaqStatus(val text: String, val tone: SabbaqTone)

private fun platformName(profileId: String): String = when (profileId) {
    "jeeny" -> "جيني"
    "bolt" -> "بولت"
    "petra" -> "بترا"
    else -> profileId
}

private val rowTime = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale("ar"))

/** One decision as a compact list row: outcome icon, plain outcome, platform and time, reason. */
@Composable
private fun DecisionRow(entry: OfferDecisionLogEntry) {
    val time = remember(entry.timestampEpochMs) {
        rowTime.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.timestampEpochMs))
    }
    val colors = MaterialTheme.colorScheme
    val (outcome, icon, tint) = when (entry.outcome) {
        DecisionLogOutcome.REJECTED -> Triple("رُفض وفق شروطك", Icons.Outlined.DoNotDisturbOn, colors.onSurfaceVariant)
        DecisionLogOutcome.MATCHED -> Triple("مطابق لشروطك", Icons.Outlined.CheckCircle, colors.primary)
        DecisionLogOutcome.SEMANTIC_CLICK_SENT,
        DecisionLogOutcome.GESTURE_CLICK_SENT -> Triple("ضغط سبّاق على «قبول»؛ بانتظار التأكيد", Icons.Outlined.TouchApp, colors.primary)
        DecisionLogOutcome.ACCEPTED_CONFIRMED -> Triple("تم قبول الرحلة", Icons.Outlined.CheckCircle, colors.primary)
        DecisionLogOutcome.CONFIRMATION_TIMEOUT -> Triple("لم يتأكد القبول (ربما أخذه سائق آخر)", Icons.Outlined.HourglassTop, colors.error)
    }
    DecisionRowContent(outcome, icon, tint, "${platformName(entry.profileId)} · $time",
        entry.reasons.takeIf { it.isNotEmpty() }?.joinToString("، ") { reasonArabic(it).removeSuffix(".") })
}

@Composable
private fun DecisionRowContent(
    outcome: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    meta: String,
    reasons: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(outcome, style = MaterialTheme.typography.titleSmall)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            reasons?.let {
                Text("السبب: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

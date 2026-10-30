package app.sabbaq.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.sabbaq.LocaleManager
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import app.sabbaq.service.RuntimeStatusRepository
import app.sabbaq.stats.DecisionLogOutcome
import app.sabbaq.stats.OfferDecisionLogEntry
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.ui.theme.sabbaqEntrance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «النشاط والتشخيص»: the monitoring check, then the statistics (today, week, month and top rejection reasons), then
 * the latest decisions as one list. Same page structure as «الإعدادات»: a back row, a heading
 * and cards, so every secondary page reads alike.
 */
@Composable
internal fun OfferDecisionLogScreen(onBack: () -> Unit, onDiagnosisAction: (DiagnosisAction) -> Unit = {}) {
    val context = LocalContext.current
    val liveRows by OfferDecisionLogStore.rows.collectAsState()
    var rows by remember { mutableStateOf(OfferDecisionLogStore.snapshot()) }
    LaunchedEffect(liveRows) { rows = OfferDecisionLogStore.snapshot() }
    var askClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<SabbaqStatus?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = stringResource(R.string.odl_back), role = Role.Button, onClick = onBack)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Auto-mirrored: points left in English, right in Arabic (the way back in each).
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
            Text(stringResource(R.string.odl_back), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.nav_history), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.odl_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val runtime by RuntimeStatusRepository.snapshot.collectAsState()
        DiagnosisCard(onAction = onDiagnosisAction, detailed = true)
        RuntimeStatusCard(runtime, Modifier.sabbaqEntrance(0))

        OfferStatsCard(Modifier.sabbaqEntrance(1))

        SabbaqCard(
            title = stringResource(R.string.odl_latest),
            subtitle = stringResource(R.string.odl_latest_sub),
            icon = Icons.Outlined.History,
            modifier = Modifier.sabbaqEntrance(2),
        ) {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.odl_empty), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.odl_empty_hint),
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
                        stringResource(R.string.odl_showing, MAX_VISIBLE_ROWS, rows.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SabbaqButton(
                    text = stringResource(R.string.odl_clear),
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
        title = { Text(stringResource(R.string.odl_clear_q)) },
        text = { Text(stringResource(R.string.odl_clear_body)) },
        confirmButton = {
            TextButton(onClick = {
                askClear = false
                message = if (OfferDecisionLogStore.clear(context)) {
                    rows = emptyList()
                    SabbaqStatus((if (LocaleManager.isArabic()) "تم مسح سجل القرارات من هذا الجهاز." else "The decision log was cleared from this device."), SabbaqTone.Success)
                } else {
                    SabbaqStatus((if (LocaleManager.isArabic()) "تعذر مسح السجل؛ أعد المحاولة أو احذف بيانات التطبيق من إعدادات الجهاز." else "Could not clear the log; try again or delete the app data from the device settings."), SabbaqTone.Error)
                }
            }) { Text(stringResource(R.string.odl_clear_confirm)) }
        },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text(stringResource(R.string.ss_cancel)) } },
    )
}

private const val MAX_VISIBLE_ROWS = 60

private data class SabbaqStatus(val text: String, val tone: SabbaqTone)

private fun platformName(profileId: String): String = if (LocaleManager.isArabic()) when (profileId) {
    "jeeny" -> "جيني"
    "bolt" -> "بولت"
    "petra" -> "بترا"
    else -> profileId
} else when (profileId) {
    "jeeny" -> "Jeeny"
    "bolt" -> "Bolt"
    "petra" -> "Petra"
    else -> profileId
}

private fun rowTime() = DateTimeFormatter.ofPattern("d MMM · HH:mm", sabbaqDateLocale())

/** One decision as a compact list row: outcome icon, plain outcome, platform and time, reason. */
@Composable
private fun DecisionRow(entry: OfferDecisionLogEntry) {
    val time = remember(entry.timestampEpochMs) {
        rowTime().withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.timestampEpochMs))
    }
    val colors = MaterialTheme.colorScheme
    val (outcome, icon, tint) = when (entry.outcome) {
        DecisionLogOutcome.REJECTED -> Triple(stringResource(R.string.odl_out_rejected), Icons.Outlined.DoNotDisturbOn, colors.onSurfaceVariant)
        DecisionLogOutcome.MATCHED -> Triple(stringResource(R.string.odl_out_matched), Icons.Outlined.CheckCircle, colors.primary)
        DecisionLogOutcome.SEMANTIC_CLICK_SENT,
        DecisionLogOutcome.GESTURE_CLICK_SENT -> Triple(stringResource(R.string.odl_out_click_sent), Icons.Outlined.TouchApp, colors.primary)
        DecisionLogOutcome.ACCEPTED_CONFIRMED -> Triple(stringResource(R.string.odl_out_accepted), Icons.Outlined.CheckCircle, colors.primary)
        DecisionLogOutcome.CONFIRMATION_TIMEOUT -> Triple(stringResource(R.string.odl_out_timeout), Icons.Outlined.HourglassTop, colors.error)
    }
    DecisionRowContent(outcome, icon, tint, "${platformName(entry.profileId)} · $time",
        entry.reasons.takeIf { it.isNotEmpty() }?.joinToString(if (LocaleManager.isArabic()) "، " else ", ") { reasonLabel(it).removeSuffix(".") })
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
                Text(stringResource(R.string.odl_reason, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * The four quick conditions on «حالة العمل» in their compact form: a small title, then one
 * row with the inputs and the switch. Inputs are always shown (dimmed while the condition is
 * off) so the stored value is visible before switching it on. Same draft and toggle rules as
 * the full page.
 */
@Composable
internal fun QuickConditionsPanel(draft: FilterDraft, errors: Map<String, String>, onDraftChange: (FilterDraft) -> Unit) {
    var showOverrideHelp by rememberSaveable { mutableStateOf(false) }
    val hasExcludedWords = draft.excludeKeywords.split(',', '،', '\n', '\r').any { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickRow("نطاق السعر", draft.fareRangeEnabled, "quick-fare-range", onToggle = { onDraftChange(draft.withFareRange(it)) }) { dimmed ->
            CompactStepperValue("أدنى سعر", draft.minFare, "", "1", KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(minFare = it)) }, testKey = "quick-min-fare",
                modifier = Modifier.weight(1f), error = errors["minFare"], dimmed = dimmed)
            CompactStepperValue("أعلى سعر", draft.maxFare, "", "1", KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(maxFare = it)) }, testKey = "quick-max-fare",
                modifier = Modifier.weight(1f), error = errors["maxFare"], dimmed = dimmed)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        QuickRow("وقت الوصول", draft.pickupTimeEnabled, "quick-pickup-time", onToggle = { onDraftChange(draft.withPickupTime(it)) }) { dimmed ->
            CompactStepperValue("أقل وقت", draft.minPickupMinutes, "د", "5", KeyboardType.Number,
                onValueChange = { onDraftChange(draft.copy(minPickupMinutes = it)) }, testKey = "quick-min-pickup-minutes",
                modifier = Modifier.weight(1f), error = errors["minPickupMinutes"], dimmed = dimmed)
            CompactStepperValue("أقصى وقت", draft.maxPickupMinutes, "د", "5", KeyboardType.Number,
                onValueChange = { onDraftChange(draft.copy(maxPickupMinutes = it)) }, testKey = "quick-max-pickup-minutes",
                modifier = Modifier.weight(1f), error = errors["maxPickupMinutes"], dimmed = dimmed)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        QuickRow(
            "تجاوز الوقت عند سعر", draft.highFareOverrideEnabled, "quick-high-fare-override",
            onToggle = { onDraftChange(draft.withHighFareOverride(it)) },
            titleAction = {
                IconButton(onClick = { showOverrideHelp = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = "شرح تجاوز الوقت عند سعر", modifier = Modifier.size(16.dp))
                }
            },
        ) { dimmed ->
            CompactStepperValue("يبدأ من سعر", draft.highFareOverrideAmount, "", "1", KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(highFareOverrideAmount = it)) }, testKey = "quick-high-fare-amount",
                modifier = Modifier.weight(1f), error = errors["highFareOverrideAmount"], dimmed = dimmed)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        QuickRow(
            "قنص الطلبات بدون وجهة", draft.acceptNoDestination, "quick-no-destination",
            switchEnabled = !hasExcludedWords,
            onToggle = { onDraftChange(draft.copy(acceptNoDestination = it)) },
        ) {
            Text(
                if (hasExcludedWords) "موقوف بسبب الكلمات المستثناة" else "اقبل العروض التي لا تعرض الوجهة",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasExcludedWords) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
        }
    }
    if (showOverrideHelp) HighFareOverrideHelp { showOverrideHelp = false }
}

@Composable
private fun QuickRow(
    title: String,
    enabled: Boolean,
    testKey: String,
    onToggle: (Boolean) -> Unit,
    switchEnabled: Boolean = true,
    titleAction: (@Composable () -> Unit)? = null,
    inputs: @Composable androidx.compose.foundation.layout.RowScope.(dimmed: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            titleAction?.invoke()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            inputs(!enabled)
            Switch(
                checked = enabled,
                enabled = switchEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .testTag("filter-switch-$testKey")
                    .semantics {
                        contentDescription = "تفعيل $title"
                        stateDescription = if (enabled) "مفعّل" else "غير مفعّل"
                    },
            )
        }
    }
}

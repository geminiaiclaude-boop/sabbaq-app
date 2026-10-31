package app.sabbaq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sabbaq.R
import app.sabbaq.core.engine.PaymentMode
import app.sabbaq.core.engine.TargetApp
import app.sabbaq.ui.theme.LocalReducedMotion
import app.sabbaq.ui.theme.SabbaqMotion

/**
 * «طلبات تناسبك»: the reference engine's conditions (engine notes) in Sabbaq's own condition cards.
 * An empty limit is "no limit", so with nothing set every offer is accepted, as in the reference engine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedFiltersEditor(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
    modifier: Modifier = Modifier,
    onOpenZones: () -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Grouped by the question the driver asks: which app, how much and how far, where to, how
        // paid, then what only experienced drivers touch.
        // The ride app is one card that names itself; a group heading over it would only repeat it.
        TargetAppCondition(draft, onDraftChange)

        ConditionGroupHeader(stringResource(R.string.afe_group_fare_time))
        FareRangeCondition(draft, errors, onDraftChange)
        PickupTimeCondition(draft, errors, onDraftChange)
        HighFareOverrideCondition(draft, errors, onDraftChange)

        ConditionGroupHeader(stringResource(R.string.afe_group_destination))
        DestinationCondition(draft, onDraftChange)
        NoDestinationCondition(draft, onDraftChange)
        FilterGroup(
            title = stringResource(R.string.zone_group_title),
            subtitle = stringResource(R.string.zone_group_sub),
            hasError = errors.keys.any { it == "zones" || it == "excludeZones" || it.startsWith("smartDest") },
            filledCount = listOf(draft.zonesEnabled, draft.excludeZonesEnabled, draft.smartDestActive).count { it },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ZoneConditions(draft, errors, onDraftChange, onOpenZones)
            }
        }

        ConditionGroupHeader(stringResource(R.string.afe_group_payment))
        PaymentCondition(draft, onDraftChange)
        if (draft.targetApp == TargetApp.BOLT || draft.targetApp == TargetApp.BOTH) BoltOnlyConditions(draft, errors, onDraftChange)

        ConditionGroupHeader(stringResource(R.string.afe_group_advanced))
        FilterGroup(
            title = stringResource(R.string.afe_combo_title),
            subtitle = stringResource(R.string.afe_combo_sub),
            filledCount = 1,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = !draft.logicOr,
                    onClick = { onDraftChange(draft.copy(logicOr = false)) },
                    label = { Text(stringResource(R.string.afe_combo_all), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp).testTag("logic-and"),
                )
                FilterChip(
                    selected = draft.logicOr,
                    onClick = { onDraftChange(draft.copy(logicOr = true)) },
                    label = { Text(stringResource(R.string.afe_combo_any), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp).testTag("logic-or"),
                )
            }
        }
        SmartDelayCondition(draft, errors, onDraftChange)
        TurboCondition(draft, errors, onDraftChange)
    }
}

/** A group's name on «طلبات تناسبك»: smaller than the page title, in the brand colour. */
@Composable
private fun ConditionGroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() },
    )
}

/**
 * The quick conditions, shared by «طلبات تناسبك» (framed cards) and the «شروط القيادة» card on
 * «حالة العمل» (unframed rows, [keyPrefix] "quick-"): one implementation for both.
 */
@Composable
internal fun FareRangeCondition(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
    keyPrefix: String = "",
    framed: Boolean = true,
) {
    ProConditionCard(
        title = stringResource(R.string.afe_fare_range),
        subtitle = if (framed) stringResource(R.string.afe_fare_range_sub) else null,
        enabled = draft.priceEnabled,
        testKey = keyPrefix + "fare-range",
        framed = framed,
        onEnabledChange = { onDraftChange(draft.copy(priceEnabled = it)) },
    ) { dimmed ->
        CompactStepperValue(
            stringResource(R.string.afe_min_fare), draft.minPrice, "", "1", KeyboardType.Decimal,
            onValueChange = { onDraftChange(draft.withPriceLimits(min = it)) }, testKey = keyPrefix + "min-fare",
            modifier = Modifier.weight(1f), error = errors["minPrice"], dimmed = dimmed,
        )
        CompactStepperValue(
            stringResource(R.string.afe_max_fare), draft.maxPrice, "", "1", KeyboardType.Decimal,
            onValueChange = { onDraftChange(draft.withPriceLimits(max = it)) }, testKey = keyPrefix + "max-fare",
            modifier = Modifier.weight(1f), error = errors["maxPrice"], dimmed = dimmed,
        )
    }
}

@Composable
internal fun PickupTimeCondition(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
    keyPrefix: String = "",
    framed: Boolean = true,
) {
    ProConditionCard(
        title = stringResource(R.string.afe_pickup_time_title),
        subtitle = if (framed) stringResource(R.string.afe_pickup_time_sub) else null,
        enabled = draft.timeEnabled,
        testKey = keyPrefix + "pickup-time",
        framed = framed,
        onEnabledChange = { onDraftChange(draft.copy(timeEnabled = it)) },
    ) { dimmed ->
        CompactStepperValue(
            stringResource(R.string.afe_min_time), draft.minTime, stringResource(R.string.ss_unit_min), "1", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.withTimeLimits(min = it)) }, testKey = keyPrefix + "min-pickup-minutes",
            modifier = Modifier.weight(1f), error = errors["minTime"], dimmed = dimmed,
        )
        CompactStepperValue(
            stringResource(R.string.afe_max_time), draft.maxTime, stringResource(R.string.ss_unit_min), "1", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.withTimeLimits(max = it)) }, testKey = keyPrefix + "max-pickup-minutes",
            modifier = Modifier.weight(1f), error = errors["maxTime"], dimmed = dimmed,
        )
    }
}

@Composable
internal fun HighFareOverrideCondition(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
    keyPrefix: String = "",
    framed: Boolean = true,
) {
    var showHelp by rememberSaveable(keyPrefix) { mutableStateOf(false) }
    ProConditionCard(
        title = stringResource(R.string.afe_highfare_title),
        subtitle = if (framed) stringResource(R.string.afe_highfare_sub) else null,
        enabled = draft.bypassTimeEnabled,
        testKey = keyPrefix + "high-fare-override",
        framed = framed,
        headerAction = {
            IconButton(onClick = { showHelp = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.afe_highfare_help_cd), modifier = Modifier.size(16.dp))
            }
        },
        onEnabledChange = { enabled ->
            onDraftChange(draft.copy(bypassTimeEnabled = enabled, bypassTimePrice = if (enabled) draft.bypassTimePrice.ifBlank { "35" } else draft.bypassTimePrice))
        },
    ) { dimmed ->
        CompactStepperValue(
            stringResource(R.string.afe_highfare_from), draft.bypassTimePrice, "", "1", KeyboardType.Decimal,
            onValueChange = { onDraftChange(draft.copy(bypassTimePrice = it)) }, testKey = keyPrefix + "high-fare-amount",
            modifier = Modifier.weight(1f), error = errors["bypassTimePrice"], dimmed = dimmed,
        )
    }
    if (showHelp) HighFareOverrideHelp { showHelp = false }
}

@Composable
internal fun HighFareOverrideHelp(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.afe_highfare_title)) },
        text = { Text(stringResource(R.string.afe_highfare_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.afe_understood)) } },
    )
}

/** Destination words: only orders going there (include) or never there (exclude), as in the reference engine. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DestinationCondition(draft: FilterDraft, onDraftChange: (FilterDraft) -> Unit) {
    val include = !draft.excludeMode
    val on = if (include) draft.destCheckEnabled else draft.destExcludeEnabled
    ProConditionCard(
        title = stringResource(R.string.afe_dest_title),
        subtitle = stringResource(R.string.afe_dest_sub),
        enabled = on,
        testKey = "destination-words",
        onEnabledChange = { enabled ->
            onDraftChange(if (include) draft.copy(destCheckEnabled = enabled) else draft.copy(destExcludeEnabled = enabled))
        },
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = include,
                        onClick = { onDraftChange(draft.copy(excludeMode = false)) },
                        label = { Text(stringResource(R.string.afe_dest_mode_include), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp).testTag("destination-include"),
                    )
                    FilterChip(
                        selected = !include,
                        onClick = { onDraftChange(draft.copy(excludeMode = true)) },
                        label = { Text(stringResource(R.string.afe_dest_mode_exclude), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp).testTag("destination-exclude"),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.afe_dest_reject_unknown), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = if (include) draft.destIncludeRejectOnUnknown else draft.destExcludeRejectOnUnknown,
                        onCheckedChange = {
                            onDraftChange(if (include) draft.copy(destIncludeRejectOnUnknown = it) else draft.copy(destExcludeRejectOnUnknown = it))
                        },
                        modifier = Modifier.testTag("destination-reject-unknown"),
                    )
                }
            }
        },
    ) { dimmed ->
        DraftField(
            value = if (include) draft.destKeywords else draft.destExcludeKeywords,
            label = stringResource(if (include) R.string.afe_dest_include_label else R.string.afe_dest_exclude_label),
            error = null,
            keyboardType = KeyboardType.Text,
            singleLine = false,
            modifier = Modifier.weight(1f).testTag("destination-keywords"),
            enabled = !dimmed,
        ) { onDraftChange(if (include) draft.copy(destKeywords = it) else draft.copy(destExcludeKeywords = it)) }
    }
}

@Composable
internal fun NoDestinationCondition(
    draft: FilterDraft,
    onDraftChange: (FilterDraft) -> Unit,
    keyPrefix: String = "",
    framed: Boolean = true,
) {
    ProConditionCard(
        title = stringResource(R.string.afe_no_dest_title),
        subtitle = null,
        enabled = draft.snipingNoDest,
        testKey = keyPrefix + "no-destination",
        framed = framed,
        onEnabledChange = { onDraftChange(draft.copy(snipingNoDest = it)) },
    ) {
        Text(
            stringResource(R.string.afe_no_dest_on),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
    }
}

/**
 * The reference engine `targetApp`: which ride app Sabbaq watches (Jeeny by default). Framed on
 * «طلبات تناسبك»; unframed at the top of «شروط القيادة» on the dashboard, editing the same draft.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TargetAppCondition(
    draft: FilterDraft,
    onDraftChange: (FilterDraft) -> Unit,
    keyPrefix: String = "",
    framed: Boolean = true,
) {
    val chips: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                TargetApp.JEENY to R.string.afe_target_jeeny, TargetApp.BOLT to R.string.afe_target_bolt,
                TargetApp.PETRA to R.string.afe_target_petra, TargetApp.BOTH to R.string.afe_target_all,
            ).forEach { (app, label) ->
                FilterChip(
                    selected = draft.targetApp == app,
                    onClick = { onDraftChange(draft.copy(targetApp = app)) },
                    label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp).testTag(keyPrefix + "target-" + app.name.lowercase()),
                )
            }
        }
    }
    if (framed) {
        FilterGroup(
            title = stringResource(R.string.afe_target_title),
            subtitle = stringResource(R.string.afe_target_sub),
            filledCount = 1,
        ) { chips() }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.afe_target_title), style = MaterialTheme.typography.titleSmall)
            chips()
        }
    }
}

/** Rating and wait-&-save: the reference engine reads them on Bolt only. */
@Composable
internal fun BoltOnlyConditions(draft: FilterDraft, errors: Map<String, String>, onDraftChange: (FilterDraft) -> Unit) {
    ProConditionCard(
        title = stringResource(R.string.afe_rating_title),
        subtitle = stringResource(R.string.afe_rating_sub),
        enabled = draft.ratingEnabled,
        testKey = "rating",
        onEnabledChange = { enabled ->
            onDraftChange(draft.copy(ratingEnabled = enabled, minRating = if (enabled) draft.minRating.ifBlank { "4.5" } else draft.minRating))
        },
        footer = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.afe_rating_reject_unknown), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = draft.ratingRejectOnUnknown, onCheckedChange = { onDraftChange(draft.copy(ratingRejectOnUnknown = it)) },
                    modifier = Modifier.testTag("rating-reject-unknown"))
            }
        },
    ) { dimmed ->
        CompactStepperValue(
            stringResource(R.string.afe_rating_min), draft.minRating, "★", "0.1", KeyboardType.Decimal,
            onValueChange = { onDraftChange(draft.copy(minRating = it)) }, testKey = "min-rating",
            modifier = Modifier.weight(1f), error = errors["minRating"], dimmed = dimmed,
        )
    }
    ProConditionCard(
        title = stringResource(R.string.afe_waitsave_title),
        subtitle = null,
        enabled = draft.excludeWaitSave,
        testKey = "wait-save",
        onEnabledChange = { onDraftChange(draft.copy(excludeWaitSave = it)) },
    ) {
        Text(stringResource(R.string.afe_waitsave_sub), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

/**
 * The reference engine turbo: tap the saved point the instant an offer word appears, before reading it.
 * It fires only while no condition is switched on; with conditions, the point is used when the
 * accept control cannot be read. The point comes from the last confirmed tap or is typed.
 */
@Composable
internal fun TurboCondition(draft: FilterDraft, errors: Map<String, String>, onDraftChange: (FilterDraft) -> Unit) {
    ProConditionCard(
        title = stringResource(R.string.afe_turbo_title),
        subtitle = stringResource(R.string.afe_turbo_sub),
        enabled = draft.turboEnabled,
        testKey = "turbo",
        onEnabledChange = { onDraftChange(draft.copy(turboEnabled = it)) },
        footer = {
            val x = app.sabbaq.Settings.lastConfirmedTapX
            val y = app.sabbaq.Settings.lastConfirmedTapY
            TextButton(
                onClick = { onDraftChange(draft.copy(clickX = x.toString(), clickY = y.toString())) },
                enabled = x > 0 && y > 0,
                modifier = Modifier.testTag("turbo-use-last"),
            ) { Text(stringResource(R.string.afe_turbo_use_last)) }
        },
    ) { dimmed ->
        CompactStepperValue(
            "X", draft.clickX, "px", "10", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.copy(clickX = it)) }, testKey = "turbo-x",
            modifier = Modifier.weight(1f), error = errors["clickX"], dimmed = dimmed,
        )
        CompactStepperValue(
            "Y", draft.clickY, "px", "10", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.copy(clickY = it)) }, testKey = "turbo-y",
            modifier = Modifier.weight(1f), error = errors["clickY"], dimmed = dimmed,
        )
    }
}

/** The reference engine's payment filter: all, cash only or card only, with its own reject-on-unknown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaymentCondition(draft: FilterDraft, onDraftChange: (FilterDraft) -> Unit) {
    ProConditionCard(
        title = stringResource(R.string.afe_payment_title),
        subtitle = stringResource(R.string.afe_payment_sub),
        enabled = draft.paymentMode != PaymentMode.ALL,
        testKey = "payment",
        onEnabledChange = { onDraftChange(draft.copy(paymentMode = if (it) PaymentMode.CASH else PaymentMode.ALL)) },
        footer = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.afe_payment_reject_unknown), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.paymentRejectOnUnknown,
                    onCheckedChange = { onDraftChange(draft.copy(paymentRejectOnUnknown = it)) },
                    modifier = Modifier.testTag("payment-reject-unknown"),
                )
            }
        },
    ) { dimmed ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            FilterChip(
                selected = draft.paymentMode == PaymentMode.CASH,
                enabled = !dimmed,
                onClick = { onDraftChange(draft.copy(paymentMode = PaymentMode.CASH)) },
                label = { Text(stringResource(R.string.afe_payment_cash), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp).testTag("payment-cash"),
            )
            FilterChip(
                selected = draft.paymentMode == PaymentMode.CARD,
                enabled = !dimmed,
                onClick = { onDraftChange(draft.copy(paymentMode = PaymentMode.CARD)) },
                label = { Text(stringResource(R.string.afe_payment_card), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(32.dp).testTag("payment-card"),
            )
        }
    }
}

/** The reference engine's smart delay: wait a random time inside the range before tapping. */
@Composable
internal fun SmartDelayCondition(draft: FilterDraft, errors: Map<String, String>, onDraftChange: (FilterDraft) -> Unit) {
    ProConditionCard(
        title = stringResource(R.string.afe_delay_title),
        subtitle = stringResource(R.string.afe_delay_sub),
        enabled = draft.smartDelayEnabled,
        testKey = "smart-delay",
        onEnabledChange = { enabled ->
            onDraftChange(draft.copy(
                smartDelayEnabled = enabled,
                smartDelayMin = if (enabled) draft.smartDelayMin.ifBlank { "200" } else draft.smartDelayMin,
                smartDelayMax = if (enabled) draft.smartDelayMax.ifBlank { "800" } else draft.smartDelayMax,
            ))
        },
    ) { dimmed ->
        CompactStepperValue(
            stringResource(R.string.afe_delay_min), draft.smartDelayMin, stringResource(R.string.afe_unit_ms), "100", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.copy(smartDelayMin = it)) }, testKey = "smart-delay-min",
            modifier = Modifier.weight(1f), error = errors["smartDelayMin"], dimmed = dimmed,
        )
        CompactStepperValue(
            stringResource(R.string.afe_delay_max), draft.smartDelayMax, stringResource(R.string.afe_unit_ms), "100", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.copy(smartDelayMax = it)) }, testKey = "smart-delay-max",
            modifier = Modifier.weight(1f), error = errors["smartDelayMax"], dimmed = dimmed,
        )
    }
}

@Composable
private fun FilterGroup(
    title: String,
    subtitle: String,
    hasError: Boolean = false,
    filledCount: Int = 0,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    LaunchedEffect(hasError) { if (hasError) expanded = true }
    val reduced = LocalReducedMotion.current
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) snap() else tween(SabbaqMotion.MEDIUM, easing = SabbaqMotion.Emphasized),
        label = "filter-group-chevron",
    )
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(
                        if (expanded || filledCount == 0) subtitle else stringResource(R.string.afe_group_selected, ArabicCount.conditions(filledCount)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.afe_hide_options) else stringResource(R.string.afe_show_options),
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevron },
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun DraftField(
    value: String,
    label: String,
    error: String?,
    keyboardType: KeyboardType,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(1_000)) },
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

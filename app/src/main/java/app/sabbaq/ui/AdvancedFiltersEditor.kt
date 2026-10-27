package app.sabbaq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sabbaq.core.PerKmBasis
import app.sabbaq.core.FilterCombination
import app.sabbaq.core.Text as CoreText
import app.sabbaq.ui.theme.LocalReducedMotion
import app.sabbaq.ui.theme.SabbaqMotion
import java.math.BigDecimal

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedFiltersEditor(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
    modifier: Modifier = Modifier,
) {

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SabbaqSectionTitle(
            title = "شروط الطلبات",
            subtitle = "تُحفظ التعديلات تلقائيًا. أطفئ الشرط لإيقافه مع الاحتفاظ بقيمته.",
        )

        FilterGroup(
            title = "منطق جمع الشروط",
            subtitle = "في AND يجب تحقق كل شروط الاختيار؛ في OR يكفي تحقق شرط واحد. شروط الاستبعاد والسلامة تبقى إلزامية.",
            filledCount = 1,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = draft.combination == FilterCombination.ALL,
                    onClick = { onDraftChange(draft.copy(combination = FilterCombination.ALL)) },
                    label = { Text("كل الشروط (AND)", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp),
                )
                FilterChip(
                    selected = draft.combination == FilterCombination.ANY,
                    onClick = { onDraftChange(draft.copy(combination = FilterCombination.ANY)) },
                    label = { Text("أي شرط (OR)", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(32.dp),
                )
            }
        }

        FareRangeCondition(draft, errors, onDraftChange)

        ProConditionCard(
            title = "الحد الأدنى للعائد لكل كم",
            subtitle = "استبعد الأجرة الضعيفة مقارنة بالمسافة",
            enabled = draft.farePerKmEnabled,
            testKey = "fare-per-km",
            onEnabledChange = { enabled ->
                onDraftChange(draft.copy(
                    farePerKmEnabled = enabled,
                    minFarePerKm = if (enabled) draft.minFarePerKm.ifBlank { "0.5" } else draft.minFarePerKm,
                ))
            },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("احتسبه على", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(
                        selected = draft.perKmBasis == PerKmBasis.TRIP,
                        onClick = { onDraftChange(draft.copy(perKmBasis = PerKmBasis.TRIP)) },
                        label = { Text("مسافة الرحلة", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp),
                    )
                    FilterChip(
                        selected = draft.perKmBasis == PerKmBasis.TOTAL,
                        onClick = { onDraftChange(draft.copy(perKmBasis = PerKmBasis.TOTAL)) },
                        label = { Text("الوصول + الرحلة", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(32.dp),
                    )
                }
            },
        ) { dimmed ->
            CompactStepperValue(
                "أدنى عائد", draft.minFarePerKm, "/كم", "0.1", KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(minFarePerKm = it)) }, testKey = "min-fare-per-km",
                modifier = Modifier.weight(1f), error = errors["minFarePerKm"], dimmed = dimmed,
            )
        }

        ProConditionCard(
            title = "مسافة الوصول",
            subtitle = "أقصى مسافة بينك وبين الراكب",
            enabled = draft.pickupDistanceEnabled,
            testKey = "pickup-distance",
            onEnabledChange = { enabled ->
                onDraftChange(draft.copy(
                    pickupDistanceEnabled = enabled,
                    maxPickupKm = if (enabled) draft.maxPickupKm.ifBlank { "10" } else draft.maxPickupKm,
                ))
            },
        ) { dimmed ->
            CompactStepperValue(
                "أقصى وصول", draft.maxPickupKm, "كم", "0.5", KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(maxPickupKm = it)) }, testKey = "max-pickup-km",
                modifier = Modifier.weight(1f), error = errors["maxPickupKm"], dimmed = dimmed,
            )
        }

        PickupTimeCondition(draft, errors, onDraftChange)

        ProConditionCard(
            title = "مسافة الرحلة",
            subtitle = "الحدان بعد استلام الراكب",
            enabled = draft.tripDistanceEnabled,
            testKey = "trip-distance",
            onEnabledChange = { enabled ->
                val lower = if (enabled) draft.minTripKm.ifBlank { "0" } else draft.minTripKm
                onDraftChange(draft.copy(
                    tripDistanceEnabled = enabled,
                    minTripKm = lower,
                    maxTripKm = if (enabled) upperDefault(lower, draft.maxTripKm, "100") else draft.maxTripKm,
                ))
            },
        ) { dimmed ->
            CompactStepperValue(
                "أقصر رحلة", draft.minTripKm, "كم", "0.5", KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(minTripKm = it)) }, testKey = "min-trip-km",
                modifier = Modifier.weight(1f), error = errors["minTripKm"], dimmed = dimmed,
            )
            CompactStepperValue(
                "أطول رحلة", draft.maxTripKm, "كم", "0.5", KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(maxTripKm = it)) }, testKey = "max-trip-km",
                modifier = Modifier.weight(1f), error = errors["maxTripKm"], dimmed = dimmed,
            )
        }

        ProConditionCard(
            title = "مدة الرحلة",
            subtitle = "المدة المتوقعة بعد استلام الراكب",
            enabled = draft.tripTimeEnabled,
            testKey = "trip-time",
            onEnabledChange = { enabled ->
                val lower = if (enabled) draft.minTripMinutes.ifBlank { "0" } else draft.minTripMinutes
                onDraftChange(draft.copy(
                    tripTimeEnabled = enabled,
                    minTripMinutes = lower,
                    maxTripMinutes = if (enabled) upperDefault(lower, draft.maxTripMinutes, "240") else draft.maxTripMinutes,
                ))
            },
        ) { dimmed ->
            CompactStepperValue(
                "أقل مدة", draft.minTripMinutes, "د", "5", KeyboardType.Number,
                onValueChange = { onDraftChange(draft.copy(minTripMinutes = it)) }, testKey = "min-trip-minutes",
                modifier = Modifier.weight(1f), error = errors["minTripMinutes"], dimmed = dimmed,
            )
            CompactStepperValue(
                "أقصى مدة", draft.maxTripMinutes, "د", "5", KeyboardType.Number,
                onValueChange = { onDraftChange(draft.copy(maxTripMinutes = it)) }, testKey = "max-trip-minutes",
                modifier = Modifier.weight(1f), error = errors["maxTripMinutes"], dimmed = dimmed,
            )
        }

        HighFareOverrideCondition(draft, errors, onDraftChange)

        NoDestinationCondition(draft, onDraftChange)

        FilterGroup(
            title = "الكلمات المستثناة",
            subtitle = "ارفض الطلب إذا ظهرت إحدى هذه الكلمات في تفاصيله؛ افصل بينها بفاصلة.",
            hasError = errors.containsKey("excludeKeywords"),
            filledCount = draft.excludeKeywords.split(',', '،', '\n', '\r').count { it.isNotBlank() },
        ) {
            DraftField(
                value = draft.excludeKeywords,
                label = "كلمات لا تناسبك",
                error = errors["excludeKeywords"],
                keyboardType = KeyboardType.Text,
                singleLine = false,
                onValueChange = { onDraftChange(draft.copy(excludeKeywords = it)) },
            )
        }

        ProConditionCard(
            title = "أوقات العمل",
            subtitle = "يمكن أن يمتد بعد منتصف الليل",
            enabled = draft.scheduleEnabled,
            testKey = "schedule",
            onEnabledChange = { onDraftChange(draft.copy(scheduleEnabled = it)) },
        ) {
            DraftField(
                value = draft.scheduleStart,
                label = "البداية 08:30",
                error = errors["scheduleStart"],
                keyboardType = KeyboardType.Ascii,
                modifier = Modifier.weight(1f),
                onValueChange = { onDraftChange(draft.copy(scheduleStart = it)) },
            )
            DraftField(
                value = draft.scheduleEnd,
                label = "النهاية 22:00",
                error = errors["scheduleEnd"],
                keyboardType = KeyboardType.Ascii,
                modifier = Modifier.weight(1f),
                onValueChange = { onDraftChange(draft.copy(scheduleEnd = it)) },
            )
        }
    }
}

/*
 * The four most-used conditions, shared by «طلبات تناسبك» (framed cards) and the «شروط القيادة»
 * card on «حالة العمل» (unframed rows, [keyPrefix] "quick-"): one implementation, so both places
 * edit the same draft with the same defaults, validation and auto-save.
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
        title = "نطاق السعر",
        subtitle = if (framed) "أدنى وأعلى أجرة للطلب" else null,
        enabled = draft.fareRangeEnabled,
        testKey = keyPrefix + "fare-range",
        framed = framed,
        onEnabledChange = { onDraftChange(draft.withFareRange(it)) },
    ) { dimmed ->
        CompactStepperValue(
            "أدنى سعر", draft.minFare, "", "1", KeyboardType.Decimal,
            onValueChange = { onDraftChange(draft.copy(minFare = it)) }, testKey = keyPrefix + "min-fare",
            modifier = Modifier.weight(1f), error = errors["minFare"], dimmed = dimmed,
        )
        CompactStepperValue(
            "أعلى سعر", draft.maxFare, "", "1", KeyboardType.Decimal,
            onValueChange = { onDraftChange(draft.copy(maxFare = it)) }, testKey = keyPrefix + "max-fare",
            modifier = Modifier.weight(1f), error = errors["maxFare"], dimmed = dimmed,
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
        title = "وقت الوصول",
        subtitle = if (framed) "أقل وأقصى وقت للوصول إلى الراكب" else null,
        enabled = draft.pickupTimeEnabled,
        testKey = keyPrefix + "pickup-time",
        framed = framed,
        onEnabledChange = { onDraftChange(draft.withPickupTime(it)) },
    ) { dimmed ->
        CompactStepperValue(
            "أقل وقت", draft.minPickupMinutes, "د", "5", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.copy(minPickupMinutes = it)) }, testKey = keyPrefix + "min-pickup-minutes",
            modifier = Modifier.weight(1f), error = errors["minPickupMinutes"], dimmed = dimmed,
        )
        CompactStepperValue(
            "أقصى وقت", draft.maxPickupMinutes, "د", "5", KeyboardType.Number,
            onValueChange = { onDraftChange(draft.copy(maxPickupMinutes = it)) }, testKey = keyPrefix + "max-pickup-minutes",
            modifier = Modifier.weight(1f), error = errors["maxPickupMinutes"], dimmed = dimmed,
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
        title = "تجاوز الوقت عند سعر",
        subtitle = if (framed) "وصول أطول عند الأجرة المجزية" else null,
        enabled = draft.highFareOverrideEnabled,
        testKey = keyPrefix + "high-fare-override",
        framed = framed,
        headerAction = {
            IconButton(onClick = { showHelp = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "شرح تجاوز الوقت عند سعر", modifier = Modifier.size(16.dp))
            }
        },
        onEnabledChange = { onDraftChange(draft.withHighFareOverride(it)) },
    ) { dimmed ->
        CompactStepperValue(
            "يبدأ من سعر", draft.highFareOverrideAmount, "", "1", KeyboardType.Decimal,
            onValueChange = { onDraftChange(draft.copy(highFareOverrideAmount = it)) }, testKey = keyPrefix + "high-fare-amount",
            modifier = Modifier.weight(1f), error = errors["highFareOverrideAmount"], dimmed = dimmed,
        )
    }
    if (showHelp) HighFareOverrideHelp { showHelp = false }
}

/** Toggle rules shared by the full page and the quick card: switching on fills sensible bounds. */
internal fun FilterDraft.withFareRange(enabled: Boolean): FilterDraft = copy(
    fareRangeEnabled = enabled,
    minFare = if (enabled) minFare.ifBlank { "0" } else minFare,
    maxFare = if (enabled) upperDefault(minFare, maxFare, "100") else maxFare,
)

internal fun FilterDraft.withPickupTime(enabled: Boolean): FilterDraft {
    val lower = if (enabled) minPickupMinutes.ifBlank { "0" } else minPickupMinutes
    return copy(
        pickupTimeEnabled = enabled,
        minPickupMinutes = lower,
        maxPickupMinutes = if (enabled) upperDefault(lower, maxPickupMinutes, "30") else maxPickupMinutes,
    )
}

internal fun FilterDraft.withHighFareOverride(enabled: Boolean): FilterDraft = copy(
    highFareOverrideEnabled = enabled,
    highFareOverrideAmount = if (enabled) highFareOverrideAmount.ifBlank { "35" } else highFareOverrideAmount,
)

@Composable
internal fun NoDestinationCondition(
    draft: FilterDraft,
    onDraftChange: (FilterDraft) -> Unit,
    keyPrefix: String = "",
    framed: Boolean = true,
) {
    val hasExcludedWords = draft.excludeKeywords.split(',', '،', '\n', '\r').any { it.isNotBlank() }
    ProConditionCard(
        title = "قنص الطلبات بدون وجهة",
        subtitle = null,
        enabled = draft.acceptNoDestination,
        switchEnabled = !hasExcludedWords,
        testKey = keyPrefix + "no-destination",
        framed = framed,
        onEnabledChange = { onDraftChange(draft.copy(acceptNoDestination = it)) },
    ) {
        Text(
            if (hasExcludedWords) "موقوف بسبب الكلمات المستثناة؛ يعود عند حذفها"
            else "اقبل العروض التي لا تعرض الوجهة؛ بقية الشروط تبقى مطبّقة",
            style = MaterialTheme.typography.bodySmall,
            color = if (hasExcludedWords) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
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
                        if (expanded || filledCount == 0) subtitle else "المحدد: ${ArabicCount.conditions(filledCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "إخفاء الخيارات" else "عرض الخيارات",
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
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

private fun upperDefault(lower: String, currentUpper: String, fallback: String): String {
    if (currentUpper.isNotBlank()) return currentUpper
    val lowerNumber = CoreText.parseNumber(CoreText.normalize(lower)) ?: return fallback
    val fallbackNumber = BigDecimal(fallback)
    return if (lowerNumber > fallbackNumber) lower else fallback
}

/** Kept as a pure helper for the existing schedule validation tests. */
internal fun parseScheduleMinute(raw: String, allowEndOfDay: Boolean): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(CoreText.normalize(raw)) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour == 24 && minute == 0 && allowEndOfDay) return 1440
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

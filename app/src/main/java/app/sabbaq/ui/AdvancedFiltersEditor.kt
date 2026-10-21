package app.sabbaq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.sabbaq.ui.theme.LocalReducedMotion
import app.sabbaq.ui.theme.SabbaqMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sabbaq.core.PerKmBasis
import app.sabbaq.core.Text as CoreText

/**
 * محرر declarative فقط: لا يحتفظ بقيم المرشحات داخليًا ولا يكتب Settings. كل تغيير
 * يعود إلى الشاشة المالكة لمسودة [FilterDraft] كي يبقى الحفظ صريحًا وذريًا.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedFiltersEditor(
    draft: FilterDraft,
    errors: Map<String, String>,
    onDraftChange: (FilterDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SabbaqSectionTitle(
            title = "حدّد شروط الطلبات",
            subtitle = "اترك أي خانة فارغة إذا لم تكن مهمة لك، ثم احفظ لتستخدم الشروط في وقت العمل."
        )

        FilterGroup(
            title = "السعر والعائد",
            subtitle = "اختر سعر الطلب والعائد الذي يناسبك لكل كيلومتر",
            hasError = errors.keys.any { it in setOf("minFare", "maxFare", "minFarePerKm") },
            filledCount = listOf(draft.minFare, draft.maxFare, draft.minFarePerKm).count { it.isNotBlank() },
        ) {
            DraftField(
                value = draft.minFare,
                label = "أقل سعر للطلب",
                error = errors["minFare"],
                keyboardType = KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(minFare = it)) },
            )
            DraftField(
                value = draft.maxFare,
                label = "أعلى سعر للطلب",
                error = errors["maxFare"],
                keyboardType = KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(maxFare = it)) },
            )
            DraftField(
                value = draft.minFarePerKm,
                label = "أقل عائد لكل كم",
                error = errors["minFarePerKm"],
                keyboardType = KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(minFarePerKm = it)) },
            )
            Text("احسب العائد لكل كم على", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.perKmBasis == PerKmBasis.TRIP,
                    onClick = { onDraftChange(draft.copy(perKmBasis = PerKmBasis.TRIP)) },
                    label = { Text("مسافة الرحلة فقط") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = draft.perKmBasis == PerKmBasis.TOTAL,
                    onClick = { onDraftChange(draft.copy(perKmBasis = PerKmBasis.TOTAL)) },
                    label = { Text("الوصول + الرحلة") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        FilterGroup(
            title = "الوصول إلى الراكب",
            subtitle = "استبعد الطلبات التي يكون الوصول إليها أبعد أو أطول مما تفضّل",
            hasError = errors.keys.any { it in setOf("maxPickupKm", "maxPickupMinutes") },
            filledCount = listOf(draft.maxPickupKm, draft.maxPickupMinutes).count { it.isNotBlank() },
        ) {
            DraftField(
                value = draft.maxPickupKm,
                label = "أبعد مسافة وصول (كم)",
                error = errors["maxPickupKm"],
                keyboardType = KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(maxPickupKm = it)) },
            )
            DraftField(
                value = draft.maxPickupMinutes,
                label = "أطول وقت للوصول (دقائق)",
                error = errors["maxPickupMinutes"],
                keyboardType = KeyboardType.Number,
                onValueChange = { onDraftChange(draft.copy(maxPickupMinutes = it)) },
            )
        }

        FilterGroup(
            title = "الرحلات التي تناسبك",
            subtitle = "حدّد مسافة الرحلة ومدتها بعد استلام الراكب",
            hasError = errors.keys.any { it in setOf("minTripKm", "maxTripKm", "maxTripMinutes") },
            filledCount = listOf(draft.minTripKm, draft.maxTripKm, draft.maxTripMinutes).count { it.isNotBlank() },
        ) {
            DraftField(
                value = draft.minTripKm,
                label = "أقصر مسافة للرحلة (كم)",
                error = errors["minTripKm"],
                keyboardType = KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(minTripKm = it)) },
            )
            DraftField(
                value = draft.maxTripKm,
                label = "أطول مسافة للرحلة (كم)",
                error = errors["maxTripKm"],
                keyboardType = KeyboardType.Decimal,
                onValueChange = { onDraftChange(draft.copy(maxTripKm = it)) },
            )
            DraftField(
                value = draft.maxTripMinutes,
                label = "أطول مدة للرحلة (دقائق)",
                error = errors["maxTripMinutes"],
                keyboardType = KeyboardType.Number,
                onValueChange = { onDraftChange(draft.copy(maxTripMinutes = it)) },
            )
        }

        FilterGroup(
            title = "طلبات لا تناسبك",
            subtitle = "اكتب كلمات لا تريدها في وصف الطلب، وافصل بينها بفاصلة",
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

        FilterGroup(
            title = "أوقات العمل",
            subtitle = "اختر متى تريد متابعة الطلبات؛ يمكن أن يمتد الوقت إلى ما بعد منتصف الليل",
            hasError = errors.keys.any { it in setOf("scheduleStart", "scheduleEnd") },
            filledCount = if (draft.scheduleEnabled) 1 else 0,
        ) {
            ToggleRow(
                text = "العمل في أوقات محددة",
                checked = draft.scheduleEnabled,
                onCheckedChange = { onDraftChange(draft.copy(scheduleEnabled = it)) },
            )
            AnimatedVisibility(draft.scheduleEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DraftField(
                        value = draft.scheduleStart,
                        label = "بداية العمل (مثال 08:30)",
                        error = errors["scheduleStart"],
                        keyboardType = KeyboardType.Ascii,
                        onValueChange = { onDraftChange(draft.copy(scheduleStart = it)) },
                    )
                    DraftField(
                        value = draft.scheduleEnd,
                        label = "نهاية العمل (مثال 22:00؛ 24:00 مسموح)",
                        error = errors["scheduleEnd"],
                        keyboardType = KeyboardType.Ascii,
                        onValueChange = { onDraftChange(draft.copy(scheduleEnd = it)) },
                    )
                }
            }
        }
    }
}

/**
 * Collapsible group. The whole header is the toggle (no separate "show options" row),
 * and a collapsed group still says how many of its conditions are set, so drivers can
 * scan their rules without expanding everything.
 */
@Composable
private fun FilterGroup(
    title: String,
    subtitle: String,
    hasError: Boolean = false,
    filledCount: Int = 0,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(title == "السعر والعائد") }
    LaunchedEffect(hasError) { if (hasError) expanded = true }
    val reduced = LocalReducedMotion.current
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) snap() else tween(SabbaqMotion.MEDIUM, easing = SabbaqMotion.Emphasized),
        label = "filter-group-chevron",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                .heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(
                    if (expanded || filledCount == 0) subtitle else "المحدد: ${ArabicCount.conditions(filledCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "إخفاء الخيارات" else "عرض الخيارات",
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = chevron },
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
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
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(2048)) },
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** توافق مع اختبارات الوحدة القديمة والحقول النصية التي قبل إعادة التصميم. */
internal fun parseScheduleMinute(raw: String, allowEndOfDay: Boolean): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(CoreText.normalize(raw)) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour == 24 && minute == 0 && allowEndOfDay) return 1440
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

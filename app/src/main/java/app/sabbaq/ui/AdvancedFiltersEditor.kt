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
    var showOverrideHelp by rememberSaveable { mutableStateOf(false) }
    val hasExcludedWords = draft.excludeKeywords.split(',', '،', '\n', '\r').any { it.isNotBlank() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SabbaqSectionTitle(
            title = "شروط الطلبات",
            subtitle = "تُحفظ التعديلات تلقائيًا. أطفئ الشرط لإيقافه مع الاحتفاظ بقيمته.",
        )

        FilterGroup(
            title = "منطق جمع الشروط",
            subtitle = "في AND يجب تحقق كل شروط الاختيار؛ في OR يكفي تحقق شرط واحد. شروط الاستبعاد والسلامة تبقى إلزامية.",
            filledCount = 1,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = draft.combination == FilterCombination.ALL,
                    onClick = { onDraftChange(draft.copy(combination = FilterCombination.ALL)) },
                    label = { Text("كل الشروط (AND)") },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
                FilterChip(
                    selected = draft.combination == FilterCombination.ANY,
                    onClick = { onDraftChange(draft.copy(combination = FilterCombination.ANY)) },
                    label = { Text("أي شرط (OR)") },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
        }

        ProConditionCard(
            title = "نطاق السعر",
            subtitle = "أدنى وأعلى أجرة للطلب",
            enabled = draft.fareRangeEnabled,
            testKey = "fare-range",
            onEnabledChange = { enabled ->
                onDraftChange(draft.copy(
                    fareRangeEnabled = enabled,
                    minFare = if (enabled) draft.minFare.ifBlank { "0" } else draft.minFare,
                    maxFare = if (enabled) upperDefault(draft.minFare, draft.maxFare, "100") else draft.maxFare,
                ))
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProStepperValue(
                    label = "أدنى سعر", value = draft.minFare, unit = "",
                    step = "1", keyboardType = KeyboardType.Decimal, error = errors["minFare"],
                    testKey = "min-fare", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(minFare = it)) },
                )
                ProStepperValue(
                    label = "أعلى سعر", value = draft.maxFare, unit = "",
                    step = "1", keyboardType = KeyboardType.Decimal, error = errors["maxFare"],
                    testKey = "max-fare", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(maxFare = it)) },
                )
            }
        }

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
        ) {
            ProStepperValue(
                label = "أدنى عائد", value = draft.minFarePerKm, unit = "سعر/كم",
                step = "0.1", keyboardType = KeyboardType.Decimal, error = errors["minFarePerKm"],
                testKey = "min-fare-per-km",
                onValueChange = { onDraftChange(draft.copy(minFarePerKm = it)) },
            )
            Text("احتسب العائد على", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = draft.perKmBasis == PerKmBasis.TRIP,
                    onClick = { onDraftChange(draft.copy(perKmBasis = PerKmBasis.TRIP)) },
                    label = { Text("مسافة الرحلة") },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
                FilterChip(
                    selected = draft.perKmBasis == PerKmBasis.TOTAL,
                    onClick = { onDraftChange(draft.copy(perKmBasis = PerKmBasis.TOTAL)) },
                    label = { Text("الوصول + الرحلة") },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
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
        ) {
            ProStepperValue(
                label = "أقصى وصول", value = draft.maxPickupKm, unit = "كم",
                step = "0.5", keyboardType = KeyboardType.Decimal, error = errors["maxPickupKm"],
                testKey = "max-pickup-km",
                onValueChange = { onDraftChange(draft.copy(maxPickupKm = it)) },
            )
        }

        ProConditionCard(
            title = "وقت الوصول",
            subtitle = "أقل وأقصى وقت للوصول إلى الراكب",
            enabled = draft.pickupTimeEnabled,
            testKey = "pickup-time",
            onEnabledChange = { enabled ->
                val lower = if (enabled) draft.minPickupMinutes.ifBlank { "0" } else draft.minPickupMinutes
                onDraftChange(draft.copy(
                    pickupTimeEnabled = enabled,
                    minPickupMinutes = lower,
                    maxPickupMinutes = if (enabled) upperDefault(lower, draft.maxPickupMinutes, "30") else draft.maxPickupMinutes,
                ))
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProStepperValue(
                    label = "أقل وقت", value = draft.minPickupMinutes, unit = "دقيقة",
                    step = "5", keyboardType = KeyboardType.Number, error = errors["minPickupMinutes"],
                    testKey = "min-pickup-minutes", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(minPickupMinutes = it)) },
                )
                ProStepperValue(
                    label = "أقصى وقت", value = draft.maxPickupMinutes, unit = "دقيقة",
                    step = "5", keyboardType = KeyboardType.Number, error = errors["maxPickupMinutes"],
                    testKey = "max-pickup-minutes", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(maxPickupMinutes = it)) },
                )
            }
        }

        ProConditionCard(
            title = "مسافة الرحلة",
            subtitle = "الحدان الأدنى والأقصى بعد استلام الراكب",
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
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProStepperValue(
                    label = "أقصر رحلة", value = draft.minTripKm, unit = "كم",
                    step = "0.5", keyboardType = KeyboardType.Decimal, error = errors["minTripKm"],
                    testKey = "min-trip-km", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(minTripKm = it)) },
                )
                ProStepperValue(
                    label = "أطول رحلة", value = draft.maxTripKm, unit = "كم",
                    step = "0.5", keyboardType = KeyboardType.Decimal, error = errors["maxTripKm"],
                    testKey = "max-trip-km", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(maxTripKm = it)) },
                )
            }
        }

        ProConditionCard(
            title = "مدة الرحلة",
            subtitle = "أقل وأقصى مدة متوقعة بعد استلام الراكب",
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
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProStepperValue(
                    label = "أقل مدة", value = draft.minTripMinutes, unit = "دقيقة",
                    step = "5", keyboardType = KeyboardType.Number, error = errors["minTripMinutes"],
                    testKey = "min-trip-minutes", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(minTripMinutes = it)) },
                )
                ProStepperValue(
                    label = "أقصى مدة", value = draft.maxTripMinutes, unit = "دقيقة",
                    step = "5", keyboardType = KeyboardType.Number, error = errors["maxTripMinutes"],
                    testKey = "max-trip-minutes", modifier = Modifier.weight(1f),
                    onValueChange = { onDraftChange(draft.copy(maxTripMinutes = it)) },
                )
            }
        }

        ProConditionCard(
            title = "تجاوز الوقت عند سعر",
            subtitle = "يسمح بوقت/مسافة وصول أطول عند الأجرة المجزية",
            enabled = draft.highFareOverrideEnabled,
            testKey = "high-fare-override",
            headerAction = {
                IconButton(onClick = { showOverrideHelp = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = "شرح تجاوز الوقت عند سعر")
                }
            },
            onEnabledChange = { enabled ->
                onDraftChange(draft.copy(
                    highFareOverrideEnabled = enabled,
                    highFareOverrideAmount = if (enabled) draft.highFareOverrideAmount.ifBlank { "35" } else draft.highFareOverrideAmount,
                ))
            },
        ) {
            ProStepperValue(
                label = "يبدأ التجاوز من", value = draft.highFareOverrideAmount, unit = "سعر الطلب",
                step = "1", keyboardType = KeyboardType.Decimal, error = errors["highFareOverrideAmount"],
                testKey = "high-fare-amount",
                onValueChange = { onDraftChange(draft.copy(highFareOverrideAmount = it)) },
            )
        }

        FilterGroup(
            title = "المناطق والوجهة الذكية والممر",
            subtitle = "مطابقة جغرافية محلية بإحداثيات صريحة؛ لا تُرسل العناوين إلى خدمة خارجية.",
            hasError = errors.keys.any { it in setOf("destinationZones", "destinationAliases", "smartDestination", "destinationCorridor") },
            filledCount = listOf(draft.destinationZones, draft.destinationAliases, draft.smartDestination, draft.destinationCorridor).count(String::isNotBlank),
        ) {
            Text(
                "سبّاق لا يستخرج إحداثيات من عرض جيني تلقائيًا. أضف ربطًا محليًا لعبارات الوجهة بإحداثياتها. عند تفعيل قاعدة جغرافية وعدم تطابق عبارة مع ربط واحد واضح، يُرفض العرض بسبب «وجهة جغرافية غير معروفة» لا بتخمين الموقع.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DraftField(
                value = draft.destinationZones,
                label = "المناطق: include أو exclude|الاسم|latitude|longitude|radius بالمتر — سطر لكل منطقة",
                error = errors["destinationZones"],
                keyboardType = KeyboardType.Text,
                singleLine = false,
                onValueChange = { onDraftChange(draft.copy(destinationZones = it)) },
            )
            DraftField(
                value = draft.destinationAliases,
                label = "ربط عبارة الوجهة بالإحداثيات: العبارة|latitude|longitude — سطر لكل ربط",
                error = errors["destinationAliases"],
                keyboardType = KeyboardType.Text,
                singleLine = false,
                onValueChange = { onDraftChange(draft.copy(destinationAliases = it)) },
            )
            DraftField(
                value = draft.smartDestination,
                label = "وجهة مفضلة اختيارية: latitude,longitude|radius بالمتر",
                error = errors["smartDestination"],
                keyboardType = KeyboardType.Text,
                onValueChange = { onDraftChange(draft.copy(smartDestination = it)) },
            )
            DraftField(
                value = draft.destinationCorridor,
                label = "ممر اختياري: العرض بالمتر|lat,lon;lat,lon;… (نقطتان فأكثر)",
                error = errors["destinationCorridor"],
                keyboardType = KeyboardType.Text,
                onValueChange = { onDraftChange(draft.copy(destinationCorridor = it)) },
            )
        }

        ProConditionCard(
            title = "قنص الطلبات بدون وجهة",
            subtitle = if (hasExcludedWords) "موقوف مؤقتًا بسبب كلمات الاستثناء؛ ستعود حالته المحفوظة عند حذفها."
                else "اسمح بالعروض التي لا تعرض وجهة، مع استمرار تطبيق بقية شروط الأمان.",
            enabled = draft.acceptNoDestination,
            switchEnabled = !hasExcludedWords,
            testKey = "no-destination",
            onEnabledChange = { onDraftChange(draft.copy(acceptNoDestination = it)) },
        ) {
            Text(
                if (hasExcludedWords) "الكلمات المستثناة لها الأولوية ولا يمكن تجاوزها بهذا الخيار."
                else "لا يتجاوز هذا الخيار السعر الأدنى أو الكلمات المحظورة أو شروط الوصول؛ فعّل شرط انتقاء آخر قبل بدء المتابعة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

        FilterGroup(
            title = "أوقات العمل",
            subtitle = "اختر متى تريد متابعة الطلبات؛ يمكن أن يمتد الوقت إلى ما بعد منتصف الليل.",
            hasError = errors.keys.any { it in setOf("scheduleStart", "scheduleEnd") },
            filledCount = if (draft.scheduleEnabled) 1 else 0,
        ) {
            ToggleRow(
                text = "العمل في أوقات محددة",
                checked = draft.scheduleEnabled,
                onCheckedChange = { onDraftChange(draft.copy(scheduleEnabled = it)) },
            )
            AnimatedVisibility(draft.scheduleEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

    if (showOverrideHelp) {
        AlertDialog(
            onDismissRequest = { showOverrideHelp = false },
            title = { Text("تجاوز الوقت عند سعر") },
            text = {
                Text("إذا كان سعر الطلب ظاهرًا ويبلغ العتبة أو يتجاوزها، يتجاوز سبّاق الحد الأقصى لوقت الوصول ومدة الرحلة ومسافة الوصول فقط. تبقى حدود الوقت الدنيا وحدود السعر والعائد والكلمات المستثناة وشروط السلامة الأخرى مطبّقة. إذا لم يظهر السعر فلا يحدث تجاوز.")
            },
            confirmButton = { TextButton(onClick = { showOverrideHelp = false }) { Text("فهمت") } },
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
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    LaunchedEffect(hasError) { if (hasError) expanded = true }
    val reduced = LocalReducedMotion.current
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) snap() else tween(SabbaqMotion.MEDIUM, easing = SabbaqMotion.Emphasized),
        label = "filter-group-chevron",
    )
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = expanded, role = Role.Button, onValueChange = { expanded = it })
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(
                        if (expanded || filledCount == 0) subtitle else "المحدد: ${ArabicCount.conditions(filledCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "إخفاء الخيارات" else "عرض الخيارات",
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = chevron },
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
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

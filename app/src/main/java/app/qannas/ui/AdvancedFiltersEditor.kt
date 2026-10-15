package app.qannas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.qannas.core.Filters
import app.qannas.core.PerKmBasis
import app.qannas.core.Text

/** كل حقل معيب يوقف التشغيل؛ لا نمرر قيمة قديمة صامتة إلى محرك القبول. */
@Composable
internal fun AdvancedFiltersEditor(
    filters: Filters,
    onChange: (Filters) -> Unit,
    onValidityChange: (Boolean) -> Unit
) {
    val invalidFields = remember { mutableStateMapOf<String, Boolean>() }
    fun report(key: String, valid: Boolean) {
        invalidFields[key] = !valid
        onValidityChange(invalidFields.values.none { it })
    }
    Text("مرشحات متقدمة", style = MaterialTheme.typography.titleMedium)
    Text("الحقول الفارغة غير مفعّلة. العرض الذي لا تُقرأ بياناته المطلوبة يُرفض بأمان.", style = MaterialTheme.typography.bodySmall)
    DecimalFilterField("أقصى سعر", filters.maxFare, { report("maxFare", it) }) { onChange(filters.copy(maxFare = it)) }
    DecimalFilterField("أدنى سعر لكل كيلومتر", filters.minFarePerKm, { report("perKm", it) }) { onChange(filters.copy(minFarePerKm = it)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("احتساب سعر الكيلومتر مع وصول الراكب")
        Switch(checked = filters.perKmBasis == PerKmBasis.TOTAL, onCheckedChange = {
            onChange(filters.copy(perKmBasis = if (it) PerKmBasis.TOTAL else PerKmBasis.TRIP))
        })
    }
    DecimalFilterField("أقصى مسافة للوصول إلى الراكب (كم)", filters.maxPickupKm, { report("pickupKm", it) }) { onChange(filters.copy(maxPickupKm = it)) }
    DecimalFilterField("أدنى مسافة للرحلة (كم)", filters.minTripKm, { report("minTripKm", it) }) { onChange(filters.copy(minTripKm = it)) }
    DecimalFilterField("أقصى مسافة للرحلة (كم)", filters.maxTripKm, { report("maxTripKm", it) }) { onChange(filters.copy(maxTripKm = it)) }
    var minutes by remember { mutableStateOf(filters.maxTripMinutes?.toString().orEmpty()) }
    var minutesError by remember { mutableStateOf(false) }
    OutlinedTextField(value = minutes, onValueChange = { raw ->
        minutes = raw
        val value = Text.normalize(raw).toIntOrNull()
        minutesError = raw.isNotBlank() && (value == null || value < 0)
        report("tripMinutes", !minutesError)
        if (!minutesError) onChange(filters.copy(maxTripMinutes = value))
    }, label = { Text("أقصى مدة للرحلة (دقائق)") }, isError = minutesError,
        supportingText = { if (minutesError) Text("أدخل عدداً صحيحاً غير سالب") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    var excluded by remember { mutableStateOf(filters.excludeKeywords.joinToString("، ")) }
    OutlinedTextField(value = excluded, onValueChange = {
        excluded = it.take(1000)
        onChange(filters.copy(excludeKeywords = excluded.split(',', '،', '\n').map(String::trim).filter(String::isNotEmpty)))
    }, label = { Text("كلمات مستبعدة، مفصولة بفاصلة") }, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("تحديد ساعات التشغيل")
        Switch(checked = filters.scheduleEnabled, onCheckedChange = { onChange(filters.copy(scheduleEnabled = it)) })
    }
    if (filters.scheduleEnabled) {
        Text("التوقيت المحلي للجهاز؛ يمكن أن تعبر الفترة منتصف الليل.", style = MaterialTheme.typography.bodySmall)
        ScheduleTimeField("من (HH:mm)", filters.scheduleStartMinute, false, { report("scheduleStart", it) }) {
            onChange(filters.copy(scheduleStartMinute = it))
        }
        ScheduleTimeField("إلى (HH:mm؛ يسمح 24:00)", filters.scheduleEndMinute, true, { report("scheduleEnd", it) }) {
            onChange(filters.copy(scheduleEndMinute = it))
        }
    } else {
        LaunchedEffect(Unit) {
            report("scheduleStart", true)
            report("scheduleEnd", true)
        }
    }
}

@Composable
private fun DecimalFilterField(label: String, initial: String?, onValid: (Boolean) -> Unit, onValue: (String?) -> Unit) {
    var input by remember { mutableStateOf(initial.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    OutlinedTextField(value = input, onValueChange = { raw ->
        input = raw
        val normalized = Text.normalize(raw)
        val decimal = Text.parseNumber(normalized)
        error = raw.isNotBlank() && (decimal == null || decimal.signum() < 0)
        onValid(!error)
        if (!error) onValue(normalized.takeIf { it.isNotEmpty() })
    }, label = { Text(label) }, isError = error,
        supportingText = { if (error) Text("أدخل رقماً غير سالب أو اترك الحقل فارغاً") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun ScheduleTimeField(label: String, initial: Int, allowEndOfDay: Boolean, onValid: (Boolean) -> Unit, onValue: (Int) -> Unit) {
    var input by remember { mutableStateOf("%02d:%02d".format(initial / 60, initial % 60)) }
    var error by remember { mutableStateOf(false) }
    OutlinedTextField(value = input, onValueChange = {
        input = it
        val minute = parseScheduleMinute(it, allowEndOfDay)
        error = minute == null
        onValid(!error)
        minute?.let(onValue)
    }, label = { Text(label) }, isError = error, singleLine = true,
        supportingText = { if (error) Text("أدخل وقتاً صالحاً مثل 08:30") }, modifier = Modifier.fillMaxWidth())
}

internal fun parseScheduleMinute(raw: String, allowEndOfDay: Boolean): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(Text.normalize(raw)) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour == 24 && minute == 0 && allowEndOfDay) return 1440
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

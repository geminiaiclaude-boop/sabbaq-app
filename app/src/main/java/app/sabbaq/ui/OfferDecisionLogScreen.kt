package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sabbaq.stats.DecisionLogOutcome
import app.sabbaq.stats.OfferDecisionLogEntry
import app.sabbaq.stats.OfferDecisionLogStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun OfferDecisionLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(OfferDecisionLogStore.snapshot()) }
    var askClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        // DriverPage owns the single vertical scroll container and page padding.
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("سجل قرارات الطلبات", style = MaterialTheme.typography.headlineSmall)
        Text(
            "يحتفظ سبّاق على هذا الجهاز بآخر 7 أيام من نتائج الفلترة ومحاولات القبول. لا يتضمن السجل نص العرض أو العناوين أو الأجرة، ولا يُرفع إلى الخادم. قد تسجل محاولة النقر دون أن يعني ذلك قبول الرحلة؛ لا يظهر القبول المؤكد إلا بعد رصد شاشة التأكيد.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("رجوع") }
            Button(onClick = { askClear = true }, enabled = rows.isNotEmpty()) { Text("مسح السجل") }
        }
        if (rows.isEmpty()) {
            Text("لا توجد قرارات مسجلة في الأيام السبعة الأخيرة.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "إن كانت الطلبات تصلك في تطبيق النقل ولا تظهر هنا، فسبّاق لا يراها: افتح «تشخيص الرصد» في لوحة التحكم لمعرفة السبب، أو أرسل معرّف الدعم من صفحة الاشتراك.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DecisionSummary(rows)
            rows.forEach { row -> DecisionRow(row) }
        }
    }
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("مسح سجل القرارات؟") },
        text = { Text("سيُحذف السجل المحلي نهائيًا من هذا الجهاز. لن تتغير شروط الطلبات أو الإحصاءات المجمعة.") },
        confirmButton = {
            TextButton(onClick = {
                askClear = false
                if (OfferDecisionLogStore.clear(context)) {
                    rows = emptyList()
                    message = "تم مسح سجل القرارات من هذا الجهاز."
                } else message = "تعذر مسح السجل؛ أعد المحاولة أو احذف بيانات التطبيق من إعدادات الجهاز."
            }) { Text("مسح") }
        },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("إلغاء") } },
    )
}

/** What the driver actually wants to know first: how many offers matched, and what blocks the rest. */
@Composable
private fun DecisionSummary(rows: List<OfferDecisionLogEntry>) {
    val matched = rows.count { it.outcome == DecisionLogOutcome.MATCHED }
    val rejected = rows.filter { it.outcome == DecisionLogOutcome.REJECTED }
    val confirmed = rows.count { it.outcome == DecisionLogOutcome.ACCEPTED_CONFIRMED }
    val topReason = rejected.flatMap { it.reasons.take(1) }.groupingBy { it }.eachCount().maxByOrNull { it.value }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("خلال 7 أيام", style = MaterialTheme.typography.titleSmall)
            Text("طلبات مطابقة: $matched · مرفوضة: ${rejected.size} · قبول مؤكد: $confirmed", style = MaterialTheme.typography.bodyMedium)
            topReason?.let { (reason, count) ->
                Text(
                    "أكثر سبب للرفض ($count مرة): ${reasonArabic(reason)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun platformName(profileId: String): String = when (profileId) {
    "jeeny" -> "جيني"
    "bolt" -> "بولت"
    "petra" -> "بترا"
    else -> profileId
}

@Composable
private fun DecisionRow(entry: OfferDecisionLogEntry) {
    val time = remember(entry.timestampEpochMs) {
        DateTimeFormatter.ofPattern("yyyy/MM/dd  HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(entry.timestampEpochMs))
    }
    val outcome = when (entry.outcome) {
        DecisionLogOutcome.REJECTED -> "رُفض وفق الشروط"
        DecisionLogOutcome.MATCHED -> "مطابق للشروط"
        DecisionLogOutcome.SEMANTIC_CLICK_SENT,
        DecisionLogOutcome.GESTURE_CLICK_SENT -> "ضغط سبّاق على «قبول»؛ بانتظار تأكيد التطبيق"
        DecisionLogOutcome.ACCEPTED_CONFIRMED -> "تم قبول الرحلة"
        DecisionLogOutcome.CONFIRMATION_TIMEOUT -> "ضُغط «قبول» ولم يتأكد القبول (ربما أخذه سائق آخر)"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(outcome, style = MaterialTheme.typography.titleSmall)
            Text("التطبيق: ${platformName(entry.profileId)}", style = MaterialTheme.typography.bodySmall)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.reasons.isNotEmpty()) {
                Text(
                    "السبب: ${entry.reasons.joinToString("، ") { reasonArabic(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

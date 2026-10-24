package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
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
        } else {
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
        DecisionLogOutcome.SEMANTIC_CLICK_SENT -> "أُرسل نقر العنصر دلاليًا؛ بانتظار التأكيد"
        DecisionLogOutcome.GESTURE_CLICK_SENT -> "أُرسلت إيماءة النقر؛ بانتظار التأكيد"
        DecisionLogOutcome.ACCEPTED_CONFIRMED -> "تأكد القبول من شاشة التطبيق"
        DecisionLogOutcome.CONFIRMATION_TIMEOUT -> "لم تظهر شاشة التأكيد خلال المهلة"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(outcome, style = MaterialTheme.typography.titleSmall)
            Text("التطبيق: ${entry.profileId}", style = MaterialTheme.typography.bodySmall)
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

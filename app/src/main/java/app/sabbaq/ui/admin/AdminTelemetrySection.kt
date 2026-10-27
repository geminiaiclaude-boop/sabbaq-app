package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.sabbaq.admin.AdminAction
import app.sabbaq.admin.AdminDiagnosis
import app.sabbaq.admin.AdminDiagnostics
import app.sabbaq.admin.AdminFunnelRow
import app.sabbaq.admin.AdminOverview
import app.sabbaq.admin.AdminRepository
import app.sabbaq.ui.SabbaqAnimatedMessage
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqMessage
import app.sabbaq.ui.SabbaqTone
import kotlinx.coroutines.launch

private sealed interface TelemetryView {
    data class Diagnosis(val result: AdminDiagnosis) : TelemetryView
    data class Fleet(val result: AdminOverview) : TelemetryView
}

/**
 * «تشخيص الشكاوى»: one driver's verdict (from «السائقون», a support conversation or a support id),
 * or the whole fleet over 24 hours. Only the metadata the server allows an admin is shown, never
 * offer text or location.
 */
@Composable
internal fun AdminTelemetrySection(session: AdminSession, consume: () -> AdminRequest?) {
    val scope = rememberCoroutineScope()
    var supportId by rememberSaveable { mutableStateOf("") }
    var view by remember { mutableStateOf<TelemetryView?>(null) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun show(result: Result<AdminDiagnosis>, fallback: String) {
        result.fold(
            onSuccess = { diagnosis ->
                diagnosis.correlationId?.let { supportId = it }
                view = TelemetryView.Diagnosis(diagnosis); failed = false
                notice = if (diagnosis.events.isEmpty()) info("اكتمل البحث.") else success("تم تحميل بيانات metadata المسموح بها للمشرف.")
            },
            onFailure = { view = null; failed = true; notice = session.failureOf(it, fallback) },
        )
    }

    fun search() {
        val id = supportId.trim().lowercase()
        if (!AdminDiagnostics.SUPPORT_ID.matches(id)) {
            notice = failure("أدخل معرّف دعم سداسي عشري صحيحًا. لم تُرسل أي عملية.")
            return
        }
        scope.launch {
            busy = true; notice = info("يجري تحميل خط سير العرض…")
            show(AdminRepository.diagnoseSupportId(id), "تعذر تحميل التشخيص. تحقق من الاتصال أو من صلاحية معرّف الدعم.")
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        when (val request = consume()) {
            is AdminRequest.DiagnoseDriver -> {
                busy = true; notice = info("يجري تحميل تشخيص ${request.name}…")
                show(AdminRepository.diagnose(request.driverId), "تعذر تحميل تشخيص هذا السائق. تحقق من الاتصال.")
                busy = false
            }
            is AdminRequest.DiagnoseSupportId -> { supportId = request.supportId; search() }
            else -> Unit
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-telemetry"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "form") {
            SabbaqCard(title = "تشخيص الشكوى", icon = Icons.Outlined.Insights,
                subtitle = "اضغط «تشخيص الشكوى» بجانب السائق في قسم السائقين، أو ابحث بمعرّف الدعم. لا تعرض هذه الصفحة نصوص العروض أو الموقع.") {
                AdminField("معرّف الدعم", supportId, { supportId = it.trim() }, maxLength = 64, ltr = true, modifier = Modifier.testTag("admin-support-id"))
                SabbaqButton("بحث", onClick = ::search, busy = busy, busyText = "جارٍ التحميل…")
                SabbaqButton("نظرة الأسطول (24 ساعة)", style = SabbaqButtonStyle.Secondary, enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        AdminRepository.overview().fold(
                            onSuccess = { view = TelemetryView.Fleet(it); failed = false; notice = success("تم تحميل نظرة الأسطول.") },
                            onFailure = { view = null; failed = true; notice = session.failureOf(it, "تعذر تحميل نظرة الأسطول.") },
                        )
                        busy = false
                    }
                })
                SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
            }
        }
        when (val current = view) {
            is TelemetryView.Diagnosis -> diagnosisItems(current.result)
            is TelemetryView.Fleet -> fleetItems(current.result)
            null -> if (failed) item(key = "none") { Text("لا تتوفر نتائج مؤكدة.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun funnelLine(row: AdminFunnelRow, withDevices: Boolean): String = buildString {
    append(AdminDiagnostics.stageLabel(row.stage)); append(" · "); append(row.reason ?: "بدون سبب"); append(" · "); append(row.count)
    if (withDevices) { append(" حدث · "); append(row.devices ?: 0); append(" جهاز") }
    row.detail?.let { append(" · "); append(it) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.diagnosisItems(result: AdminDiagnosis) {
    item(key = "verdict") {
        val label = when (result.action) {
            AdminAction.UPDATE -> "يحتاج تحديث التطبيق"
            AdminAction.USER -> "وجّه المستخدم"
            AdminAction.NONE -> "الإجراء"
        }
        SabbaqMessage("السبب: ${result.cause}\n$label: ${result.advice}", tone = if (result.action == AdminAction.NONE) SabbaqTone.Success else SabbaqTone.Error)
    }
    if (result.events.isEmpty()) return
    item(key = "funnel") {
        SabbaqCard(title = "مراحل التوقف (المجموع خلال 7 أيام)") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.funnel.forEach { Text("• ${funnelLine(it, withDevices = false)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    item(key = "timeline-head") { Text("آخر الأحداث (${result.eventCount})", style = MaterialTheme.typography.titleMedium) }
    itemsIndexed(result.events, key = { index, _ -> "e$index" }) { index, event ->
        Text(buildString {
            append("${index + 1}. "); append(formatDateTime(event.at)); append(" · "); append(AdminDiagnostics.stageLabel(event.stage))
            append(" · "); append(event.reason ?: "بدون سبب"); append(" ×"); append(event.count)
            append(" · سبّاق "); append(event.appVersion ?: "غير محدد"); append(" · جيني "); append(event.targetVersion ?: "غير معروف")
            event.latencyMs?.let { append(" · زمن الاستجابة $it ملّي ثانية") }
        }, style = MaterialTheme.typography.bodySmall)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.fleetItems(result: AdminOverview) {
    item(key = "fleet") {
        SabbaqCard(title = "الأسطول خلال 24 ساعة: ${result.devices.size} جهاز") {
            Text(
                if (result.samples > 0 && result.p50Ms != null && result.p90Ms != null)
                    "زمن الاستجابة من ظهور الطلب إلى الضغط: الوسيط ${result.p50Ms} ملّي ثانية · 90% أسرع من ${result.p90Ms} ملّي ثانية (${result.samples} عينة)"
                else "لا توجد عينات زمن استجابة بعد.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.funnel.forEach { Text("• ${funnelLine(it, withDevices = true)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (result.devices.isNotEmpty()) item(key = "devices-head") { Text("الأجهزة", style = MaterialTheme.typography.titleMedium) }
    itemsIndexed(result.devices, key = { index, _ -> "d$index" }) { index, device ->
        Text("${index + 1}. ${device.correlationId ?: "غير محدد"} · ${formatDateTime(device.lastSeen)} · سبّاق ${device.appVersion ?: "غير محدد"} · ${device.lastHeartbeat ?: "بلا نبضة"}",
            style = MaterialTheme.typography.bodySmall)
    }
}

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
import app.sabbaq.LocaleManager
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
                notice = if (diagnosis.events.isEmpty()) info((if (LocaleManager.isArabic()) "اكتمل البحث." else "The search is complete.")) else success((if (LocaleManager.isArabic()) "تم تحميل بيانات metadata المسموح بها للمشرف." else "Loaded the metadata the admin is allowed to see."))
            },
            onFailure = { view = null; failed = true; notice = session.failureOf(it, fallback) },
        )
    }

    fun search() {
        val id = supportId.trim().lowercase()
        if (!AdminDiagnostics.SUPPORT_ID.matches(id)) {
            notice = failure((if (LocaleManager.isArabic()) "أدخل معرّف دعم سداسي عشري صحيحًا. لم تُرسل أي عملية." else "Enter a valid hexadecimal support ID. No operation was sent."))
            return
        }
        scope.launch {
            busy = true; notice = info((if (LocaleManager.isArabic()) "يجري تحميل خط سير العرض…" else "Loading the offer funnel…"))
            show(AdminRepository.diagnoseSupportId(id), (if (LocaleManager.isArabic()) "تعذر تحميل التشخيص. تحقق من الاتصال أو من صلاحية معرّف الدعم." else "Could not load the diagnosis. Check the connection or the support ID validity."))
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        when (val request = consume()) {
            is AdminRequest.DiagnoseDriver -> {
                busy = true; notice = info((if (LocaleManager.isArabic()) "يجري تحميل تشخيص ${request.name}…" else "Loading ${request.name}'s diagnosis…"))
                show(AdminRepository.diagnose(request.driverId), (if (LocaleManager.isArabic()) "تعذر تحميل تشخيص هذا السائق. تحقق من الاتصال." else "Could not load this driver's diagnosis. Check the connection."))
                busy = false
            }
            is AdminRequest.DiagnoseSupportId -> { supportId = request.supportId; search() }
            else -> Unit
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-telemetry"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "form") {
            SabbaqCard(title = (if (LocaleManager.isArabic()) "تشخيص الشكوى" else "Diagnose complaint"), icon = Icons.Outlined.Insights,
                subtitle = (if (LocaleManager.isArabic()) "اضغط «تشخيص الشكوى» بجانب السائق في قسم السائقين، أو ابحث بمعرّف الدعم. لا تعرض هذه الصفحة نصوص العروض أو الموقع." else "Press \u00ab Diagnose complaint \u00bb next to the driver in the drivers section, or search by support ID. This page does not show offer text or location.")) {
                AdminField((if (LocaleManager.isArabic()) "معرّف الدعم" else "Support ID"), supportId, { supportId = it.trim() }, maxLength = 64, ltr = true, modifier = Modifier.testTag("admin-support-id"))
                SabbaqButton((if (LocaleManager.isArabic()) "بحث" else "Search"), onClick = ::search, busy = busy, busyText = (if (LocaleManager.isArabic()) "جارٍ التحميل…" else "Loading…"))
                SabbaqButton((if (LocaleManager.isArabic()) "نظرة الأسطول (24 ساعة)" else "Fleet overview (24 hours)"), style = SabbaqButtonStyle.Secondary, enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        AdminRepository.overview().fold(
                            onSuccess = { view = TelemetryView.Fleet(it); failed = false; notice = success((if (LocaleManager.isArabic()) "تم تحميل نظرة الأسطول." else "Loaded the fleet overview.")) },
                            onFailure = { view = null; failed = true; notice = session.failureOf(it, (if (LocaleManager.isArabic()) "تعذر تحميل نظرة الأسطول." else "Could not load the fleet overview.")) },
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
            null -> if (failed) item(key = "none") { Text((if (LocaleManager.isArabic()) "لا تتوفر نتائج مؤكدة." else "No confirmed results available."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun funnelLine(row: AdminFunnelRow, withDevices: Boolean): String = buildString {
    append(AdminDiagnostics.stageLabel(row.stage)); append(" · "); append(row.reason ?: (if (LocaleManager.isArabic()) "بدون سبب" else "no reason")); append(" · "); append(row.count)
    if (withDevices) { append((if (LocaleManager.isArabic()) " حدث · " else " events · ")); append(row.devices ?: 0); append((if (LocaleManager.isArabic()) " جهاز" else " devices")) }
    row.detail?.let { append(" · "); append(it) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.diagnosisItems(result: AdminDiagnosis) {
    item(key = "verdict") {
        val label = when (result.action) {
            AdminAction.UPDATE -> (if (LocaleManager.isArabic()) "يحتاج تحديث التطبيق" else "Needs an app update")
            AdminAction.USER -> (if (LocaleManager.isArabic()) "وجّه المستخدم" else "Guide the user")
            AdminAction.NONE -> (if (LocaleManager.isArabic()) "الإجراء" else "Action")
        }
        SabbaqMessage((if (LocaleManager.isArabic()) "السبب: ${result.cause}\n$label: ${result.advice}" else "Cause: ${result.cause}\n$label: ${result.advice}"), tone = if (result.action == AdminAction.NONE) SabbaqTone.Success else SabbaqTone.Error)
    }
    if (result.events.isEmpty()) return
    item(key = "funnel") {
        SabbaqCard(title = (if (LocaleManager.isArabic()) "مراحل التوقف (المجموع خلال 7 أيام)" else "Stop stages (total over 7 days)")) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.funnel.forEach { Text("• ${funnelLine(it, withDevices = false)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    item(key = "timeline-head") { Text((if (LocaleManager.isArabic()) "آخر الأحداث (${result.eventCount})" else "Latest events (${result.eventCount})"), style = MaterialTheme.typography.titleMedium) }
    itemsIndexed(result.events, key = { index, _ -> "e$index" }) { index, event ->
        Text(buildString {
            append("${index + 1}. "); append(formatDateTime(event.at)); append(" · "); append(AdminDiagnostics.stageLabel(event.stage))
            append(" · "); append(event.reason ?: (if (LocaleManager.isArabic()) "بدون سبب" else "no reason")); append(" ×"); append(event.count)
            append((if (LocaleManager.isArabic()) " · سبّاق " else " · Sabbaq ")); append(event.appVersion ?: (if (LocaleManager.isArabic()) "غير محدد" else "Not set")); append((if (LocaleManager.isArabic()) " · جيني " else " · Jeeny ")); append(event.targetVersion ?: (if (LocaleManager.isArabic()) "غير معروف" else "Unknown"))
            event.latencyMs?.let { append(if (LocaleManager.isArabic()) " · زمن الاستجابة $it ملّي ثانية" else " · response time $it ms") }
        }, style = MaterialTheme.typography.bodySmall)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.fleetItems(result: AdminOverview) {
    item(key = "fleet") {
        SabbaqCard(title = if (LocaleManager.isArabic()) "الأسطول خلال 24 ساعة: ${result.devices.size} جهاز" else "Fleet over 24 hours: ${result.devices.size} devices") {
            Text(
                if (result.samples > 0 && result.p50Ms != null && result.p90Ms != null)
                    (if (LocaleManager.isArabic()) "زمن الاستجابة من ظهور الطلب إلى الضغط: الوسيط ${result.p50Ms} ملّي ثانية · 90% أسرع من ${result.p90Ms} ملّي ثانية (${result.samples} عينة)" else "Response time from order appearing to tap: median ${result.p50Ms} ms · 90% faster than ${result.p90Ms} ms (${result.samples} samples)")
                else (if (LocaleManager.isArabic()) "لا توجد عينات زمن استجابة بعد." else "No response-time samples yet."),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.funnel.forEach { Text("• ${funnelLine(it, withDevices = true)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (result.devices.isNotEmpty()) item(key = "devices-head") { Text((if (LocaleManager.isArabic()) "الأجهزة" else "Devices"), style = MaterialTheme.typography.titleMedium) }
    itemsIndexed(result.devices, key = { index, _ -> "d$index" }) { index, device ->
        Text(if (LocaleManager.isArabic()) "${index + 1}. ${device.correlationId ?: "غير محدد"} · ${formatDateTime(device.lastSeen)} · سبّاق ${device.appVersion ?: "غير محدد"} · ${device.lastHeartbeat ?: "بلا نبضة"}" else "${index + 1}. ${device.correlationId ?: "Not set"} · ${formatDateTime(device.lastSeen)} · Sabbaq ${device.appVersion ?: "Not set"} · ${device.lastHeartbeat ?: "No heartbeat"}",
            style = MaterialTheme.typography.bodySmall)
    }
}

package app.sabbaq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.sabbaq.LocaleManager
import app.sabbaq.R
import app.sabbaq.core.TelemetryDiagnosis
import app.sabbaq.core.TelemetryStage
import app.sabbaq.telemetry.TelemetryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Pair<String, String>.pick(): String = if (LocaleManager.isArabic()) first else second

/**
 * On-device answer to "why is nothing happening": the funnel since the service started and
 * the earliest stage where offers stop. Works offline; uploading only adds the support view.
 */
@Composable
internal fun TelemetryDiagnosticsCard(modifier: Modifier = Modifier) {
    var status by remember { mutableStateOf(TelemetryRepository.status()) }
    var sending by remember { mutableStateOf(false) }
    var sendResult by remember { mutableStateOf<Pair<TelemetryRepository.SendResult, Long>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            status = TelemetryRepository.status()
            delay(2_000)
        }
    }
    val verdict = TelemetryDiagnosis.diagnose(status.funnel)
    SabbaqCard(
        title = stringResource(R.string.tdc_title),
        subtitle = verdictText(verdict),
        icon = Icons.Outlined.BugReport,
        modifier = modifier,
    ) {
        Text(
            verdictAdvice(verdict),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        status.funnel.filter { it.key.stage != TelemetryStage.EVENT_RECEIVED && it.key.stage != TelemetryStage.HEARTBEAT }
            .take(6).forEach { row ->
                val reason = RuntimeLabels.reason(row.key.reason)
                Text(
                    stageText(row.key.stage) + (if (reason.isNotEmpty()) " · $reason" else "") + " · " + stringResource(R.string.tdc_times, row.total),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        Text(
            stringResource(R.string.tdc_pending, status.pending, uploadText(status.state)) +
                (status.lastUploadEpochMs?.let { stringResource(R.string.tdc_last_upload, clock(it)) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sendResult?.let { (result, at) ->
            SabbaqMessage(
                when (result) {
                    is TelemetryRepository.SendResult.Sent -> stringResource(R.string.tdc_sent, clock(at))
                    TelemetryRepository.SendResult.NoSession -> stringResource(R.string.tdc_no_session)
                    TelemetryRepository.SendResult.Rejected -> stringResource(R.string.tdc_rejected)
                    TelemetryRepository.SendResult.Retrying -> stringResource(R.string.tdc_retrying)
                },
                tone = if (result is TelemetryRepository.SendResult.Sent) SabbaqTone.Success else SabbaqTone.Error,
            )
        }
        SabbaqButton(
            text = if (sendResult?.first is TelemetryRepository.SendResult.Sent) stringResource(R.string.tdc_send_again) else stringResource(R.string.tdc_send_now),
            onClick = {
                if (!sending) scope.launch {
                    sending = true
                    try {
                        sendResult = TelemetryRepository.sendNow() to System.currentTimeMillis()
                        status = TelemetryRepository.status()
                    } finally {
                        sending = false
                    }
                }
            },
            busy = sending,
            busyText = stringResource(R.string.tdc_sending),
            style = SabbaqButtonStyle.Secondary,
            icon = Icons.Outlined.CloudUpload,
        )
    }
}

private fun verdictText(verdict: TelemetryDiagnosis.Verdict): String = (when (verdict) {
    TelemetryDiagnosis.Verdict.NO_EVENTS -> "لم يصل أي حدث من تطبيق النقل" to "No event arrived from the ride app"
    TelemetryDiagnosis.Verdict.DISCLOSURE_REQUIRED -> "إفصاح إمكانية الوصول غير مقبول" to "The accessibility disclosure is not accepted"
    TelemetryDiagnosis.Verdict.SERVICE_PAUSED -> "المتابعة متوقفة" to "Monitoring is stopped"
    TelemetryDiagnosis.Verdict.LICENSE_INVALID -> "الاشتراك غير صالح على هذا الجهاز" to "The subscription is not valid on this device"
    TelemetryDiagnosis.Verdict.NO_PROFILES -> "لا توجد ملفات تطبيقات مدعومة محمّلة" to "No supported app profiles are loaded"
    TelemetryDiagnosis.Verdict.WINDOW_NOT_READABLE -> "نافذة الطلب غير قابلة للقراءة" to "The order window is not readable"
    TelemetryDiagnosis.Verdict.TREE_REJECTED -> "شاشة التطبيق أكبر من حد القراءة الآمن" to "The app screen is larger than the safe read limit"
    TelemetryDiagnosis.Verdict.OFFER_NOT_RECOGNISED -> "الشاشة تُقرأ لكن الطلب لا يُتعرّف عليه" to "The screen is read but the order is not recognised"
    TelemetryDiagnosis.Verdict.OFFER_NOT_PARSED -> "الطلب ظهر وتعذر استخراج تفاصيله" to "The order appeared but its details could not be extracted"
    TelemetryDiagnosis.Verdict.OFFERS_REJECTED_BY_RULES -> "الطلبات تُرى وترفضها شروطك" to "Orders are seen and rejected by your conditions"
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_DISPATCHED -> "تعذر الضغط على زر القبول" to "Could not tap the accept button"
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_CONFIRMED -> "أُرسل القبول ولم يتأكد" to "The accept was sent but not confirmed"
    TelemetryDiagnosis.Verdict.WAITING_FOR_OFFER -> "يعمل؛ بانتظار طلب جديد" to "Running; waiting for a new order"
    TelemetryDiagnosis.Verdict.WORKING -> "يعمل ويقبل الطلبات" to "Running and accepting orders"
}).pick()

private fun verdictAdvice(verdict: TelemetryDiagnosis.Verdict): String = (when (verdict) {
    TelemetryDiagnosis.Verdict.NO_EVENTS -> "تأكد أن خدمة إمكانية الوصول لسبّاق مفعّلة وأن تطبيق النقل مفتوح ومتصل." to "Make sure Sabbaq's accessibility service is enabled and the ride app is open and connected."
    TelemetryDiagnosis.Verdict.DISCLOSURE_REQUIRED -> "افتح سبّاق ووافق على إفصاح إمكانية الوصول ثم شغّل المتابعة." to "Open Sabbaq, accept the accessibility disclosure, then start monitoring."
    TelemetryDiagnosis.Verdict.SERVICE_PAUSED -> "اضغط تشغيل المتابعة من لوحة التحكم." to "Press start monitoring from the dashboard."
    TelemetryDiagnosis.Verdict.LICENSE_INVALID -> "افتح صفحة الاشتراك لتجديده أو إعادة تسجيل الدخول." to "Open the subscription page to renew it or sign in again."
    TelemetryDiagnosis.Verdict.NO_PROFILES -> "أعد تشغيل سبّاق؛ إن استمر أرسل التشخيص للدعم." to "Restart Sabbaq; if it persists, send the diagnosis to support."
    TelemetryDiagnosis.Verdict.WINDOW_NOT_READABLE,
    TelemetryDiagnosis.Verdict.TREE_REJECTED,
    TelemetryDiagnosis.Verdict.OFFER_NOT_RECOGNISED,
    TelemetryDiagnosis.Verdict.OFFER_NOT_PARSED,
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_DISPATCHED,
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_CONFIRMED -> "اضغط «إرسال التشخيص الآن» وأرسل معرّف الدعم من صفحة الاشتراك؛ يحتاج هذا إصلاحًا من فريق سبّاق." to "Press \"Send the diagnosis now\" and send the support ID from the subscription page; this needs a fix from the Sabbaq team."
    TelemetryDiagnosis.Verdict.OFFERS_REJECTED_BY_RULES -> "راجع الشروط في «طلبات تناسبك» وخففها إن كانت أشد من اللازم." to "Review the conditions in \"Offers for you\" and ease them if they are stricter than needed."
    TelemetryDiagnosis.Verdict.WAITING_FOR_OFFER,
    TelemetryDiagnosis.Verdict.WORKING -> "لا يلزم أي إجراء." to "No action is needed."
}).pick()

private fun stageText(stage: TelemetryStage): String = (when (stage) {
    TelemetryStage.EVENT_RECEIVED -> "حدث وارد" to "Incoming event"
    TelemetryStage.PACKAGE_SEEN -> "تطبيق مدعوم" to "Supported app"
    TelemetryStage.GATE_BLOCKED -> "موقوف" to "Blocked"
    TelemetryStage.ROOT_UNAVAILABLE -> "نافذة غير مقروءة" to "Unreadable window"
    TelemetryStage.TREE_REJECTED -> "شاشة مرفوضة" to "Rejected screen"
    TelemetryStage.STALE -> "إطار مكرر" to "Repeated frame"
    TelemetryStage.SIGNAL_MISS -> "لا طلب" to "No order"
    TelemetryStage.SIGNAL_FOUND -> "إشارة طلب" to "Order signal"
    TelemetryStage.FRAME_INCOMPLETE -> "إطار ناقص" to "Incomplete frame"
    TelemetryStage.OFFER_NOT_PARSED -> "تعذر الاستخراج" to "Extraction failed"
    TelemetryStage.OFFER_DETECTED -> "طلب مكتشف" to "Order detected"
    TelemetryStage.REJECTED -> "مرفوض بالشروط" to "Rejected by conditions"
    TelemetryStage.DECISION_ACCEPTED -> "مطابق للشروط" to "Matches conditions"
    TelemetryStage.ACCEPT_NODE -> "زر القبول" to "Accept button"
    TelemetryStage.CLICK_RESULT -> "الضغط" to "Tap"
    TelemetryStage.CONFIRMATION_CONFIRMED -> "قبول مؤكد" to "Accept confirmed"
    TelemetryStage.CONFIRMATION_TIMEOUT -> "قبول غير مؤكد" to "Accept unconfirmed"
    TelemetryStage.SERVICE_CONNECTED -> "الخدمة متصلة" to "Service connected"
    TelemetryStage.SERVICE_INTERRUPTED -> "الخدمة مقاطَعة" to "Service interrupted"
    TelemetryStage.SERVICE_DESTROYED -> "الخدمة أُوقفت" to "Service stopped"
    TelemetryStage.UPLOAD_REJECTED -> "رفض الإرسال" to "Upload rejected"
    TelemetryStage.QUEUE_DROPPED -> "أحداث محذوفة" to "Dropped events"
    TelemetryStage.PAUSED -> "إيقاف مؤقت" to "Paused"
    TelemetryStage.HEARTBEAT -> "نبضة" to "Heartbeat"
    TelemetryStage.APP_CRASH -> "انهيار التطبيق" to "App crash"
    TelemetryStage.PROCESS_EXIT -> "إنهاء من أندرويد" to "Ended by Android"
    TelemetryStage.DEVICE_INFO -> "معلومات الجهاز" to "Device info"
    TelemetryStage.ACCEPT_LEARNED -> "بصمة قبول" to "Accept signature"
    TelemetryStage.UPDATE_STATE -> "التحديث" to "Update"
    TelemetryStage.PARSED -> "قُرئ الطلب" to "Offer read"
    TelemetryStage.ACCEPT_DISPATCHED -> "أُرسل النقر" to "Tap sent"
}).pick()

private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm", SabbaqLocale)

private fun clock(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).format(clockFormat)

private fun uploadText(state: TelemetryRepository.UploadState): String = (when (state) {
    TelemetryRepository.UploadState.IDLE -> "لم يُرسل بعد" to "Not sent yet"
    TelemetryRepository.UploadState.UPLOADED -> "أُرسل" to "Sent"
    TelemetryRepository.UploadState.WAITING_FOR_SESSION -> "بانتظار تسجيل الدخول" to "Waiting for sign-in"
    TelemetryRepository.UploadState.RETRYING -> "يعيد المحاولة تلقائيًا" to "Retrying automatically"
    TelemetryRepository.UploadState.BATCH_DROPPED -> "رُفضت دفعة غير صالحة" to "An invalid batch was rejected"
}).pick()

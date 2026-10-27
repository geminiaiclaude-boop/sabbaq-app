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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.sabbaq.core.TelemetryDiagnosis
import app.sabbaq.core.TelemetryStage
import app.sabbaq.telemetry.TelemetryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        title = "تشخيص الرصد",
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
                    stageText(row.key.stage) + (if (reason.isNotEmpty()) " · $reason" else "") + " · ${row.total} مرة",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        Text(
            "بانتظار الإرسال: ${status.pending} · ${uploadText(status.state)}" +
                (status.lastUploadEpochMs?.let { " · آخر إرسال ${clock(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sendResult?.let { (result, at) ->
            SabbaqMessage(
                when (result) {
                    is TelemetryRepository.SendResult.Sent ->
                        "أُرسل التشخيص إلى فريق سبّاق الساعة ${clock(at)}. أرسل معرّف الدعم أو راسل الدعم ليُراجَع."
                    TelemetryRepository.SendResult.NoSession -> "لم يُرسل: سجّل الدخول أولًا ثم أعد المحاولة."
                    TelemetryRepository.SendResult.Rejected -> "رفض الخادم جزءًا من البيانات وحُذف؛ أُرسل الباقي. أعد المحاولة إن لزم."
                    TelemetryRepository.SendResult.Retrying -> "تعذر الاتصال بالخادم الآن. سيُعاد الإرسال تلقائيًا، أو أعد المحاولة بعد التأكد من الإنترنت."
                },
                tone = if (result is TelemetryRepository.SendResult.Sent) SabbaqTone.Success else SabbaqTone.Error,
            )
        }
        SabbaqButton(
            text = if (sendResult?.first is TelemetryRepository.SendResult.Sent) "إرسال مرة أخرى" else "إرسال التشخيص الآن",
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
            busyText = "جارٍ إرسال التشخيص…",
            style = SabbaqButtonStyle.Secondary,
            icon = Icons.Outlined.CloudUpload,
        )
    }
}

private fun verdictText(verdict: TelemetryDiagnosis.Verdict): String = when (verdict) {
    TelemetryDiagnosis.Verdict.NO_EVENTS -> "لم يصل أي حدث من تطبيق النقل"
    TelemetryDiagnosis.Verdict.DISCLOSURE_REQUIRED -> "إفصاح إمكانية الوصول غير مقبول"
    TelemetryDiagnosis.Verdict.SERVICE_PAUSED -> "المتابعة متوقفة"
    TelemetryDiagnosis.Verdict.LICENSE_INVALID -> "الاشتراك غير صالح على هذا الجهاز"
    TelemetryDiagnosis.Verdict.NO_PROFILES -> "لا توجد ملفات تطبيقات مدعومة محمّلة"
    TelemetryDiagnosis.Verdict.WINDOW_NOT_READABLE -> "نافذة الطلب غير قابلة للقراءة"
    TelemetryDiagnosis.Verdict.TREE_REJECTED -> "شاشة التطبيق أكبر من حد القراءة الآمن"
    TelemetryDiagnosis.Verdict.OFFER_NOT_RECOGNISED -> "الشاشة تُقرأ لكن الطلب لا يُتعرّف عليه"
    TelemetryDiagnosis.Verdict.OFFER_NOT_PARSED -> "الطلب ظهر وتعذر استخراج تفاصيله"
    TelemetryDiagnosis.Verdict.OFFERS_REJECTED_BY_RULES -> "الطلبات تُرى وترفضها شروطك"
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_DISPATCHED -> "تعذر الضغط على زر القبول"
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_CONFIRMED -> "أُرسل القبول ولم يتأكد"
    TelemetryDiagnosis.Verdict.WAITING_FOR_OFFER -> "يعمل؛ بانتظار طلب جديد"
    TelemetryDiagnosis.Verdict.WORKING -> "يعمل ويقبل الطلبات"
}

private fun verdictAdvice(verdict: TelemetryDiagnosis.Verdict): String = when (verdict) {
    TelemetryDiagnosis.Verdict.NO_EVENTS -> "تأكد أن خدمة إمكانية الوصول لسبّاق مفعّلة وأن تطبيق النقل مفتوح ومتصل."
    TelemetryDiagnosis.Verdict.DISCLOSURE_REQUIRED -> "افتح سبّاق ووافق على إفصاح إمكانية الوصول ثم شغّل المتابعة."
    TelemetryDiagnosis.Verdict.SERVICE_PAUSED -> "اضغط تشغيل المتابعة من لوحة التحكم."
    TelemetryDiagnosis.Verdict.LICENSE_INVALID -> "افتح صفحة الاشتراك لتجديده أو إعادة تسجيل الدخول."
    TelemetryDiagnosis.Verdict.NO_PROFILES -> "أعد تشغيل سبّاق؛ إن استمر أرسل التشخيص للدعم."
    TelemetryDiagnosis.Verdict.WINDOW_NOT_READABLE,
    TelemetryDiagnosis.Verdict.TREE_REJECTED,
    TelemetryDiagnosis.Verdict.OFFER_NOT_RECOGNISED,
    TelemetryDiagnosis.Verdict.OFFER_NOT_PARSED,
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_DISPATCHED,
    TelemetryDiagnosis.Verdict.ACCEPT_NOT_CONFIRMED -> "اضغط «إرسال التشخيص الآن» وأرسل معرّف الدعم من صفحة الاشتراك؛ يحتاج هذا إصلاحًا من فريق سبّاق."
    TelemetryDiagnosis.Verdict.OFFERS_REJECTED_BY_RULES -> "راجع الشروط في «سجل القرارات» وخففها إن كانت أشد من اللازم."
    TelemetryDiagnosis.Verdict.WAITING_FOR_OFFER,
    TelemetryDiagnosis.Verdict.WORKING -> "لا يلزم أي إجراء."
}

private fun stageText(stage: TelemetryStage): String = when (stage) {
    TelemetryStage.EVENT_RECEIVED -> "حدث وارد"
    TelemetryStage.PACKAGE_SEEN -> "تطبيق مدعوم"
    TelemetryStage.GATE_BLOCKED -> "موقوف"
    TelemetryStage.ROOT_UNAVAILABLE -> "نافذة غير مقروءة"
    TelemetryStage.TREE_REJECTED -> "شاشة مرفوضة"
    TelemetryStage.STALE -> "إطار مكرر"
    TelemetryStage.SIGNAL_MISS -> "لا طلب"
    TelemetryStage.SIGNAL_FOUND -> "إشارة طلب"
    TelemetryStage.FRAME_INCOMPLETE -> "إطار ناقص"
    TelemetryStage.OFFER_NOT_PARSED -> "تعذر الاستخراج"
    TelemetryStage.OFFER_DETECTED -> "طلب مكتشف"
    TelemetryStage.REJECTED -> "مرفوض بالشروط"
    TelemetryStage.DECISION_ACCEPTED -> "مطابق للشروط"
    TelemetryStage.ACCEPT_NODE -> "زر القبول"
    TelemetryStage.CLICK_RESULT -> "الضغط"
    TelemetryStage.CONFIRMATION_CONFIRMED -> "قبول مؤكد"
    TelemetryStage.CONFIRMATION_TIMEOUT -> "قبول غير مؤكد"
    TelemetryStage.SERVICE_CONNECTED -> "الخدمة متصلة"
    TelemetryStage.SERVICE_INTERRUPTED -> "الخدمة مقاطَعة"
    TelemetryStage.SERVICE_DESTROYED -> "الخدمة أُوقفت"
    TelemetryStage.UPLOAD_REJECTED -> "رفض الإرسال"
    TelemetryStage.QUEUE_DROPPED -> "أحداث محذوفة"
    TelemetryStage.PAUSED -> "إيقاف مؤقت"
    TelemetryStage.HEARTBEAT -> "نبضة"
    TelemetryStage.APP_CRASH -> "انهيار التطبيق"
    TelemetryStage.PARSED, TelemetryStage.ACCEPT_DISPATCHED -> stage.name.lowercase()
}

private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm", SabbaqLocale)

private fun clock(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).format(clockFormat)

private fun uploadText(state: TelemetryRepository.UploadState): String = when (state) {
    TelemetryRepository.UploadState.IDLE -> "لم يُرسل بعد"
    TelemetryRepository.UploadState.UPLOADED -> "أُرسل"
    TelemetryRepository.UploadState.WAITING_FOR_SESSION -> "بانتظار تسجيل الدخول"
    TelemetryRepository.UploadState.RETRYING -> "يعيد المحاولة تلقائيًا"
    TelemetryRepository.UploadState.BATCH_DROPPED -> "رُفضت دفعة غير صالحة"
}

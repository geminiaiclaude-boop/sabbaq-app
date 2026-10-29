package app.sabbaq.ui

import app.sabbaq.LocaleManager
import app.sabbaq.core.DiagnosticStage

/**
 * Driver-facing wording for runtime and diagnostic codes. The cards used to print raw
 * elapsedRealtime numbers and enum names; every code a driver can see is translated here in the
 * in-force language, and an unknown code falls back to a neutral sentence rather than leaking an
 * identifier.
 */
internal object RuntimeLabels {
    private fun Pair<String, String>.pick(): String = if (LocaleManager.isArabic()) first else second

    fun relativeTime(atElapsedMs: Long?, nowElapsedMs: Long): String {
        if (atElapsedMs == null) return ("لم يحدث بعد" to "Not yet").pick()
        val seconds = ((nowElapsedMs - atElapsedMs) / 1000).coerceAtLeast(0)
        val ar = LocaleManager.isArabic()
        return when {
            seconds < 5 -> ("الآن" to "Now").pick()
            seconds < 60 -> ArabicCount.seconds(seconds.toInt()).let { if (ar) "قبل $it" else "$it ago" }
            seconds < 3600 -> ArabicCount.minutes((seconds / 60).toInt()).let { if (ar) "قبل $it" else "$it ago" }
            else -> ArabicCount.hours((seconds / 3600).toInt()).let { if (ar) "قبل $it" else "$it ago" }
        }
    }

    fun pausedReason(reason: String?): String = (when (reason) {
        null -> "متوقفة" to "Stopped"
        "disclosure_required" -> "متوقفة: يلزم الموافقة على إفصاح إمكانية الوصول" to "Stopped: the accessibility disclosure must be accepted"
        "service_paused" -> "متوقفة: المتابعة غير مشغّلة" to "Stopped: monitoring is not running"
        "license_expired" -> "متوقفة مؤقتًا: نتحقق من الاشتراك" to "Paused: verifying the subscription"
        "interrupted" -> "قاطعها النظام؛ تعود تلقائيًا" to "Interrupted by the system; it returns automatically"
        "stale" -> "لا نشاط حديث من تطبيقات النقل" to "No recent activity from the ride apps"
        "service_destroyed" -> "أوقف النظام خدمة إمكانية الوصول" to "The system stopped the accessibility service"
        else -> "متوقفة" to "Stopped"
    }).pick()

    fun stage(stage: DiagnosticStage): String = (when (stage) {
        DiagnosticStage.PACKAGE_SEEN -> "تطبيق مدعوم" to "Supported app"
        DiagnosticStage.EVENT_RECEIVED -> "حدث وارد" to "Incoming event"
        DiagnosticStage.SIGNAL_FOUND -> "إشارة طلب" to "Order signal"
        DiagnosticStage.SIGNAL_MISS -> "لا طلب" to "No order"
        DiagnosticStage.FRAME_INCOMPLETE -> "طلب لم يكتمل ظهوره" to "Order not fully shown"
        DiagnosticStage.FARE_MISSING -> "طلب بلا أجرة ظاهرة" to "Order with no visible fare"
        DiagnosticStage.OFFER_DETECTED -> "طلب مكتشف" to "Order detected"
        DiagnosticStage.OFFER_NOT_PARSED -> "تعذر قراءة الطلب" to "Could not read the order"
        DiagnosticStage.REJECTED -> "مرفوض بالشروط" to "Rejected by conditions"
        DiagnosticStage.ACCEPT_NODE_FOUND -> "زر القبول" to "Accept button"
        DiagnosticStage.CLICK_PATH -> "الضغط" to "Tap"
        DiagnosticStage.CONFIRMATION_CONFIRMED -> "قبول مؤكد" to "Accept confirmed"
        DiagnosticStage.CONFIRMATION_TIMEOUT -> "قبول غير مؤكد" to "Accept unconfirmed"
        DiagnosticStage.STALE -> "إطار مكرر" to "Repeated frame"
        DiagnosticStage.WATCHDOG -> "مراقبة الخدمة" to "Service watchdog"
        DiagnosticStage.GATE_BLOCKED -> "موقوف" to "Blocked"
        DiagnosticStage.ROOT_UNAVAILABLE -> "نافذة غير مقروءة" to "Unreadable window"
        DiagnosticStage.TREE_REJECTED -> "شاشة مرفوضة" to "Rejected screen"
    }).pick()

    /** Telemetry/diagnostic reason codes shown on the device. */
    fun reason(code: String?): String {
        if (code == null) return ""
        if (code.startsWith("failed_") || code in FARESNIPER_REASON_KEYS) return reasonLabel(code).removeSuffix(".")
        return (when (code) {
            "cls_none" -> "الشاشة الحالية ليست طلبًا" to "The current screen is not an order"
            "cls_incomplete" -> "الطلب لم يكتمل ظهوره" to "The order is not fully shown"
            "cls_confirmed_or_active" -> "رحلة جارية" to "Trip in progress"
            "no_accept_control" -> "زر القبول غير موجود" to "No accept button"
            "preflight" -> "فحص أولي" to "Pre-check"
            "unchanged_frame" -> "الشاشة لم تتغير" to "The screen did not change"
            "throttled" -> "تخفيف القراءة" to "Read throttled"
            "same_offer" -> "الطلب نفسه" to "Same order"
            "overlay_window" -> "نافذة طافية" to "Overlay window"
            "parser_empty" -> "تعذر استخراج التفاصيل" to "Could not extract the details"
            "active_null" -> "لا توجد نافذة نشطة" to "No active window"
            "tree_budget" -> "الشاشة أكبر من حد القراءة" to "The screen is larger than the read limit"
            "found" -> "موجود" to "Found"
            "missing" -> "غير موجود" to "Not found"
            "semantic" -> "ضغط مباشر" to "Direct tap"
            "gesture" -> "ضغط بالإيماءة" to "Gesture tap"
            "not_dispatched" -> "لم يُرسل الضغط" to "The tap was not dispatched"
            "unsafe_bounds" -> "موضع الزر غير آمن" to "The button position is unsafe"
            "gate_suppressed" -> "منع ضغط مكرر" to "Duplicate tap prevented"
            "rules_passed" -> "مطابق لشروطك" to "Matches your conditions"
            "complete" -> "مكتمل" to "Complete"
            "fare_missing", "price_missing" -> "الأجرة غير ظاهرة" to "The fare is not shown"
            "retap_semantic", "retap_gesture" -> "ضغط ثانٍ بعد تجاهل الأول" to "Second tap after the first was ignored"
            "retap_not_dispatched" -> "تعذر الضغط الثاني" to "The second tap could not be dispatched"
            "disclosure_required" -> "الإفصاح غير مقبول" to "The disclosure is not accepted"
            "service_paused" -> "المتابعة متوقفة" to "Monitoring is stopped"
            "license_invalid" -> "الاشتراك يحتاج تحقق" to "The subscription needs verification"
            "ready" -> "جاهزة" to "Ready"
            "destroyed", "interrupted" -> "توقفت الخدمة" to "The service stopped"
            else -> when {
                code.startsWith("active_") -> "تطبيق آخر في المقدمة" to "Another app in the foreground"
                code.startsWith("keeper_") -> "حالة المتابعة" to "Monitoring state"
                else -> "تفاصيل تقنية" to "Technical details"
            }
        }).pick()
    }
}

/** Decision reasons that come from the FareSniper engine (DecisionEngine). */
private val FARESNIPER_REASON_KEYS = setOf(
    "dest_excluded", "exclude_no_dest_safe_reject", "no_dest_sniping_disabled", "no_ride_data_detected", "conservative_ocr_reject",
)

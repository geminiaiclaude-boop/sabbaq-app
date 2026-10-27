package app.sabbaq.ui

import app.sabbaq.core.DiagnosticStage
import app.sabbaq.core.ReasonCode

/**
 * Driver-facing wording for runtime and diagnostic codes. The cards used to print raw
 * elapsedRealtime numbers and enum names; every code a driver can see is translated here, and an
 * unknown code falls back to a neutral sentence rather than leaking an identifier.
 */
internal object RuntimeLabels {
    fun relativeTime(atElapsedMs: Long?, nowElapsedMs: Long): String {
        if (atElapsedMs == null) return "لم يحدث بعد"
        val seconds = ((nowElapsedMs - atElapsedMs) / 1000).coerceAtLeast(0)
        return when {
            seconds < 5 -> "الآن"
            seconds < 60 -> "قبل ${ArabicCount.seconds(seconds.toInt())}"
            seconds < 3600 -> "قبل ${ArabicCount.minutes((seconds / 60).toInt())}"
            else -> "قبل ${ArabicCount.hours((seconds / 3600).toInt())}"
        }
    }

    fun pausedReason(reason: String?): String = when (reason) {
        null -> "متوقفة"
        "disclosure_required" -> "متوقفة: يلزم الموافقة على إفصاح إمكانية الوصول"
        "service_paused" -> "متوقفة: المتابعة غير مشغّلة"
        "license_expired" -> "متوقفة مؤقتًا: نتحقق من الاشتراك"
        "interrupted" -> "قاطعها النظام؛ تعود تلقائيًا"
        "stale" -> "لا نشاط حديث من تطبيقات النقل"
        "service_destroyed" -> "أوقف النظام خدمة إمكانية الوصول"
        else -> "متوقفة"
    }

    fun stage(stage: DiagnosticStage): String = when (stage) {
        DiagnosticStage.PACKAGE_SEEN -> "تطبيق مدعوم"
        DiagnosticStage.EVENT_RECEIVED -> "حدث وارد"
        DiagnosticStage.SIGNAL_FOUND -> "إشارة طلب"
        DiagnosticStage.SIGNAL_MISS -> "لا طلب"
        DiagnosticStage.FRAME_INCOMPLETE -> "طلب لم يكتمل ظهوره"
        DiagnosticStage.FARE_MISSING -> "طلب بلا أجرة ظاهرة"
        DiagnosticStage.OFFER_DETECTED -> "طلب مكتشف"
        DiagnosticStage.OFFER_NOT_PARSED -> "تعذر قراءة الطلب"
        DiagnosticStage.REJECTED -> "مرفوض بالشروط"
        DiagnosticStage.ACCEPT_NODE_FOUND -> "زر القبول"
        DiagnosticStage.CLICK_PATH -> "الضغط"
        DiagnosticStage.CONFIRMATION_CONFIRMED -> "قبول مؤكد"
        DiagnosticStage.CONFIRMATION_TIMEOUT -> "قبول غير مؤكد"
        DiagnosticStage.STALE -> "إطار مكرر"
        DiagnosticStage.WATCHDOG -> "مراقبة الخدمة"
        DiagnosticStage.GATE_BLOCKED -> "موقوف"
        DiagnosticStage.ROOT_UNAVAILABLE -> "نافذة غير مقروءة"
        DiagnosticStage.TREE_REJECTED -> "شاشة مرفوضة"
    }

    /** Telemetry/diagnostic reason codes shown on the device. */
    fun reason(code: String?): String {
        if (code == null) return ""
        runCatching { ReasonCode.valueOf(code.uppercase()) }.getOrNull()?.let { return reasonArabic(it).removeSuffix(".") }
        return when (code) {
            "cls_none" -> "الشاشة الحالية ليست طلبًا"
            "cls_incomplete" -> "الطلب لم يكتمل ظهوره"
            "cls_confirmed_or_active" -> "رحلة جارية"
            "no_accept_control" -> "زر القبول غير موجود"
            "preflight" -> "فحص أولي"
            "unchanged_frame" -> "الشاشة لم تتغير"
            "throttled" -> "تخفيف القراءة"
            "same_offer" -> "الطلب نفسه"
            "overlay_window" -> "نافذة طافية"
            "parser_empty" -> "تعذر استخراج التفاصيل"
            "active_null" -> "لا توجد نافذة نشطة"
            "tree_budget" -> "الشاشة أكبر من حد القراءة"
            "found" -> "موجود"
            "missing" -> "غير موجود"
            "semantic" -> "ضغط مباشر"
            "gesture" -> "ضغط بالإيماءة"
            "not_dispatched" -> "لم يُرسل الضغط"
            "unsafe_bounds" -> "موضع الزر غير آمن"
            "gate_suppressed" -> "منع ضغط مكرر"
            "rules_passed" -> "مطابق لشروطك"
            "complete" -> "مكتمل"
            "fare_missing", "price_missing" -> "الأجرة غير ظاهرة"
            "retap_semantic", "retap_gesture" -> "ضغط ثانٍ بعد تجاهل الأول"
            "retap_not_dispatched" -> "تعذر الضغط الثاني"
            "disclosure_required" -> "الإفصاح غير مقبول"
            "service_paused" -> "المتابعة متوقفة"
            "license_invalid" -> "الاشتراك يحتاج تحقق"
            "ready" -> "جاهزة"
            "destroyed", "interrupted" -> "توقفت الخدمة"
            else -> when {
                code.startsWith("active_") -> "تطبيق آخر في المقدمة"
                code.startsWith("keeper_") -> "حالة المتابعة"
                else -> "تفاصيل تقنية"
            }
        }
    }
}

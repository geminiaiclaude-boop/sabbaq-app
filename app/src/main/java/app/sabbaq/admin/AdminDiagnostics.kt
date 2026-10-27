package app.sabbaq.admin

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** What support does about a verdict: guide the driver, ship a fix, or nothing. */
enum class AdminAction { USER, UPDATE, NONE }

data class AdminFunnelRow(val stage: String, val reason: String?, val count: Long, val devices: Int?, val detail: String?)

data class AdminEvent(
    val stage: String,
    val reason: String?,
    val detail: String?,
    val at: Instant?,
    val atRaw: String,
    val count: Int,
    val appVersion: String?,
    val targetVersion: String?,
    val latencyMs: Long?,
)

/**
 * The «تشخيص الشكوى» verdict for one driver, the same rules and wording as the web panel: the
 * earliest place where offers stop, and whether support can fix it by guiding the driver or it
 * needs a release. Events arrive newest first, as admin_offer_telemetry returns them.
 */
object AdminDiagnostics {
    private val HEALTH = mapOf(
        "a11y_disabled" to ("خدمة إمكانية الوصول لسبّاق غير مفعّلة في إعدادات الهاتف" to "وجّهه: الإعدادات ← إمكانية الوصول ← التطبيقات المثبتة ← سبّاق ← تفعيل. في أندرويد 13+ قد يلزم أولًا «السماح بالإعدادات المقيدة» من معلومات التطبيق."),
        "a11y_not_connected" to ("الخدمة مفعّلة لكن النظام أوقفها" to "وجّهه: أطفئ خدمة سبّاق في إمكانية الوصول وأعد تشغيلها، ثم استثنِ سبّاق من تحسين البطارية وفعّل التشغيل التلقائي إن وُجد في الهاتف."),
        "target_not_installed" to ("تطبيق جيني للسائقين غير مثبت على الجهاز" to "اطلب منه تثبيت تطبيق جيني للسائقين وتسجيل الدخول فيه."),
        "target_other_profile" to ("تطبيق جيني مثبت في ملف آخر (نسخة مزدوجة أو ملف عمل أو مجلد آمن) لا يستطيع سبّاق قراءته" to "وجّهه: ثبّت جيني في المساحة الأساسية للجوال وسجّل الدخول منه، واترك النسخة المزدوجة."),
        "target_unknown_package" to ("جيني مثبت بحزمة لا يعرفها ملف التطبيقات" to "أضف الحزمة الظاهرة في حدث PACKAGE_SEEN إلى ملف جيني في قسم «ملفات التطبيقات»؛ يصل للسائقين دون إصدار جديد."),
        "signed_out" to ("المستخدم غير مسجل الدخول في سبّاق" to "اطلب منه فتح سبّاق وتسجيل الدخول."),
        "license_invalid" to ("الاشتراك غير صالح على هذا الجهاز" to "تحقق من اشتراكه في قسم السائقين، ثم اطلب منه فتح سبّاق لتحديث الحالة."),
        "disclosure_required" to ("لم يوافق على إفصاح إمكانية الوصول" to "اطلب منه فتح سبّاق والموافقة على الإفصاح ثم تشغيل المتابعة."),
        "monitoring_off" to ("المتابعة متوقفة في سبّاق" to "اطلب منه الضغط على «تشغيل المتابعة» في لوحة التحكم."),
        "background_restricted" to ("النظام يقيّد عمل سبّاق في الخلفية" to "وجّهه: معلومات التطبيق ← البطارية ← «غير مقيد»."),
        "battery_optimized" to ("تحسين البطارية مفعّل لسبّاق وقد يوقفه النظام" to "وجّهه: معلومات التطبيق ← البطارية ← «غير مقيد/بدون تحسين»."),
        "power_save" to ("وضع توفير الطاقة مفعّل وقد يبطئ الرصد" to "اطلب منه إيقاف توفير الطاقة أثناء العمل."),
        "notifications_off" to ("إشعارات سبّاق متوقفة" to "وجّهه لتفعيل إشعارات سبّاق حتى يبقى يعمل في الخلفية."),
        "offline" to ("الجهاز بلا إنترنت" to "اطلب منه التأكد من الاتصال بالإنترنت."),
    )

    private val KEEPER = mapOf(
        "keeper_restore_timeout" to ("توقفت المتابعة بعد إعادة تشغيل الهاتف لعدم توفر الإنترنت لمدة طويلة" to "اطلب منه فتح سبّاق مرة واحدة مع اتصال بالإنترنت؛ تُستأنف المتابعة تلقائيًا."),
        "keeper_waiting_network" to ("المتابعة متوقفة مؤقتًا بانتظار الإنترنت لتجديد الاشتراك" to "تُستأنف تلقائيًا عند عودة الاتصال؛ اطلب منه التأكد من بيانات الجوال."),
        "keeper_restore_refused" to ("انتهت جلسة الدخول المحفوظة (خروج من جهاز آخر أو حذف الحساب)" to "اطلب منه تسجيل الدخول من جديد ثم تشغيل المتابعة."),
        "keeper_license_lost" to ("توقفت المتابعة لأن الاشتراك لم يعد صالحًا" to "تحقق من اشتراكه في قسم السائقين وجدده إن لزم."),
        "keeper_notifications_denied" to ("توقفت المتابعة لأن إذن الإشعارات مسحوب" to "وجّهه لتفعيل إشعارات سبّاق ثم تشغيل المتابعة."),
        "keeper_disclosure_withdrawn" to ("سحب موافقة إفصاح إمكانية الوصول" to "اطلب منه الموافقة على الإفصاح ثم تشغيل المتابعة."),
    )

    private val REJECTION = mapOf(
        "no_filters" to ("لم يحدد المستخدم أي شرط، والقبول دون شروط غير مفعّل" to "اطلب منه اختيار شروط من «الشروط» أو تفعيل «قبول الطلبات دون شروط»."),
        "destination_missing" to ("الطلبات لا تعرض الوجهة والمستخدم يشترط وجودها" to "جيني يخفي الوجهة غالبًا قبل القبول؛ اطلب منه تفعيل «قبول الطلبات بلا وجهة»."),
        "fare_too_low" to ("أجرة الطلبات أقل من الحد الأدنى الذي حدده" to "راجع معه الحد الأدنى للأجرة."),
        "fare_missing" to ("الأجرة لا تُقرأ من الطلب وشرط الأجرة مفعّل" to "سبب تقني في قراءة الأجرة؛ يلزم تحديث المحلل، ومؤقتًا يمكنه تعطيل شرط الأجرة."),
        // Fare reasons travel as price_*: "fare" is refused in telemetry reason codes.
        "price_too_low" to ("أجرة الطلبات أقل من الحد الأدنى الذي حدده" to "راجع معه الحد الأدنى للأجرة."),
        "price_too_high" to ("أجرة الطلبات أعلى من الحد الأعلى الذي حدده" to "راجع معه الحد الأعلى للأجرة."),
        "price_missing" to ("الأجرة لا تُقرأ من الطلب وشرط الأجرة مفعّل" to "سبب تقني في قراءة الأجرة؛ يلزم تحديث المحلل، ومؤقتًا يمكنه تعطيل شرط الأجرة."),
        "trip_km_missing" to ("جيني لا يعرض مسافة الرحلة في بعض الطلبات وشرطها مفعّل" to "إن أراد قبول هذه الطلبات فليوقف شرط مسافة الرحلة لطلبات جيني."),
        "pickup_too_far" to ("مسافة الوصول للراكب أبعد من حده" to "راجع معه أقصى مسافة للوصول."),
        "pickup_too_long" to ("وقت الوصول للراكب أطول من حده" to "راجع معه أقصى وقت للوصول."),
        "per_km_too_low" to ("العائد لكل كم أقل من حده" to "راجع معه حد العائد لكل كم."),
        "outside_schedule" to ("الطلبات تصل خارج أوقات العمل التي حددها" to "راجع معه جدول أوقات العمل."),
        "trip_unknown" to ("جيني لا يعرض مسافة الرحلة وشرطها مفعّل" to "اطلب منه إيقاف شرطي مسافة ومدة الرحلة لطلبات جيني."),
    )

    private val STAGES = mapOf(
        "EVENT_RECEIVED" to "حدث وارد", "PACKAGE_SEEN" to "تطبيق مدعوم", "GATE_BLOCKED" to "موقوف", "ROOT_UNAVAILABLE" to "نافذة غير مقروءة",
        "TREE_REJECTED" to "شاشة مرفوضة", "STALE" to "إطار مكرر", "SIGNAL_MISS" to "لا طلب", "SIGNAL_FOUND" to "إشارة طلب",
        "FRAME_INCOMPLETE" to "إطار ناقص", "OFFER_NOT_PARSED" to "تعذر الاستخراج", "OFFER_DETECTED" to "طلب مكتشف",
        "REJECTED" to "مرفوض بالشروط", "DECISION_ACCEPTED" to "مطابق للشروط", "ACCEPT_NODE" to "زر القبول", "CLICK_RESULT" to "الضغط",
        "CONFIRMATION_CONFIRMED" to "قبول مؤكد", "CONFIRMATION_TIMEOUT" to "قبول غير مؤكد", "SERVICE_CONNECTED" to "الخدمة متصلة",
        "SERVICE_INTERRUPTED" to "الخدمة مقاطَعة", "SERVICE_DESTROYED" to "الخدمة أُوقفت", "HEARTBEAT" to "نبضة",
        "UPLOAD_REJECTED" to "رفض الإرسال", "QUEUE_DROPPED" to "أحداث محذوفة", "PAUSED" to "إيقاف مؤقت", "APP_CRASH" to "انهيار التطبيق",
    )

    fun stageLabel(stage: String): String = STAGES[stage] ?: stage

    fun funnelRows(array: JsonArray?): List<AdminFunnelRow> = array.orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        AdminFunnelRow(row.str("stage") ?: return@mapNotNull null, row.str("reason_code"), (row.int("count") ?: 0).toLong(), row.int("devices"), row.str("detail"))
    }

    fun from(root: JsonObject): AdminDiagnosis {
        val has = root.bool("driver_has_telemetry") ?: true
        val events = (root["events"] as? JsonArray).orEmpty().mapNotNull { element ->
            val e = element as? JsonObject ?: return@mapNotNull null
            AdminEvent(
                stage = e.str("stage") ?: return@mapNotNull null,
                reason = e.str("reason_code"),
                detail = e.str("detail"),
                at = e.instant("occurred_at"),
                atRaw = e.str("occurred_at").orEmpty(),
                count = e.int("event_count") ?: 1,
                appVersion = e.str("app_version"),
                targetVersion = e.str("target_app_version"),
                latencyMs = e.int("latency_ms")?.toLong(),
            )
        }
        val rows = funnelRows(root["funnel"] as? JsonArray)
        val verdict = verdict(has, events, rows)
        return AdminDiagnosis(
            hasTelemetry = has,
            cause = verdict.cause,
            action = verdict.action,
            advice = verdict.advice,
            correlationId = root.str("correlation_id")?.takeIf { SUPPORT_ID.matches(it) },
            funnel = rows,
            events = events.take(100),
            eventCount = events.size,
        )
    }

    val SUPPORT_ID = Regex("^[a-f0-9]{16,64}$")

    private data class Verdict(val cause: String, val action: AdminAction, val advice: String)

    private fun user(pair: Pair<String, String>) = Verdict(pair.first, AdminAction.USER, pair.second)

    private fun verdict(has: Boolean, events: List<AdminEvent>, rows: List<AdminFunnelRow>): Verdict {
        if (!has || rows.isEmpty()) return Verdict("لا توجد بيانات تشخيص من جهاز هذا المستخدم", AdminAction.USER,
            "اطلب منه تحديث سبّاق لآخر إصدار، وفتحه مع تسجيل الدخول ودقيقتين من الاتصال بالإنترنت، ثم أعد البحث.")
        fun total(stage: String, reason: String? = null) = rows.filter { it.stage == stage && (reason == null || it.reason == reason) }.sumOf { it.count }
        fun newer(than: AdminEvent, stages: Set<String>) = events.any { it.stage in stages && it.atRaw > than.atRaw }

        val crash = events.firstOrNull { it.stage == "APP_CRASH" }
        val heartbeat = events.firstOrNull { it.stage == "HEARTBEAT" }
        if (heartbeat != null && !newer(heartbeat, setOf("OFFER_DETECTED", "CLICK_RESULT"))) HEALTH[heartbeat.reason]?.let { return user(it) }
        val keeper = events.firstOrNull { it.stage == "PAUSED" && it.reason?.startsWith("keeper_") == true }
        if (keeper != null && !newer(keeper, setOf("PACKAGE_SEEN", "OFFER_DETECTED", "CLICK_RESULT", "SERVICE_CONNECTED"))) KEEPER[keeper.reason]?.let { return user(it) }
        if (crash != null && (heartbeat == null || crash.atRaw >= heartbeat.atRaw)) {
            return Verdict("انهيار في سبّاق (${crash.reason ?: "غير محدد"})", AdminAction.UPDATE,
                "عطل برمجي يلزمه إصلاح وتحديث؛ موضع العطل: ${crash.detail ?: "غير معروف"}. وجّه المستخدم مؤقتًا لإعادة فتح سبّاق.")
        }
        val gate = events.firstOrNull { it.stage == "GATE_BLOCKED" }
        val progress = events.firstOrNull { it.stage in setOf("PACKAGE_SEEN", "SIGNAL_FOUND", "SIGNAL_MISS", "OFFER_DETECTED", "CLICK_RESULT") }
        if (gate != null && (progress == null || gate.atRaw >= progress.atRaw)) when (gate.reason) {
            "disclosure_required" -> return Verdict("لم يوافق على إفصاح إمكانية الوصول", AdminAction.USER, "اطلب منه فتح سبّاق والموافقة على الإفصاح ثم تشغيل المتابعة.")
            "service_paused" -> return Verdict("المتابعة متوقفة في سبّاق", AdminAction.USER, "اطلب منه الضغط على «تشغيل المتابعة» في لوحة التحكم.")
            "license_invalid" -> return Verdict("الاشتراك غير صالح على هذا الجهاز", AdminAction.USER, "تحقق من الاشتراك في قسم السائقين، ثم اطلب منه إعادة تسجيل الدخول.")
            "no_profiles", "no_parser", "no_adapter" -> return Verdict("ملفات التطبيقات المدعومة لم تُحمّل", AdminAction.UPDATE, "عطل في الإصدار؛ يلزم إصلاح وتحديث التطبيق.")
        }
        if (total("OFFER_DETECTED") == 0L) {
            val root = total("ROOT_UNAVAILABLE"); val tree = total("TREE_REJECTED"); val miss = total("SIGNAL_MISS")
            return when {
                total("PACKAGE_SEEN") == 0L -> Verdict("لم يصل أي حدث من تطبيق جيني إلى سبّاق", AdminAction.USER, "تأكد أن جيني مفتوح ومتصل وأن خدمة إمكانية الوصول لسبّاق مفعّلة، ثم انتظر طلبًا.")
                root > 0 && root >= tree && root >= miss -> Verdict("نافذة الطلب في جيني غير قابلة للقراءة", AdminAction.UPDATE, "سبب تقني (نافذة طافية/نظام)؛ يلزم إصلاح في التطبيق. السبب الدقيق في reason_code لمرحلة ROOT_UNAVAILABLE.")
                tree > 0 && tree >= miss -> Verdict("شاشة جيني رُفضت أثناء القراءة", AdminAction.UPDATE, "يلزم إصلاح في التطبيق؛ راجع reason_code لمرحلة TREE_REJECTED.")
                total("OFFER_NOT_PARSED") > 0 -> Verdict("الطلب ظهر وتعذر استخراج تفاصيله", AdminAction.UPDATE, "تغيّرت واجهة جيني؛ يلزم تحديث المحلل. راجع detail للمعالم المفقودة.")
                miss > 0 -> Verdict("الشاشة تُقرأ لكن الطلب لا يُتعرّف عليه", AdminAction.UPDATE, "غالبًا تغيّرت تسميات جيني؛ detail يبيّن ما وُجد (a=زر قبول، f=أجرة، d=مسافة، t=وقت، p=عنوان، o=كلمة طلب، c=رحلة نشطة).")
                else -> Verdict("يعمل؛ لم يظهر طلب خلال الفترة", AdminAction.NONE, "لا يلزم إجراء.")
            }
        }
        if (total("DECISION_ACCEPTED") == 0L) {
            val top = rows.filter { it.stage == "REJECTED" }.maxByOrNull { it.count }
            REJECTION[top?.reason]?.let { (cause, advice) ->
                return Verdict(cause, if (advice.startsWith("سبب تقني")) AdminAction.UPDATE else AdminAction.USER, advice)
            }
            return Verdict("الطلبات تُرى وكلها ترفضها شروط المستخدم", AdminAction.USER, "راجع معه الشروط؛ أكثر سبب رفض: ${top?.reason ?: "غير محدد"}.")
        }
        if (total("CLICK_RESULT", "semantic") + total("CLICK_RESULT", "gesture") == 0L) {
            return Verdict("تعذر الضغط على زر القبول", AdminAction.UPDATE, "يلزم إصلاح في التطبيق؛ راجع reason_code لمرحلة CLICK_RESULT.")
        }
        if (total("CONFIRMATION_CONFIRMED") == 0L && total("CONFIRMATION_TIMEOUT") > 0) {
            return Verdict("أُرسل القبول ولم يتأكد في جيني", AdminAction.UPDATE, "غالبًا ضغط في مكان غير صحيح أو طلب أخذه سائق آخر؛ راجع التسلسل الزمني.")
        }
        return Verdict("التطبيق يقبل الطلبات", AdminAction.NONE, "لا يلزم إجراء.")
    }
}

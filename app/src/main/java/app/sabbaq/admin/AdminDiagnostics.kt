package app.sabbaq.admin

import app.sabbaq.LocaleManager
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
    private val HEALTH_AR = mapOf(
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

    private val KEEPER_AR = mapOf(
        "keeper_restore_timeout" to ("توقفت المتابعة بعد إعادة تشغيل الهاتف لعدم توفر الإنترنت لمدة طويلة" to "اطلب منه فتح سبّاق مرة واحدة مع اتصال بالإنترنت؛ تُستأنف المتابعة تلقائيًا."),
        "keeper_waiting_network" to ("المتابعة متوقفة مؤقتًا بانتظار الإنترنت لتجديد الاشتراك" to "تُستأنف تلقائيًا عند عودة الاتصال؛ اطلب منه التأكد من بيانات الجوال."),
        "keeper_restore_refused" to ("انتهت جلسة الدخول المحفوظة (خروج من جهاز آخر أو حذف الحساب)" to "اطلب منه تسجيل الدخول من جديد ثم تشغيل المتابعة."),
        "keeper_license_lost" to ("توقفت المتابعة لأن الاشتراك لم يعد صالحًا" to "تحقق من اشتراكه في قسم السائقين وجدده إن لزم."),
        "keeper_notifications_denied" to ("توقفت المتابعة لأن إذن الإشعارات مسحوب" to "وجّهه لتفعيل إشعارات سبّاق ثم تشغيل المتابعة."),
        "keeper_disclosure_withdrawn" to ("سحب موافقة إفصاح إمكانية الوصول" to "اطلب منه الموافقة على الإفصاح ثم تشغيل المتابعة."),
    )

    private val REJECTION_AR = mapOf(
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

    private val STAGES_AR = mapOf(
        "EVENT_RECEIVED" to "حدث وارد", "PACKAGE_SEEN" to "تطبيق مدعوم", "GATE_BLOCKED" to "موقوف", "ROOT_UNAVAILABLE" to "نافذة غير مقروءة",
        "TREE_REJECTED" to "شاشة مرفوضة", "STALE" to "إطار مكرر", "SIGNAL_MISS" to "لا طلب", "SIGNAL_FOUND" to "إشارة طلب",
        "FRAME_INCOMPLETE" to "إطار ناقص", "OFFER_NOT_PARSED" to "تعذر الاستخراج", "OFFER_DETECTED" to "طلب مكتشف",
        "REJECTED" to "مرفوض بالشروط", "DECISION_ACCEPTED" to "مطابق للشروط", "ACCEPT_NODE" to "زر القبول", "CLICK_RESULT" to "الضغط",
        "CONFIRMATION_CONFIRMED" to "قبول مؤكد", "CONFIRMATION_TIMEOUT" to "قبول غير مؤكد", "SERVICE_CONNECTED" to "الخدمة متصلة",
        "SERVICE_INTERRUPTED" to "الخدمة مقاطَعة", "SERVICE_DESTROYED" to "الخدمة أُوقفت", "HEARTBEAT" to "نبضة",
        "UPLOAD_REJECTED" to "رفض الإرسال", "QUEUE_DROPPED" to "أحداث محذوفة", "PAUSED" to "إيقاف مؤقت", "APP_CRASH" to "انهيار التطبيق",
    )

    private val HEALTH_EN = mapOf(
        "a11y_disabled" to ("Sabbaq's accessibility service is not enabled in the phone settings" to "Guide them: Settings -> Accessibility -> Installed apps -> Sabbaq -> enable. On Android 13+ you may first need \"Allow restricted settings\" from the app info."),
        "a11y_not_connected" to ("The service is enabled but the system stopped it" to "Guide them: turn Sabbaq's accessibility service off and on, then exclude Sabbaq from battery optimization and enable auto-start if the phone has it."),
        "target_not_installed" to ("The Jeeny driver app is not installed on the device" to "Ask them to install the Jeeny driver app and sign in to it."),
        "target_other_profile" to ("Jeeny is installed in another profile (clone, work profile or secure folder) that Sabbaq cannot read" to "Guide them: install Jeeny in the phone's main space and sign in there, and leave the clone."),
        "target_unknown_package" to ("Jeeny is installed with a package the app profiles do not know" to "Add the package shown in the PACKAGE_SEEN event to the Jeeny profile under \"App profiles\"; it reaches drivers without a new release."),
        "signed_out" to ("The user is not signed in to Sabbaq" to "Ask them to open Sabbaq and sign in."),
        "license_invalid" to ("The subscription is not valid on this device" to "Check their subscription in the drivers section, then ask them to open Sabbaq to refresh the status."),
        "disclosure_required" to ("They did not accept the accessibility disclosure" to "Ask them to open Sabbaq, accept the disclosure, then start monitoring."),
        "monitoring_off" to ("Monitoring is stopped in Sabbaq" to "Ask them to press \"Start monitoring\" on the dashboard."),
        "background_restricted" to ("The system restricts Sabbaq from running in the background" to "Guide them: App info -> Battery -> \"Unrestricted\"."),
        "battery_optimized" to ("Battery optimization is on for Sabbaq and the system may stop it" to "Guide them: App info -> Battery -> \"Unrestricted / no optimization\"."),
        "power_save" to ("Power-saving mode is on and may slow detection" to "Ask them to turn off power saving while working."),
        "notifications_off" to ("Sabbaq's notifications are off" to "Guide them to enable Sabbaq's notifications so it keeps running in the background."),
        "offline" to ("The device has no internet" to "Ask them to make sure they are connected to the internet."),
    )

    private val KEEPER_EN = mapOf(
        "keeper_restore_timeout" to ("Monitoring stopped after a phone restart because the internet was unavailable for a long time" to "Ask them to open Sabbaq once with an internet connection; monitoring resumes automatically."),
        "keeper_waiting_network" to ("Monitoring is paused waiting for the internet to renew the subscription" to "It resumes automatically when the connection returns; ask them to check their mobile data."),
        "keeper_restore_refused" to ("The saved sign-in session ended (signed out from another device or the account was deleted)" to "Ask them to sign in again, then start monitoring."),
        "keeper_license_lost" to ("Monitoring stopped because the subscription is no longer valid" to "Check their subscription in the drivers section and renew it if needed."),
        "keeper_notifications_denied" to ("Monitoring stopped because the notifications permission was revoked" to "Guide them to enable Sabbaq's notifications, then start monitoring."),
        "keeper_disclosure_withdrawn" to ("They withdrew the accessibility disclosure consent" to "Ask them to accept the disclosure, then start monitoring."),
    )

    private val REJECTION_EN = mapOf(
        "no_filters" to ("The user set no condition, and accept-without-conditions is off" to "Ask them to choose conditions in \"Conditions\" or enable \"Accept orders without conditions\"."),
        "destination_missing" to ("Orders do not show the destination and the user requires it" to "Jeeny often hides the destination before acceptance; ask them to enable \"Accept orders with no destination\"."),
        "fare_too_low" to ("Order fares are below the minimum they set" to "Review the minimum fare with them."),
        "fare_missing" to ("The fare is not read from the order and the fare condition is on" to "A technical cause in reading the fare; the parser needs an update, and meanwhile they can disable the fare condition."),
        "price_too_low" to ("Order fares are below the minimum they set" to "Review the minimum fare with them."),
        "price_too_high" to ("Order fares are above the maximum they set" to "Review the maximum fare with them."),
        "price_missing" to ("The fare is not read from the order and the fare condition is on" to "A technical cause in reading the fare; the parser needs an update, and meanwhile they can disable the fare condition."),
        "trip_km_missing" to ("Jeeny does not show the trip distance in some orders and its condition is on" to "If they want to accept these orders, they should turn off the trip-distance condition for Jeeny orders."),
        "pickup_too_far" to ("The pickup distance to the rider is farther than their limit" to "Review the maximum pickup distance with them."),
        "pickup_too_long" to ("The pickup time to the rider is longer than their limit" to "Review the maximum pickup time with them."),
        "per_km_too_low" to ("The return per km is below their limit" to "Review the return-per-km limit with them."),
        "outside_schedule" to ("Orders arrive outside the working hours they set" to "Review the working-hours schedule with them."),
        "trip_unknown" to ("Jeeny does not show the trip distance and its condition is on" to "Ask them to turn off the trip distance and duration conditions for Jeeny orders."),
    )

    private val STAGES_EN = mapOf(
        "EVENT_RECEIVED" to "Incoming event", "PACKAGE_SEEN" to "Supported app", "GATE_BLOCKED" to "Blocked", "ROOT_UNAVAILABLE" to "Unreadable window", "TREE_REJECTED" to "Rejected screen", "STALE" to "Repeated frame", "SIGNAL_MISS" to "No order", "SIGNAL_FOUND" to "Order signal", "FRAME_INCOMPLETE" to "Incomplete frame", "OFFER_NOT_PARSED" to "Extraction failed", "OFFER_DETECTED" to "Order detected", "REJECTED" to "Rejected by conditions", "DECISION_ACCEPTED" to "Matches conditions", "ACCEPT_NODE" to "Accept button", "CLICK_RESULT" to "Tap", "CONFIRMATION_CONFIRMED" to "Accept confirmed", "CONFIRMATION_TIMEOUT" to "Accept unconfirmed", "SERVICE_CONNECTED" to "Service connected", "SERVICE_INTERRUPTED" to "Service interrupted", "SERVICE_DESTROYED" to "Service stopped", "HEARTBEAT" to "Heartbeat", "UPLOAD_REJECTED" to "Upload rejected", "QUEUE_DROPPED" to "Dropped events", "PAUSED" to "Paused", "APP_CRASH" to "App crash"
    )

    private val ar: Boolean get() = LocaleManager.isArabic()
    private fun health() = if (ar) HEALTH_AR else HEALTH_EN
    private fun keeper() = if (ar) KEEPER_AR else KEEPER_EN
    private fun rejection() = if (ar) REJECTION_AR else REJECTION_EN
    private fun stages() = if (ar) STAGES_AR else STAGES_EN

    fun stageLabel(stage: String): String = stages()[stage] ?: stage

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
        if (!has || rows.isEmpty()) return Verdict((if (ar) "لا توجد بيانات تشخيص من جهاز هذا المستخدم" else "No diagnostic data from this user's device"), AdminAction.USER,
            (if (ar) "اطلب منه تحديث سبّاق لآخر إصدار، وفتحه مع تسجيل الدخول ودقيقتين من الاتصال بالإنترنت، ثم أعد البحث." else "Ask them to update Sabbaq to the latest version, open it signed in with two minutes of internet, then search again."))
        fun total(stage: String, reason: String? = null) = rows.filter { it.stage == stage && (reason == null || it.reason == reason) }.sumOf { it.count }
        fun newer(than: AdminEvent, stages: Set<String>) = events.any { it.stage in stages && it.atRaw > than.atRaw }

        val crash = events.firstOrNull { it.stage == "APP_CRASH" }
        val heartbeat = events.firstOrNull { it.stage == "HEARTBEAT" }
        if (heartbeat != null && !newer(heartbeat, setOf("OFFER_DETECTED", "CLICK_RESULT"))) health()[heartbeat.reason]?.let { return user(it) }
        val keeper = events.firstOrNull { it.stage == "PAUSED" && it.reason?.startsWith("keeper_") == true }
        if (keeper != null && !newer(keeper, setOf("PACKAGE_SEEN", "OFFER_DETECTED", "CLICK_RESULT", "SERVICE_CONNECTED"))) keeper()[keeper.reason]?.let { return user(it) }
        if (crash != null && (heartbeat == null || crash.atRaw >= heartbeat.atRaw)) {
            return Verdict((if (ar) "انهيار في سبّاق (${crash.reason ?: "غير محدد"})" else "A crash in Sabbaq (${crash.reason ?: "unspecified"})"), AdminAction.UPDATE,
                (if (ar) "عطل برمجي يلزمه إصلاح وتحديث؛ موضع العطل: ${crash.detail ?: "غير معروف"}. وجّه المستخدم مؤقتًا لإعادة فتح سبّاق." else "A software fault that needs a fix and update; fault site: ${crash.detail ?: "unknown"}. Meanwhile, guide the user to reopen Sabbaq."))
        }
        val gate = events.firstOrNull { it.stage == "GATE_BLOCKED" }
        val progress = events.firstOrNull { it.stage in setOf("PACKAGE_SEEN", "SIGNAL_FOUND", "SIGNAL_MISS", "OFFER_DETECTED", "CLICK_RESULT") }
        if (gate != null && (progress == null || gate.atRaw >= progress.atRaw)) when (gate.reason) {
            "disclosure_required" -> return Verdict((if (ar) "لم يوافق على إفصاح إمكانية الوصول" else "They did not accept the accessibility disclosure"), AdminAction.USER, (if (ar) "اطلب منه فتح سبّاق والموافقة على الإفصاح ثم تشغيل المتابعة." else "Ask them to open Sabbaq, accept the disclosure, then start monitoring."))
            "service_paused" -> return Verdict((if (ar) "المتابعة متوقفة في سبّاق" else "Monitoring is stopped in Sabbaq"), AdminAction.USER, (if (ar) "اطلب منه الضغط على «تشغيل المتابعة» في لوحة التحكم." else "Ask them to press \u00ab Start monitoring \u00bb on the dashboard."))
            "license_invalid" -> return Verdict((if (ar) "الاشتراك غير صالح على هذا الجهاز" else "The subscription is not valid on this device"), AdminAction.USER, (if (ar) "تحقق من الاشتراك في قسم السائقين، ثم اطلب منه إعادة تسجيل الدخول." else "Check the subscription in the drivers section, then ask them to sign in again."))
            "no_profiles", "no_parser", "no_adapter" -> return Verdict((if (ar) "ملفات التطبيقات المدعومة لم تُحمّل" else "The supported app profiles did not load"), AdminAction.UPDATE, (if (ar) "عطل في الإصدار؛ يلزم إصلاح وتحديث التطبيق." else "A fault in the build; the app needs a fix and update."))
        }
        if (total("OFFER_DETECTED") == 0L) {
            val root = total("ROOT_UNAVAILABLE"); val tree = total("TREE_REJECTED"); val miss = total("SIGNAL_MISS")
            return when {
                total("PACKAGE_SEEN") == 0L -> Verdict((if (ar) "لم يصل أي حدث من تطبيق جيني إلى سبّاق" else "No event reached Sabbaq from the Jeeny app"), AdminAction.USER, (if (ar) "تأكد أن جيني مفتوح ومتصل وأن خدمة إمكانية الوصول لسبّاق مفعّلة، ثم انتظر طلبًا." else "Make sure Jeeny is open and connected and Sabbaq's accessibility service is on, then wait for an order."))
                root > 0 && root >= tree && root >= miss -> Verdict((if (ar) "نافذة الطلب في جيني غير قابلة للقراءة" else "The Jeeny order window is not readable"), AdminAction.UPDATE, (if (ar) "سبب تقني (نافذة طافية/نظام)؛ يلزم إصلاح في التطبيق. السبب الدقيق في reason_code لمرحلة ROOT_UNAVAILABLE." else "A technical cause (overlay/system window); a fix in the app is needed. The exact cause is in the reason_code of the ROOT_UNAVAILABLE stage."))
                tree > 0 && tree >= miss -> Verdict((if (ar) "شاشة جيني رُفضت أثناء القراءة" else "The Jeeny screen was rejected while reading"), AdminAction.UPDATE, (if (ar) "يلزم إصلاح في التطبيق؛ راجع reason_code لمرحلة TREE_REJECTED." else "A fix in the app is needed; check the reason_code of the TREE_REJECTED stage."))
                total("OFFER_NOT_PARSED") > 0 -> Verdict((if (ar) "الطلب ظهر وتعذر استخراج تفاصيله" else "The order appeared but its details could not be extracted"), AdminAction.UPDATE, (if (ar) "تغيّرت واجهة جيني؛ يلزم تحديث المحلل. راجع detail للمعالم المفقودة." else "Jeeny's interface changed; the parser needs an update. Check detail for the missing markers."))
                miss > 0 -> Verdict((if (ar) "الشاشة تُقرأ لكن الطلب لا يُتعرّف عليه" else "The screen is read but the order is not recognised"), AdminAction.UPDATE, (if (ar) "غالبًا تغيّرت تسميات جيني؛ detail يبيّن ما وُجد (a=زر قبول، f=أجرة، d=مسافة، t=وقت، p=عنوان، o=كلمة طلب، c=رحلة نشطة)." else "Jeeny's labels likely changed; detail shows what was found (a=accept button, f=fare, d=distance, t=time, p=address, o=order word, c=active trip)."))
                else -> Verdict((if (ar) "يعمل؛ لم يظهر طلب خلال الفترة" else "Running; no order appeared during the period"), AdminAction.NONE, (if (ar) "لا يلزم إجراء." else "No action needed."))
            }
        }
        if (total("DECISION_ACCEPTED") == 0L) {
            val top = rows.filter { it.stage == "REJECTED" }.maxByOrNull { it.count }
            rejection()[top?.reason]?.let { (cause, advice) ->
                return Verdict(cause, if (advice.startsWith("سبب تقني") || advice.startsWith("A technical cause")) AdminAction.UPDATE else AdminAction.USER, advice)
            }
            return Verdict((if (ar) "الطلبات تُرى وكلها ترفضها شروط المستخدم" else "Orders are seen and all are rejected by the user's conditions"), AdminAction.USER, (if (ar) "راجع معه الشروط؛ أكثر سبب رفض: ${top?.reason ?: "غير محدد"}." else "Review the conditions with them; top rejection reason: ${top?.reason ?: "unspecified"}."))
        }
        if (total("CLICK_RESULT", "semantic") + total("CLICK_RESULT", "gesture") == 0L) {
            return Verdict((if (ar) "تعذر الضغط على زر القبول" else "Could not tap the accept button"), AdminAction.UPDATE, (if (ar) "يلزم إصلاح في التطبيق؛ راجع reason_code لمرحلة CLICK_RESULT." else "A fix in the app is needed; check the reason_code of the CLICK_RESULT stage."))
        }
        if (total("CONFIRMATION_CONFIRMED") == 0L && total("CONFIRMATION_TIMEOUT") > 0) {
            return Verdict((if (ar) "أُرسل القبول ولم يتأكد في جيني" else "The accept was sent but not confirmed in Jeeny"), AdminAction.UPDATE, (if (ar) "غالبًا ضغط في مكان غير صحيح أو طلب أخذه سائق آخر؛ راجع التسلسل الزمني." else "Likely a tap in the wrong place or an order another driver took; review the timeline."))
        }
        return Verdict((if (ar) "التطبيق يقبل الطلبات" else "The app is accepting orders"), AdminAction.NONE, (if (ar) "لا يلزم إجراء." else "No action needed."))
    }
}

package app.sabbaq.admin

import app.sabbaq.LocaleManager
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One rule on the server (private.plan_state); the app only names it. */
enum class PlanState { SUBSCRIBED, TRIAL, EXPIRED, NONE }

/** From the app's 5-minute heartbeat (private.live_state). */
enum class LiveState { MONITORING, ONLINE, OFFLINE, NEVER }

/** The drivers table's filters, as admin_driver_list names them. */
enum class DriverFilter(val wire: String) {
    ALL("all"), MONITORING("monitoring"), ONLINE("online"), OFFLINE("offline"),
    SUBSCRIBED("subscribed"), TRIAL("trial"), EXPIRED("expired"), NONE("none"), PENDING("pending"), BANNED("banned"),
}

data class AdminDriverRow(
    val id: String,
    val name: String,
    val phone: String,
    val status: String,
    val plan: PlanState,
    val trialUntil: Instant?,
    val proUntil: Instant?,
    val live: LiveState,
    val issue: String?,
    val lastSeen: Instant?,
    val versionName: String?,
    val versionCode: Int?,
    val deviceLinked: Boolean,
    val createdAt: Instant?,
) {
    /** The date that belongs to the plan: the subscription's or the trial's end, none otherwise. */
    val planUntil: Instant? get() = when (plan) {
        PlanState.SUBSCRIBED -> proUntil
        PlanState.TRIAL -> trialUntil
        PlanState.EXPIRED -> listOfNotNull(proUntil, trialUntil).maxOrNull()
        PlanState.NONE -> null
    }
}

data class AdminDriverList(val items: List<AdminDriverRow>, val total: Int, val hasMore: Boolean, val counts: Map<DriverFilter, Int>)

data class AdminRedeemedCode(val code: String, val days: Int?, val batch: String?, val redeemedAt: Instant?)

data class AdminReason(val code: String, val count: Long)

data class AdminDriverDetail(
    val row: AdminDriverRow,
    val email: String?,
    val note: String?,
    val deviceId: String?,
    val deviceBoundAt: Instant?,
    val referralCode: String?,
    val maxActiveZones: Int?,
    val codes: List<AdminRedeemedCode>,
    val heartbeatAt: Instant?,
    val licenceCheckAt: Instant?,
    val flags: Map<String, Boolean>,
    val device: Map<String, String>,
    val androidApi: Int?,
    val rideAppVersion: String?,
    val dayOffers: Long, val dayMatched: Long, val dayRejected: Long, val dayTaps: Long, val dayConfirmed: Long,
    val weekOffers: Long, val weekConfirmed: Long,
    val rejectReasons: List<AdminReason>,
    val referredById: String?, val referredByName: String?,
    val invited: Int, val rewarded: Int,
    val supportStatus: String?, val supportUnread: Int, val supportLastAt: Instant?, val supportMessages: Int,
    val notifications30d: Int,
)

data class AdminBatchStat(val batch: String, val total: Int, val redeemed: Int, val available: Int, val createdAt: Instant?)

data class AdminCodeStats(
    val total: Int, val available: Int, val redeemed: Int, val revoked: Int,
    val redeemed7d: Int, val redeemed30d: Int,
    val byDays: List<Triple<Int, Int, Int>>,
    val byBatch: List<AdminBatchStat>,
)

data class AdminAcceptCandidate(val viewId: String, val accepts: Long, val drivers: Int)

data class AdminAcceptLearning(val live: List<String>, val confirmed30d: Long, val candidates: List<AdminAcceptCandidate>)

/** Parsing and wording for the console; pure, so the rules are unit tested. */
object AdminConsole {
    private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val ar: Boolean get() = LocaleManager.isArabic()

    fun plan(wire: String?): PlanState = when (wire) {
        "subscribed" -> PlanState.SUBSCRIBED
        "trial" -> PlanState.TRIAL
        "expired" -> PlanState.EXPIRED
        else -> PlanState.NONE
    }

    fun live(wire: String?): LiveState = when (wire) {
        "monitoring" -> LiveState.MONITORING
        "online" -> LiveState.ONLINE
        "offline" -> LiveState.OFFLINE
        else -> LiveState.NEVER
    }

    fun row(o: JsonObject): AdminDriverRow? {
        val id = o.str("id")?.takeIf(ID::matches) ?: return null
        return AdminDriverRow(
            id = id,
            name = o.str("full_name") ?: (if (ar) "سائق بلا اسم" else "Unnamed driver"),
            phone = o.str("phone").orEmpty(),
            status = o.str("status") ?: "pending",
            plan = plan(o.str("plan")),
            trialUntil = o.instant("trial_until"),
            proUntil = o.instant("pro_until"),
            live = live(o.str("live")),
            issue = o.str("issue"),
            lastSeen = o.instant("last_seen"),
            versionName = o.str("version_name"),
            versionCode = o.int("version_code"),
            deviceLinked = o.bool("device_linked") == true,
            createdAt = o.instant("created_at"),
        )
    }

    fun list(o: JsonObject): AdminDriverList {
        val counts = (o["counts"] as? JsonObject)
        return AdminDriverList(
            items = (o["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::row) },
            total = o.int("total") ?: 0,
            hasMore = o.bool("has_more") == true,
            counts = DriverFilter.entries.associateWith { counts?.int(it.wire) ?: 0 },
        )
    }

    /** "a11y=1,svc=0" → {a11y: true, svc: false}; anything else is ignored. */
    fun flags(detail: String?): Map<String, Boolean> = detail.orEmpty().split(',').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        if (v == "1" || v == "0") k to (v == "1") else null
    }.toMap()

    fun pairs(detail: String?): Map<String, String> = detail.orEmpty().split(',').mapNotNull { part ->
        part.split('=').takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] }
    }.toMap()

    fun detail(o: JsonObject): AdminDriverDetail? {
        val account = o["account"] as? JsonObject ?: return null
        val plan = o["plan"] as? JsonObject
        val live = o["live"] as? JsonObject
        val activity = o["activity"] as? JsonObject
        val day = activity?.get("day") as? JsonObject
        val week = activity?.get("week") as? JsonObject
        val referrals = o["referrals"] as? JsonObject
        val referredBy = referrals?.get("referred_by") as? JsonObject
        val support = o["support"] as? JsonObject
        val row = AdminDriverRow(
            id = account.str("id")?.takeIf(ID::matches) ?: return null,
            name = account.str("full_name") ?: (if (ar) "سائق بلا اسم" else "Unnamed driver"),
            phone = account.str("phone").orEmpty(),
            status = account.str("status") ?: "pending",
            plan = plan(plan?.str("state")),
            trialUntil = plan?.instant("trial_until"),
            proUntil = plan?.instant("pro_until"),
            live = live(live?.str("state")),
            issue = live?.str("issue"),
            lastSeen = live?.instant("last_seen"),
            versionName = live?.str("version_name"),
            versionCode = live?.int("version_code"),
            deviceLinked = account.str("device_id") != null,
            createdAt = account.instant("created_at"),
        )
        fun long(x: JsonObject?, k: String) = x?.int(k)?.toLong() ?: 0L
        return AdminDriverDetail(
            row = row,
            email = account.str("email"),
            note = account.str("note"),
            deviceId = account.str("device_id"),
            deviceBoundAt = account.instant("device_bound_at"),
            referralCode = account.str("referral_code"),
            maxActiveZones = account.int("max_active_zones"),
            codes = (plan?.get("codes") as? JsonArray).orEmpty().mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                AdminRedeemedCode(c.str("code") ?: return@mapNotNull null, c.int("days"), c.str("batch"), c.instant("redeemed_at"))
            },
            heartbeatAt = live?.instant("heartbeat_at"),
            licenceCheckAt = live?.instant("licence_check_at"),
            flags = flags(live?.str("flags")),
            device = pairs(live?.str("device")),
            androidApi = live?.int("android_api"),
            rideAppVersion = live?.str("ride_app_version"),
            dayOffers = long(day, "offers"), dayMatched = long(day, "matched"), dayRejected = long(day, "rejected"),
            dayTaps = long(day, "taps"), dayConfirmed = long(day, "confirmed"),
            weekOffers = long(week, "offers"), weekConfirmed = long(week, "confirmed"),
            rejectReasons = (activity?.get("reject_reasons") as? JsonArray).orEmpty().mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                AdminReason(r.str("reason") ?: return@mapNotNull null, (r.int("count") ?: 0).toLong())
            },
            referredById = referredBy?.str("id"), referredByName = referredBy?.str("full_name"),
            invited = referrals?.int("invited") ?: 0, rewarded = referrals?.int("rewarded") ?: 0,
            supportStatus = support?.str("status"), supportUnread = support?.int("admin_unread") ?: 0,
            supportLastAt = support?.instant("last_message_at"), supportMessages = support?.int("messages") ?: 0,
            notifications30d = o.int("notifications_30d") ?: 0,
        )
    }

    fun codeStats(o: JsonObject): AdminCodeStats = AdminCodeStats(
        total = o.int("total") ?: 0, available = o.int("available") ?: 0, redeemed = o.int("redeemed") ?: 0,
        revoked = o.int("revoked") ?: 0, redeemed7d = o.int("redeemed_7d") ?: 0, redeemed30d = o.int("redeemed_30d") ?: 0,
        byDays = (o["by_days"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            Triple(r.int("days") ?: return@mapNotNull null, r.int("total") ?: 0, r.int("available") ?: 0)
        },
        byBatch = (o["by_batch"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            AdminBatchStat(r.str("batch").orEmpty(), r.int("total") ?: 0, r.int("redeemed") ?: 0, r.int("available") ?: 0, r.instant("created_at"))
        },
    )

    fun acceptLearning(o: JsonObject): AdminAcceptLearning = AdminAcceptLearning(
        live = (o["live"] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
        confirmed30d = (o.int("confirmed_30d") ?: 0).toLong(),
        candidates = (o["candidates"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            AdminAcceptCandidate(r.str("view_id") ?: return@mapNotNull null, (r.int("accepts") ?: 0).toLong(), r.int("drivers") ?: 0)
        },
    )

    // ---- Wording

    fun planLabel(p: PlanState): String = when (p) {
        PlanState.SUBSCRIBED -> if (ar) "مشترك" else "Subscribed"
        PlanState.TRIAL -> if (ar) "تجربة" else "Trial"
        PlanState.EXPIRED -> if (ar) "منتهٍ" else "Expired"
        PlanState.NONE -> if (ar) "بلا اشتراك" else "No plan"
    }

    fun liveLabel(l: LiveState): String = when (l) {
        LiveState.MONITORING -> if (ar) "يراقب الآن" else "Monitoring"
        LiveState.ONLINE -> if (ar) "متصل، المتابعة متوقفة" else "Online, monitoring off"
        LiveState.OFFLINE -> if (ar) "غير متصل" else "Offline"
        LiveState.NEVER -> if (ar) "لم يتصل بعد" else "Never connected"
    }

    fun filterLabel(f: DriverFilter): String = when (f) {
        DriverFilter.ALL -> if (ar) "الكل" else "All"
        DriverFilter.MONITORING -> if (ar) "يراقب الآن" else "Monitoring"
        DriverFilter.ONLINE -> if (ar) "متصل" else "Online"
        DriverFilter.OFFLINE -> if (ar) "غير متصل" else "Offline"
        DriverFilter.SUBSCRIBED -> if (ar) "مشترك" else "Subscribed"
        DriverFilter.TRIAL -> if (ar) "تجربة" else "Trial"
        DriverFilter.EXPIRED -> if (ar) "منتهٍ" else "Expired"
        DriverFilter.NONE -> if (ar) "بلا اشتراك" else "No plan"
        DriverFilter.PENDING -> if (ar) "بانتظار التفعيل" else "Pending"
        DriverFilter.BANNED -> if (ar) "محظور" else "Banned"
    }

    /** «منذ ٥ د» style; the table's last-seen column. */
    fun ago(at: Instant?, now: Instant = Instant.now()): String {
        if (at == null) return if (ar) "—" else "—"
        val d = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.toMinutes() < 1 -> if (ar) "الآن" else "now"
            d.toMinutes() < 60 -> if (ar) "منذ ${d.toMinutes()} د" else "${d.toMinutes()} min ago"
            d.toHours() < 24 -> if (ar) "منذ ${d.toHours()} س" else "${d.toHours()} h ago"
            else -> if (ar) "منذ ${d.toDays()} يوم" else "${d.toDays()} d ago"
        }
    }

    /** Days left on the plan's date, for «باقي ١٢ يومًا»; null when it has passed or there is none. */
    fun daysLeft(until: Instant?, now: Instant = Instant.now()): Long? =
        until?.takeIf { it.isAfter(now) }?.let { Duration.between(now, it).toHours().let { h -> (h + 23) / 24 } }

    fun issueLabel(code: String?): String? = when (code) {
        null, "healthy", "ok" -> null
        "a11y_disabled" -> if (ar) "إذن متابعة الطلبات (إمكانية الوصول) مطفأ" else "Accessibility permission is off"
        "a11y_not_connected" -> if (ar) "خدمة المتابعة غير متصلة" else "Monitoring service not connected"
        "target_not_installed" -> if (ar) "تطبيق الرحلات غير مثبت" else "Ride app not installed"
        "target_other_profile" -> if (ar) "تطبيق الرحلات في ملف شخصي آخر" else "Ride app in another profile"
        "target_unknown_package" -> if (ar) "نسخة تطبيق رحلات غير معروفة" else "Unknown ride app package"
        "signed_out" -> if (ar) "خرج من حسابه" else "Signed out"
        "offline" -> if (ar) "الهاتف بلا إنترنت" else "Phone offline"
        "license_invalid" -> if (ar) "الاشتراك غير صالح على الهاتف" else "Licence not valid on the phone"
        "update_required" -> if (ar) "يحتاج تحديثًا إلزاميًا" else "Mandatory update needed"
        "disclosure_required" -> if (ar) "لم يوافق على الإفصاح" else "Disclosure not accepted"
        "monitoring_off" -> if (ar) "المتابعة متوقفة" else "Monitoring off"
        "background_restricted" -> if (ar) "أندرويد يقيّد العمل في الخلفية" else "Background restricted"
        "battery_optimized" -> if (ar) "قيود البطارية مفعّلة" else "Battery optimised"
        "hibernation_on" -> if (ar) "«إيقاف نشاط التطبيق» مفعّل" else "Pause app activity is on"
        "standby_restricted" -> if (ar) "أندرويد صنّفه نادر الاستخدام" else "Standby restricted"
        "power_save" -> if (ar) "وضع توفير الطاقة" else "Power saving"
        "notifications_off" -> if (ar) "الإشعارات مطفأة" else "Notifications off"
        else -> code
    }

    fun reasonLabel(code: String): String = when {
        code == "failed_price" -> if (ar) "الأجرة خارج نطاقه" else "Fare outside range"
        code == "failed_time" -> if (ar) "وقت الوصول خارج حده" else "Pickup time outside limit"
        code.startsWith("failed_") -> if (ar) "لم يطابق الأجرة والوقت" else "Fare and time not matched"
        code == "dest_excluded" -> if (ar) "وجهة مستبعدة بالكلمات" else "Excluded destination word"
        code == "dest_excluded_zone" -> if (ar) "وجهة في منطقة يتجنبها" else "Destination in an avoided zone"
        code == "dest_global_excluded_zone" -> if (ar) "وجهة في منطقة استثناء عامة" else "Destination in a shared excluded zone"
        code == "dest_zone_unknown_reject" || code == "dest_exclude_zone_unknown_reject" -> if (ar) "مكان الوجهة غير معروف (اختياره)" else "Unknown destination (his option)"
        code == "exclude_no_dest_safe_reject" -> if (ar) "بلا وجهة مع استبعاد مفعّل" else "No destination with exclusions on"
        code == "no_dest_sniping_disabled" -> if (ar) "طلب بلا وجهة وقبوله مطفأ" else "No destination, option off"
        code.startsWith("payment_") -> if (ar) "طريقة الدفع لا تناسبه" else "Payment method"
        code.startsWith("rating_") -> if (ar) "تقييم الراكب أقل من حده" else "Rider rating"
        code == "wait_save_filtered" -> if (ar) "«انتظر ووفّر» مستبعد" else "Wait & save excluded"
        code == "no_ride_data_detected" -> if (ar) "لم تُقرأ الأجرة ولا الوقت" else "Neither fare nor time read"
        else -> code
    }

    fun deviceLine(device: Map<String, String>): String? {
        if (device.isEmpty()) return null
        val parts = buildList {
            device["mf"]?.let { add(it.replaceFirstChar(Char::uppercase)) }
            device["ram"]?.takeIf { it != "0" }?.let { add(if (ar) "ذاكرة $it ج.ب" else "$it GB RAM") }
            device["bat"]?.let { add(if (it == "1") (if (ar) "البطارية بلا قيود" else "battery unrestricted") else (if (ar) "البطارية مقيّدة" else "battery restricted")) }
            device["hib"]?.takeIf { it == "1" }?.let { add(if (ar) "إيقاف النشاط مفعّل" else "pause activity on") }
        }
        return parts.joinToString(" · ").ifBlank { null }
    }
}

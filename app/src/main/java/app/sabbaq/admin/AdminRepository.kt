package app.sabbaq.admin

import app.sabbaq.LocaleManager

import app.sabbaq.network.SupabaseClient
import app.sabbaq.network.SupabaseClient.FeatureException
import app.sabbaq.network.SupabaseClient.FeatureFailure
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class AdminPageOf<T>(val items: List<T>, val offset: Int, val hasMore: Boolean)

data class AdminCode(val code: String, val days: Int?, val batch: String?, val redeemedBy: String?, val revoked: Boolean, val createdAt: Instant?) {
    val available: Boolean get() = redeemedBy == null && !revoked
}

data class AdminExportCode(val code: String, val days: Int?, val batch: String?)

data class AdminPolicy(
    val trialEnabled: Boolean,
    val trialDays: Int,
    val priceMinor: Long,
    val subscriptionDays: Int,
    val contactUrl: String?,
    val whatsapp: String?,
    val updatedAt: Instant,
)

data class AdminNotification(
    val id: String,
    val toAll: Boolean,
    val driverName: String?,
    val title: String,
    val kind: String,
    val readCount: Int,
    val createdAt: Instant?,
)

data class AdminThread(
    val driverId: String,
    val name: String,
    val phone: String,
    val closed: Boolean,
    val unread: Int,
    val lastAt: Instant?,
    val preview: String?,
    val previewFromAdmin: Boolean,
)

data class AdminProfile(val id: String, val version: Int, val enabled: Boolean, val updatedAt: Instant?, val body: String)

data class AdminDevice(val correlationId: String?, val lastSeen: Instant?, val appVersion: String?, val lastHeartbeat: String?)

data class AdminOverview(val p50Ms: Long?, val p90Ms: Long?, val samples: Int, val funnel: List<AdminFunnelRow>, val devices: List<AdminDevice>)

data class AdminMessage(val fromAdmin: Boolean, val body: String, val at: Instant?)

data class AdminConversation(
    val name: String,
    val phone: String,
    val closed: Boolean,
    val appVersion: String?,
    val correlationId: String?,
    val messages: List<AdminMessage>,
)

/** One verdict a support person can act on: what stops this driver and what to tell them. */
data class AdminDiagnosis(
    val hasTelemetry: Boolean,
    val cause: String,
    val action: AdminAction,
    val advice: String,
    val correlationId: String?,
    val funnel: List<AdminFunnelRow>,
    val events: List<AdminEvent>,
    val eventCount: Int,
)

/**
 * Native administration: every call goes through SupabaseClient with the signed-in admin's
 * session, and the server's require_admin() decides access. Parsing is tolerant: one malformed
 * row is skipped, never the whole list. Inputs are checked with the same rules as the server and
 * the web panel, so a refused value never leaves the phone.
 */
object AdminRepository {
    const val PAGE_SIZE = 100
    const val MAX_EXPORT_ROWS = 1000

    /** Allowed durations, as the server enforces them. */
    val CODE_DAYS = listOf(7, 30, 90, 365)

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }
    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val VERSION_NAME = Regex("^[A-Za-z0-9._-]{1,32}$")
    private val PROFILE_ID = Regex("^[a-z0-9_]{2,32}$")

    private fun rejected(): Result<Nothing> = Result.failure(FeatureException(FeatureFailure.REJECTED))

    // ---- Drivers

    /** The drivers table: plan and live state per account, searched and filtered on the server (0020). */
    suspend fun driverList(search: String, filter: DriverFilter, offset: Int, limit: Int = PAGE_SIZE): Result<AdminDriverList> =
        SupabaseClient.adminRpc("admin_driver_list", buildJsonObject {
            put("p_search", search.trim().take(40)); put("p_filter", filter.wire); put("p_offset", offset.coerceAtLeast(0)); put("p_limit", limit.coerceIn(1, 100))
        }).mapCatching { body -> AdminConsole.list(json.parseToJsonElement(body) as JsonObject) }

    /** Everything about one driver for the driver page (0020). */
    suspend fun driverDetail(driverId: String): Result<AdminDriverDetail> {
        if (!UUID_PATTERN.matches(driverId)) return rejected()
        return SupabaseClient.adminRpc("admin_driver_detail", buildJsonObject { put("p_driver", driverId) })
            .mapCatching { body -> AdminConsole.detail(json.parseToJsonElement(body) as JsonObject) ?: error("bad driver page") }
    }

    /** What the fleet learned from confirmed accepts (0020). */
    suspend fun acceptLearning(): Result<AdminAcceptLearning> = SupabaseClient.adminRpc("admin_accept_learning")
        .mapCatching { body -> AdminConsole.acceptLearning(json.parseToJsonElement(body) as JsonObject) }

    /** Activation codes at a glance (0020). */
    suspend fun codeStats(): Result<AdminCodeStats> = SupabaseClient.adminRpc("admin_code_stats")
        .mapCatching { body -> AdminConsole.codeStats(json.parseToJsonElement(body) as JsonObject) }

    suspend fun resetDevice(driverId: String): Result<Unit> {
        if (!UUID_PATTERN.matches(driverId)) return rejected()
        return SupabaseClient.adminRpc("admin_reset_device", buildJsonObject { put("p_driver", driverId) }).map { }
    }

    // ---- Complaint diagnosis

    suspend fun diagnose(driverId: String): Result<AdminDiagnosis> {
        if (!UUID_PATTERN.matches(driverId)) return rejected()
        return SupabaseClient.adminRpc("admin_offer_telemetry_for_driver", buildJsonObject { put("p_driver", driverId) })
            .mapCatching { body -> AdminDiagnostics.from(json.parseToJsonElement(body) as JsonObject) }
    }

    /** Diagnosis by the support id a driver sends (installation correlation id). */
    suspend fun diagnoseSupportId(supportId: String): Result<AdminDiagnosis> {
        val id = supportId.trim().lowercase()
        if (!AdminDiagnostics.SUPPORT_ID.matches(id)) return rejected()
        return SupabaseClient.adminRpc("admin_offer_telemetry", buildJsonObject { put("p_correlation_id", id) })
            .mapCatching { body -> AdminDiagnostics.from(json.parseToJsonElement(body) as JsonObject).let { it.copy(correlationId = it.correlationId ?: id) } }
    }

    /** The fleet over the last 24 hours: tap latency, the pipeline stages and every reporting device. */
    suspend fun overview(): Result<AdminOverview> = SupabaseClient.adminRpc("admin_offer_telemetry_overview").mapCatching { body ->
        val root = json.parseToJsonElement(body) as JsonObject
        val response = root["response_ms"] as? JsonObject
        AdminOverview(
            p50Ms = (response?.get("p50") as? JsonPrimitive)?.doubleOrNull?.let { Math.round(it) },
            p90Ms = (response?.get("p90") as? JsonPrimitive)?.doubleOrNull?.let { Math.round(it) },
            samples = response?.int("samples") ?: 0,
            funnel = AdminDiagnostics.funnelRows(root["funnel"] as? JsonArray).take(30),
            devices = (root["devices"] as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                AdminDevice(row.str("correlation_id"), row.instant("last_seen"), row.str("app_version"), row.str("last_heartbeat"))
            },
        )
    }

    // ---- Activation codes

    suspend fun codes(offset: Int): Result<AdminPageOf<AdminCode>> =
        SupabaseClient.adminRead("activation_codes", "code,days,batch,redeemed_by,revoked,created_at", "created_at.desc", PAGE_SIZE + 1, offset)
            .mapCatching { page ->
                val list = rows(page.body).mapNotNull { row ->
                    AdminCode(row.str("code") ?: return@mapNotNull null, row.int("days"), row.str("batch"),
                        row.str("redeemed_by"), row.bool("revoked") == true, row.instant("created_at"))
                }
                AdminPageOf(list.take(PAGE_SIZE), offset, hasMore = list.size > PAGE_SIZE)
            }

    /** Count, days and batch exactly as admin_generate_codes accepts them; null when invalid. */
    fun generationValues(countText: String, days: Int, batchText: String): Triple<Int, Int, String?>? {
        val count = AdminInputs.boundedInt(countText, 1, 500) ?: return null
        val batch = batchText.trim()
        if (days !in CODE_DAYS || batch.length > 80 || batch.any { it.isISOControl() }) return null
        return Triple(count, days, batch.ifEmpty { null })
    }

    suspend fun generateCodes(count: Int, days: Int, batch: String?): Result<List<String>> {
        if (count !in 1..500 || days !in CODE_DAYS || (batch != null && (batch.length > 80 || batch.any { it.isISOControl() }))) return rejected()
        return SupabaseClient.adminRpc("admin_generate_codes", buildJsonObject {
            put("p_days", days); put("p_count", count); put("p_batch", batch)
        }).mapCatching { body ->
            (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonPrimitive)?.contentOrNull ?: (element as? JsonObject)?.values?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
        }
    }

    sealed interface ExportResult {
        data class Ready(val csv: String, val count: Int) : ExportResult
        data object Empty : ExportResult
        data object TooMany : ExportResult
        data object Incomplete : ExportResult
    }

    /**
     * Every available code, or nothing: the server's exact count must match the rows received,
     * so a capped or truncated response never becomes a partial file.
     */
    suspend fun exportAvailableCodes(): Result<ExportResult> =
        SupabaseClient.adminRead("activation_codes", "code,days,batch", "created_at.desc", MAX_EXPORT_ROWS + 1,
            filters = mapOf("redeemed_by" to "is.null", "revoked" to "eq.false"), exactCount = true)
            .mapCatching { page ->
                val total = page.total ?: return@mapCatching ExportResult.Incomplete
                if (total > MAX_EXPORT_ROWS) return@mapCatching ExportResult.TooMany
                val list = rows(page.body).mapNotNull { row -> AdminExportCode(row.str("code") ?: return@mapNotNull null, row.int("days"), row.str("batch")) }
                when {
                    list.size.toLong() != total -> ExportResult.Incomplete
                    list.isEmpty() -> ExportResult.Empty
                    else -> ExportResult.Ready(AdminInputs.codesCsv(list), list.size)
                }
            }

    // ---- Commercial policy

    /** The confirmed policy, or a failure: a reply missing any field never fills the form. */
    suspend fun policy(): Result<AdminPolicy> = SupabaseClient.adminRpc("admin_commercial_policy").mapCatching { body ->
        parsePolicy(json.parseToJsonElement(body) as? JsonObject) ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
    }

    internal fun parsePolicy(row: JsonObject?): AdminPolicy? {
        row ?: return null
        val trialEnabled = row.bool("trial_enabled") ?: return null
        val trialDays = row.int("trial_days")?.takeIf { it in 1..365 } ?: return null
        val price = (row["price_minor"] as? JsonPrimitive)?.longOrNull?.takeIf { it in AdminInputs.MIN_MINOR..AdminInputs.MAX_MINOR } ?: return null
        val days = row.int("subscription_days")?.takeIf { it in 1..365 } ?: return null
        if (row.str("currency") != "SAR") return null
        val contact = row["purchase_contact_url"]
        val contactUrl = when {
            contact == null || contact is JsonNull -> null
            else -> row.str("purchase_contact_url")?.takeIf(AdminInputs::isHttpsContact) ?: return null
        }
        val updatedAt = row.instant("updated_at") ?: return null
        return AdminPolicy(trialEnabled, trialDays, price, days, contactUrl,
            row.str("admin_whatsapp")?.takeIf(AdminInputs.WHATSAPP::matches), updatedAt)
    }

    /**
     * Saves the policy in one server operation, then the WhatsApp number in its own; the caller
     * reloads afterwards so the form only shows values the server confirmed.
     */
    suspend fun savePolicy(policy: AdminPolicy): Result<Unit> {
        if (policy.trialDays !in 1..365 || policy.subscriptionDays !in 1..365 || policy.priceMinor !in AdminInputs.MIN_MINOR..AdminInputs.MAX_MINOR ||
            (policy.contactUrl != null && !AdminInputs.isHttpsContact(policy.contactUrl)) ||
            (policy.whatsapp != null && !AdminInputs.WHATSAPP.matches(policy.whatsapp))) {
            return rejected()
        }
        val saved = SupabaseClient.adminRpc("admin_set_commercial_policy", buildJsonObject {
            put("p_trial_enabled", policy.trialEnabled)
            put("p_trial_days", policy.trialDays)
            put("p_price_minor", policy.priceMinor)
            put("p_subscription_days", policy.subscriptionDays)
            put("p_purchase_contact_url", policy.contactUrl)
        })
        saved.exceptionOrNull()?.let { return Result.failure(it) }
        if (runCatching { json.parseToJsonElement(saved.getOrThrow()) as? JsonObject }.getOrNull() == null) {
            return Result.failure(FeatureException(FeatureFailure.UNAVAILABLE))
        }
        return SupabaseClient.adminRpc("admin_set_whatsapp", buildJsonObject { put("p_number", policy.whatsapp) })
            .fold({ Result.success(Unit) }, { Result.failure(WhatsappNotSaved(it)) })
    }

    data class ReferralLeader(val name: String, val code: String, val invited: Int, val rewarded: Int)
    data class ReferralSettings(
        val enabled: Boolean, val rewardDays: Int, val claimWindowDays: Int,
        val claims: Int, val rewarded: Int, val rejected: Int, val top: List<ReferralLeader>,
    )

    suspend fun referralSettings(): Result<ReferralSettings> =
        SupabaseClient.adminRpc("admin_referral_settings").mapCatching { parseReferral(it) }

    suspend fun saveReferralSettings(enabled: Boolean, rewardDays: Int, claimWindowDays: Int): Result<ReferralSettings> {
        if (rewardDays !in 1..90 || claimWindowDays !in 1..30) return rejected()
        return SupabaseClient.adminRpc("admin_referral_settings", buildJsonObject {
            put("p_enabled", enabled); put("p_reward_days", rewardDays); put("p_claim_window_days", claimWindowDays)
        }).mapCatching { parseReferral(it) }
    }

    private fun parseReferral(body: String): ReferralSettings {
        val row = json.parseToJsonElement(body) as? JsonObject ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
        return ReferralSettings(
            enabled = row.bool("enabled") ?: throw FeatureException(FeatureFailure.UNAVAILABLE),
            rewardDays = row.int("reward_days") ?: 7, claimWindowDays = row.int("claim_window_days") ?: 7,
            claims = row.int("claims") ?: 0, rewarded = row.int("rewarded") ?: 0, rejected = row.int("rejected") ?: 0,
            top = (row["top"] as? JsonArray).orEmpty().mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                ReferralLeader(o.str("name").orEmpty(), o.str("code").orEmpty(), o.int("invited") ?: 0, o.int("rewarded") ?: 0)
            },
        )
    }

    /** `admin_geocode_settings`: the key itself never comes back, only whether one is set. */
    data class GeocodeSettings(
        val enabled: Boolean,
        val hasKey: Boolean,
        val monthlyCap: Int,
        val driverDailyCap: Int,
        val monthCalls: Int,
        val cachedAddresses: Int,
    )

    /** A Google API key: letters, digits, '-' and '_' (Google keys are 39 characters). */
    val GEOCODE_KEY = Regex("[A-Za-z0-9_-]{20,80}")

    suspend fun geocodeSettings(): Result<GeocodeSettings> =
        SupabaseClient.adminRpc("admin_geocode_settings").mapCatching { parseGeocode(it) }

    suspend fun saveGeocodeSettings(enabled: Boolean, key: String?, monthlyCap: Int, driverDailyCap: Int): Result<GeocodeSettings> {
        if (monthlyCap < 0 || driverDailyCap < 0 || (key != null && !GEOCODE_KEY.matches(key))) return rejected()
        return SupabaseClient.adminRpc("admin_geocode_settings", buildJsonObject {
            put("p_enabled", enabled)
            key?.let { put("p_google_api_key", it) }
            put("p_monthly_cap", monthlyCap)
            put("p_driver_daily_cap", driverDailyCap)
        }).mapCatching { parseGeocode(it) }
    }

    private fun parseGeocode(body: String): GeocodeSettings {
        val row = json.parseToJsonElement(body) as? JsonObject ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
        return GeocodeSettings(
            enabled = row.bool("enabled") ?: throw FeatureException(FeatureFailure.UNAVAILABLE),
            hasKey = row.bool("has_key") ?: false,
            monthlyCap = row.int("monthly_cap") ?: 0,
            driverDailyCap = row.int("driver_daily_cap") ?: 0,
            monthCalls = row.int("month_calls") ?: 0,
            cachedAddresses = row.int("cached_addresses") ?: 0,
        )
    }

    /** `admin_places_settings`: the paid Places step of zone warming and its stored cells. */
    data class PlacesSettings(val enabled: Boolean, val cells: Int)

    /** [enabled] null only reads. */
    suspend fun placesSettings(enabled: Boolean? = null): Result<PlacesSettings> =
        SupabaseClient.adminRpc("admin_places_settings", buildJsonObject { enabled?.let { put("p_enabled", it) } }).mapCatching { body ->
            val row = json.parseToJsonElement(body) as? JsonObject ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
            PlacesSettings(row.bool("places_enabled") ?: throw FeatureException(FeatureFailure.UNAVAILABLE), row.int("cells") ?: 0)
        }

    /** The policy was saved but the WhatsApp number was not confirmed. */
    class WhatsappNotSaved(val reason: Throwable) : Exception("whatsapp_not_saved")

    // ---- Notifications

    private val adminAr: Boolean get() = LocaleManager.isArabic()
    fun notificationKinds() = if (adminAr) linkedMapOf("info" to "معلومة", "warning" to "تنبيه", "success" to "خبر سار", "account" to "الاشتراك", "update" to "تحديث التطبيق")
        else linkedMapOf("info" to "Info", "warning" to "Warning", "success" to "Good news", "account" to "Subscription", "update" to "App update")
    fun notificationActions() = if (adminAr) linkedMapOf("" to "بدون زر", "open_account" to "فتح حسابي", "open_setup" to "إكمال الإعداد",
        "open_filters" to "مراجعة الشروط", "update_app" to "تحديث التطبيق", "open_support" to "فتح محادثة الدعم")
        else linkedMapOf("" to "No button", "open_account" to "Open My Account", "open_setup" to "Complete setup",
        "open_filters" to "Review conditions", "update_app" to "Update the app", "open_support" to "Open support chat")
    fun notificationExpiry() = if (adminAr) linkedMapOf(0 to "لا ينتهي (يُحذف بعد 90 يومًا)", 1 to "يوم", 3 to "3 أيام", 7 to "أسبوع", 30 to "30 يومًا")
        else linkedMapOf(0 to "Does not expire (deleted after 90 days)", 1 to "A day", 3 to "3 days", 7 to "A week", 30 to "30 days")

    suspend fun notifications(): Result<List<AdminNotification>> =
        SupabaseClient.adminRpc("admin_list_notifications", buildJsonObject { put("p_limit", 100) }).mapCatching { body ->
            rows(body).mapNotNull { row ->
                val id = row.str("id") ?: return@mapNotNull null
                AdminNotification(id, row.str("driver_id") == null, row.str("driver_name"), row.str("title").orEmpty(),
                    row.str("kind") ?: "info", row.int("read_count") ?: 0, row.instant("created_at"))
            }
        }

    suspend fun sendNotification(driverId: String?, title: String, body: String, kind: String, action: String, expiryDays: Int): Result<Unit> {
        val cleanTitle = title.trim(); val cleanBody = body.trim()
        if (cleanTitle.length !in 2..80 || cleanBody.length !in 2..500 || kind !in notificationKinds() || action !in notificationActions() ||
            expiryDays !in notificationExpiry() || (driverId != null && !UUID_PATTERN.matches(driverId))) return rejected()
        val expiresAt = expiryDays.takeIf { it > 0 }?.let { Instant.now().plusSeconds(it * 86_400L).toString() }
        return SupabaseClient.adminRpc("admin_send_notification", buildJsonObject {
            put("p_driver", driverId); put("p_title", cleanTitle); put("p_body", cleanBody)
            put("p_kind", kind); put("p_action", action.ifEmpty { null }); put("p_expires_at", expiresAt)
        }).map { }
    }

    suspend fun deleteNotification(id: String): Result<Unit> {
        if (!UUID_PATTERN.matches(id)) return rejected()
        return SupabaseClient.adminRpc("admin_delete_notification", buildJsonObject { put("p_id", id) }).map { }
    }

    // ---- Support inbox

    /** [status] is "open", "closed" or null for every conversation. */
    suspend fun threads(status: String?): Result<List<AdminThread>> {
        if (status != null && status != "open" && status != "closed") return rejected()
        return SupabaseClient.adminRpc("admin_support_threads", buildJsonObject {
            put("p_status", status?.let { JsonPrimitive(it) } ?: JsonNull); put("p_limit", 200)
        }).mapCatching { body ->
            rows(body).mapNotNull { row ->
                val id = row.str("driver_id")?.takeIf(UUID_PATTERN::matches) ?: return@mapNotNull null
                val last = row["last_message"] as? JsonObject
                AdminThread(id, row.str("driver_name") ?: (if (LocaleManager.isArabic()) "سائق" else "Driver"), row.str("driver_phone").orEmpty(), row.str("status") == "closed",
                    row.int("admin_unread")?.coerceAtLeast(0) ?: 0, row.instant("last_message_at"), last?.str("body"), last?.str("sender") == "admin")
            }
        }
    }

    suspend fun conversation(driverId: String): Result<AdminConversation> {
        if (!UUID_PATTERN.matches(driverId)) return rejected()
        return SupabaseClient.adminRpc("admin_support_thread", buildJsonObject { put("p_driver", driverId); put("p_limit", 200) }).mapCatching { body ->
            val root = json.parseToJsonElement(body) as JsonObject
            AdminConversation(
                name = root.str("driver_name") ?: (if (LocaleManager.isArabic()) "سائق" else "Driver"),
                phone = root.str("driver_phone").orEmpty(),
                closed = root.str("status") == "closed",
                appVersion = root.str("last_app_version")?.takeIf(VERSION_NAME::matches),
                correlationId = root.str("last_correlation_id")?.takeIf(AdminDiagnostics.SUPPORT_ID::matches),
                messages = (root["messages"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val row = element as? JsonObject ?: return@mapNotNull null
                    AdminMessage(row.str("sender") == "admin", row.str("body") ?: return@mapNotNull null, row.instant("created_at"))
                },
            )
        }
    }

    /** The client id is created once per typed reply and reused on retry, so a reply never doubles. */
    suspend fun reply(driverId: String, clientId: String, body: String): Result<Unit> {
        if (!UUID_PATTERN.matches(driverId) || body.trim().length !in 1..1000) return rejected()
        return SupabaseClient.adminRpc("admin_support_reply", buildJsonObject {
            put("p_driver", driverId); put("p_client_id", clientId); put("p_body", body.trim())
        }).map { }
    }

    fun newClientId(): String = UUID.randomUUID().toString()

    suspend fun setThreadClosed(driverId: String, closed: Boolean): Result<Unit> {
        if (!UUID_PATTERN.matches(driverId)) return rejected()
        return SupabaseClient.adminRpc("admin_support_set_status", buildJsonObject {
            put("p_driver", driverId); put("p_status", if (closed) "closed" else "open")
        }).map { }
    }

    // ---- Ride-app profiles

    suspend fun profiles(): Result<List<AdminProfile>> =
        SupabaseClient.adminRead("ride_profiles", "id,version,enabled,updated_at,body", "id.asc", 100).mapCatching { page ->
            rows(page.body).mapNotNull { row ->
                AdminProfile(row.str("id") ?: return@mapNotNull null, row.int("version") ?: 0, row.bool("enabled") == true, row.instant("updated_at"),
                    prettyJson.encodeToString(JsonObject.serializer(), row["body"] as? JsonObject ?: JsonObject(emptyMap())))
            }
        }

    /** Same rules as admin_upsert_profile and the app: valid id, required lists, a version above the published one. */
    fun validateProfile(text: String, published: List<AdminProfile>): Result<JsonObject> {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException(if (LocaleManager.isArabic()) "المحتوى ليس JSON صالحًا." else "The content is not valid JSON."))
        val body = parsed as? JsonObject ?: return Result.failure(IllegalArgumentException(if (LocaleManager.isArabic()) "الملف يجب أن يكون كائن JSON." else "The file must be a JSON object."))
        val id = (body["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null || !PROFILE_ID.matches(id)) return Result.failure(IllegalArgumentException(if (LocaleManager.isArabic()) "المعرّف id غير صالح." else "The id is invalid."))
        val version = (body["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (version == null || version < 1 || version > Int.MAX_VALUE) return Result.failure(IllegalArgumentException(if (LocaleManager.isArabic()) "رقم الإصدار version يجب أن يكون عددًا صحيحًا موجبًا." else "The version number must be a positive integer."))
        for (field in listOf("packages", "acceptLabels", "currencyTokens")) {
            val list = body[field] as? JsonArray
            if (list == null || list.none { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.isNotBlank() == true }) {
                return Result.failure(IllegalArgumentException(if (LocaleManager.isArabic()) "الحقل $field يجب أن يكون قائمة غير فارغة." else "The $field field must be a non-empty list."))
            }
        }
        published.firstOrNull { it.id == id }?.let { current ->
            if (version <= current.version) return Result.failure(IllegalArgumentException(if (LocaleManager.isArabic()) "ارفع رقم الإصدار فوق ${current.version}؛ لن يطبّق التطبيق إصدارًا مساويًا أو أقدم." else "Raise the version above ${current.version}; the app will not apply an equal or older version."))
        }
        return Result.success(body)
    }

    suspend fun publishProfile(body: JsonObject, enabled: Boolean): Result<Unit> =
        SupabaseClient.adminRpc("admin_upsert_profile", buildJsonObject { put("p_body", body); put("p_enabled", enabled) }).map { }

    // ---- Parsing

    private fun rows(body: String): List<JsonObject> =
        (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.takeIf { d -> d % 1.0 == 0.0 && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt() }
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.instant(key: String): Instant? = str(key)?.let { raw ->
    runCatching { Instant.parse(raw) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
}

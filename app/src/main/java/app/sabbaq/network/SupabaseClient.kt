package app.sabbaq.network

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import app.sabbaq.BuildConfig
import app.sabbaq.core.OfferTelemetryContract
import app.sabbaq.core.OfferTelemetryEvent
import app.sabbaq.core.TelemetryUploadPolicy
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/** العرض العام الوحيد القادم من الخادم؛ السعر بالهللات ولا يُعامل كدفعة محلية. */
data class PublicOffer(
    val trialEnabled: Boolean,
    val trialDays: Int,
    val priceMinor: Int,
    val currency: String,
    val subscriptionDays: Int,
    val purchaseContactUrl: String? = null,
    /** wa.me digits of the administration's WhatsApp, set from the admin panel. */
    val adminWhatsapp: String? = null,
)

/** لقطة قراءة فقط من آخر `license_status` ناجح للجلسة الذاكرية الحالية. */
data class SubscriptionSnapshot(
    val state: LicenseState,
    val serverTime: Instant?,
    val validUntil: Instant?,
    val trialUntil: Instant?,
    val proUntil: Instant?,
    val trialEligible: Boolean?,
    val trialEnabled: Boolean?,
    val trialDays: Int?
)

/**
 * عميل API عديم الحالة الدائمة: لا تُخزَّن رموز الوصول أو معرّفات الأجهزة على القرص.
 * كل عملية حساسة تلتقط لقطة متسقة من الجلسة كي لا تستخدم بيانات جلسة سبقتها عملية دخول أخرى.
 */
object SupabaseClient {
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val ADMIN_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_TOKEN_LENGTH = 8 * 1024
    private const val MAX_ANON_KEY_LENGTH = 8 * 1024
    private const val MAX_EMAIL_LENGTH = 254
    private const val MAX_NAME_LENGTH = 160
    private const val ACCOUNT_DELETION_CONFIRMATION = "DELETE"
    private const val MAX_PHONE_LENGTH = 64
    private const val MAX_CODE_LENGTH = 128
    private const val MIN_OFFER_DAYS = 1
    private const val MAX_OFFER_DAYS = 365
    // حد عرض دفاعي مطابق لسياسة الخادم: لا يقبل العميل قيمة سالبة/صفرية أو غير معقولة.
    private const val MIN_PRICE_MINOR = 1
    private const val MAX_PRICE_MINOR = 100_000_000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        // إعادة محاولة POST بعد انقطاع الاتصال قد تكرر التسجيل أو استبدال كود التفعيل.
        .retryOnConnectionFailure(false)
        // لا تُمرَّر ترويسة Authorization إلى عنوان إعادة توجيه غير متوقع.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { isLenient = false }
    private val sessions = AuthSessionStore(MonotonicClock { SystemClock.elapsedRealtime() })

    // Session renewal. Supabase access tokens live about an hour; without renewal monitoring
    // stopped itself an hour after sign-in. The refresh token rotates on every renewal.
    @Volatile private var refreshToken: String? = null
    @Volatile private var accessIssuedAt: Long = 0L
    @Volatile private var appContext: Context? = null
    private val renewLock = kotlinx.coroutines.sync.Mutex()
    private const val RENEW_AFTER_MILLIS = 40L * 60L * 1000L

    /** Called once from Application so the client can keep the session across process restarts. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * توافق مؤقت مع مستهلكي الواجهة الحاليين. القيمة تُقرأ من الذاكرة ولا يمكن تعديلها
     * من خارج العميل؛ يجب أن تهاجر الواجهة إلى [hasRedeemableSession] بدلًا من قراءة الرمز.
     */
    @Deprecated("استخدم hasRedeemableSession() ولا تمرر رموز الجلسة بين المكونات")
    val currentToken: String
        get() = sessions.snapshot()?.token.orEmpty()

    /** معرّف الجهاز المقترن بالجلسة الذاكرية فقط، وليس معرّفًا محفوظًا. */
    @Deprecated("استخدم hasRedeemableSession()")
    val currentDeviceId: String
        get() = sessions.snapshot()?.deviceId.orEmpty()

    fun hasRedeemableSession(): Boolean = sessions.snapshot()?.deviceId != null

    /** True when the last license answer named a minimum version newer than this build. */
    @Volatile var appUpdateRequired: Boolean = false
        private set

    /**
     * A stable, non-reversible key for the signed-in account (SHA-256 of the token's `sub`), so
     * device-local data such as the notification inbox is never shown to another account. Read
     * locally from the current token; null without a session.
     */
    fun accountKey(): String? {
        val token = sessions.snapshot()?.token ?: return null
        val payload = token.split('.').getOrNull(1) ?: return null
        val subject = runCatching {
            val claims = json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')), Charsets.UTF_8))
            (claims as? JsonObject)?.get("sub")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return java.security.MessageDigest.getInstance("SHA-256").digest(subject.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    /** فحص محلي فقط؛ لا ينفّذ اتصالًا من مسار أحداث إمكانية الوصول. */
    fun hasValidLicense(): Boolean = sessions.hasValidLicense()

    /** Uploads only the bounded metadata contract; requires the existing authenticated session. */
    suspend fun uploadOfferTelemetry(events: List<OfferTelemetryEvent>): TelemetryUploadPolicy.Outcome {
        // Only KeeperService renews the access token; with monitoring off it would expire after
        // an hour and telemetry about exactly that state could never be uploaded.
        if (refreshToken != null && SystemClock.elapsedRealtime() - accessIssuedAt > RENEW_AFTER_MILLIS) renewAccessToken()
        val session = sessions.snapshot() ?: return TelemetryUploadPolicy.Outcome.NO_SESSION
        if (events.isEmpty() || events.size > OfferTelemetryContract.MAX_BATCH_EVENTS) return TelemetryUploadPolicy.Outcome.INVALID_BATCH
        val payload = json.encodeToString(events)
        if (payload.toByteArray().size > 200_000) return TelemetryUploadPolicy.Outcome.INVALID_BATCH
        val result = networkCall {
            val request = authenticatedRequest(endpoint("rest/v1/rpc/ingest_offer_telemetry"), session.token,
                buildJsonObject { put("p_events", json.parseToJsonElement(payload)) }.toString().toRequestBody(jsonMediaType))
            val response = execute(request)
            Result.success(when {
                response.isSuccessful -> TelemetryUploadPolicy.Outcome.ACCEPTED
                // 400/422: the server's contract refused the payload; retrying unchanged cannot help.
                response.code == 400 || response.code == 422 -> TelemetryUploadPolicy.Outcome.INVALID_BATCH
                response.code == 401 || response.code == 403 -> TelemetryUploadPolicy.Outcome.NO_SESSION
                else -> TelemetryUploadPolicy.Outcome.TRANSIENT_FAILURE
            })
        }
        return result.getOrDefault(TelemetryUploadPolicy.Outcome.TRANSIENT_FAILURE)
    }

    /**
     * Enabled ride-app profiles published by the administration (RLS: authenticated, enabled
     * only). Returned as a `{"profiles":[...]}` bundle for [app.sabbaq.core.Profiles.decode], which
     * drops anything unusable, so a bad row can never disable bundled parsing.
     */
    suspend fun fetchRideProfiles(): Result<String> {
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        return networkCall {
            val url = endpoint("rest/v1/ride_profiles").newBuilder()
                .addQueryParameter("select", "body")
                .addQueryParameter("enabled", "eq.true")
                .build()
            val response = execute(requestBuilder(url).header("Authorization", "Bearer ${session.token}").get().build())
            if (response.code == 401) return@networkCall Result.failure(ClientRequestException("انتهت الجلسة"))
            if (!response.isSuccessful) return@networkCall httpFailure("قراءة ملفات التطبيقات", response)
            val rows = json.parseToJsonElement(response.body) as? JsonArray
                ?: return@networkCall Result.failure(ClientRequestException("استجابة ملفات التطبيقات غير صالحة"))
            val bodies = JsonArray(rows.mapNotNull { (it as? JsonObject)?.get("body") as? JsonObject })
            Result.success(buildJsonObject { put("profiles", bodies) }.toString())
        }
    }

    /** The signed-in driver's notifications (own and broadcasts) as the raw `my_notifications` array. */
    suspend fun fetchNotifications(): Result<String> = supportCall("rpc/my_notifications", buildJsonObject { put("p_limit", 50) }) { response ->
        if (response.isSuccessful) Result.success(response.body) else Result.failure(FeatureException.fromStatus(response.code))
    }

    /** Sends read receipts; ids the server does not show to this driver are ignored there. */
    suspend fun markNotificationsRead(ids: Collection<String>): Result<Unit> {
        if (ids.isEmpty()) return Result.success(Unit)
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        return networkCall {
            val request = authenticatedRequest(endpoint("rest/v1/rpc/mark_notifications_read"), session.token,
                buildJsonObject { put("p_ids", JsonArray(ids.take(200).map { JsonPrimitive(it) })) }.toString().toRequestBody(jsonMediaType))
            val response = execute(request)
            if (!response.isSuccessful) return@networkCall httpFailure("تحديث الإشعارات", response)
            Result.success(Unit)
        }
    }

    /** The signed-in driver's support conversation (`support_my_thread`); reading it clears the reply badge. */
    suspend fun fetchSupportThread(): Result<String> = supportCall("rpc/support_my_thread", buildJsonObject { put("p_limit", 100) }) { response ->
        if (response.isSuccessful) Result.success(response.body) else Result.failure(FeatureException.fromStatus(response.code))
    }

    /** Unread administration replies, for the top-bar badge; marks nothing read. */
    suspend fun fetchSupportUnread(): Result<Int> = supportCall("rpc/support_unread", buildJsonObject { }) { response ->
        if (!response.isSuccessful) Result.failure(FeatureException.fromStatus(response.code))
        else response.body.trim().toIntOrNull()?.let { Result.success(it.coerceAtLeast(0)) }
            ?: Result.failure(FeatureException(FeatureFailure.UNAVAILABLE))
    }

    enum class SupportSendResult { SENT, RATE_LIMITED, REJECTED, RETRY }

    enum class FeatureFailure { OFFLINE, SESSION, UNAVAILABLE, REJECTED }

    class FeatureException(val kind: FeatureFailure) : Exception(kind.name) {
        companion object {
            fun fromStatus(code: Int): FeatureException = FeatureException(when (code) {
                401, 403 -> FeatureFailure.SESSION
                400, 409, 422 -> FeatureFailure.REJECTED
                else -> FeatureFailure.UNAVAILABLE
            })
        }
    }

    fun featureFailureOf(error: Throwable?): FeatureFailure =
        (error as? FeatureException)?.kind ?: FeatureFailure.UNAVAILABLE

    /**
     * Sends one support message. Idempotent on [clientId], so a retry after a lost response is
     * safe. REJECTED means the server refused the content (retrying unchanged cannot help);
     * RETRY covers network and server faults.
     */
    suspend fun sendSupportMessage(clientId: String, body: String, appVersion: String?, correlationId: String?): SupportSendResult {
        val payload = buildJsonObject {
            put("p_client_id", clientId)
            put("p_body", body)
            appVersion?.let { put("p_app_version", it) }
            correlationId?.let { put("p_correlation_id", it) }
        }
        return supportCall("rpc/support_send", payload) { response ->
            Result.success(when {
                response.isSuccessful -> SupportSendResult.SENT
                response.code == 429 -> SupportSendResult.RATE_LIMITED
                response.code == 400 || response.code == 422 -> SupportSendResult.REJECTED
                else -> SupportSendResult.RETRY
            })
        }.getOrDefault(SupportSendResult.RETRY)
    }

    /**
     * Calls for the notification and support layers. Failures carry a [FeatureFailure] so the
     * screens can say what actually happened (no internet, session, service) instead of always
     * blaming the connection. An expired access token is renewed once and the call retried.
     */
    private suspend fun <T> supportCall(path: String, body: JsonObject, handle: (HttpResponse) -> Result<T>): Result<T> =
        featureCall({ token -> authenticatedRequest(endpoint("rest/v1/$path"), token, body.toString().toRequestBody(jsonMediaType)) }, handle = handle)

    private suspend fun <T> featureCall(build: (String) -> Request, maxBytes: Int = MAX_RESPONSE_BYTES, handle: (HttpResponse) -> Result<T>): Result<T> {
        if (refreshToken != null && SystemClock.elapsedRealtime() - accessIssuedAt > RENEW_AFTER_MILLIS) renewAccessToken()
        repeat(2) { attempt ->
            val session = sessions.snapshot() ?: return Result.failure(FeatureException(FeatureFailure.SESSION))
            val response = withContext(Dispatchers.IO) {
                try {
                    execute(build(session.token), maxBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: ResponseTooLargeException) {
                    HttpResponse(599, "")
                } catch (_: IOException) {
                    null
                } catch (_: Exception) {
                    HttpResponse(599, "")
                }
            } ?: return Result.failure(FeatureException(FeatureFailure.OFFLINE))
            if (response.code == 401 && attempt == 0 && refreshToken != null && renewAccessToken()) return@repeat
            return if (response.code == 401) Result.failure(FeatureException(FeatureFailure.SESSION)) else handle(response)
        }
        return Result.failure(FeatureException(FeatureFailure.SESSION))
    }

    private val ADMIN_NAME = Regex("^[a-z_]{3,48}$")

    /**
     * An administration RPC for the native admin screens. The server's require_admin() decides;
     * the body comes back raw for the admin repository to parse.
     */
    suspend fun adminRpc(function: String, body: JsonObject = buildJsonObject { }): Result<String> {
        if (!ADMIN_NAME.matches(function)) return Result.failure(FeatureException(FeatureFailure.REJECTED))
        return featureCall({ token -> authenticatedRequest(endpoint("rest/v1/rpc/$function"), token, body.toString().toRequestBody(jsonMediaType)) }, ADMIN_RESPONSE_BYTES) { response ->
            if (response.isSuccessful) Result.success(response.body) else Result.failure(FeatureException.fromStatus(response.code))
        }
    }

    /** Rows of one admin read plus, when asked for, the exact number of matching rows on the server. */
    data class AdminPage(val body: String, val total: Long?)

    /**
     * A read of an admin-visible table (RLS lets administrators select). [filters] are PostgREST
     * column filters such as `revoked=eq.false`; [exactCount] asks the server for the full count
     * so a capped response can never pass for a complete one.
     */
    suspend fun adminRead(
        table: String,
        select: String,
        order: String,
        limit: Int,
        offset: Int = 0,
        filters: Map<String, String> = emptyMap(),
        exactCount: Boolean = false,
    ): Result<AdminPage> {
        if (!ADMIN_NAME.matches(table) || !Regex("^[a-z_,]{2,200}$").matches(select) || !Regex("^[a-z_]+\\.(asc|desc)$").matches(order) ||
            filters.any { (column, condition) -> !ADMIN_NAME.matches(column) || !Regex("^(eq|is)\\.[a-z0-9_]{1,40}$").matches(condition) }) {
            return Result.failure(FeatureException(FeatureFailure.REJECTED))
        }
        return featureCall({ token ->
            val url = endpoint("rest/v1/$table").newBuilder()
                .addQueryParameter("select", select)
                .apply { filters.forEach { (column, condition) -> addQueryParameter(column, condition) } }
                .addQueryParameter("order", order)
                .addQueryParameter("limit", limit.coerceIn(1, 1001).toString())
                .addQueryParameter("offset", offset.coerceAtLeast(0).toString())
                .build()
            requestBuilder(url).header("Authorization", "Bearer $token")
                .apply { if (exactCount) header("Prefer", "count=exact") }
                .get().build()
        }, ADMIN_RESPONSE_BYTES) { response ->
            if (!response.isSuccessful) return@featureCall Result.failure(FeatureException.fromStatus(response.code))
            val total = response.contentRange?.substringAfter('/', "")?.toLongOrNull()
            if (exactCount && total == null) Result.failure(FeatureException(FeatureFailure.UNAVAILABLE))
            else Result.success(AdminPage(response.body, total))
        }
    }

    /**
     * Whether the signed-in account is an administrator right now. The native admin screen asks
     * once on entry; every admin call is still checked by the server's require_admin().
     */
    suspend fun adminAccess(): Result<Boolean> {
        val snapshot = sessions.snapshot() ?: return Result.failure(FeatureException(FeatureFailure.SESSION))
        return adminRpc("is_admin").mapCatching { body ->
            if (sessions.snapshot()?.generation != snapshot.generation) throw FeatureException(FeatureFailure.SESSION)
            json.parseToJsonElement(body).jsonPrimitive.booleanOrNull ?: throw FeatureException(FeatureFailure.UNAVAILABLE)
        }
    }

    /** يمكّن مسار تسجيل خروج مستقبلي من إبطال الجلسة الذاكرية صراحةً. */
    fun clearSession(forget: Boolean = true) {
        sessions.clear()
        refreshToken = null
        if (forget) appContext?.let(SessionVault::clear)
    }

    /** True when a saved session exists that [restoreSession] can try to resume. */
    fun hasSavedSession(): Boolean = appContext?.let(SessionVault::exists) == true

    /**
     * Resumes the saved session after a restart: renews the access token with the stored
     * refresh token, rebinds the stored device and checks the license. A refused refresh
     * token (signed out elsewhere, account deleted) wipes the vault.
     */
    suspend fun restoreSession(): Result<Boolean> {
        if (sessions.hasValidLicense()) return Result.success(true)
        val context = appContext ?: return invalidInput("التطبيق لم يجهز بعد")
        return renewMutex {
            // Re-check under the lock: the screen and the service may both try to resume, and a
            // second exchange of an already-rotated refresh token would be refused.
            if (sessions.hasValidLicense()) return@renewMutex Result.success(true)
            val saved = try {
                SessionVault.load(context)
            } catch (unavailable: SessionVault.VaultUnavailableException) {
                return@renewMutex Result.failure(unavailable)
            } ?: return@renewMutex Result.success(false)
            val exchanged = exchangeRefreshToken(saved.refreshToken)
            val pair = exchanged.getOrNull()
            if (pair == null) {
                val failure = exchanged.exceptionOrNull()
                    ?: ClientRequestException("تعذر تجديد الجلسة")
                // A server refusal means the refresh token is dead. Network/server failures
                // are returned to the caller and leave the encrypted vault intact for retry.
                if (failure is RefreshRefusedException) {
                    SessionVault.clear(context)
                    return@renewMutex Result.success(false)
                }
                return@renewMutex Result.failure(failure)
            }
            // Supabase rotates refresh tokens. Persist the new token before the separate
            // license request so a transient license/network failure cannot leave the vault
            // holding an already-consumed refresh token.
            val persisted = withContext(Dispatchers.IO) {
                SessionVault.save(context, pair.second, saved.deviceId)
            }
            if (!persisted) return@renewMutex Result.failure(
                ClientRequestException("تعذر حفظ جلسة الدخول بأمان على هذا الجهاز")
            )
            val attempt = sessions.beginAuthentication()
            if (!sessions.activate(attempt, pair.first)) return@renewMutex staleSessionFailure()
            refreshToken = pair.second
            accessIssuedAt = SystemClock.elapsedRealtime()
            checkLicenseAndBindDevice(pair.first, saved.deviceId)
        }
    }

    private suspend fun <T> renewMutex(block: suspend () -> T): T = renewLock.lock().let {
        try { block() } finally { renewLock.unlock() }
    }

    /** Renews the in-memory access token; the device binding and license lease are kept. */
    private suspend fun renewAccessToken(): Boolean = renewMutex {
        val current = sessions.snapshot() ?: return@renewMutex false
        val stored = refreshToken ?: return@renewMutex false
        val exchanged = exchangeRefreshToken(stored)
        val pair = exchanged.getOrNull()
        if (pair == null) {
            // Refused by the server: revoke everywhere so nothing keeps running on a dead session.
            if (exchanged.exceptionOrNull() is RefreshRefusedException) {
                sessions.clearIfMatches(current.token)
                refreshToken = null
                appContext?.let(SessionVault::clear)
            }
            return@renewMutex false
        }
        if (!sessions.replaceToken(current.token, pair.first)) return@renewMutex false
        refreshToken = pair.second
        accessIssuedAt = SystemClock.elapsedRealtime()
        // Keystore + commit(): never on the caller's thread, which is Main for KeeperService.
        current.deviceId?.let { device -> appContext?.let { withContext(Dispatchers.IO) { SessionVault.save(it, pair.second, device) } } }
        true
    }

    private suspend fun exchangeRefreshToken(token: String): Result<Pair<String, String>> = networkCall {
        val url = endpoint("auth/v1/token").newBuilder().addQueryParameter("grant_type", "refresh_token").build()
        val request = requestBuilder(url)
            .post(buildJsonObject { put("refresh_token", token) }.toString().toRequestBody(jsonMediaType))
            .build()
        val response = execute(request)
        if (response.code in 400..499) {
            return@networkCall if (isPermanentRefreshTokenRejection(response.code)) {
                Result.failure(RefreshRefusedException())
            } else {
                Result.failure(ClientRequestException("تعذر تجديد الجلسة مؤقتًا"))
            }
        }
        if (!response.isSuccessful) return@networkCall Result.failure(ClientRequestException("تعذر تجديد الجلسة"))
        tokenPairFrom(response.body)?.let { Result.success(it) }
            ?: Result.failure(ClientRequestException("تعذر تجديد الجلسة"))
    }

    internal fun isPermanentRefreshTokenRejection(statusCode: Int): Boolean =
        statusCode in 400..499 && statusCode != 408 && statusCode != 425 && statusCode != 429

    /** Saves the session for automatic resume once a device is bound and the license is valid. */
    private fun persistSession(deviceId: String) {
        val context = appContext ?: return
        val token = refreshToken ?: return
        SessionVault.save(context, token, deviceId)
    }

    /**
     * يبدأ Google Credential Manager من Activity فقط، ثم يستبدل Google ID token بجلسة
     * Supabase ويفحص إنشاء ملف السائق قبل كشف رمز الجلسة للمستدعي.
     */
    suspend fun signInWithGoogle(context: Context): Result<String> {
        val webClientId = NetworkInputValidator.googleWebClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            ?: return invalidInput("الدخول باستخدام Google غير متاح في هذا الإصدار. حدّث التطبيق أو جرّب رقم الجوال.")
        val activity = context.findActivity()
            ?: return invalidInput("تسجيل Google يجب أن يبدأ من شاشة التطبيق")
        val attempt = sessions.beginAuthentication()
        val nonce = GoogleNonce.create()
        val googleCredential = try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .setServerClientId(webClientId)
                .setNonce(nonce.sha256)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Result.failure(ClientRequestException("نوع هوية Google غير متطابق. اختر حسابك وحاول مجددًا. [G-CRED-TYPE]"))
            }
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: GetCredentialCancellationException) {
            return Result.failure(ClientRequestException("تم إلغاء تسجيل الدخول باستخدام Google. [G-USER-CANCEL]"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (credEx: GetCredentialException) {
            val type = credEx.type.substringAfterLast('.').ifBlank { "UNKNOWN" }
            return Result.failure(ClientRequestException("تعذر الحصول على هوية Google من خدمات الجهاز. [G-PLAY-$type]"))
        } catch (_: Exception) {
            return Result.failure(ClientRequestException("تعذر بدء تسجيل الدخول باستخدام Google. [G-INIT-FAIL]"))
        }
        val idToken = NetworkInputValidator.token(googleCredential.idToken)
            ?: return invalidInput("رمز هوية Google غير صالح أو منتهي الصلاحية. [G-TOKEN-INVALID]")
        val displayName = NetworkInputValidator.displayName(googleCredential.displayName)

        return networkCall {
            val url = endpoint("auth/v1/token").newBuilder()
                .addQueryParameter("grant_type", "id_token")
                .build()
            val request = requestBuilder(url)
                .post(
                    buildJsonObject {
                        put("provider", "google")
                        put("id_token", idToken)
                        put("nonce", nonce.raw)
                    }.toString().toRequestBody(jsonMediaType)
                )
                .build()
            val response = execute(request)
            if (!response.isSuccessful) {
                return@networkCall httpFailure("تبادل هوية Google", response)
            }
            val token = accessTokenFrom(response.body)
                ?: return@networkCall Result.failure(ClientRequestException("لم يكتمل تسجيل الدخول. حاول مجددًا."))
            val profile = ensureDriverProfile(token, displayName)
            if (profile.isFailure) {
                return@networkCall Result.failure(profile.exceptionOrNull()!!)
            }
            if (!sessions.activate(attempt, token)) return@networkCall staleSessionFailure()
            refreshToken = tokenPairFrom(response.body)?.second
            accessIssuedAt = SystemClock.elapsedRealtime()
            Result.success(token)
        }
    }

    /** يرسل تحدي OTP إلى رقم سعودي بعد تحويله فقط إلى +9665XXXXXXXX. */
    suspend fun requestPhoneOtp(phone: String): Result<Unit> {
        val safePhone = NetworkInputValidator.saudiE164(phone)
            ?: return invalidInput("أدخل رقم جوالك السعودي المكوّن من 10 أرقام ويبدأ بـ05.")
        val attempt = sessions.beginAuthentication()
        return networkCall {
            val request = requestBuilder(endpoint("auth/v1/otp"))
                .post(
                    buildJsonObject {
                        put("phone", safePhone)
                        put("create_user", true)
                    }.toString().toRequestBody(jsonMediaType)
                )
                .build()
            val response = execute(request)
            if (!response.isSuccessful) return@networkCall httpFailure("إرسال رمز التحقق", response)
            if (!sessions.markPhoneOtpRequested(attempt, safePhone)) return@networkCall staleSessionFailure()
            Result.success(Unit)
        }
    }

    /**
     * يتحقق من ستة أرقام فقط ثم ينشئ ملف السائق قبل تفعيل الجلسة الذاكرية.
     * لا يُحفظ الرمز أو nonce أو أي token على القرص.
     */
    suspend fun verifyPhoneOtp(phone: String, code: String): Result<String> {
        val safePhone = NetworkInputValidator.saudiE164(phone)
            ?: return invalidInput("أدخل رقم جوالك السعودي المكوّن من 10 أرقام ويبدأ بـ05.")
        val safeCode = NetworkInputValidator.otpCode(code)
            ?: return invalidInput("رمز التحقق يجب أن يتكون من 6 أرقام")
        val attempt = sessions.phoneOtpAttempt(safePhone)
            ?: return Result.failure(ClientRequestException("اطلب رمز تحقق جديدًا قبل التحقق"))

        return networkCall {
            val request = requestBuilder(endpoint("auth/v1/verify"))
                .post(
                    buildJsonObject {
                        put("type", "sms")
                        put("phone", safePhone)
                        put("token", safeCode)
                    }.toString().toRequestBody(jsonMediaType)
                )
                .build()
            val response = execute(request)
            if (!response.isSuccessful) return@networkCall httpFailure("التحقق من الرمز", response)
            val token = accessTokenFrom(response.body)
                ?: return@networkCall Result.failure(ClientRequestException("لم يكتمل تسجيل الدخول. حاول مجددًا."))
            val profile = ensureDriverProfile(token)
            if (profile.isFailure) {
                return@networkCall Result.failure(profile.exceptionOrNull()!!)
            }
            if (!sessions.activate(attempt, token)) return@networkCall staleSessionFailure()
            refreshToken = tokenPairFrom(response.body)?.second
            accessIssuedAt = SystemClock.elapsedRealtime()
            Result.success(token)
        }
    }

    /** يقرأ سياسة الاشتراك العامة من RPC بلا جلسة ولا يقدم بديلًا محليًا عند الفشل. */
    suspend fun fetchPublicOffer(): Result<PublicOffer> = networkCall {
        val request = requestBuilder(endpoint("rest/v1/rpc/public_offer"))
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        val response = execute(request)
        if (!response.isSuccessful) return@networkCall httpFailure("قراءة عرض الاشتراك", response)
        parsePublicOffer(response.body)?.let { Result.success(it) }
            ?: Result.failure(ClientRequestException("تعذر عرض تفاصيل الاشتراك الآن. حاول مجددًا بعد قليل."))
    }

    /**
     * طرق الدخول المفعّلة على الخادم الآن. تسمح للواجهة بإخفاء طريقة معطّلة (مثل الجوال
     * قبل ربط مزود SMS مدفوع) دون إصدار جديد؛ تفعيلها في Supabase يعيد إظهارها.
     */
    suspend fun fetchAuthProviders(): Result<AuthProviders> = networkCall {
        val response = execute(requestBuilder(endpoint("auth/v1/settings")).get().build())
        if (!response.isSuccessful) return@networkCall httpFailure("قراءة طرق الدخول", response)
        AuthSettingsParser.parse(response.body)?.let { Result.success(it) }
            ?: Result.failure(ClientRequestException("تعذر قراءة طرق الدخول المتاحة."))
    }

    /** آخر لقطة `license_status` تخص رمز الجلسة الموجود في الذاكرة فقط. */
    fun subscriptionSnapshot(): SubscriptionSnapshot? = sessions.subscriptionSnapshot()

    /**
     * يبدأ التجربة على الخادم للجهاز المربوط فقط. الرد `ok` لا يكوّن lease محليًا؛
     * يجب أن يعقبَه `license_status` ناجح، وتكون قيمة النتيجة هي نتيجة إعادة الفحص.
     */
    suspend fun startTrial(): Result<Boolean> {
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        val deviceId = session.deviceId
            ?: return Result.failure(ClientRequestException("أعد تسجيل الدخول قبل بدء تجربتك."))
        return networkCall {
            val request = authenticatedRequest(
                endpoint("rest/v1/rpc/start_trial"),
                session.token,
                buildJsonObject {
                    put("p_device_id", deviceId)
                    put("p_version_code", BuildConfig.VERSION_CODE)
                }.toString().toRequestBody(jsonMediaType)
            )
            val response = execute(request)
            if (response.code == 401) sessions.clearIfMatches(session.token)
            if (!response.isSuccessful) return@networkCall httpFailure("بدء التجربة", response)
            val ok = try {
                responseObject(response.body)["ok"]?.jsonPrimitive?.booleanOrNull == true
            } catch (_: Exception) {
                false
            }
            if (!ok) return@networkCall Result.failure(ClientRequestException("تعذر بدء التجربة"))
            Result.success(Unit)
        }.fold(
            onSuccess = {
                val current = sessions.snapshot()
                if (current?.token != session.token || current.deviceId != deviceId) staleSessionFailure()
                else revalidateLicense()
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * يحذف حساب الجلسة الحالية نهائيًا عبر `delete_my_account`. لا يُرسل الطلب إلا بعد
     * تأكيد صريح في الواجهة، وتُمسح الجلسة الذاكرية بعد نجاح الخادم فقط.
     */
    suspend fun deleteAccount(): Result<Unit> {
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        return networkCall {
            val request = authenticatedRequest(
                endpoint("rest/v1/rpc/delete_my_account"),
                session.token,
                buildJsonObject { put("p_confirmation", ACCOUNT_DELETION_CONFIRMATION) }
                    .toString().toRequestBody(jsonMediaType)
            )
            val response = execute(request)
            if (response.code == 401) sessions.clearIfMatches(session.token)
            if (!response.isSuccessful) return@networkCall httpFailure("حذف الحساب", response)
            val result = responseObject(response.body)
            if (result["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                sessions.clearIfMatches(session.token)
                Result.success(Unit)
            } else {
                Result.failure(ClientRequestException(
                    when (result["error"]?.jsonPrimitive?.contentOrNull) {
                        "admin_account" -> "حسابات الإدارة لا تُحذف من التطبيق؛ تواصل مع مسؤول النظام."
                        "not_found" -> "لم يعد هذا الحساب موجودًا."
                        else -> "تعذر حذف الحساب الآن. حاول مجددًا بعد قليل."
                    }
                ))
            }
        }
    }

    /** هوية الدخول للعرض فقط، مقنّعة قبل مغادرة العميل الشبكي ولا تُخزَّن. */
    /** What the account page shows about who is signed in; [identity] is already masked. */
    data class AccountProfile(val identity: String, val displayName: String?, val viaGoogle: Boolean)

    suspend fun accountIdentity(): Result<AccountProfile> {
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        return networkCall {
            val request = requestBuilder(endpoint("auth/v1/user"))
                .header("Authorization", "Bearer ${session.token}")
                .get()
                .build()
            val response = execute(request)
            if (response.code == 401) sessions.clearIfMatches(session.token)
            if (!response.isSuccessful) return@networkCall httpFailure("قراءة بيانات الحساب", response)
            val user = responseObject(response.body)
            val label = AccountIdentityMask.phone(user["phone"]?.jsonPrimitive?.contentOrNull)
                ?: AccountIdentityMask.email(user["email"]?.jsonPrimitive?.contentOrNull)
                ?: return@networkCall Result.failure(ClientRequestException("لا تتوفر بيانات دخول لعرضها."))
            val metadata = user["user_metadata"] as? JsonObject
            val name = (metadata?.get("full_name") ?: metadata?.get("name"))?.jsonPrimitive?.contentOrNull
                ?.trim()?.filter { !it.isISOControl() }?.take(40)?.takeIf { it.length >= 2 }
            val provider = (user["app_metadata"] as? JsonObject)?.get("provider")?.jsonPrimitive?.contentOrNull
            Result.success(AccountProfile(label, name, viaGoogle = provider == "google"))
        }
    }

    suspend fun isAdmin(token: String): Result<Boolean> {
        val safeToken = NetworkInputValidator.token(token)
            ?: return invalidInput("رمز الجلسة غير صالح")

        return networkCall {
            val request = authenticatedRequest(
                endpoint("rest/v1/rpc/is_admin"),
                safeToken,
                "{}".toRequestBody(jsonMediaType)
            )
            val response = execute(request)
            if (response.code == 401) sessions.clearIfMatches(safeToken)
            if (!response.isSuccessful) {
                return@networkCall httpFailure("التحقق من صلاحية المشرف", response)
            }

            val value = try {
                json.parseToJsonElement(response.body).jsonPrimitive.booleanOrNull
            } catch (_: Exception) {
                null
            }
            if (value == null) {
                Result.failure(ClientRequestException("استجابة التحقق من صلاحية المشرف غير صالحة"))
            } else {
                Result.success(value)
            }
        }
    }

    /**
     * يفحص حالة الترخيص ويربط الجهاز المرشح في الذاكرة فقط. تعني false الناجحة أن
     * الجلسة قابلة للاسترداد (pending/expired)، لا أنها مرخّصة.
     */
    suspend fun checkLicenseAndBindDevice(token: String, deviceId: String): Result<Boolean> {
        val safeToken = NetworkInputValidator.token(token)
            ?: return invalidInput("رمز الجلسة غير صالح")
        val safeDeviceId = NetworkInputValidator.deviceId(deviceId)
            ?: return invalidInput("معرّف الجهاز غير صالح")
        if (!sessions.isCurrentToken(safeToken)) {
            return Result.failure(ClientRequestException("انتهت الجلسة أو تغيّرت؛ سجّل الدخول من جديد"))
        }

        return networkCall {
            val requestStartedAt = sessions.monotonicNow()
            val request = authenticatedRequest(
                endpoint("rest/v1/rpc/license_status"),
                safeToken,
                buildJsonObject {
                    put("p_device_id", safeDeviceId)
                    put("p_version_code", BuildConfig.VERSION_CODE)
                }.toString().toRequestBody(jsonMediaType)
            )
            val response = execute(request)
            if (response.code == 401) sessions.clearIfMatches(safeToken)
            if (!response.isSuccessful) {
                return@networkCall httpFailure("فحص الترخيص", response)
            }

            handleLicenseStatus(
                token = safeToken,
                candidateDeviceId = safeDeviceId,
                body = response.body,
                requestStartedAt = requestStartedAt
            ).also { if (it.getOrNull() == true) persistSession(safeDeviceId) }
        }
    }

    /**
     * Moves the signed-in account to this device (self-service, limited by the server to
     * two moves per 30 days). The caller re-checks the license afterwards.
     */
    suspend fun transferDevice(token: String, deviceId: String): Result<Unit> {
        val safeToken = NetworkInputValidator.token(token)
            ?: return invalidInput("رمز الجلسة غير صالح")
        val safeDeviceId = NetworkInputValidator.deviceId(deviceId)
            ?: return invalidInput("معرّف الجهاز غير صالح")
        if (!sessions.isCurrentToken(safeToken)) {
            return Result.failure(ClientRequestException("انتهت الجلسة أو تغيّرت؛ سجّل الدخول من جديد"))
        }
        return networkCall {
            val request = authenticatedRequest(
                endpoint("rest/v1/rpc/transfer_device"),
                safeToken,
                buildJsonObject { put("p_device_id", safeDeviceId) }.toString().toRequestBody(jsonMediaType)
            )
            val response = execute(request)
            if (response.code == 401) sessions.clearIfMatches(safeToken)
            if (!response.isSuccessful) return@networkCall httpFailure("نقل الحساب", response)
            val result = responseObject(response.body)
            if (result["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                Result.success(Unit)
            } else {
                when (result["error"]?.jsonPrimitive?.contentOrNull) {
                    "transfer_limit" -> Result.failure(DeviceTransferLimitException(
                        result["retry_after"]?.jsonPrimitive?.contentOrNull?.take(10)
                    ))
                    "account_blocked" -> Result.failure(ClientRequestException("تم حظر أو رفض الحساب"))
                    else -> Result.failure(ClientRequestException("تعذر نقل الحساب الآن. حاول مجددًا بعد قليل."))
                }
            }
        }
    }

    /**
     * يعيد الفحص باستعمال جلسة الجهاز الحالية؛ تستدعيه الواجهة/نبض الخدمة فقط.
     * لا يستدعي [hasValidLicense] هذه الدالة مطلقًا.
     */
    suspend fun revalidateLicense(): Result<Boolean> {
        // Renew before the access token ages out, and once more if the server already refused it.
        if (refreshToken != null && SystemClock.elapsedRealtime() - accessIssuedAt > RENEW_AFTER_MILLIS) renewAccessToken()
        val first = revalidateOnce()
        if (first.exceptionOrNull() is SessionExpiredException) {
            if (renewAccessToken()) return revalidateOnce()
            // The server rejected the access token and it could not be renewed: fail closed.
            sessions.snapshot()?.let { sessions.clearIfMatches(it.token) }
        }
        return first
    }

    private suspend fun revalidateOnce(): Result<Boolean> {
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        val deviceId = session.deviceId
            ?: return Result.failure(ClientRequestException("تحقق من الترخيص والجهاز قبل المتابعة"))

        return networkCall {
            val requestStartedAt = sessions.monotonicNow()
            val request = authenticatedRequest(
                endpoint("rest/v1/rpc/license_status"),
                session.token,
                buildJsonObject {
                    put("p_device_id", deviceId)
                    put("p_version_code", BuildConfig.VERSION_CODE)
                }.toString().toRequestBody(jsonMediaType)
            )
            val response = execute(request)
            if (response.code == 401) {
                // Renewable: keep the session for one refresh attempt; drop it only if that fails.
                if (refreshToken != null) return@networkCall Result.failure(SessionExpiredException())
                sessions.clearIfMatches(session.token)
            }
            if (!response.isSuccessful) {
                return@networkCall httpFailure("إعادة التحقق من الترخيص", response)
            }
            handleLicenseStatus(
                token = session.token,
                candidateDeviceId = deviceId,
                body = response.body,
                requestStartedAt = requestStartedAt
            ).also { if (it.getOrNull() == true) persistSession(deviceId) }
        }
    }

    suspend fun redeemCode(code: String): Result<Boolean> {
        val safeCode = NetworkInputValidator.activationCode(code)
            ?: return invalidInput("رمز التفعيل غير صالح")
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        val deviceId = session.deviceId
            ?: return Result.failure(ClientRequestException("تحقق من الترخيص والجهاز قبل إدخال رمز التفعيل"))

        return networkCall {
            val request = authenticatedRequest(
                endpoint("rest/v1/rpc/redeem_code"),
                session.token,
                buildJsonObject {
                    put("p_code", safeCode)
                    put("p_device_id", deviceId)
                }.toString().toRequestBody(jsonMediaType)
            )
            val response = execute(request)
            if (response.code == 401) sessions.clearIfMatches(session.token)
            if (!response.isSuccessful) {
                return@networkCall httpFailure("تفعيل الرمز", response)
            }

            val result = responseObject(response.body)
            if (result["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                if (sessions.invalidateLicenseIfMatches(session.token)) Result.success(true)
                else staleSessionFailure()
            } else {
                when (result["error"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT)) {
                    "invalid_code" -> Result.failure(ClientRequestException("الرمز غير صحيح أو استُخدم مسبقًا"))
                    "rate_limited" -> Result.failure(ClientRequestException("محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجددًا"))
                    "device_mismatch" -> Result.failure(ClientRequestException("هذا الحساب مرتبط بجهاز آخر"))
                    "unregistered" -> Result.failure(licenseStateFailure(LicenseState.UNREGISTERED))
                    else -> Result.failure(ClientRequestException("تعذر تفعيل الرمز"))
                }
            }
        }
    }

    /** RPC واحد مشترك لكل هويات العملاء الجديدة؛ لا يفعّل الجلسة أو الرخصة بذاته. */
    private fun ensureDriverProfile(token: String, displayName: String? = null): Result<Unit> {
        val request = authenticatedRequest(
            endpoint("rest/v1/rpc/ensure_driver_profile"),
            token,
            buildJsonObject {
                displayName?.let { put("p_display_name", it) }
            }.toString().toRequestBody(jsonMediaType)
        )
        val response = execute(request)
        if (response.code == 401) sessions.clearIfMatches(token)
        if (!response.isSuccessful) return httpFailure("إعداد ملف السائق", response)
        return Result.success(Unit)
    }

    private fun parsePublicOffer(body: String): PublicOffer? {
        return try {
        val response = responseObject(body)
        val trialEnabled = response["trial_enabled"]?.jsonPrimitive?.booleanOrNull ?: return null
        val trialDays = response["trial_days"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it in MIN_OFFER_DAYS..MAX_OFFER_DAYS } ?: return null
        val priceMinor = response["price_minor"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it in MIN_PRICE_MINOR..MAX_PRICE_MINOR } ?: return null
        val currency = response["currency"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it == "SAR" } ?: return null
        val subscriptionDays = response["subscription_days"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it in MIN_OFFER_DAYS..MAX_OFFER_DAYS } ?: return null
        val purchaseContactUrl = response["purchase_contact_url"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (purchaseContactUrl != null && !NetworkInputValidator.httpsUrl(purchaseContactUrl)) return null
        // An invalid number is dropped rather than failing the whole offer.
        val adminWhatsapp = response["admin_whatsapp"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { Regex("^[1-9][0-9]{7,14}$").matches(it) }
        PublicOffer(
            trialEnabled = trialEnabled,
            trialDays = trialDays,
            priceMinor = priceMinor,
            currency = currency,
            subscriptionDays = subscriptionDays,
            purchaseContactUrl = purchaseContactUrl,
            adminWhatsapp = adminWhatsapp,
        )
    } catch (_: Exception) {
        null
    }
    }

    private fun requestBuilder(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("apikey", apiKey())
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")

    private fun authenticatedRequest(url: HttpUrl, token: String, body: okhttp3.RequestBody): Request =
        requestBuilder(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

    private fun endpoint(path: String): HttpUrl {
        val base = NetworkInputValidator.baseUrl(BuildConfig.SUPABASE_URL)
            ?: throw ClientRequestException("إعداد عنوان الخدمة غير صالح")
        return base.newBuilder().addPathSegments(path).build()
    }

    private fun apiKey(): String = NetworkInputValidator.headerSecret(BuildConfig.SUPABASE_ANON_KEY, MAX_ANON_KEY_LENGTH)
        ?: throw ClientRequestException("إعداد مفتاح الخدمة غير صالح")

    private fun tokenPairFrom(body: String): Pair<String, String>? = try {
        val response = responseObject(body)
        val source = response["session"]?.jsonObject ?: response
        val access = source["access_token"]?.jsonPrimitive?.contentOrNull?.let(NetworkInputValidator::token)
        val refresh = source["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.length in 8..512 && it.none(Char::isWhitespace) }
        if (access != null && refresh != null) access to refresh else null
    } catch (_: Exception) {
        null
    }

    private fun accessTokenFrom(body: String): String? {
        val response = try {
            responseObject(body)
        } catch (_: Exception) {
            return null
        }
        val token = try {
            response["session"]?.jsonObject?.get("access_token")?.jsonPrimitive?.contentOrNull
                ?: response["access_token"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
        return token?.let(NetworkInputValidator::token)
    }

    private fun handleLicenseStatus(
        token: String,
        candidateDeviceId: String,
        body: String,
        requestStartedAt: Long
    ): Result<Boolean> {
        val status = parseLicenseStatus(body)
            ?: return Result.failure(ClientRequestException("استجابة حالة الترخيص غير صالحة"))
        if (!sessions.updateSubscriptionIfMatches(token, status)) return staleSessionFailure()
        appUpdateRequired = status.minimumVersionCode?.let { it > BuildConfig.VERSION_CODE } == true

        if (!status.state.canBindCandidateDevice) {
            sessions.invalidateLicenseIfMatches(token)
            return Result.failure(licenseStateFailure(status.state))
        }
        if (!sessions.bindDevice(token, candidateDeviceId)) return staleSessionFailure()

        return when (status.state) {
            LicenseState.PENDING, LicenseState.EXPIRED -> {
                sessions.invalidateLicenseIfMatches(token)
                Result.success(false)
            }
            LicenseState.ACTIVE, LicenseState.TRIAL -> {
                val valid = sessions.replaceLicenseIfMatches(
                    token = token,
                    status = status,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    requestStartedAt = requestStartedAt
                ) ?: return staleSessionFailure()
                if (valid) Result.success(true) else Result.failure(licenseResponseFailure(status))
            }
            else -> error("الحالة غير القابلة للربط فُحصت أعلاه")
        }
    }

    private fun parseLicenseStatus(body: String): LicenseStatus? {
        return try {
            val response = responseObject(body)
            val state = response["state"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.lowercase(Locale.ROOT)
                ?.let(LicenseState::fromWire)
                ?: return null
            LicenseStatus(
                state = state,
                serverTime = response.instantOrNull("server_time"),
                validUntil = response.instantOrNull("valid_until"),
                minimumVersionCode = response["min_version_code"]?.jsonPrimitive?.intOrNull,
                trialUntil = response.instantOrNull("trial_until"),
                proUntil = response.instantOrNull("pro_until"),
                trialEligible = response["trial_eligible"]?.jsonPrimitive?.booleanOrNull,
                trialEnabled = response["trial_enabled"]?.jsonPrimitive?.booleanOrNull,
                trialDays = response["trial_days"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in MIN_OFFER_DAYS..MAX_OFFER_DAYS }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.instantOrNull(name: String): Instant? =
        this[name]?.jsonPrimitive?.contentOrNull?.let { raw ->
            try {
                Instant.parse(raw)
            } catch (_: Exception) {
                null
            }
        }

    private fun licenseResponseFailure(status: LicenseStatus): ClientRequestException = when {
        status.minimumVersionCode == null || status.minimumVersionCode < 1 ->
            ClientRequestException("تعذر التحقق من اشتراكك الآن. حاول مجددًا.")
        BuildConfig.VERSION_CODE < status.minimumVersionCode ->
            ClientRequestException("يلزم تحديث التطبيق قبل استخدام الخدمة")
        status.serverTime == null || status.validUntil == null ->
            ClientRequestException("تعذر تأكيد مدة اشتراكك الآن. حاول مجددًا.")
        else -> ClientRequestException("الترخيص غير صالح أو انتهت صلاحيته")
    }

    private fun licenseStateFailure(state: LicenseState): LicenseStateException = when (state) {
        LicenseState.UNREGISTERED -> LicenseStateException(
            state,
            "حساب السائق غير مكتمل؛ أعد تسجيل الدخول أو تواصل مع الإدارة"
        )
        LicenseState.DEVICE_MISMATCH -> LicenseStateException(state, "الحساب مرتبط بجهاز آخر")
        LicenseState.BANNED, LicenseState.REJECTED ->
            LicenseStateException(state, "تم حظر أو رفض الحساب")
        else -> LicenseStateException(state, "اشتراكك غير متاح للاستخدام الآن. راجع صفحة حسابك.")
    }

    private fun responseObject(body: String): JsonObject {
        val element = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            throw ClientRequestException("تعذر إكمال هذه الخطوة. حاول مجددًا بعد قليل.")
        }
        return element.asResponseObject()
            ?: throw ClientRequestException("تعذر إكمال هذه الخطوة. حاول مجددًا بعد قليل.")
    }

    private fun JsonElement.asResponseObject(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonArray -> singleOrNull() as? JsonObject
        else -> null
    }

    private fun execute(request: Request, maxBytes: Int = MAX_RESPONSE_BYTES): HttpResponse = client.newCall(request).execute().use { response ->
        HttpResponse(code = response.code, body = readResponseBody(response.body, maxBytes), contentRange = response.header("Content-Range"))
    }

    private fun readResponseBody(body: ResponseBody?, maxBytes: Int): String {
        if (body == null) return ""
        if (body.contentLength() > maxBytes) {
            throw ResponseTooLargeException()
        }
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                if (output.size() + bytesRead > maxBytes) {
                    throw ResponseTooLargeException()
                }
                output.write(buffer, 0, bytesRead)
            }
            return output.toString(body.contentType()?.charset(Charsets.UTF_8)?.name() ?: Charsets.UTF_8.name())
        }
    }

    private fun httpFailure(operation: String, response: HttpResponse): Result<Nothing> {
        val providerCode = runCatching {
            json.parseToJsonElement(response.body).jsonObject["error_code"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val message = CustomerErrorMessages.forResponse(response.code, providerCode, serverErrorDetail(response.body), operation)
        return Result.failure(ClientRequestException(message))
    }

    private fun serverErrorDetail(body: String): String? {
        val objectBody = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return null
        }
        val raw = listOf("error_description", "msg", "message", "error", "hint")
            .firstNotNullOfOrNull { field -> objectBody[field]?.jsonPrimitive?.contentOrNull }
            ?: return null
        val sanitized = raw.filter { it.code >= 0x20 && it.code != 0x7f }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
        return sanitized.takeIf { it.isNotEmpty() }
    }

    private suspend fun <T> networkCall(block: () -> Result<T>): Result<T> = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ResponseTooLargeException) {
            Result.failure(ClientRequestException("تعذر إكمال هذه الخطوة. حاول مجددًا بعد قليل."))
        } catch (_: IOException) {
            Result.failure(ClientRequestException("تعذر الاتصال بالخدمة؛ تحقق من الشبكة وحاول لاحقًا"))
        } catch (error: ClientRequestException) {
            Result.failure(error)
        } catch (_: Exception) {
            Result.failure(ClientRequestException("تعذر إكمال العملية بسبب استجابة غير متوقعة"))
        }
    }

    private fun <T> invalidInput(message: String): Result<T> = Result.failure(ClientRequestException(message))

    private fun <T> staleSessionFailure(): Result<T> =
        Result.failure(ClientRequestException("أعد تسجيل الدخول لإكمال هذه الخطوة."))

    private data class HttpResponse(val code: Int, val body: String, val contentRange: String? = null) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private class ClientRequestException(message: String) : Exception(message)
    private class SessionExpiredException : Exception("انتهت الجلسة؛ جارٍ تجديدها")
    private class RefreshRefusedException : Exception("رفض الخادم تجديد الجلسة")
    private class ResponseTooLargeException : IOException()
}

/** تحقق محلي لا يكشف القيم الحساسة ولا يحوّلها إلى سجلات. */
/** طرق الدخول كما يعلنها `auth/v1/settings` العام. */
data class AuthProviders(val google: Boolean, val phone: Boolean)

internal object AuthSettingsParser {
    fun parse(body: String): AuthProviders? = runCatching {
        val external = Json.parseToJsonElement(body).jsonObject["external"]?.jsonObject ?: return null
        AuthProviders(
            google = external["google"]?.jsonPrimitive?.booleanOrNull == true,
            phone = external["phone"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }.getOrNull()
}

/** يعرض ما يكفي ليتعرّف السائق على حسابه، دون كشف المعرّف كاملًا على الشاشة. */
internal object AccountIdentityMask {
    fun phone(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit)?.takeIf { it.length >= 8 } ?: return null
        val local = if (digits.startsWith("966")) "0" + digits.drop(3) else digits
        return local.take(2) + "•".repeat(local.length - 5) + local.takeLast(3)
    }

    fun email(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val at = value.indexOf('@').takeIf { it > 0 && it == value.lastIndexOf('@') && it < value.length - 1 }
            ?: return null
        return value.first() + "•••" + value.substring(at)
    }
}

internal object NetworkInputValidator {
    fun baseUrl(raw: String): HttpUrl? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val url = value.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.isHttps &&
                it.host.isNotBlank() &&
                it.username.isEmpty() &&
                it.password.isEmpty() &&
                it.querySize == 0 &&
                it.fragment == null
        }
    }

    fun token(raw: String?): String? = headerSecret(raw, 8 * 1024)

    fun headerSecret(raw: String?, maxLength: Int): String? = raw?.takeIf {
        it.length in 1..maxLength && it.none(::isHeaderControlCharacter) && it == it.trim()
    }

    fun email(raw: String): String? {
        val value = raw.trim()
        return value.takeIf {
            it.length in 3..254 && it.contains('@') && !it.any(::isControlCharacter)
        }
    }

    fun password(raw: String): String? = raw.takeIf { it.length in 1..(4 * 1024) }

    fun registrationName(raw: String): String? = normalizedText(raw, 160)

    fun phone(raw: String): String? = normalizedText(raw, 64)

    fun displayName(raw: String?): String? = raw?.let { normalizedText(it, 160) }

    fun googleWebClientId(raw: String?): String? = raw?.trim()?.takeIf {
        it.length in 1..512 && !it.any(::isControlCharacter)
    }

    /** يقبل كتابات سعودية مألوفة لكنه يخرج دائمًا E.164 السعودي فقط. */
    fun saudiE164(raw: String): String? {
        val compact = buildString {
            raw.trim().forEach { character ->
                when {
                    character in '0'..'9' || character == '+' -> append(character)
                    character in '\u0660'..'\u0669' -> append('0' + (character.code - '\u0660'.code))
                    character in '\u06f0'..'\u06f9' -> append('0' + (character.code - '\u06f0'.code))
                    character.isWhitespace() || character == '-' || character == '(' || character == ')' -> Unit
                    else -> return null
                }
            }
        }
        val national = when {
            SAUDI_E164.matches(compact) -> return compact
            SAUDI_INTL_PREFIX.matches(compact) -> compact.substring(2)
            SAUDI_COUNTRY_PREFIX.matches(compact) -> compact
            SAUDI_LOCAL_PREFIX.matches(compact) -> "966${compact.substring(1)}"
            SAUDI_BARE_PREFIX.matches(compact) -> "966$compact"
            else -> return null
        }
        return "+$national".takeIf(SAUDI_E164::matches)
    }

    /** يطبع الأرقام العربية إلى ASCII ويرفض أي مسافة/محرف غير الرقم. */
    fun otpCode(raw: String): String? {
        val normalized = buildString {
            raw.trim().forEach { character ->
                when {
                    character in '0'..'9' -> append(character)
                    character in '\u0660'..'\u0669' -> append('0' + (character.code - '\u0660'.code))
                    character in '\u06f0'..'\u06f9' -> append('0' + (character.code - '\u06f0'.code))
                    else -> return null
                }
            }
        }
        return normalized.takeIf(OTP_CODE::matches)
    }

    fun httpsUrl(raw: String): Boolean {
        if (raw.length > 2_048 || raw.any(::isControlCharacter)) return false
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host.isNotBlank() && url.username.isEmpty() && url.password.isEmpty()
    }

    fun deviceId(raw: String?): String? = raw?.let { value ->
        val normalized = value.trim()
        normalized.takeIf {
            it.toByteArray(Charsets.UTF_8).size in 16..256 && !it.any(::isControlCharacter)
        }
    }

    fun activationCode(raw: String): String? = normalizedText(raw, 128)?.uppercase(Locale.ROOT)

    private fun normalizedText(raw: String, maxLength: Int): String? {
        val value = raw.trim()
        return value.takeIf { it.length in 1..maxLength && !it.any(::isControlCharacter) }
    }

    private fun isHeaderControlCharacter(character: Char): Boolean =
        character.code < 0x20 || character.code == 0x7f

    private fun isControlCharacter(character: Char): Boolean = character.code < 0x20 || character.code == 0x7f

    private val SAUDI_E164 = Regex("^\\+9665\\d{8}$")
    private val SAUDI_INTL_PREFIX = Regex("^009665\\d{8}$")
    private val SAUDI_COUNTRY_PREFIX = Regex("^9665\\d{8}$")
    private val SAUDI_LOCAL_PREFIX = Regex("^05\\d{8}$")
    private val SAUDI_BARE_PREFIX = Regex("^5\\d{8}$")
    private val OTP_CODE = Regex("^\\d{6}$")
}

/** قيمة عابرة فقط: Google يتلقى SHA-256 hex وSupabase يتلقى الأصل لمطابقة nonce. */
internal data class GoogleNonceValue(val raw: String, val sha256: String)

internal object GoogleNonce {
    private val secureRandom = SecureRandom()

    fun create(): GoogleNonceValue {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return GoogleNonceValue(raw = raw, sha256 = sha256Hex(raw))
    }

    internal fun sha256Hex(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** جلسة ومهلة ترخيص محميتان بالقفل نفسه، ولا تُحفظان خارج الذاكرة. */
internal class AuthSessionStore(
    private val clock: MonotonicClock = MonotonicClock { android.os.SystemClock.elapsedRealtime() }
) {
    private val lock = Any()
    private val licenseLease = LicenseLease(clock)
    private var generation = 0L
    private var session: SessionSnapshot? = null
    private var pendingPhoneOtp: PendingPhoneOtp? = null
    private var subscriptionSnapshot: SubscriptionSnapshot? = null

    fun beginAuthentication(): Long = synchronized(lock) {
        generation += 1
        session = null
        pendingPhoneOtp = null
        subscriptionSnapshot = null
        licenseLease.invalidate()
        generation
    }

    fun activate(attempt: Long, token: String): Boolean = synchronized(lock) {
        if (attempt != generation || session != null) return false
        session = SessionSnapshot(generation = generation, token = token, deviceId = null)
        pendingPhoneOtp = null
        subscriptionSnapshot = null
        licenseLease.invalidate()
        true
    }

    fun snapshot(): SessionSnapshot? = synchronized(lock) { session }

    fun isCurrentToken(token: String): Boolean = synchronized(lock) { session?.token == token }

    fun markPhoneOtpRequested(attempt: Long, phone: String): Boolean = synchronized(lock) {
        if (attempt != generation || session != null) return false
        pendingPhoneOtp = PendingPhoneOtp(attempt, phone)
        true
    }

    fun phoneOtpAttempt(phone: String): Long? = synchronized(lock) {
        pendingPhoneOtp?.takeIf { it.phone == phone && it.generation == generation }?.generation
    }

    fun monotonicNow(): Long = clock.nowMillis()

    fun hasValidLicense(): Boolean = synchronized(lock) { licenseLease.isValid() }

    fun subscriptionSnapshot(): SubscriptionSnapshot? = synchronized(lock) { subscriptionSnapshot }

    fun bindDevice(token: String, deviceId: String): Boolean = synchronized(lock) {
        val current = session ?: return false
        if (current.token != token) return false
        session = current.copy(deviceId = deviceId)
        licenseLease.invalidate()
        true
    }

    /** null تعني أن الجلسة تغيّرت قبل تطبيق الاستجابة. */
    fun replaceLicenseIfMatches(
        token: String,
        status: LicenseStatus,
        appVersionCode: Int,
        requestStartedAt: Long
    ): Boolean? = synchronized(lock) {
        if (session?.token != token) return@synchronized null
        licenseLease.replace(status, appVersionCode, requestStartedAt)
    }

    fun updateSubscriptionIfMatches(token: String, status: LicenseStatus): Boolean = synchronized(lock) {
        if (session?.token != token) return false
        subscriptionSnapshot = status.toSubscriptionSnapshot()
        true
    }

    fun invalidateLicenseIfMatches(token: String): Boolean = synchronized(lock) {
        if (session?.token != token) return@synchronized false
        licenseLease.invalidate()
        true
    }

    /** Renewed access token for the same session: device binding and license lease are kept. */
    fun replaceToken(oldToken: String, newToken: String): Boolean = synchronized(lock) {
        val current = session ?: return false
        if (current.token != oldToken) return false
        session = current.copy(token = newToken)
        true
    }

    fun clearIfMatches(token: String) = synchronized(lock) {
        if (session?.token == token) {
            licenseLease.invalidate()
            session = null
            pendingPhoneOtp = null
            subscriptionSnapshot = null
        }
    }

    fun clear() = synchronized(lock) {
        generation += 1
        licenseLease.invalidate()
        session = null
        pendingPhoneOtp = null
        subscriptionSnapshot = null
    }

    private data class PendingPhoneOtp(val generation: Long, val phone: String)
}

internal data class SessionSnapshot(
    val generation: Long,
    val token: String,
    val deviceId: String?
)

private fun LicenseStatus.toSubscriptionSnapshot(): SubscriptionSnapshot = SubscriptionSnapshot(
    state = state,
    serverTime = serverTime,
    validUntil = validUntil,
    trialUntil = trialUntil,
    proUntil = proUntil,
    trialEligible = trialEligible,
    trialEnabled = trialEnabled,
    trialDays = trialDays
)

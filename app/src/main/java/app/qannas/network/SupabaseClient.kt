package app.qannas.network

import android.os.SystemClock
import app.qannas.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * عميل API عديم الحالة الدائمة: لا تُخزَّن رموز الوصول أو معرّفات الأجهزة على القرص.
 * كل عملية حساسة تلتقط لقطة متسقة من الجلسة كي لا تستخدم بيانات جلسة سبقتها عملية دخول أخرى.
 */
object SupabaseClient {
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val MAX_TOKEN_LENGTH = 8 * 1024
    private const val MAX_ANON_KEY_LENGTH = 8 * 1024
    private const val MAX_EMAIL_LENGTH = 254
    private const val MAX_PASSWORD_LENGTH = 4 * 1024
    private const val MAX_NAME_LENGTH = 160
    private const val MAX_PHONE_LENGTH = 64
    private const val MAX_CODE_LENGTH = 128

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

    /**
     * توافق مؤقت مع مستهلكي الواجهة الحاليين. القيمة تُقرأ من الذاكرة ولا يمكن تعديلها
     * من خارج العميل؛ يجب أن تهاجر الواجهة إلى [hasRedeemableSession] بدلاً من قراءة الرمز.
     */
    @Deprecated("استخدم hasRedeemableSession() ولا تمرر رموز الجلسة بين المكونات")
    val currentToken: String
        get() = sessions.snapshot()?.token.orEmpty()

    /** معرّف الجهاز المقترن بالجلسة الذاكرية فقط، وليس معرّفاً محفوظاً. */
    @Deprecated("استخدم hasRedeemableSession()")
    val currentDeviceId: String
        get() = sessions.snapshot()?.deviceId.orEmpty()

    fun hasRedeemableSession(): Boolean = sessions.snapshot()?.deviceId != null

    /** فحص محلي فقط؛ لا ينفّذ اتصالاً من مسار أحداث إمكانية الوصول. */
    fun hasValidLicense(): Boolean = sessions.hasValidLicense()

    /** يمكّن مسار تسجيل خروج مستقبلي من إبطال الجلسة الذاكرية صراحةً. */
    fun clearSession() {
        sessions.clear()
    }

    suspend fun signUp(email: String, password: String, name: String, phone: String): Result<String> {
        val safeEmail = NetworkInputValidator.email(email)
            ?: return invalidInput("البريد الإلكتروني غير صالح")
        val safePassword = NetworkInputValidator.password(password)
            ?: return invalidInput("كلمة المرور يجب أن تكون بين 1 و$MAX_PASSWORD_LENGTH حرفاً")
        val safeName = NetworkInputValidator.registrationName(name)
            ?: return invalidInput("الاسم الكامل غير صالح")
        val safePhone = NetworkInputValidator.phone(phone)
            ?: return invalidInput("رقم الهاتف غير صالح")
        val attempt = sessions.beginAuthentication()

        return networkCall {
            val request = requestBuilder(endpoint("auth/v1/signup"))
                .post(
                    buildJsonObject {
                        put("email", safeEmail)
                        put("password", safePassword)
                        put("data", buildJsonObject {
                            put("full_name", safeName)
                            put("phone", safePhone)
                        })
                    }.toString().toRequestBody(jsonMediaType)
                )
                .build()
            val response = execute(request)
            if (!response.isSuccessful) {
                return@networkCall httpFailure("إنشاء الحساب", response)
            }

            val token = accessTokenFrom(response.body)
                ?: return@networkCall Result.failure(ClientRequestException(
                    "تم إنشاء الحساب. فعّل البريد الإلكتروني ثم سجّل الدخول لإكمال الإعداد."
                ))

            // لا نفعّل الجلسة قبل نجاح RPC كي لا يصل المستخدم إلى التطبيق بحساب غير مهيأ.
            val registrationRequest = authenticatedRequest(
                endpoint("rest/v1/rpc/register_driver"),
                token,
                buildJsonObject {
                    put("p_full_name", safeName)
                    put("p_phone", safePhone)
                }.toString().toRequestBody(jsonMediaType)
            )
            val registrationResponse = execute(registrationRequest)
            if (!registrationResponse.isSuccessful) {
                return@networkCall httpFailure("إعداد ملف السائق", registrationResponse)
            }

            if (!sessions.activate(attempt, token)) {
                return@networkCall staleSessionFailure()
            }
            Result.success(token)
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

    suspend fun login(email: String, password: String): Result<String> {
        val safeEmail = NetworkInputValidator.email(email)
            ?: return invalidInput("البريد الإلكتروني غير صالح")
        val safePassword = NetworkInputValidator.password(password)
            ?: return invalidInput("كلمة المرور يجب أن تكون بين 1 و$MAX_PASSWORD_LENGTH حرفاً")
        val attempt = sessions.beginAuthentication()

        return networkCall {
            val url = endpoint("auth/v1/token").newBuilder()
                .addQueryParameter("grant_type", "password")
                .build()
            val request = requestBuilder(url)
                .post(
                    buildJsonObject {
                        put("email", safeEmail)
                        put("password", safePassword)
                    }.toString().toRequestBody(jsonMediaType)
                )
                .build()
            val response = execute(request)
            if (!response.isSuccessful) {
                return@networkCall httpFailure("تسجيل الدخول", response)
            }

            val accessToken = accessTokenFrom(response.body)
                ?: return@networkCall Result.failure(ClientRequestException("لم تُرجع الخدمة جلسة صالحة"))
            if (!sessions.activate(attempt, accessToken)) {
                return@networkCall staleSessionFailure()
            }
            Result.success(accessToken)
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
            )
        }
    }

    /**
     * يعيد الفحص باستعمال جلسة الجهاز الحالية؛ تستدعيه الواجهة/نبض الخدمة فقط.
     * لا يستدعي [hasValidLicense] هذه الدالة مطلقاً.
     */
    suspend fun revalidateLicense(): Result<Boolean> {
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
            if (response.code == 401) sessions.clearIfMatches(session.token)
            if (!response.isSuccessful) {
                return@networkCall httpFailure("إعادة التحقق من الترخيص", response)
            }
            handleLicenseStatus(
                token = session.token,
                candidateDeviceId = deviceId,
                body = response.body,
                requestStartedAt = requestStartedAt
            )
        }
    }

    suspend fun redeemCode(code: String): Result<Boolean> {
        val safeCode = NetworkInputValidator.activationCode(code)
            ?: return invalidInput("كود التفعيل غير صالح")
        val session = sessions.snapshot()
            ?: return Result.failure(ClientRequestException("انتهت الجلسة؛ سجّل الدخول من جديد"))
        val deviceId = session.deviceId
            ?: return Result.failure(ClientRequestException("تحقق من الترخيص والجهاز قبل إدخال كود التفعيل"))

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
                return@networkCall httpFailure("تفعيل الكود", response)
            }

            val result = responseObject(response.body)
            if (result["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                if (sessions.invalidateLicenseIfMatches(session.token)) Result.success(true)
                else staleSessionFailure()
            } else {
                when (result["error"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT)) {
                    "invalid_code" -> Result.failure(ClientRequestException("الكود غير صحيح أو استُخدم مسبقاً"))
                    "rate_limited" -> Result.failure(ClientRequestException("محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجدداً"))
                    "device_mismatch" -> Result.failure(ClientRequestException("هذا الحساب مرتبط بجهاز آخر"))
                    "unregistered" -> Result.failure(licenseStateFailure(LicenseState.UNREGISTERED))
                    else -> Result.failure(ClientRequestException("تعذر تفعيل الكود"))
                }
            }
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
                minimumVersionCode = response["min_version_code"]?.jsonPrimitive?.intOrNull
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
            ClientRequestException("استجابة الترخيص لا تتضمن الحد الأدنى للإصدار")
        BuildConfig.VERSION_CODE < status.minimumVersionCode ->
            ClientRequestException("يلزم تحديث التطبيق قبل استخدام الخدمة")
        status.serverTime == null || status.validUntil == null ->
            ClientRequestException("استجابة الترخيص لا تتضمن تاريخ صلاحية صالحاً")
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
        else -> LicenseStateException(state, "حالة الترخيص غير قابلة للاستخدام")
    }

    private fun responseObject(body: String): JsonObject {
        val element = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            throw ClientRequestException("استجابة الخادم غير صالحة")
        }
        return element.asResponseObject()
            ?: throw ClientRequestException("استجابة الخادم غير صالحة")
    }

    private fun JsonElement.asResponseObject(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonArray -> singleOrNull() as? JsonObject
        else -> null
    }

    private fun execute(request: Request): HttpResponse = client.newCall(request).execute().use { response ->
        HttpResponse(code = response.code, body = readResponseBody(response.body))
    }

    private fun readResponseBody(body: ResponseBody?): String {
        if (body == null) return ""
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw ResponseTooLargeException()
        }
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                if (output.size() + bytesRead > MAX_RESPONSE_BYTES) {
                    throw ResponseTooLargeException()
                }
                output.write(buffer, 0, bytesRead)
            }
            return output.toString(body.contentType()?.charset(Charsets.UTF_8)?.name() ?: Charsets.UTF_8.name())
        }
    }

    private fun httpFailure(operation: String, response: HttpResponse): Result<Nothing> {
        val message = when (response.code) {
            401 -> "انتهت الجلسة أو بيانات الدخول غير صحيحة"
            403 -> "لا تملك صلاحية تنفيذ هذه العملية"
            408, 504 -> "انتهت مهلة الاتصال بالخدمة؛ حاول لاحقاً"
            429 -> "تم تجاوز حد المحاولات؛ يرجى الانتظار"
            in 500..599 -> "الخدمة غير متاحة مؤقتاً؛ حاول لاحقاً"
            else -> serverErrorDetail(response.body)?.let { "تعذر $operation: $it" }
                ?: "تعذر $operation (رمز HTTP ${response.code})"
        }
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
            Result.failure(ClientRequestException("استجابة الخادم تجاوزت الحد الآمن"))
        } catch (_: IOException) {
            Result.failure(ClientRequestException("تعذر الاتصال بالخدمة؛ تحقق من الشبكة وحاول لاحقاً"))
        } catch (error: ClientRequestException) {
            Result.failure(error)
        } catch (_: Exception) {
            Result.failure(ClientRequestException("تعذر إكمال العملية بسبب استجابة غير متوقعة"))
        }
    }

    private fun <T> invalidInput(message: String): Result<T> = Result.failure(ClientRequestException(message))

    private fun <T> staleSessionFailure(): Result<T> =
        Result.failure(ClientRequestException("تغيّرت الجلسة أثناء العملية؛ سجّل الدخول من جديد"))

    private data class HttpResponse(val code: Int, val body: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private class ClientRequestException(message: String) : Exception(message)
    private class ResponseTooLargeException : IOException()
}

/** تحقق محلي لا يكشف القيم الحساسة ولا يحوّلها إلى سجلات. */
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
}

/** جلسة ومهلة ترخيص محميتان بالقفل نفسه، ولا تُحفظان خارج الذاكرة. */
internal class AuthSessionStore(
    private val clock: MonotonicClock = MonotonicClock { android.os.SystemClock.elapsedRealtime() }
) {
    private val lock = Any()
    private val licenseLease = LicenseLease(clock)
    private var generation = 0L
    private var session: SessionSnapshot? = null

    fun beginAuthentication(): Long = synchronized(lock) {
        generation += 1
        session = null
        licenseLease.invalidate()
        generation
    }

    fun activate(attempt: Long, token: String): Boolean = synchronized(lock) {
        if (attempt != generation) return false
        session = SessionSnapshot(generation = generation, token = token, deviceId = null)
        licenseLease.invalidate()
        true
    }

    fun snapshot(): SessionSnapshot? = synchronized(lock) { session }

    fun isCurrentToken(token: String): Boolean = synchronized(lock) { session?.token == token }

    fun monotonicNow(): Long = clock.nowMillis()

    fun hasValidLicense(): Boolean = synchronized(lock) { licenseLease.isValid() }

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

    fun invalidateLicenseIfMatches(token: String): Boolean = synchronized(lock) {
        if (session?.token != token) return@synchronized false
        licenseLease.invalidate()
        true
    }

    fun clearIfMatches(token: String) = synchronized(lock) {
        if (session?.token == token) {
            licenseLease.invalidate()
            session = null
        }
    }

    fun clear() = synchronized(lock) {
        generation += 1
        licenseLease.invalidate()
        session = null
    }
}

internal data class SessionSnapshot(
    val generation: Long,
    val token: String,
    val deviceId: String?
)

package app.qannas.network

import app.qannas.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object SupabaseClient {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    var currentToken: String = ""
    var currentDeviceId: String = ""

    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=password")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .post(payload)
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = Json.parseToJsonElement(body).jsonObject
                val token = json["access_token"]?.jsonPrimitive?.content ?: ""
                currentToken = token
                Result.success(token)
            } else {
                Result.failure(Exception("Login Failed: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkLicenseAndBindDevice(token: String, deviceId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        currentDeviceId = deviceId
        
        val payload = buildJsonObject { 
            put("p_device_id", deviceId)
            // p_version_code is optional/default null in SQL
        }.toString().toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/license_status")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = Json.parseToJsonElement(body).jsonObject
                val state = json["state"]?.jsonPrimitive?.content ?: "unknown"
                
                return@withContext when (state) {
                    "active", "trial" -> Result.success(true)
                    "pending" -> Result.failure(Exception("الحساب بانتظار التفعيل (يرجى إدخال الكود)"))
                    "expired" -> Result.failure(Exception("انتهى اشتراكك، يرجى شحن الرصيد"))
                    "device_mismatch" -> Result.failure(Exception("الحساب مرتبط بجهاز آخر"))
                    "banned", "rejected" -> Result.failure(Exception("تم حظر أو رفض الحساب"))
                    else -> Result.failure(Exception("حالة الحساب غير صالحة: $state"))
                }
            } else {
                Result.failure(Exception("Server connection error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun redeemCode(code: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { 
            put("p_code", code.trim())
            put("p_device_id", currentDeviceId)
        }.toString().toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/redeem_code")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $currentToken")
            .post(payload)
            .build()
            
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = Json.parseToJsonElement(body).jsonObject
                val ok = json["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                if (ok) {
                    Result.success(true)
                } else {
                    val err = json["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    val msg = when (err) {
                        "invalid_code" -> "الكود غير صحيح أو مستخدم مسبقاً"
                        "rate_limited" -> "محاولات كثيرة خاطئة، يرجى الانتظار"
                        "device_mismatch" -> "هذا الحساب مرتبط بجهاز آخر"
                        else -> "فشل التفعيل: $err"
                    }
                    Result.failure(Exception(msg))
                }
            } else {
                Result.failure(Exception("فشل الاتصال بالخادم: ${response.code}"))
            }
        } catch(e: Exception) {
            Result.failure(e)
        }
    }
}

package app.qannas.network

import app.qannas.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

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
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/rest/v1/drivers?select=*")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $token")
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "[]"
            if (response.isSuccessful) {
                val jsonArray = Json.parseToJsonElement(body).jsonArray
                if (jsonArray.isEmpty()) return@withContext Result.failure(Exception("Driver account not found"))
                
                val driver = jsonArray[0].jsonObject
                val dbDeviceId = driver["device_id"]?.jsonPrimitive?.contentOrNull
                val status = driver["status"]?.jsonPrimitive?.content ?: ""
                val proUntilStr = driver["pro_until"]?.jsonPrimitive?.contentOrNull

                if (status != "active" && status != "pending") {
                    return@withContext Result.failure(Exception("Account not active or banned"))
                }

                // Check pro_until expiration
                if (proUntilStr != null) {
                    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    format.timeZone = TimeZone.getTimeZone("UTC")
                    val proUntilDate = format.parse(proUntilStr)
                    if (proUntilDate != null && proUntilDate.before(Date())) {
                        return@withContext Result.failure(Exception("انتهى اشتراكك، يرجى شحن الرصيد"))
                    }
                } else if (status == "pending") {
                    return@withContext Result.failure(Exception("الحساب بانتظار التفعيل (يرجى إدخال الكود)"))
                }

                if (dbDeviceId == null || dbDeviceId == deviceId) {
                    if (dbDeviceId == null) {
                        val bindPayload = buildJsonObject { put("p_device_id", deviceId) }.toString().toRequestBody(jsonMediaType)
                        val bindReq = Request.Builder()
                            .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/bind_device")
                            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .header("Authorization", "Bearer $token")
                            .post(bindPayload)
                            .build()
                        val bindRes = client.newCall(bindReq).execute()
                        if (!bindRes.isSuccessful) {
                            return@withContext Result.failure(Exception("Failed to bind device"))
                        }
                    }
                    Result.success(true)
                } else {
                    Result.failure(Exception("Account bound to another device"))
                }
            } else {
                Result.failure(Exception("Server connection error"))
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
                    Result.failure(Exception(err))
                }
            } else {
                Result.failure(Exception("فشل الاتصال بالخادم: ${response.code}"))
            }
        } catch(e: Exception) {
            Result.failure(e)
        }
    }
}

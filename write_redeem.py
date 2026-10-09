import os

files = {
    'app/src/main/java/app/qannas/network/SupabaseClient.kt': """package app.qannas.network

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

                if (status != "active" && status != "pending") {
                    return@withContext Result.failure(Exception("Account not active"))
                }

                if (dbDeviceId == null || dbDeviceId == deviceId) {
                    if (dbDeviceId == null) {
                        val patchPayload = buildJsonObject { put("device_id", deviceId) }.toString().toRequestBody(jsonMediaType)
                        val patchReq = Request.Builder()
                            .url("${BuildConfig.SUPABASE_URL}/rest/v1/drivers")
                            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .header("Authorization", "Bearer $token")
                            .patch(patchPayload)
                            .build()
                        client.newCall(patchReq).execute()
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
        val payload = buildJsonObject { put("activation_code", code.trim()) }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/redeem_activation_code")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $currentToken")
            .post(payload)
            .build()
        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && body == "true") {
                Result.success(true)
            } else {
                Result.failure(Exception("الكود غير صحيح أو مستخدم مسبقاً"))
            }
        } catch(e: Exception) {
            Result.failure(e)
        }
    }
}
""",

    'app/src/main/java/app/qannas/ui/SettingsScreen.kt': """package app.qannas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.qannas.Settings
import app.qannas.core.Filters
import app.qannas.network.SupabaseClient
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenA11y: () -> Unit, onToggleKeeper: (Boolean) -> Unit) {
    var filters by remember { mutableStateOf(Settings.filters) }
    var isActive by remember { mutableStateOf(Settings.isActive) }
    var activationCode by remember { mutableStateOf("") }
    var codeMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("إعدادات قناص الطلبات", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = filters.minFare ?: "",
            onValueChange = { 
                filters = filters.copy(minFare = it)
                Settings.filters = filters 
            },
            label = { Text("الحد الأدنى للسعر (مثال: 10)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = filters.maxPickupMinutes?.toString() ?: "",
            onValueChange = { 
                filters = filters.copy(maxPickupMinutes = it.toIntOrNull())
                Settings.filters = filters 
            },
            label = { Text("أقصى مسافة للراكب (بالدقائق)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("تشغيل القناص", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = isActive,
                onCheckedChange = { 
                    isActive = it
                    Settings.isActive = it
                    onToggleKeeper(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenA11y, modifier = Modifier.fillMaxWidth()) {
            Text("تفعيل صلاحية الوصول (Accessibility)")
        }

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        Text("تجديد الاشتراك", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = activationCode,
                onValueChange = { activationCode = it.uppercase() },
                label = { Text("كود التفعيل (مثال: QAN-XXXX)") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    codeMessage = "جاري التحقق..."
                    val res = SupabaseClient.redeemCode(activationCode)
                    if(res.isSuccess) {
                        codeMessage = "تم تفعيل الاشتراك بنجاح!"
                        activationCode = ""
                    } else {
                        codeMessage = res.exceptionOrNull()?.message ?: "حدث خطأ"
                    }
                }
            }) {
                Text("تفعيل")
            }
        }
        if (codeMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(codeMessage, color = if (codeMessage.contains("نجاح")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}
"""
}

for path, content in files.items():
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"Updated {path}")

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
}
""",

    'app/src/main/java/app/qannas/ui/theme/Theme.kt': """package app.qannas.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.qannas.R

val TajawalFontFamily = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2196F3),
    onPrimary = Color.White,
    secondary = Color(0xFF4CAF50),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5)
)

@Composable
fun QannasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = androidx.compose.material3.Typography(
            bodyLarge = androidx.compose.material3.Typography().bodyLarge.copy(fontFamily = TajawalFontFamily),
            titleLarge = androidx.compose.material3.Typography().titleLarge.copy(fontFamily = TajawalFontFamily)
        ),
        content = content
    )
}
""",

    'app/src/main/java/app/qannas/ui/LoginScreen.kt': """package app.qannas.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.qannas.network.SupabaseClient
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("تسجيل الدخول", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("البريد الإلكتروني أو الهاتف") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("رمز التفعيل (كلمة المرور)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (errorMessage.isNotEmpty()) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                isLoading = true
                errorMessage = ""
                scope.launch {
                    val loginRes = SupabaseClient.login(email, password)
                    if (loginRes.isSuccess) {
                        val deviceId = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ANDROID_ID)
                        val bindRes = SupabaseClient.checkLicenseAndBindDevice(loginRes.getOrThrow(), deviceId)
                        if (bindRes.isSuccess) {
                            app.qannas.Settings.isActive = false
                            onLoginSuccess()
                        } else {
                            errorMessage = bindRes.exceptionOrNull()?.message ?: "Unknown Error"
                        }
                    } else {
                        errorMessage = loginRes.exceptionOrNull()?.message ?: "Login Error"
                    }
                    isLoading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            else Text("دخول وربط الجهاز")
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

@Composable
fun SettingsScreen(onOpenA11y: () -> Unit, onToggleKeeper: (Boolean) -> Unit) {
    var filters by remember { mutableStateOf(Settings.filters) }
    var isActive by remember { mutableStateOf(Settings.isActive) }

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
    }
}
""",

    'app/src/main/java/app/qannas/ui/MainActivity.kt': """package app.qannas.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.qannas.service.KeeperService
import app.qannas.ui.theme.QannasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QannasTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        var isLoggedIn by remember { mutableStateOf(false) }

                        if (!isLoggedIn) {
                            LoginScreen(onLoginSuccess = { isLoggedIn = true })
                        } else {
                            SettingsScreen(
                                onOpenA11y = {
                                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onToggleKeeper = { start ->
                                    val intent = Intent(this, KeeperService::class.java)
                                    if (start) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            startForegroundService(intent)
                                        } else {
                                            startService(intent)
                                        }
                                    } else {
                                        stopService(intent)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
""",

    'admin/index.html': """<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8">
    <title>لوحة تحكم قناص</title>
    <script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2"></script>
    <style>
        body { font-family: Tahoma, Arial, sans-serif; background: #f4f7f6; padding: 20px; }
        .container { max-width: 1000px; margin: auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 0 10px rgba(0,0,0,0.1); }
        h1 { color: #2196F3; }
        table { width: 100%; border-collapse: collapse; margin: 20px 0; }
        th, td { padding: 12px; border: 1px solid #ddd; text-align: right; }
        th { background: #2196F3; color: white; }
        button { background: #4CAF50; color: white; border: none; padding: 10px 15px; border-radius: 4px; cursor: pointer; }
        input { padding: 10px; margin: 5px 0; width: calc(100% - 22px); border: 1px solid #ccc; border-radius: 4px; }
        .flex { display: flex; gap: 10px; align-items: center; }
    </style>
</head>
<body>
    <div class="container">
        <h1>لوحة الإدارة - السائقين</h1>
        <div id="login-section">
            <input type="email" id="email" placeholder="البريد الإلكتروني (المشرف)">
            <input type="password" id="password" placeholder="كلمة المرور">
            <button onclick="login()">تسجيل الدخول</button>
        </div>
        <div id="dashboard-section" style="display: none;">
            <div class="flex">
                <button onclick="loadDrivers()">تحديث البيانات</button>
            </div>
            <table>
                <thead>
                    <tr><th>الاسم</th><th>الهاتف</th><th>الحالة</th><th>تاريخ الاشتراك</th><th>معرف الجهاز</th><th>إجراءات</th></tr>
                </thead>
                <tbody id="drivers-list"></tbody>
            </table>
        </div>
    </div>
    <script>
        const SUPABASE_URL = 'ضع_رابط_قاعدة_البيانات_هنا';
        const SUPABASE_KEY = 'ضع_مفتاح_API_هنا';
        const supabase = supabase.createClient(SUPABASE_URL, SUPABASE_KEY);

        async function login() {
            const { error } = await supabase.auth.signInWithPassword({
                email: document.getElementById('email').value,
                password: document.getElementById('password').value
            });
            if (!error) {
                document.getElementById('login-section').style.display = 'none';
                document.getElementById('dashboard-section').style.display = 'block';
                loadDrivers();
            } else alert("خطأ: " + error.message);
        }

        async function loadDrivers() {
            const { data, error } = await supabase.from('drivers').select('*');
            if (data) {
                const tbody = document.getElementById('drivers-list');
                tbody.innerHTML = '';
                data.forEach(d => {
                    tbody.innerHTML += `<tr>
                        <td>${d.full_name}</td>
                        <td>${d.phone}</td>
                        <td>${d.status}</td>
                        <td>${d.pro_until ? d.pro_until.substring(0, 10) : 'تجريبي'}</td>
                        <td>${d.device_id || 'غير مرتبط'}</td>
                        <td>
                            <button onclick="resetDevice('${d.id}')">فك ربط الجهاز</button>
                        </td>
                    </tr>`;
                });
            }
        }
        
        async function resetDevice(id) {
            if(confirm("هل أنت متأكد من فك ربط جهاز هذا السائق؟")) {
                await supabase.from('drivers').update({ device_id: null }).eq('id', id);
                loadDrivers();
            }
        }
    </script>
</body>
</html>"""
}

for path, content in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"Created {path}")


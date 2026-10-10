package app.qannas.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.qannas.network.SupabaseClient
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoginSuccess: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isSignUpMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    var errorDialogMessage by remember { mutableStateOf("") }

    if (errorDialogMessage.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { errorDialogMessage = "" },
            title = { Text("تفاصيل المشكلة (للتشخيص)") },
            text = { Text(errorDialogMessage) },
            confirmButton = {
                TextButton(onClick = { errorDialogMessage = "" }) { Text("حسناً") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("تطبيق القناص", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { isSignUpMode = false }) {
                Text("تسجيل الدخول", color = if (!isSignUpMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            TextButton(onClick = { isSignUpMode = true }) {
                Text("حساب جديد", color = if (isSignUpMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (isSignUpMode) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("الاسم الكامل") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("رقم الهاتف") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("البريد الإلكتروني") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("كلمة المرور") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                isLoading = true
                errorDialogMessage = ""
                scope.launch {
                    val authRes = if (isSignUpMode) {
                        SupabaseClient.signUp(email, password, name, phone)
                    } else {
                        SupabaseClient.login(email, password)
                    }
                    
                    if (authRes.isSuccess) {
                        val token = authRes.getOrThrow()
                        val adminRes = SupabaseClient.isAdmin(token)
                        
                        if (adminRes.isSuccess && adminRes.getOrThrow()) {
                            onLoginSuccess(true)
                        } else {
                            val deviceId = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ANDROID_ID)
                            val bindRes = SupabaseClient.checkLicenseAndBindDevice(token, deviceId)
                            if (bindRes.isSuccess) {
                                app.qannas.Settings.isActive = false
                                onLoginSuccess(false)
                            } else {
                                val debugMsg = buildString {
                                    append("نتيجة فحص المشرف: ").append(adminRes.exceptionOrNull()?.message ?: "ليس مشرفاً").append("\n\n")
                                    append("نتيجة فحص السائق: ").append(bindRes.exceptionOrNull()?.message ?: "خطأ مجهول")
                                }
                                errorDialogMessage = debugMsg
                            }
                        }
                    } else {
                        errorDialogMessage = "خطأ في المصادقة:\n${authRes.exceptionOrNull()?.message}"
                    }
                    isLoading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            else Text(if (isSignUpMode) "إنشاء حساب" else "دخول")
        }
    }
}

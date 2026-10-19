package app.qannas.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.qannas.Settings
import app.qannas.network.SupabaseClient
import kotlinx.coroutines.CancellationException
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
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showActivation by remember { mutableStateOf(false) }
    var activationCode by remember { mutableStateOf("") }
    var activationError by remember { mutableStateOf<String?>(null) }

    fun completeDriverLogin() {
        Settings.isActive = false
        password = ""
        showActivation = false
        onLoginSuccess(false)
    }

    fun submitAuthentication() {
        val validationError = if (isSignUpMode) {
            UiFormValidation.signUpError(name, phone, email, password)
        } else UiFormValidation.loginError(email, password)
        if (validationError != null) {
            errorMessage = validationError
            return
        }
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val authResult = if (isSignUpMode) {
                    SupabaseClient.signUp(email.trim(), password, name.trim(), phone.trim())
                } else SupabaseClient.login(email.trim(), password)
                if (authResult.isFailure) {
                    errorMessage = authenticationErrorMessage(authResult.exceptionOrNull())
                    return@launch
                }
                password = ""
                val token = authResult.getOrThrow()
                val adminResult = SupabaseClient.isAdmin(token)
                when {
                    adminResult.isFailure -> errorMessage = "تعذر التحقق من صلاحيات الحساب. تحقق من الاتصال ثم أعد المحاولة."
                    adminResult.getOrThrow() -> onLoginSuccess(true)
                    else -> {
                        val deviceId = AndroidSettings.Secure.getString(
                            context.contentResolver, AndroidSettings.Secure.ANDROID_ID
                        )?.takeIf { it.isNotBlank() }
                        if (deviceId == null) {
                            errorMessage = "تعذر تحديد هذا الجهاز. أعد فتح التطبيق ثم حاول مرة أخرى."
                            return@launch
                        }
                        val licenseResult = SupabaseClient.checkLicenseAndBindDevice(token, deviceId)
                        if (licenseResult.getOrNull() == true) {
                            completeDriverLogin()
                        } else if (licenseResult.isSuccess && SupabaseClient.hasRedeemableSession()) {
                            activationError = null
                            showActivation = true
                        } else {
                            errorMessage = licenseErrorMessage(licenseResult.exceptionOrNull())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errorMessage = "تعذر إكمال تسجيل الدخول. تحقق من اتصال الإنترنت ثم أعد المحاولة."
            } finally {
                isLoading = false
            }
        }
    }

    if (showActivation) {
        AlertDialog(
            onDismissRequest = {
                if (!isLoading) {
                    showActivation = false
                    SupabaseClient.clearSession()
                }
            },
            title = { Text("تفعيل الاشتراك") },
            text = {
                Column {
                    Text("حسابك بحاجة إلى اشتراك صالح. أدخل كود التفعيل الذي حصلت عليه من الإدارة.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = activationCode,
                        onValueChange = { activationCode = UiFormValidation.normalizeActivationCode(it) },
                        enabled = !isLoading,
                        singleLine = true,
                        label = { Text("XXXX-XXXX-XXXX-XXXX") },
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                    activationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !isLoading, onClick = {
                    val error = UiFormValidation.activationCodeError(activationCode)
                    if (error != null) {
                        activationError = error
                    } else {
                        isLoading = true
                        activationError = null
                        scope.launch {
                            try {
                                val redeemed = SupabaseClient.redeemCode(activationCode)
                                if (redeemed.getOrNull() == true && SupabaseClient.revalidateLicense().getOrNull() == true) {
                                    activationCode = ""
                                    completeDriverLogin()
                                } else activationError = "تعذر تفعيل الاشتراك أو التحقق منه. راجع الكود والاتصال ثم أعد المحاولة."
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }) { Text(if (isLoading) "جارٍ التحقق…" else "تفعيل") }
            },
            dismissButton = {
                TextButton(enabled = !isLoading, onClick = {
                    showActivation = false
                    SupabaseClient.clearSession()
                }) { Text("العودة") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("قناص", style = MaterialTheme.typography.headlineLarge)
        Text("مرشحات دقيقة وتحكم واضح في عروض الرحلات", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        TabRow(selectedTabIndex = if (isSignUpMode) 1 else 0) {
            Tab(selected = !isSignUpMode, enabled = !isLoading, onClick = {
                isSignUpMode = false; errorMessage = null
            }, text = { Text("تسجيل الدخول") })
            Tab(selected = isSignUpMode, enabled = !isLoading, onClick = {
                isSignUpMode = true; errorMessage = null
            }, text = { Text("حساب جديد") })
        }
        Spacer(Modifier.height(16.dp))
        if (isSignUpMode) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم الكامل") },
                enabled = !isLoading, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("رقم الهاتف") },
                enabled = !isLoading, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("البريد الإلكتروني") },
            enabled = !isLoading, singleLine = true,
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("كلمة المرور") },
            enabled = !isLoading, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth())
        errorMessage?.let {
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = ::submitAuthentication, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
            if (isLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Text(if (isSignUpMode) "إنشاء حساب" else "دخول")
        }
    }
}

private fun authenticationErrorMessage(error: Throwable?): String = when (error?.message) {
    "تم إنشاء الحساب. فعّل البريد الإلكتروني ثم سجّل الدخول لإكمال الإعداد." -> error.message.orEmpty()
    else -> "تعذر إتمام المصادقة. تأكد من البريد وكلمة المرور واتصال الإنترنت ثم أعد المحاولة."
}

private fun licenseErrorMessage(error: Throwable?): String = when (error?.message) {
    "الحساب مرتبط بجهاز آخر", "تم حظر أو رفض الحساب", "حساب السائق غير مكتمل؛ تواصل مع الإدارة" -> error.message.orEmpty()
    else -> "تعذر التحقق من حالة الاشتراك. تحقق من الاتصال ومن تحديث التطبيق ثم أعد المحاولة."
}

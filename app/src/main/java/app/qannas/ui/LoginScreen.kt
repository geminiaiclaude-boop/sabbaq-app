package app.qannas.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.qannas.Settings
import app.qannas.network.SupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** مراحل واجهة المصادقة فقط؛ تظل صلاحية الجلسة والترخيص داخل SupabaseClient. */
private enum class AuthJourney {
    Idle,
    Authenticating,
    CheckingRole,
    CheckingLicense,
    AwaitingActivation,
    Activating
}

@Composable
fun LoginScreen(onLoginSuccess: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val nameFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    var isSignUpMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    // تبقى كلمة المرور في ذاكرة تركيب الشاشة فقط ولا تُحفظ أو تُستعاد.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var validationRequested by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var journey by remember { mutableStateOf(AuthJourney.Idle) }
    var activationCode by remember { mutableStateOf("") }
    var activationValidationRequested by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf<String?>(null) }

    val isBusy = journey == AuthJourney.Authenticating ||
        journey == AuthJourney.CheckingRole ||
        journey == AuthJourney.CheckingLicense ||
        journey == AuthJourney.Activating
    val awaitingActivation = journey == AuthJourney.AwaitingActivation || journey == AuthJourney.Activating

    fun hideKeyboard() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun resetFormFeedback() {
        validationRequested = false
        errorMessage = null
        infoMessage = null
    }

    fun cancelActivation() {
        activationCode = ""
        activationError = null
        activationValidationRequested = false
        SupabaseClient.clearSession()
        journey = AuthJourney.Idle
    }

    fun completeDriverLogin() {
        Settings.isActive = false
        password = ""
        activationCode = ""
        journey = AuthJourney.Idle
        onLoginSuccess(false)
    }

    fun submitAuthentication() {
        if (journey != AuthJourney.Idle) return
        validationRequested = true
        val validationError = if (isSignUpMode) {
            UiFormValidation.signUpError(name, phone, email, password)
        } else {
            UiFormValidation.loginError(email, password)
        }
        if (validationError != null) {
            errorMessage = null
            return
        }

        hideKeyboard()
        errorMessage = null
        infoMessage = null
        journey = AuthJourney.Authenticating
        scope.launch {
            try {
                val authResult = if (isSignUpMode) {
                    SupabaseClient.signUp(email.trim(), password, name.trim(), phone.trim())
                } else {
                    SupabaseClient.login(email.trim(), password)
                }
                // لا نحتاج كلمة المرور بعد أن ينتهي طلب المصادقة، سواء نجح أم فشل.
                password = ""
                if (authResult.isFailure) {
                    val confirmation = accountConfirmationMessage(authResult.exceptionOrNull())
                    if (confirmation != null) {
                        infoMessage = confirmation
                        isSignUpMode = false
                        name = ""
                        phone = ""
                        validationRequested = false
                    } else {
                        errorMessage = authenticationErrorMessage(authResult.exceptionOrNull())
                    }
                    return@launch
                }

                val token = authResult.getOrThrow()
                journey = AuthJourney.CheckingRole
                val adminResult = SupabaseClient.isAdmin(token)
                when {
                    // لا نعامل تعذر فحص دور المشرف كحساب سائق، ولا نتابع إلى الترخيص.
                    adminResult.isFailure -> {
                        SupabaseClient.clearSession()
                        errorMessage = "تعذر التحقق من صلاحيات الحساب. تحقق من الاتصال ثم أعد المحاولة."
                    }
                    adminResult.getOrThrow() -> onLoginSuccess(true)
                    else -> {
                        val deviceId = AndroidSettings.Secure.getString(
                            context.contentResolver,
                            AndroidSettings.Secure.ANDROID_ID
                        )?.takeIf { it.isNotBlank() }
                        if (deviceId == null) {
                            SupabaseClient.clearSession()
                            errorMessage = "تعذر تحديد هذا الجهاز. أعد فتح التطبيق ثم حاول مرة أخرى."
                            return@launch
                        }

                        journey = AuthJourney.CheckingLicense
                        val licenseResult = SupabaseClient.checkLicenseAndBindDevice(token, deviceId)
                        when {
                            licenseResult.getOrNull() == true -> completeDriverLogin()
                            licenseResult.isSuccess && SupabaseClient.hasRedeemableSession() -> {
                                activationError = null
                                activationValidationRequested = false
                                journey = AuthJourney.AwaitingActivation
                            }
                            else -> {
                                SupabaseClient.clearSession()
                                errorMessage = licenseErrorMessage(licenseResult.exceptionOrNull())
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SupabaseClient.clearSession()
                errorMessage = "تعذر إكمال تسجيل الدخول. تحقق من اتصال الإنترنت ثم أعد المحاولة."
            } finally {
                if (journey != AuthJourney.AwaitingActivation) journey = AuthJourney.Idle
            }
        }
    }

    fun submitActivation() {
        if (journey != AuthJourney.AwaitingActivation) return
        activationValidationRequested = true
        val validationError = UiFormValidation.activationCodeError(activationCode)
        if (validationError != null) {
            activationError = null
            return
        }

        hideKeyboard()
        activationError = null
        journey = AuthJourney.Activating
        scope.launch {
            try {
                val redeemed = SupabaseClient.redeemCode(activationCode)
                if (redeemed.getOrNull() != true) {
                    activationError = activationErrorMessage(redeemed.exceptionOrNull())
                    return@launch
                }

                // لا يكفي قبول الكود؛ يلزم أن يؤكد فحص الترخيص اللاحق صلاحيته على هذا الجهاز.
                val revalidated = SupabaseClient.revalidateLicense()
                if (revalidated.getOrNull() == true) {
                    completeDriverLogin()
                } else {
                    activationError = licenseErrorMessage(revalidated.exceptionOrNull())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activationError = "تعذر التحقق من كود التفعيل. تحقق من الاتصال ثم أعد المحاولة."
            } finally {
                if (journey != AuthJourney.Idle) journey = AuthJourney.AwaitingActivation
            }
        }
    }

    val nameError = if (validationRequested && isSignUpMode) registrationNameError(name) else null
    val phoneError = if (validationRequested && isSignUpMode) registrationPhoneError(phone) else null
    val emailError = if (validationRequested) emailFieldError(email) else null
    val passwordError = if (validationRequested) passwordFieldError(password, isSignUpMode) else null
    val codeError = if (activationValidationRequested) UiFormValidation.activationCodeError(activationCode) else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                AuthBrandHero()

                AnimatedContent(targetState = awaitingActivation, label = "auth-flow") { isActivationStage ->
                    if (isActivationStage) {
                        ActivationCard(
                            code = activationCode,
                            codeError = codeError,
                            serverError = activationError,
                            isBusy = isBusy,
                            onCodeChange = {
                                activationCode = UiFormValidation.normalizeActivationCode(it.take(128))
                                activationError = null
                            },
                            onActivate = ::submitActivation,
                            onCancel = ::cancelActivation
                        )
                    } else {
                        AuthenticationCard(
                            isSignUpMode = isSignUpMode,
                            isBusy = isBusy,
                            journey = journey,
                            name = name,
                            phone = phone,
                            email = email,
                            password = password,
                            passwordVisible = passwordVisible,
                            nameError = nameError,
                            phoneError = phoneError,
                            emailError = emailError,
                            passwordError = passwordError,
                            serverError = errorMessage,
                            infoMessage = infoMessage,
                            nameFocus = nameFocus,
                            phoneFocus = phoneFocus,
                            emailFocus = emailFocus,
                            passwordFocus = passwordFocus,
                            onModeChange = { signUp ->
                                if (!isBusy) {
                                    isSignUpMode = signUp
                                    passwordVisible = false
                                    resetFormFeedback()
                                }
                            },
                            onNameChange = { name = it.take(128) },
                            onPhoneChange = { phone = it.take(64) },
                            onEmailChange = { email = it.take(512) },
                            onPasswordChange = { password = it.take(4096) },
                            onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                            onSubmit = ::submitAuthentication
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthBrandHero() {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            QannasStatusPill(text = "وصول آمن", ready = true)
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large
            ) {
                Box(Modifier.padding(20.dp)) {
                    QannasBrand()
                }
            }
            Text(
                text = "ابدأ من حسابك، ثم نتحقق من الدور والترخيص قبل تشغيل الخدمة.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AuthenticationCard(
    isSignUpMode: Boolean,
    isBusy: Boolean,
    journey: AuthJourney,
    name: String,
    phone: String,
    email: String,
    password: String,
    passwordVisible: Boolean,
    nameError: String?,
    phoneError: String?,
    emailError: String?,
    passwordError: String?,
    serverError: String?,
    infoMessage: String?,
    nameFocus: FocusRequester,
    phoneFocus: FocusRequester,
    emailFocus: FocusRequester,
    passwordFocus: FocusRequester,
    onModeChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordVisibilityChange: () -> Unit,
    onSubmit: () -> Unit
) {
    QannasCard(
        title = if (isSignUpMode) "إنشاء حساب" else "تسجيل الدخول",
        subtitle = if (isSignUpMode) "قد يلزم تأكيد البريد حسب إعدادات الحساب." else "أدخل بيانات حسابك لمتابعة الإعداد الآمن.",
        icon = Icons.Outlined.Login
    ) {
        TabRow(selectedTabIndex = if (isSignUpMode) 1 else 0) {
            Tab(
                selected = !isSignUpMode,
                enabled = !isBusy,
                onClick = { onModeChange(false) },
                text = { Text("دخول") },
                modifier = Modifier.heightIn(min = 48.dp)
            )
            Tab(
                selected = isSignUpMode,
                enabled = !isBusy,
                onClick = { onModeChange(true) },
                text = { Text("حساب جديد") },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }

        QannasSectionTitle(
            title = if (isSignUpMode) "بيانات الحساب" else "مرحباً بعودتك",
            subtitle = if (isSignUpMode) "أكمل الحقول المطلوبة، ثم فعّل بريدك عند الطلب." else "سنفحص دور الحساب والترخيص بعد المصادقة."
        )

        if (isBusy) {
            QannasStatusPill(text = authProgressLabel(journey), ready = false)
        }

        if (isSignUpMode) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                enabled = !isBusy,
                singleLine = true,
                label = { Text("الاسم الكامل") },
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { phoneFocus.requestFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus)
            )
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                enabled = !isBusy,
                singleLine = true,
                label = { Text("رقم الهاتف") },
                leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                isError = phoneError != null,
                supportingText = phoneError?.let { { Text(it) } },
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus)
            )
        }

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            enabled = !isBusy,
            singleLine = true,
            label = { Text("البريد الإلكتروني") },
            leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
            isError = emailError != null,
            supportingText = emailError?.let { { Text(it) } },
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(emailFocus)
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            enabled = !isBusy,
            singleLine = true,
            label = { Text("كلمة المرور") },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onPasswordVisibilityChange, enabled = !isBusy) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
                    )
                }
            },
            isError = passwordError != null,
            supportingText = {
                when {
                    passwordError != null -> Text(passwordError)
                    isSignUpMode -> Text("استخدم 8 أحرف على الأقل.")
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus)
        )

        infoMessage?.let { QannasMessage(message = it) }
        serverError?.let { QannasMessage(message = it, isError = true) }

        Button(
            onClick = onSubmit,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.padding(start = 10.dp))
                Text(authProgressLabel(journey))
            } else {
                Text(if (isSignUpMode) "إنشاء الحساب" else "متابعة")
            }
        }
    }
}

@Composable
private fun ActivationCard(
    code: String,
    codeError: String?,
    serverError: String?,
    isBusy: Boolean,
    onCodeChange: (String) -> Unit,
    onActivate: () -> Unit,
    onCancel: () -> Unit
) {
    QannasCard(
        title = "تفعيل الاشتراك",
        subtitle = "هذه الخطوة مطلوبة قبل تشغيل حساب السائق على هذا الجهاز.",
        icon = Icons.Outlined.Lock
    ) {
        QannasStatusPill(text = "بانتظار كود التفعيل", ready = false)
        QannasSectionTitle(
            title = "أدخل الكود",
            subtitle = "أدخل الكود الذي وصلك من الإدارة. لا يُخزَّن الكود على الجهاز."
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            enabled = !isBusy,
            singleLine = true,
            label = { Text("كود التفعيل") },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            isError = codeError != null,
            supportingText = codeError?.let { { Text(it) } },
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onActivate() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (isBusy) "جارٍ التحقق من كود التفعيل" else "بانتظار إدخال كود التفعيل" }
        )
        serverError?.let { QannasMessage(message = it, isError = true) }
        Button(
            onClick = onActivate,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.padding(start = 10.dp))
                Text("جارٍ التحقق…")
            } else {
                Text("تفعيل والتحقق")
            }
        }
        TextButton(
            onClick = onCancel,
            enabled = !isBusy,
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
        ) {
            Text("إلغاء والعودة لتسجيل الدخول")
        }
    }
}

private fun registrationNameError(value: String): String? = when {
    value.trim().isEmpty() -> "أدخل الاسم الكامل."
    value.trim().length !in 2..80 -> "الاسم الكامل يجب أن يكون بين حرفين و80 حرفاً."
    else -> null
}

private fun registrationPhoneError(value: String): String? = when {
    value.trim().isEmpty() -> "أدخل رقم الهاتف."
    !Regex("^\\+?[0-9]{7,15}$").matches(value.replace(Regex("[\\s-]"), "")) -> "أدخل رقم هاتف من 7 إلى 15 رقماً."
    else -> null
}

private fun emailFieldError(value: String): String? =
    if (Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value.trim())) null else "أدخل بريداً إلكترونياً صحيحاً."

private fun passwordFieldError(value: String, isSignUp: Boolean): String? = when {
    value.isBlank() -> "أدخل كلمة المرور."
    isSignUp && value.length < 8 -> "استخدم كلمة مرور من 8 أحرف على الأقل."
    else -> null
}

private fun accountConfirmationMessage(error: Throwable?): String? =
    error?.message?.takeIf { it == "تم إنشاء الحساب. فعّل البريد الإلكتروني ثم سجّل الدخول لإكمال الإعداد." }

private fun authenticationErrorMessage(error: Throwable?): String = when (error?.message) {
    "البريد الإلكتروني غير صالح", "كلمة المرور يجب أن تكون بين 1 و128 حرفاً" ->
        "تحقق من البريد الإلكتروني وكلمة المرور ثم أعد المحاولة."
    else -> "تعذر إتمام المصادقة. تأكد من البريد وكلمة المرور واتصال الإنترنت ثم أعد المحاولة."
}

private fun licenseErrorMessage(error: Throwable?): String = when (error?.message) {
    "الحساب مرتبط بجهاز آخر",
    "تم حظر أو رفض الحساب",
    "حساب السائق غير مكتمل؛ أعد تسجيل الدخول أو تواصل مع الإدارة",
    "حساب السائق غير مكتمل؛ تواصل مع الإدارة" -> error.message.orEmpty()
    "يلزم تحديث التطبيق قبل استخدام الخدمة" -> error.message.orEmpty()
    else -> "تعذر التحقق من حالة الاشتراك. تحقق من الاتصال ومن تحديث التطبيق ثم أعد المحاولة."
}

private fun activationErrorMessage(error: Throwable?): String = when (error?.message) {
    "كود التفعيل غير صالح" -> "كود التفعيل غير صالح. راجعه ثم أعد المحاولة."
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> "انتهت جلسة التفعيل. ألغِ العملية ثم سجّل الدخول من جديد."
    else -> "تعذر قبول كود التفعيل. راجع الكود والاتصال ثم أعد المحاولة."
}

private fun authProgressLabel(journey: AuthJourney): String = when (journey) {
    AuthJourney.Authenticating -> "جارٍ التحقق…"
    AuthJourney.CheckingRole -> "جارٍ التحقق من الدور…"
    AuthJourney.CheckingLicense -> "جارٍ التحقق من الترخيص…"
    AuthJourney.Activating -> "جارٍ تفعيل الاشتراك…"
    AuthJourney.AwaitingActivation -> "بانتظار كود التفعيل"
    AuthJourney.Idle -> "متابعة"
}

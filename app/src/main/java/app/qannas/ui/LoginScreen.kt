package app.qannas.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import android.net.Uri
import android.os.SystemClock
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import app.qannas.BuildConfig
import app.qannas.Settings
import app.qannas.network.DeviceTransferLimitException
import app.qannas.network.LicenseState
import app.qannas.network.LicenseStateException
import app.qannas.network.PublicOffer
import app.qannas.network.SupabaseClient
import app.qannas.service.KeeperService
import app.qannas.ui.theme.LocalQannasBrand
import app.qannas.ui.theme.QannasDepthContent
import app.qannas.ui.theme.qannasEntrance
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** مراحل محلية للعرض فقط؛ الجلسة والـ lease يظلان حصرًا داخل SupabaseClient. */
private enum class AuthJourney {
    Idle,
    Authenticating,
    CheckingRole,
    CheckingLicense,
    AwaitingAccess,
    StartingTrial,
    ActivatingCode,
    /** Signed in, but the account is bound to another device; offer to move it here. */
    DeviceTransfer
}

private const val OTP_COOLDOWN_MILLIS = 60_000L

@Composable
fun LoginScreen(onLoginSuccess: (Boolean) -> Unit, initialIntent: AccessIntent = AccessIntent.None) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val phoneFocus = remember { FocusRequester() }
    val otpFocus = remember { FocusRequester() }

    // حالة تركيب عابرة فقط: الجوال والرمز لا ينجوان من إعادة إنشاء العملية.
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var otpRequested by remember { mutableStateOf(false) }
    var otpCooldownEndsAt by remember { mutableStateOf(0L) }
    var otpCooldownSeconds by remember { mutableStateOf(0) }
    var phoneValidationRequested by remember { mutableStateOf(false) }
    var otpValidationRequested by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var offerErrorMessage by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<PublicOffer?>(null) }
    var loadingOffer by remember { mutableStateOf(false) }
    var journey by remember { mutableStateOf(AuthJourney.Idle) }
    // Which sign-in method owns the current busy state, so only its button shows progress.
    var googleFlow by remember { mutableStateOf(false) }
    // يظهر الجوال ما لم يؤكد الخادم أنه معطّل؛ فشل القراءة لا يُخفي طريقة قد تعمل.
    var phoneAvailable by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        SupabaseClient.fetchAuthProviders().onSuccess { phoneAvailable = it.phone }
    }
    var isAdmin by remember { mutableStateOf(false) }
    var activationCode by remember { mutableStateOf("") }
    var activationValidationRequested by remember { mutableStateOf(false) }
    var activationError by remember { mutableStateOf<String?>(null) }
    // The driver's choice before signing in (trial / subscribe / existing subscription).
    var accessIntent by rememberSaveable { mutableStateOf(initialIntent) }
    val knownSubscriber = remember { Settings.knownSubscriber }

    // Device move: kept only for the signed-in session that hit «مرتبط بجهاز آخر».
    var transferToken by remember { mutableStateOf<String?>(null) }
    var transferDeviceId by remember { mutableStateOf<String?>(null) }
    var transferring by remember { mutableStateOf(false) }
    var transferError by remember { mutableStateOf<String?>(null) }
    var transferLimitReached by remember { mutableStateOf(false) }

    val isBusy = journey != AuthJourney.Idle && journey != AuthJourney.AwaitingAccess &&
        journey != AuthJourney.DeviceTransfer
    val awaitingAccess = journey == AuthJourney.AwaitingAccess ||
        journey == AuthJourney.StartingTrial || journey == AuthJourney.ActivatingCode
    // A returning subscriber goes straight to sign-in; everyone else chooses first.
    val choosing = accessIntent == AccessIntent.None && !knownSubscriber
    BackHandler(enabled = !choosing && !knownSubscriber && !awaitingAccess && journey != AuthJourney.DeviceTransfer) {
        accessIntent = AccessIntent.None
    }

    LaunchedEffect(otpCooldownEndsAt) {
        while (true) {
            val remaining = ((otpCooldownEndsAt - SystemClock.elapsedRealtime() + 999L) / 1_000L)
                .coerceAtLeast(0L)
                .toInt()
            otpCooldownSeconds = remaining
            if (remaining == 0) break
            delay(250L)
        }
    }

    fun hideKeyboard() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun clearPhoneInput() {
        phone = ""
        otp = ""
        otpRequested = false
        otpCooldownEndsAt = 0L
        otpCooldownSeconds = 0
        phoneValidationRequested = false
        otpValidationRequested = false
    }

    fun resetMessages() {
        errorMessage = null
        infoMessage = null
    }

    fun abandonAccess() {
        transferToken = null
        transferDeviceId = null
        transferError = null
        transferLimitReached = false
        activationCode = ""
        activationError = null
        activationValidationRequested = false
        offer = null
        offerErrorMessage = null
        loadingOffer = false
        isAdmin = false
        clearPhoneInput()
        SupabaseClient.clearSession()
        journey = AuthJourney.Idle
    }

    fun completeLogin(admin: Boolean) {
        // لا تقبل نتيجة RPC وحدها؛ لا انتقال قبل أن تكون المهلة الذاكرية صالحة الآن.
        if (!SupabaseClient.hasValidLicense()) {
            SupabaseClient.clearSession()
            errorMessage = "تعذر تأكيد صلاحية الوصول لحسابك. سجّل الدخول مجددًا ثم حاول."
            journey = AuthJourney.Idle
            return
        }
        // A fresh sign-in never starts monitoring by itself; a driver who left it on gets it
        // back through the service, which re-checks the license before re-opening the gate.
        if (!Settings.monitoringWanted) Settings.isActive = false
        KeeperService.resumeIfWanted(context)
        // A paid subscription on this device hides the free-trial choice on later sign-ins.
        if (SupabaseClient.subscriptionSnapshot()?.state == LicenseState.ACTIVE) Settings.knownSubscriber = true
        activationCode = ""
        clearPhoneInput()
        journey = AuthJourney.Idle
        // حتى لو بلغ حساب إداري lease صالحًا في play، يظل انتقاله انتقال عميل.
        onLoginSuccess(admin && BuildConfig.DIRECT_DISTRIBUTION)
    }

    fun completeDirectAdminLogin() {
        // أدوات الإدارة المحلية معزولة في direct فقط؛ لا يصل هذا المسار إلى play.
        Settings.isActive = false
        activationCode = ""
        clearPhoneInput()
        journey = AuthJourney.Idle
        onLoginSuccess(true)
    }

    suspend fun refreshPublicOffer() {
        loadingOffer = true
        offerErrorMessage = null
        SupabaseClient.fetchPublicOffer().fold(
            onSuccess = { offer = it },
            onFailure = {
                offer = null
                offerErrorMessage = "تعذر عرض تفاصيل التجربة الآن. تحقق من الاتصال ثم أعد المحاولة."
            }
        )
        loadingOffer = false
    }

    // The public offer (trial length, price) feeds the choice cards before sign-in.
    LaunchedEffect(Unit) { if (offer == null) refreshPublicOffer() }

    // A saved session (from an earlier sign-in on this device) resumes without asking again.
    LaunchedEffect(Unit) {
        if (journey != AuthJourney.Idle || !SupabaseClient.hasSavedSession()) return@LaunchedEffect
        journey = AuthJourney.CheckingLicense
        val restored = runCatching { SupabaseClient.restoreSession().getOrNull() == true }.getOrDefault(false)
        if (restored && SupabaseClient.hasValidLicense()) {
            completeLogin(admin = false)
        } else if (journey == AuthJourney.CheckingLicense) {
            journey = AuthJourney.Idle
        }
    }

    fun checkRoleAndLicense(token: String) {
        journey = AuthJourney.CheckingRole
        scope.launch {
            try {
                val adminResult = SupabaseClient.isAdmin(token)
                if (adminResult.isFailure) {
                    SupabaseClient.clearSession()
                    errorMessage = "تعذر تأكيد حسابك الآن. تحقق من الاتصال ثم أعد المحاولة."
                    return@launch
                }
                val accountIsAdmin = adminResult.getOrThrow()
                if (accountIsAdmin && BuildConfig.DIRECT_DISTRIBUTION) {
                    completeDirectAdminLogin()
                    return@launch
                }
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
                    licenseResult.getOrNull() == true -> completeLogin(
                        admin = accountIsAdmin && BuildConfig.DIRECT_DISTRIBUTION
                    )
                    licenseResult.isSuccess -> {
                        // pending/expired لا يفتح أي لوحة؛ يبقى القرار النهائي للخادم.
                        isAdmin = accountIsAdmin
                        journey = AuthJourney.AwaitingAccess
                        refreshPublicOffer()
                    }
                    else -> {
                        val failure = licenseResult.exceptionOrNull()
                        if (failure is LicenseStateException && failure.state == LicenseState.DEVICE_MISMATCH) {
                            // Not a dead end: the signed-in owner can move the account here.
                            isAdmin = accountIsAdmin
                            transferToken = token
                            transferDeviceId = deviceId
                            transferError = null
                            transferLimitReached = false
                            journey = AuthJourney.DeviceTransfer
                        } else {
                            SupabaseClient.clearSession()
                            errorMessage = licenseErrorMessage(failure)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SupabaseClient.clearSession()
                errorMessage = "تعذر تأكيد الوصول إلى حسابك. تحقق من اتصال الإنترنت ثم أعد المحاولة."
            } finally {
                if (journey != AuthJourney.AwaitingAccess && journey != AuthJourney.Idle &&
                    journey != AuthJourney.DeviceTransfer) {
                    journey = AuthJourney.Idle
                }
            }
        }
    }

    fun transferDeviceHere() {
        val token = transferToken ?: return
        val device = transferDeviceId ?: return
        if (transferring) return
        transferring = true
        transferError = null
        scope.launch {
            try {
                SupabaseClient.transferDevice(token, device).fold(
                    onSuccess = {
                        transferToken = null
                        transferDeviceId = null
                        checkRoleAndLicense(token)
                    },
                    onFailure = { failure ->
                        transferLimitReached = failure is DeviceTransferLimitException
                        transferError = when (failure) {
                            is DeviceTransferLimitException -> failure.retryAfter
                                ?.let { "نقلت حسابك مرتين خلال 30 يومًا. يمكنك نقله مجددًا ابتداءً من $it، أو تواصل مع الإدارة الآن." }
                                ?: "نقلت حسابك مرتين خلال 30 يومًا. تواصل مع الإدارة لنقله الآن."
                            else -> failure.message ?: "تعذر نقل الحساب الآن. حاول مجددًا بعد قليل."
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                transferError = "تعذر نقل الحساب الآن. تحقق من الاتصال ثم أعد المحاولة."
            } finally {
                transferring = false
            }
        }
    }

    fun submitGoogle() {
        if (journey != AuthJourney.Idle) return
        hideKeyboard()
        resetMessages()
        journey = AuthJourney.Authenticating
        googleFlow = true
        scope.launch {
            try {
                val result = SupabaseClient.signInWithGoogle(context)
                if (result.isFailure) {
                    errorMessage = googleErrorMessage(result.exceptionOrNull())
                    return@launch
                }
                clearPhoneInput()
                checkRoleAndLicense(result.getOrThrow())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SupabaseClient.clearSession()
                errorMessage = e.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر إتمام الدخول باستخدام Google. تحقق من الاتصال ثم أعد المحاولة. [G-RUNTIME-EX]"
            } finally {
                if (journey == AuthJourney.Authenticating) journey = AuthJourney.Idle
            }
        }
    }

    fun requestOtp() {
        if (journey != AuthJourney.Idle || otpCooldownSeconds > 0) return
        phoneValidationRequested = true
        if (!SaudiLoginInput.isSaudiMobile(phone)) return

        hideKeyboard()
        resetMessages()
        journey = AuthJourney.Authenticating
        googleFlow = false
        scope.launch {
            try {
                val result = SupabaseClient.requestPhoneOtp(phone)
                if (result.isFailure) {
                    errorMessage = otpRequestErrorMessage(result.exceptionOrNull())
                    return@launch
                }
                otp = ""
                otpRequested = true
                otpValidationRequested = false
                otpCooldownEndsAt = SystemClock.elapsedRealtime() + OTP_COOLDOWN_MILLIS
                infoMessage = "أرسلنا رمزًا من 6 أرقام إلى جوالك."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errorMessage = "تعذر إرسال رمز التحقق. تحقق من الاتصال ثم أعد المحاولة."
            } finally {
                if (journey == AuthJourney.Authenticating) journey = AuthJourney.Idle
            }
        }
    }

    fun verifyOtp() {
        if (journey != AuthJourney.Idle || !otpRequested) return
        otpValidationRequested = true
        if (!SaudiLoginInput.isSixDigitOtp(otp)) return

        hideKeyboard()
        resetMessages()
        journey = AuthJourney.Authenticating
        googleFlow = false
        scope.launch {
            try {
                val result = SupabaseClient.verifyPhoneOtp(phone, SaudiLoginInput.normalizeOtp(otp))
                // لا يبقى الرمز في الذاكرة بعد إرسال طلب التحقق، ناجحًا كان أو فاشلًا.
                otp = ""
                if (result.isFailure) {
                    errorMessage = otpVerificationErrorMessage(result.exceptionOrNull())
                    return@launch
                }
                clearPhoneInput()
                checkRoleAndLicense(result.getOrThrow())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SupabaseClient.clearSession()
                errorMessage = "تعذر تأكيد الرمز. اطلب رمزًا جديدًا ثم أعد المحاولة."
            } finally {
                if (journey == AuthJourney.Authenticating) journey = AuthJourney.Idle
            }
        }
    }

    fun startTrial() {
        if (journey != AuthJourney.AwaitingAccess || !canStartTrial(SupabaseClient.subscriptionSnapshot(), offer)) return
        hideKeyboard()
        activationError = null
        journey = AuthJourney.StartingTrial
        scope.launch {
            try {
                val result = SupabaseClient.startTrial()
                if (result.getOrNull() == true && SupabaseClient.hasValidLicense()) {
                    completeLogin(isAdmin)
                } else {
                    activationError = trialErrorMessage(result.exceptionOrNull())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activationError = "تعذر بدء التجربة الآن. تحقق من الاتصال ثم أعد المحاولة."
            } finally {
                if (journey != AuthJourney.Idle) journey = AuthJourney.AwaitingAccess
            }
        }
    }

    fun submitActivationCode() {
        if (!BuildConfig.DIRECT_DISTRIBUTION || journey != AuthJourney.AwaitingAccess) return
        activationValidationRequested = true
        if (UiFormValidation.activationCodeError(activationCode) != null) return

        hideKeyboard()
        activationError = null
        journey = AuthJourney.ActivatingCode
        scope.launch {
            try {
                val redeemed = SupabaseClient.redeemCode(activationCode)
                activationCode = ""
                if (redeemed.getOrNull() != true) {
                    activationError = activationErrorMessage(redeemed.exceptionOrNull())
                    return@launch
                }
                val revalidated = SupabaseClient.revalidateLicense()
                if (revalidated.getOrNull() == true && SupabaseClient.hasValidLicense()) {
                    completeLogin(isAdmin)
                } else {
                    activationError = licenseErrorMessage(revalidated.exceptionOrNull())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activationError = "تعذر تأكيد رمز التفعيل. تحقق من الاتصال ثم أعد المحاولة."
            } finally {
                if (journey != AuthJourney.Idle) journey = AuthJourney.AwaitingAccess
            }
        }
    }

    val phoneError = if (phoneValidationRequested && !SaudiLoginInput.isSaudiMobile(phone)) {
        "أدخل رقم جوال سعودي بصيغة 05XXXXXXXX أو +9665XXXXXXXX."
    } else {
        null
    }
    val otpError = if (otpValidationRequested && !SaudiLoginInput.isSixDigitOtp(otp)) {
        "أدخل رمز التحقق المكوّن من 6 أرقام."
    } else {
        null
    }

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
                QannasDepthContent(target = awaitingAccess, order = { if (it) 1 else 0 }, label = "auth-flow") { isPendingAccess ->
                    if (isPendingAccess) {
                        PendingAccessCard(
                            isBusy = isBusy,
                            journey = journey,
                            offer = offer,
                            loadingOffer = loadingOffer,
                            offerError = offerErrorMessage,
                            actionError = activationError,
                            activationCode = activationCode,
                            activationCodeError = if (activationValidationRequested) {
                                UiFormValidation.activationCodeError(activationCode)
                            } else {
                                null
                            },
                            showTrial = canStartTrial(SupabaseClient.subscriptionSnapshot(), offer),
                            intent = accessIntent,
                            onRetryOffer = {
                                if (!loadingOffer && journey == AuthJourney.AwaitingAccess) {
                                    scope.launch { refreshPublicOffer() }
                                }
                            },
                            onStartTrial = ::startTrial,
                            onActivationCodeChange = {
                                activationCode = UiFormValidation.normalizeActivationCode(it.take(128))
                                activationError = null
                            },
                            onActivateCode = ::submitActivationCode,
                            onOpenPurchaseContact = { url ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    .onFailure {
                                        activationError = "تعذر فتح قناة التواصل. أعد المحاولة أو تواصل مع الإدارة."
                                    }
                            },
                            onCancel = ::abandonAccess
                        )
                    } else if (journey == AuthJourney.DeviceTransfer) {
                        DeviceTransferCard(
                            busy = transferring,
                            error = transferError,
                            supportUrl = if (transferLimitReached) offer?.adminWhatsapp?.let { PurchaseLink.supportChatUrl(it) } else null,
                            onTransfer = ::transferDeviceHere,
                            onOpenSupport = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            onCancel = ::abandonAccess,
                        )
                    } else QannasDepthContent(
                        // Two pages: first the choice, then a sign-in / sign-up page shaped by it.
                        target = choosing,
                        order = { if (it) 0 else 1 },
                        label = "access-step",
                    ) { onChoicePage ->
                    if (onChoicePage) Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        AccessChoiceCard(
                            offer = offer,
                            knownSubscriber = knownSubscriber,
                            selected = accessIntent,
                            onSelect = { accessIntent = it },
                        )
                    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!knownSubscriber) {
                            TextButton(
                                onClick = { accessIntent = AccessIntent.None; resetMessages() },
                                enabled = !isBusy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("تغيير اختيارك")
                            }
                        }
                        AccessIntentBanner(accessIntent, offer)
                        AuthenticationCard(
                            title = when (accessIntent) {
                                AccessIntent.Trial -> "أنشئ حسابك وابدأ تجربتك"
                                AccessIntent.Subscribe -> "سجّل الدخول أو أنشئ حسابك"
                                else -> "تسجيل الدخول"
                            },
                            phone = phone,
                            otp = otp,
                            otpRequested = otpRequested,
                            otpCooldownSeconds = otpCooldownSeconds,
                            phoneError = phoneError,
                            otpError = otpError,
                            serverError = errorMessage,
                            infoMessage = infoMessage,
                            isBusy = isBusy,
                            googleBusy = isBusy && googleFlow,
                            phoneAvailable = phoneAvailable || otpRequested,
                            journey = journey,
                            phoneFocus = phoneFocus,
                            otpFocus = otpFocus,
                            onPhoneChange = {
                                if (phone != it) {
                                    otp = ""
                                    otpRequested = false
                                    otpValidationRequested = false
                                }
                                phone = it.take(32)
                                phoneValidationRequested = false
                                errorMessage = null
                            },
                            onOtpChange = {
                                otp = SaudiLoginInput.otpDigits(it).take(6)
                                otpValidationRequested = false
                                errorMessage = null
                            },
                            onGoogle = ::submitGoogle,
                            onRequestOtp = ::requestOtp,
                            onVerifyOtp = ::verifyOtp
                        )
                    }
                    }
                }
                Text(
                    "بالمتابعة توافق على معالجة بياناتك كما توضح سياسة الخصوصية.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QannasLegalLinks(showDeletion = false)
            }
        }
    }
}

@Composable
private fun AuthBrandHero() {
    val brand = LocalQannasBrand.current
    QannasHeroSurface(Modifier.qannasEntrance(0)) {
        QannasBrand(onHero = true)
        Text(
            text = "ادخل إلى حسابك، ثم اضبط شروط طلباتك.",
            style = MaterialTheme.typography.bodyMedium,
            color = brand.onHeroMuted,
        )
    }
}

/** Reminds the driver what they chose on the previous page, and what happens after signing in. */
@Composable
private fun AccessIntentBanner(intent: AccessIntent, offer: PublicOffer?) {
    val text = when (intent) {
        AccessIntent.Trial -> offer?.takeIf { it.trialEnabled && it.trialDays > 0 }
            ?.let { "اخترت التجربة المجانية (${ArabicCount.days(it.trialDays)}). بعد الدخول تبدأها بضغطة." }
            ?: "اخترت التجربة المجانية. بعد الدخول نتحقق من أهلية حسابك."
        AccessIntent.Subscribe -> "اخترت الاشتراك. بعد الدخول ترى طريقة الدفع وتفعّل اشتراكك."
        else -> null
    } ?: return
    QannasMessage(text, tone = QannasTone.Info)
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text("أو", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AuthenticationCard(
    title: String,
    phone: String,
    otp: String,
    otpRequested: Boolean,
    otpCooldownSeconds: Int,
    phoneError: String?,
    otpError: String?,
    serverError: String?,
    infoMessage: String?,
    isBusy: Boolean,
    googleBusy: Boolean,
    phoneAvailable: Boolean,
    journey: AuthJourney,
    phoneFocus: FocusRequester,
    otpFocus: FocusRequester,
    onPhoneChange: (String) -> Unit,
    onOtpChange: (String) -> Unit,
    onGoogle: () -> Unit,
    onRequestOtp: () -> Unit,
    onVerifyOtp: () -> Unit
) {
    QannasCard(
        title = title,
        subtitle = "حساب واحد للدخول والتسجيل: إن لم يكن لديك حساب يُنشأ تلقائيًا عند أول دخول.",
        icon = Icons.Outlined.Login
    ) {
        QannasButton(
            text = "المتابعة باستخدام Google",
            onClick = onGoogle,
            busy = googleBusy,
            busyText = authProgressLabel(journey),
            enabled = !isBusy,
        )
        if (phoneAvailable) {
        OrDivider()
        QannasSectionTitle(
            title = "رقم الجوال السعودي",
            subtitle = if (otpRequested) "أدخل الرمز المكوّن من 6 أرقام الذي وصلك." else "نرسل رمزًا لمرة واحدة إلى جوالك. رقم الجوال محفوظ بأمان في حسابك لتأكيد هويتك والوصول إليه عند الحاجة."
        )
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            enabled = !isBusy,
            singleLine = true,
            label = { Text("رقم الجوال السعودي") },
            placeholder = { Text("05XXXXXXXX") },
            leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
            isError = phoneError != null,
            supportingText = phoneError?.let { { Text(it) } },
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = if (otpRequested) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(
                onNext = { if (otpRequested) otpFocus.requestFocus() },
                onDone = { if (!otpRequested) onRequestOtp() else onVerifyOtp() }
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus)
        )
        if (otpRequested) {
            OutlinedTextField(
                value = otp,
                onValueChange = onOtpChange,
                enabled = !isBusy,
                singleLine = true,
                label = { Text("رمز التحقق") },
                placeholder = { Text("123456") },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                isError = otpError != null,
                supportingText = otpError?.let { { Text(it) } },
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onVerifyOtp() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(otpFocus)
                    .semantics { stateDescription = "رمز تحقق من 6 أرقام" }
            )
        }
        }

        QannasAnimatedMessage(infoMessage, QannasTone.Success)
        QannasAnimatedMessage(serverError, QannasTone.Error)

        if (phoneAvailable && otpRequested) {
            QannasButton(
                text = "تحقق وتابع",
                onClick = onVerifyOtp,
                busy = isBusy && !googleBusy,
                busyText = authProgressLabel(journey),
            )
            QannasButton(
                text = if (otpCooldownSeconds > 0) "يمكن طلب رمز جديد بعد $otpCooldownSeconds ث" else "إرسال رمز جديد",
                onClick = onRequestOtp,
                enabled = !isBusy && otpCooldownSeconds == 0,
                style = QannasButtonStyle.Text,
                icon = Icons.Outlined.Autorenew,
            )
        } else if (phoneAvailable) {
            QannasButton(
                text = "إرسال رمز التحقق",
                onClick = onRequestOtp,
                enabled = !isBusy && otpCooldownSeconds == 0,
                busy = isBusy && !googleBusy,
                busyText = "جارٍ إرسال الرمز…",
                style = QannasButtonStyle.Secondary,
            )
        }
    }
}

/**
 * Shown instead of a dead-end error when the account is bound to another device: the
 * driver just proved who they are, so they may move it here (server-limited).
 */
@Composable
private fun DeviceTransferCard(
    busy: Boolean,
    error: String?,
    supportUrl: String?,
    onTransfer: () -> Unit,
    onOpenSupport: (String) -> Unit,
    onCancel: () -> Unit,
) {
    QannasCard(
        title = "حسابك مفعّل على جهاز آخر",
        subtitle = "يحدث هذا عند تغيير الجوال أو إعادة ضبطه أو تثبيت نسخة أخرى من قناص.",
        icon = Icons.Outlined.PhonelinkSetup,
    ) {
        Text(
            "يمكنك نقل حسابك إلى هذا الجهاز الآن. سيتوقف قناص على الجهاز الآخر، وتبقى مدة اشتراكك كما هي. يمكن النقل مرتين كل 30 يومًا.",
            style = MaterialTheme.typography.bodyMedium,
        )
        QannasAnimatedMessage(error, QannasTone.Error)
        if (supportUrl != null) {
            QannasButton("تواصل مع الإدارة عبر واتساب", onClick = { onOpenSupport(supportUrl) },
                icon = Icons.AutoMirrored.Outlined.Chat)
        } else {
            QannasButton(
                text = "نقل حسابي إلى هذا الجهاز",
                onClick = onTransfer,
                busy = busy,
                busyText = "جارٍ نقل الحساب…",
                icon = Icons.Outlined.PhonelinkSetup,
            )
        }
        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text("تسجيل الخروج والعودة")
        }
    }
}

@Composable
private fun PendingAccessCard(
    isBusy: Boolean,
    journey: AuthJourney,
    offer: PublicOffer?,
    loadingOffer: Boolean,
    offerError: String?,
    actionError: String?,
    activationCode: String,
    activationCodeError: String?,
    showTrial: Boolean,
    intent: AccessIntent,
    onRetryOffer: () -> Unit,
    onStartTrial: () -> Unit,
    onActivationCodeChange: (String) -> Unit,
    onActivateCode: () -> Unit,
    onOpenPurchaseContact: (String) -> Unit,
    onCancel: () -> Unit
) {
    QannasCard(
        title = "ابدأ مع قناص",
        subtitle = "رتّب الفرص بما يناسبك عبر المرشحات والشروط، وراجع العائد المعروض ووقت العرض قبل اتخاذ قرارك.",
        icon = Icons.Outlined.Key
    ) {
        QannasStatusPill(text = "بانتظار تفعيل الوصول", ready = false)
        if (loadingOffer) {
            QannasStatusPill(text = "جارٍ تجهيز تفاصيل التجربة…", ready = false)
        }
        offerError?.let {
            QannasMessage(message = it, isError = true)
            TextButton(
                onClick = onRetryOffer,
                enabled = !isBusy && !loadingOffer,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
            ) { Text("إعادة المحاولة") }
        }

        // Subscribing drivers see the purchase path first; everyone else sees the trial first.
        val purchaseFirst = intent == AccessIntent.Subscribe && BuildConfig.DIRECT_DISTRIBUTION
        if (purchaseFirst) {
            DirectAccessOptions(
                offer = offer,
                isBusy = isBusy,
                journey = journey,
                activationCode = activationCode,
                activationCodeError = activationCodeError,
                actionError = actionError,
                onActivationCodeChange = onActivationCodeChange,
                onActivateCode = onActivateCode,
                onOpenPurchaseContact = onOpenPurchaseContact
            )
        }
        if (showTrial) {
            QannasSectionTitle(
                title = "تجربة متاحة لحسابك",
                subtitle = "جرّب أدوات الفرز والشروط، وراجع العائد ووقت العرض. التجربة لمدة ${ArabicCount.days(offer?.trialDays)} وتبدأ فقط بعد ضغطك على الزر."
            )
            QannasButton(
                text = "ابدأ التجربة",
                onClick = onStartTrial,
                enabled = !isBusy,
                busy = journey == AuthJourney.StartingTrial,
                busyText = "جارٍ تأكيد التجربة…",
            )
        }

        // العزل بالثابت التركيبي: مسار الكود وقناة الإدارة لا يُضمّنان في play.
        if (purchaseFirst) {
            // Already shown above.
        } else if (BuildConfig.DIRECT_DISTRIBUTION) {
            DirectAccessOptions(
                offer = offer,
                isBusy = isBusy,
                journey = journey,
                activationCode = activationCode,
                activationCodeError = activationCodeError,
                actionError = actionError,
                onActivationCodeChange = onActivationCodeChange,
                onActivateCode = onActivateCode,
                onOpenPurchaseContact = onOpenPurchaseContact
            )
        } else {
            // لا سعر خادمي ولا كود ولا رابط/CTA خارجي قبل تكامل Play Billing والتحقق الخادمي.
            QannasMessage(
                message = "لا تتوفر طريقة شراء داخل التطبيق حاليًا. لن نطلب رمز تفعيل أو نحيلك إلى دفع خارج التطبيق هنا."
            )
            actionError?.let { QannasMessage(message = it, isError = true) }
        }

        TextButton(
            onClick = onCancel,
            enabled = !isBusy,
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
        ) { Text("تسجيل الخروج والعودة") }
    }
}

@Composable
private fun DirectAccessOptions(
    offer: PublicOffer?,
    isBusy: Boolean,
    journey: AuthJourney,
    activationCode: String,
    activationCodeError: String?,
    actionError: String?,
    onActivationCodeChange: (String) -> Unit,
    onActivateCode: () -> Unit,
    onOpenPurchaseContact: (String) -> Unit
) {
    QannasSectionTitle(
        title = "اشتراك قناص",
        subtitle = offer?.let {
            "السعر ${formatOfferPrice(it.priceMinor, it.currency)} لمدة ${ArabicCount.days(it.subscriptionDays)}."
        } ?: "لا نستطيع عرض السعر أو المدة الآن. أعد المحاولة قبل اتخاذ قرار الاشتراك."
    )
    PurchaseSteps(offer = offer, enabled = !isBusy, onOpenPurchase = onOpenPurchaseContact)
    ActivationCodeField(
        value = activationCode,
        onValueChange = onActivationCodeChange,
        enabled = !isBusy,
        error = activationCodeError,
        onDone = onActivateCode,
    )
    QannasAnimatedMessage(actionError, QannasTone.Error)
    QannasButton(
        text = "تفعيل الوصول",
        onClick = onActivateCode,
        enabled = !isBusy,
        busy = journey == AuthJourney.ActivatingCode,
        busyText = "جارٍ التحقق…",
    )
}

/** تطبيع واجهة فقط؛ يبقى التحقق الحاسم وتطبيع E.164 في NetworkInputValidator. */
internal object SaudiLoginInput {
    fun isSaudiMobile(value: String): Boolean {
        val compact = normalizeDigits(value).filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' }
        return Regex("^(?:\\+9665|009665|9665|05|5)\\d{8}$").matches(compact)
    }

    fun otpDigits(value: String): String = normalizeDigits(value).filter(Char::isDigit)

    fun normalizeOtp(value: String): String = otpDigits(value)

    fun isSixDigitOtp(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any { character ->
                character !in '0'..'9' && character !in '\u0660'..'\u0669' && character !in '\u06f0'..'\u06f9'
            }) {
            return false
        }
        return Regex("^\\d{6}$").matches(normalizeOtp(trimmed))
    }

    private fun normalizeDigits(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                in '0'..'9' -> append(character)
                in '\u0660'..'\u0669' -> append('0' + (character.code - '\u0660'.code))
                in '\u06f0'..'\u06f9' -> append('0' + (character.code - '\u06f0'.code))
                else -> append(character)
            }
        }
    }
}

private fun canStartTrial(snapshot: app.qannas.network.SubscriptionSnapshot?, offer: PublicOffer?): Boolean =
    snapshot?.trialEligible == true &&
        snapshot.trialEnabled == true &&
        offer?.trialEnabled == true &&
        offer.trialDays > 0

private fun formatOfferPrice(minor: Int, currency: String): String {
    val riyals = minor / 100
    val halalas = (minor % 100).toString().padStart(2, '0')
    val currencyLabel = if (currency.equals("SAR", ignoreCase = true)) "ر.س" else currency
    return "$riyals.$halalas $currencyLabel"
}

private fun googleErrorMessage(error: Throwable?): String {
    val msg = error?.message?.trim()
    if (!msg.isNullOrBlank()) {
        return msg
    }
    return "تعذر إتمام الدخول باستخدام Google. حاول مجددًا بعد قليل. [G-UNKNOWN]"
}

private fun otpRequestErrorMessage(error: Throwable?): String = when (error?.message) {
    "تم تجاوز حد المحاولات؛ يرجى الانتظار" -> "تم تجاوز حد طلب الرموز. انتظر ثم أعد المحاولة."
    "أدخل رقم جوال سعودي بصيغة +9665XXXXXXXX" -> "أدخل رقم جوال سعودي صالحًا."
    else -> "تعذر إرسال رمز التحقق. تحقق من الرقم والاتصال ثم أعد المحاولة."
}

private fun otpVerificationErrorMessage(error: Throwable?): String = when (error?.message) {
    "رمز التحقق يجب أن يتكون من 6 أرقام" -> "أدخل رمز التحقق المكوّن من 6 أرقام."
    "اطلب رمز تحقق جديدًا قبل التحقق" -> "اطلب رمز تحقق جديدًا أولًا."
    "تم تجاوز حد المحاولات؛ يرجى الانتظار" -> "تم تجاوز حد المحاولات. انتظر ثم اطلب رمزًا جديدًا."
    else -> "تعذر التحقق من الرمز. تأكد من الرمز أو اطلب رمزًا جديدًا."
}

private fun licenseErrorMessage(error: Throwable?): String = when (error?.message) {
    "الحساب مرتبط بجهاز آخر",
    "تم حظر أو رفض الحساب",
    "حساب السائق غير مكتمل؛ أعد تسجيل الدخول أو تواصل مع الإدارة",
    "حساب السائق غير مكتمل؛ تواصل مع الإدارة",
    "يلزم تحديث التطبيق قبل استخدام الخدمة" -> error.message.orEmpty()
    else -> "تعذر تأكيد حالة الاشتراك. تحقق من الاتصال ثم أعد المحاولة."
}

private fun trialErrorMessage(error: Throwable?): String = when (error?.message) {
    "تعذر بدء التجربة" -> "لا يمكن بدء التجربة لحسابك الآن."
    "تحقق من الترخيص والجهاز قبل بدء التجربة" -> "سجّل الدخول مجددًا ثم أعد محاولة بدء التجربة."
    "تم تجاوز حد المحاولات؛ يرجى الانتظار" -> "تم تجاوز حد المحاولات. انتظر قبل إعادة المحاولة."
    else -> "تعذر بدء التجربة الآن. تحقق من الاتصال ثم أعد المحاولة."
}

private fun activationErrorMessage(error: Throwable?): String = when (error?.message) {
    "رمز التفعيل غير صالح", "الرمز غير صحيح أو استُخدم مسبقًا" -> "رمز التفعيل غير صالح. راجعه ثم أعد المحاولة."
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> "انتهت جلسة الدخول. سجّل الدخول من جديد."
    else -> "تعذر قبول رمز التفعيل. راجع الرمز والاتصال ثم أعد المحاولة."
}

private fun authProgressLabel(journey: AuthJourney): String = when (journey) {
    AuthJourney.Authenticating -> "جارٍ التحقق…"
    AuthJourney.CheckingRole -> "جارٍ مراجعة الحساب…"
    AuthJourney.CheckingLicense -> "جارٍ تأكيد الوصول…"
    AuthJourney.StartingTrial -> "جارٍ تأكيد التجربة…"
    AuthJourney.ActivatingCode -> "جارٍ تفعيل الاشتراك…"
    AuthJourney.AwaitingAccess -> "بانتظار التفعيل"
    AuthJourney.DeviceTransfer -> "جارٍ نقل الحساب…"
    AuthJourney.Idle -> "متابعة"
}

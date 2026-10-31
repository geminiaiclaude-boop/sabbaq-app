package app.sabbaq.ui

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
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sabbaq.R
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import app.sabbaq.BuildConfig
import app.sabbaq.Settings
import app.sabbaq.network.DeviceTransferLimitException
import app.sabbaq.network.LicenseState
import app.sabbaq.network.LicenseStateException
import app.sabbaq.network.PublicOffer
import app.sabbaq.network.SupabaseClient
import app.sabbaq.service.KeeperService
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.ui.theme.SabbaqDepthContent
import app.sabbaq.ui.theme.sabbaqEntrance
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
    var phone by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var otpRequested by rememberSaveable { mutableStateOf(false) }
    var otpCooldownEndsAt by rememberSaveable { mutableStateOf(0L) }
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
    var activationCode by rememberSaveable { mutableStateOf("") }
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
            errorMessage = context.getString(R.string.login_err_access_confirm)
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
                offerErrorMessage = context.getString(R.string.login_err_offer_details)
            }
        )
        loadingOffer = false
    }

    // The public offer (trial length, price) feeds the choice cards before sign-in.
    LaunchedEffect(Unit) { if (offer == null) refreshPublicOffer() }

    fun checkRoleAndLicense(token: String) {
        journey = AuthJourney.CheckingRole
        scope.launch {
            try {
                val adminResult = SupabaseClient.isAdmin(token)
                if (adminResult.isFailure) {
                    SupabaseClient.clearSession()
                    errorMessage = context.getString(R.string.login_err_confirm_account)
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
                    errorMessage = context.getString(R.string.login_err_identify_device)
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
                            errorMessage = licenseErrorMessage(context, failure)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SupabaseClient.clearSession()
                errorMessage = context.getString(R.string.login_err_account_access)
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
                                ?.let { context.getString(R.string.login_err_transfer_limit_until, it) }
                                ?: context.getString(R.string.login_err_transfer_limit)
                            else -> failure.message ?: context.getString(R.string.login_err_transfer_generic)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                transferError = context.getString(R.string.login_err_transfer_retry)
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
                    errorMessage = googleErrorMessage(context, result.exceptionOrNull())
                    return@launch
                }
                clearPhoneInput()
                checkRoleAndLicense(result.getOrThrow())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unexpected exception's message is English/internal text; never show it.
                SupabaseClient.clearSession()
                errorMessage = context.getString(R.string.login_err_google_runtime)
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
                    errorMessage = otpRequestErrorMessage(context, result.exceptionOrNull())
                    return@launch
                }
                otp = ""
                otpRequested = true
                otpValidationRequested = false
                otpCooldownEndsAt = SystemClock.elapsedRealtime() + OTP_COOLDOWN_MILLIS
                infoMessage = context.getString(R.string.login_info_otp_sent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errorMessage = context.getString(R.string.login_err_otp_send)
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
                    errorMessage = otpVerificationErrorMessage(context, result.exceptionOrNull())
                    return@launch
                }
                clearPhoneInput()
                checkRoleAndLicense(result.getOrThrow())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SupabaseClient.clearSession()
                errorMessage = context.getString(R.string.login_err_otp_confirm)
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
                    Celebrations.accessGranted(SupabaseClient.subscriptionSnapshot())
                    completeLogin(isAdmin)
                } else {
                    activationError = trialErrorMessage(context, result.exceptionOrNull())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activationError = context.getString(R.string.login_err_trial_start)
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
                    activationError = activationErrorMessage(context, redeemed.exceptionOrNull())
                    return@launch
                }
                val revalidated = SupabaseClient.revalidateLicense()
                if (revalidated.getOrNull() == true && SupabaseClient.hasValidLicense()) {
                    Celebrations.accessGranted(SupabaseClient.subscriptionSnapshot())
                    completeLogin(isAdmin)
                } else {
                    activationError = licenseErrorMessage(context, revalidated.exceptionOrNull())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                activationError = context.getString(R.string.login_err_code_confirm)
            } finally {
                if (journey != AuthJourney.Idle) journey = AuthJourney.AwaitingAccess
            }
        }
    }

    val phoneError = if (phoneValidationRequested && !SaudiLoginInput.isSaudiMobile(phone)) {
        stringResource(R.string.login_err_phone_format)
    } else {
        null
    }
    val otpError = if (otpValidationRequested && !SaudiLoginInput.isSixDigitOtp(otp)) {
        stringResource(R.string.login_err_otp_format)
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
                SabbaqDepthContent(target = awaitingAccess, order = { if (it) 1 else 0 }, label = "auth-flow") { isPendingAccess ->
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
                                        activationError = context.getString(R.string.login_err_open_contact)
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
                    } else SabbaqDepthContent(
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
                        Text(stringResource(R.string.login_works_with), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SupportedPlatformsRow()
                    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!knownSubscriber) {
                            TextButton(
                                onClick = { accessIntent = AccessIntent.None; resetMessages() },
                                enabled = !isBusy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.login_change_choice))
                            }
                        }
                        AccessIntentBanner(accessIntent, offer)
                        AuthenticationCard(
                            title = when (accessIntent) {
                                AccessIntent.Trial -> stringResource(R.string.login_title_trial)
                                AccessIntent.Subscribe -> stringResource(R.string.login_title_subscribe)
                                else -> stringResource(R.string.login_title_signin)
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
                    stringResource(R.string.login_privacy_consent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SabbaqLegalLinks(showDeletion = false)
            }
        }
    }
}

@Composable
private fun AuthBrandHero() {
    val brand = LocalSabbaqBrand.current
    SabbaqHeroSurface(Modifier.sabbaqEntrance(0)) {
        SabbaqBrand(onHero = true)
        Text(
            text = stringResource(R.string.login_hero_subtitle),
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
            ?.let { stringResource(R.string.login_intent_trial_days, pluralStringResource(R.plurals.days_count, it.trialDays, it.trialDays)) }
            ?: stringResource(R.string.login_intent_trial)
        AccessIntent.Subscribe -> stringResource(R.string.login_intent_subscribe)
        else -> null
    } ?: return
    SabbaqMessage(text, tone = SabbaqTone.Info)
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.login_or), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    SabbaqCard(
        title = title,
        subtitle = stringResource(R.string.login_card_subtitle),
        icon = Icons.Outlined.Login
    ) {
        SabbaqButton(
            text = stringResource(R.string.login_continue_google),
            onClick = onGoogle,
            busy = googleBusy,
            busyText = authProgressLabel(journey),
            enabled = !isBusy,
        )
        if (phoneAvailable) {
        OrDivider()
        SabbaqSectionTitle(
            title = stringResource(R.string.login_saudi_mobile),
            subtitle = if (otpRequested) stringResource(R.string.login_otp_prompt_entered) else stringResource(R.string.login_otp_prompt)
        )
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            enabled = !isBusy,
            singleLine = true,
            label = { Text(stringResource(R.string.login_saudi_mobile)) },
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
            val otpStateDesc = stringResource(R.string.login_otp_desc)
            OutlinedTextField(
                value = otp,
                onValueChange = onOtpChange,
                enabled = !isBusy,
                singleLine = true,
                label = { Text(stringResource(R.string.login_otp_label)) },
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
                    .semantics { stateDescription = otpStateDesc }
            )
        }
        }

        SabbaqAnimatedMessage(infoMessage, SabbaqTone.Success)
        SabbaqAnimatedMessage(serverError, SabbaqTone.Error)

        if (phoneAvailable && otpRequested) {
            SabbaqButton(
                text = stringResource(R.string.login_verify_continue),
                onClick = onVerifyOtp,
                busy = isBusy && !googleBusy,
                busyText = authProgressLabel(journey),
            )
            SabbaqButton(
                text = if (otpCooldownSeconds > 0) stringResource(R.string.login_resend_in, otpCooldownSeconds) else stringResource(R.string.login_resend),
                onClick = onRequestOtp,
                enabled = !isBusy && otpCooldownSeconds == 0,
                style = SabbaqButtonStyle.Text,
                icon = Icons.Outlined.Autorenew,
            )
        } else if (phoneAvailable) {
            SabbaqButton(
                text = stringResource(R.string.login_send_otp),
                onClick = onRequestOtp,
                enabled = !isBusy && otpCooldownSeconds == 0,
                busy = isBusy && !googleBusy,
                busyText = stringResource(R.string.login_sending_otp),
                style = SabbaqButtonStyle.Secondary,
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
    SabbaqCard(
        title = stringResource(R.string.login_transfer_title),
        subtitle = stringResource(R.string.login_transfer_subtitle),
        icon = Icons.Outlined.PhonelinkSetup,
    ) {
        Text(
            stringResource(R.string.login_transfer_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        SabbaqAnimatedMessage(error, SabbaqTone.Error)
        if (supportUrl != null) {
            SabbaqButton(stringResource(R.string.login_transfer_whatsapp), onClick = { onOpenSupport(supportUrl) },
                icon = Icons.AutoMirrored.Outlined.Chat)
        } else {
            SabbaqButton(
                text = stringResource(R.string.login_transfer_here),
                onClick = onTransfer,
                busy = busy,
                busyText = stringResource(R.string.login_transfer_busy),
                icon = Icons.Outlined.PhonelinkSetup,
            )
        }
        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.login_signout_return))
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
    // Every way in is one tap away; the first page's choice only decides which tab opens first.
    val tabs = if (BuildConfig.DIRECT_DISTRIBUTION) AccessTab.entries.toList() else listOf(AccessTab.Trial)
    var tab by rememberSaveable { mutableStateOf(intent.toTab().takeIf { it in tabs } ?: tabs.first()) }
    SabbaqCard(
        title = stringResource(R.string.login_start_title),
        subtitle = stringResource(R.string.login_start_subtitle),
        icon = Icons.Outlined.Key
    ) {
        if (tabs.size > 1) SabbaqTabs(tabs = tabs, selected = tab, onSelect = { tab = it })
        offerError?.let {
            SabbaqMessage(message = it, isError = true)
            TextButton(
                onClick = onRetryOffer,
                enabled = !isBusy && !loadingOffer,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.login_retry)) }
        }

        when (tab) {
            AccessTab.Trial -> when {
                loadingOffer -> SabbaqStatusPill(text = stringResource(R.string.login_trial_preparing), ready = false)
                showTrial -> {
                    SabbaqSectionTitle(
                        title = stringResource(R.string.login_trial_card_title),
                        subtitle = stringResource(R.string.login_trial_card_subtitle, pluralStringResource(R.plurals.days_count, offer?.trialDays ?: 0, offer?.trialDays ?: 0))
                    )
                    SabbaqButton(
                        text = stringResource(R.string.login_start_trial),
                        onClick = onStartTrial,
                        enabled = !isBusy,
                        busy = journey == AuthJourney.StartingTrial,
                        busyText = stringResource(R.string.login_confirming_trial),
                    )
                    actionError?.let { SabbaqMessage(message = it, isError = true) }
                }
                else -> {
                    // Never a silent dead end: say why, and offer the next step.
                    SabbaqMessage(
                        if (offer?.trialEnabled == false) stringResource(R.string.login_trial_unavailable)
                        else stringResource(R.string.login_trial_used)
                    )
                    if (BuildConfig.DIRECT_DISTRIBUTION) {
                        SabbaqButton(stringResource(R.string.login_show_subscription), onClick = { tab = AccessTab.Subscribe }, style = SabbaqButtonStyle.Secondary)
                    } else {
                        SabbaqMessage(stringResource(R.string.login_no_purchase))
                    }
                }
            }
            AccessTab.Subscribe -> DirectAccessOptions(
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
            AccessTab.Code -> {
                SabbaqSectionTitle(
                    title = stringResource(R.string.login_code_title),
                    subtitle = stringResource(R.string.login_code_subtitle)
                )
                ActivationCodeField(
                    value = activationCode,
                    onValueChange = onActivationCodeChange,
                    enabled = !isBusy,
                    error = activationCodeError,
                    onDone = onActivateCode,
                )
                SabbaqAnimatedMessage(actionError, SabbaqTone.Error)
                SabbaqButton(
                    text = stringResource(R.string.login_activate),
                    onClick = onActivateCode,
                    enabled = !isBusy,
                    busy = journey == AuthJourney.ActivatingCode,
                    busyText = stringResource(R.string.login_verifying),
                )
            }
        }

        TextButton(
            onClick = onCancel,
            enabled = !isBusy,
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
        ) { Text(stringResource(R.string.login_other_account)) }
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
    SabbaqSectionTitle(
        title = stringResource(R.string.login_subscription_title),
        subtitle = offer?.let {
            stringResource(R.string.login_subscription_price, formatOfferPrice(it.priceMinor, it.currency, stringResource(R.string.currency_sar)), pluralStringResource(R.plurals.days_count, it.subscriptionDays, it.subscriptionDays))
        } ?: stringResource(R.string.login_subscription_no_price)
    )
    PurchaseSteps(offer = offer, enabled = !isBusy, onOpenPurchase = onOpenPurchaseContact)
    ActivationCodeField(
        value = activationCode,
        onValueChange = onActivationCodeChange,
        enabled = !isBusy,
        error = activationCodeError,
        onDone = onActivateCode,
    )
    SabbaqAnimatedMessage(actionError, SabbaqTone.Error)
    SabbaqButton(
        text = stringResource(R.string.login_activate),
        onClick = onActivateCode,
        enabled = !isBusy,
        busy = journey == AuthJourney.ActivatingCode,
        busyText = stringResource(R.string.login_verifying),
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

private fun canStartTrial(snapshot: app.sabbaq.network.SubscriptionSnapshot?, offer: PublicOffer?): Boolean =
    snapshot?.trialEligible == true &&
        snapshot.trialEnabled == true &&
        offer?.trialEnabled == true &&
        offer.trialDays > 0

private fun formatOfferPrice(minor: Int, currency: String, sarLabel: String): String {
    val riyals = minor / 100
    val halalas = (minor % 100).toString().padStart(2, '0')
    val currencyLabel = if (currency.equals("SAR", ignoreCase = true)) sarLabel else currency
    return "$riyals.$halalas $currencyLabel"
}

private fun googleErrorMessage(context: Context, error: Throwable?): String {
    val msg = error?.message?.trim()
    if (!msg.isNullOrBlank()) {
        return msg
    }
    return context.getString(R.string.login_err_google_unknown)
}

/** A message already written for the driver, carrying its support code («… [OTP-EXPIRED]»), is shown as is. */
private fun codedMessage(error: Throwable?): String? = error?.message?.takeIf { Regex("""\[[A-Z0-9-]{3,24}]""").containsMatchIn(it) }

private fun otpRequestErrorMessage(context: Context, error: Throwable?): String = when (error?.message) {
    "تم تجاوز حد المحاولات؛ يرجى الانتظار" -> context.getString(R.string.login_err_otp_rate)
    "أدخل رقم جوال سعودي بصيغة +9665XXXXXXXX" -> context.getString(R.string.login_err_phone_invalid)
    else -> codedMessage(error) ?: context.getString(R.string.login_err_otp_send)
}

private fun otpVerificationErrorMessage(context: Context, error: Throwable?): String = when (error?.message) {
    "رمز التحقق يجب أن يتكون من 6 أرقام" -> context.getString(R.string.login_err_otp_six)
    "اطلب رمز تحقق جديدًا قبل التحقق" -> context.getString(R.string.login_err_otp_request_first)
    "تم تجاوز حد المحاولات؛ يرجى الانتظار" -> context.getString(R.string.login_err_otp_rate_new)
    else -> codedMessage(error) ?: context.getString(R.string.login_err_otp_verify)
}

private fun licenseErrorMessage(context: Context, error: Throwable?): String = when (error?.message) {
    "الحساب مرتبط بجهاز آخر" -> context.getString(R.string.login_err_device_other)
    "تم حظر أو رفض الحساب" -> context.getString(R.string.login_err_banned)
    "حساب السائق غير مكتمل؛ أعد تسجيل الدخول أو تواصل مع الإدارة" -> context.getString(R.string.login_err_incomplete_relogin)
    "حساب السائق غير مكتمل؛ تواصل مع الإدارة" -> context.getString(R.string.login_err_incomplete_admin)
    "يلزم تحديث التطبيق قبل استخدام الخدمة" -> context.getString(R.string.login_err_update_required)
    else -> codedMessage(error) ?: context.getString(R.string.login_err_license_generic)
}

private fun trialErrorMessage(context: Context, error: Throwable?): String = when (error?.message) {
    "تعذر بدء التجربة" -> context.getString(R.string.login_err_trial_cannot)
    "تحقق من الترخيص والجهاز قبل بدء التجربة" -> context.getString(R.string.login_err_trial_relogin)
    "تم تجاوز حد المحاولات؛ يرجى الانتظار" -> context.getString(R.string.login_err_trial_rate)
    else -> context.getString(R.string.login_err_trial_generic2)
}

private fun activationErrorMessage(context: Context, error: Throwable?): String = when (error?.message) {
    "رمز التفعيل غير صالح", "الرمز غير صحيح أو استُخدم مسبقًا" -> context.getString(R.string.login_err_code_invalid)
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> context.getString(R.string.login_err_code_session)
    "محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجددًا" -> context.getString(R.string.login_err_code_rate)
    "هذا الحساب مرتبط بجهاز آخر" -> context.getString(R.string.login_err_code_device)
    else -> context.getString(R.string.login_err_code_generic)
}

@Composable
private fun authProgressLabel(journey: AuthJourney): String = stringResource(when (journey) {
    AuthJourney.Authenticating -> R.string.login_progress_auth
    AuthJourney.CheckingRole -> R.string.login_progress_role
    AuthJourney.CheckingLicense -> R.string.login_progress_license
    AuthJourney.StartingTrial -> R.string.login_progress_trial
    AuthJourney.ActivatingCode -> R.string.login_progress_code
    AuthJourney.AwaitingAccess -> R.string.login_progress_awaiting
    AuthJourney.DeviceTransfer -> R.string.login_progress_transfer
    AuthJourney.Idle -> R.string.login_progress_continue
})

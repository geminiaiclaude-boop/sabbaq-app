package app.sabbaq.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Share
import app.sabbaq.CrashLog
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sabbaq.BuildConfig
import app.sabbaq.R
import app.sabbaq.LocaleManager
import androidx.compose.ui.res.stringResource
import app.sabbaq.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.network.LicenseState
import app.sabbaq.network.PublicOffer
import app.sabbaq.network.SubscriptionSnapshot
import app.sabbaq.network.SupabaseClient
import app.sabbaq.telemetry.TelemetryRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * شاشة اشتراك لا تنشئ صلاحية محلية: كل الحالة والتواريخ المعروضة صادرة من آخر
 * `license_status` للخادم، وكل منح تجربة/كود يعاد فحصه عبر العميل الشبكي.
 *
 * يفصل شرط [BuildConfig.DIRECT_DISTRIBUTION] مسار APK المباشر عن Play عند البناء؛
 * نسخة Play لا تركّب حقول الأكواد أو رابطًا خارجيًا أو سعرًا من خادم APK.
 */
@Composable
internal fun SubscriptionScreen(
    licenseInMemory: Boolean,
    externalMessage: String?,
    externalMessageIsError: Boolean,
    onLicenseChanged: (Boolean) -> Unit,
    onMessage: (String?, Boolean) -> Unit,
    onAccountDeleted: () -> Unit = {},
    onOpenSupport: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<SupabaseClient.AccountProfile?>(null) }
    // The account page starts asynchronous reads on first composition. Keep a destructive
    // confirmation open if that work causes the screen to be recreated.
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    fun deleteAccount() {
        if (isDeleting) return
        isDeleting = true
        deleteError = null
        scope.launch {
            try {
                SupabaseClient.deleteAccount().fold(
                    onSuccess = {
                        showDeleteDialog = false
                        onAccountDeleted()
                    },
                    onFailure = { deleteError = it.message?.takeIf { message -> message.any { ch -> ch in '؀'..'ۿ' } } ?: (if (LocaleManager.isArabic()) "تعذر حذف الحساب الآن. حاول مجددًا بعد قليل." else "Could not delete the account now. Try again shortly.") },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                deleteError = (if (LocaleManager.isArabic()) "تعذر حذف الحساب الآن. تحقق من الاتصال ثم أعد المحاولة." else "Could not delete the account now. Check the connection and try again.")
            } finally {
                isDeleting = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // للعرض فقط: فشل القراءة لا يغيّر الوصول ولا يظهر خطأ؛ تبقى البطاقة بلا معرّف.
        profile = SupabaseClient.accountIdentity().getOrNull()
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            isDeleting = isDeleting,
            error = deleteError,
            onConfirm = ::deleteAccount,
            onDismiss = { if (!isDeleting) { showDeleteDialog = false; deleteError = null } },
        )
    }
    val context = LocalContext.current
    val isDirectDistribution = BuildConfig.DIRECT_DISTRIBUTION
    var snapshot by remember { mutableStateOf(SupabaseClient.subscriptionSnapshot()) }
    var offer by remember { mutableStateOf<PublicOffer?>(null) }
    var isLoadingOffer by remember { mutableStateOf(false) }
    var isRefreshingLicense by remember { mutableStateOf(false) }
    var isStartingTrial by remember { mutableStateOf(false) }
    var activationCode by remember { mutableStateOf("") }
    var isRedeemingCode by remember { mutableStateOf(false) }

    suspend fun refreshLicense(showResult: Boolean) {
        if (isRefreshingLicense) return
        if (!SupabaseClient.hasRedeemableSession()) {
            snapshot = SupabaseClient.subscriptionSnapshot()
            onLicenseChanged(SupabaseClient.hasValidLicense())
            if (showResult) {
                onMessage((if (LocaleManager.isArabic()) "سجّل الدخول مجددًا ثم حدّث حالة الوصول." else "Sign in again, then refresh the access status."), true)
            }
            return
        }

        isRefreshingLicense = true
        try {
            val result = SupabaseClient.revalidateLicense()
            snapshot = SupabaseClient.subscriptionSnapshot()
            onLicenseChanged(SupabaseClient.hasValidLicense())
            if (snapshot?.state == LicenseState.ACTIVE) Settings.knownSubscriber = true
            if (showResult) {
                when (result.getOrNull()) {
                    true -> onMessage((if (LocaleManager.isArabic()) "تم تحديث حالة الوصول إلى حسابك." else "Your account's access status has been updated."), false)
                    false -> onMessage((if (LocaleManager.isArabic()) "تغيّرت حالة الوصول، لكنها لا تتيح استخدام الخدمة الآن." else "The access status changed, but it does not allow using the service now."), true)
                    null -> onMessage(subscriptionRequestError(result.exceptionOrNull()), true)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snapshot = SupabaseClient.subscriptionSnapshot()
            onLicenseChanged(SupabaseClient.hasValidLicense())
            if (showResult) onMessage((if (LocaleManager.isArabic()) "تعذر تحديث حالة الوصول. تحقق من الاتصال ثم أعد المحاولة." else "Could not refresh the access status. Check the connection and try again."), true)
        } finally {
            isRefreshingLicense = false
        }
    }

    suspend fun loadOffer() {
        if (!isDirectDistribution || isLoadingOffer) return
        isLoadingOffer = true
        offer = null // لا نُبقي سعرًا أو مدة قديمين عند فشل طلب الخادم.
        try {
            offer = SupabaseClient.fetchPublicOffer().getOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // يظل العرض null؛ لا قيمة سعر/مدة بديلة عند فشل العميل أو الشبكة.
            offer = null
        } finally {
            isLoadingOffer = false
        }
    }

    fun beginTrial() {
        if (isStartingTrial || isRefreshingLicense || !SupabaseClient.hasRedeemableSession()) return
        isStartingTrial = true
        onMessage(null, false)
        scope.launch {
            try {
                val result = SupabaseClient.startTrial()
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                when (result.getOrNull()) {
                    true -> {
                        onMessage((if (LocaleManager.isArabic()) "بدأت التجربة. يمكنك الآن استكشاف أدوات سبّاق حتى موعد انتهائها الظاهر في حالة الوصول." else "The trial has started. You can now explore Sabbaq's tools until its end date shown in the access status."), false)
                        if (SupabaseClient.hasValidLicense()) Celebrations.accessGranted(snapshot)
                    }
                    false -> onMessage((if (LocaleManager.isArabic()) "تغيّرت حالة التجربة، لكنها غير متاحة للاستخدام الآن." else "The trial status changed, but it is not available for use now."), true)
                    null -> onMessage(subscriptionRequestError(result.exceptionOrNull()), true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                onMessage((if (LocaleManager.isArabic()) "تعذر بدء التجربة الآن. تحقق من الاتصال ثم أعد المحاولة." else "Could not start the trial now. Check the connection and try again."), true)
            } finally {
                isStartingTrial = false
            }
        }
    }

    fun redeemDirectCode() {
        if (isRedeemingCode || isRefreshingLicense || !isDirectDistribution) return
        val validationError = UiFormValidation.activationCodeError(activationCode)
        when {
            validationError != null -> {
                onMessage(validationError, true)
                return
            }
            !SupabaseClient.hasRedeemableSession() -> {
                onMessage((if (LocaleManager.isArabic()) "سجّل الدخول مجددًا ثم أرسل رمز التفعيل." else "Sign in again, then send the activation code."), true)
                return
            }
        }

        isRedeemingCode = true
        onMessage(null, false)
        scope.launch {
            try {
                val redemption = SupabaseClient.redeemCode(activationCode)
                if (redemption.getOrNull() != true) {
                    snapshot = SupabaseClient.subscriptionSnapshot()
                    onLicenseChanged(SupabaseClient.hasValidLicense())
                    onMessage(directCodeError(redemption.exceptionOrNull()), true)
                    return@launch
                }

                // قبول الكود ليس lease: لا نعلن الصلاحية قبل إعادة فحص license_status.
                val revalidation = SupabaseClient.revalidateLicense()
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                if (revalidation.getOrNull() == true && SupabaseClient.hasValidLicense()) {
                    activationCode = ""
                    onMessage((if (LocaleManager.isArabic()) "تم تفعيل الوصول إلى حسابك." else "Access to your account has been activated."), false)
                    if (snapshot?.state == LicenseState.ACTIVE) Settings.knownSubscriber = true
                    Celebrations.accessGranted(snapshot)
                } else {
                    onMessage(
                        if (revalidation.isFailure) subscriptionRequestError(revalidation.exceptionOrNull())
                        else (if (LocaleManager.isArabic()) "تمت معالجة الرمز، لكن لا يمكن تأكيد الوصول إلى حسابك الآن." else "The code was processed, but access to your account cannot be confirmed now."),
                        true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                onMessage((if (LocaleManager.isArabic()) "تعذر تأكيد رمز التفعيل. تحقق من الاتصال ثم أعد المحاولة." else "Could not confirm the activation code. Check the connection and try again."), true)
            } finally {
                isRedeemingCode = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // لا يوجد تجاوز مصادقة: العميل الشبكي يرفض الجلسة أو الجهاز غير المربوط.
        refreshLicense(showResult = false)
    }
    LaunchedEffect(isDirectDistribution) {
        if (isDirectDistribution) loadOffer()
    }

    // A paying subscriber is never sold to: no trial, no purchase steps. Until the server
    // answers, a device that already saw this account subscribed is treated as subscribed.
    val subscribed = snapshot?.state == LicenseState.ACTIVE ||
        (snapshot == null && Settings.knownSubscriber)

    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AccountHeader(
            profile = profile,
            snapshot = snapshot,
            leaseInMemory = licenseInMemory,
            periodDays = if (snapshot?.state == LicenseState.TRIAL) snapshot?.trialDays else offer?.subscriptionDays,
            isRefreshing = isRefreshingLicense,
            onRefresh = { scope.launch { refreshLicense(showResult = true) } },
        )

        SabbaqAnimatedMessage(externalMessage, if (externalMessageIsError) SabbaqTone.Error else SabbaqTone.Info)

        AccountSectionHeader(stringResource(R.string.sub_sec_subscription))
        if (!subscribed) TrialCard(
            snapshot = snapshot,
            isStarting = isStartingTrial,
            isBusy = isRefreshingLicense || isRedeemingCode,
            hasBoundSession = SupabaseClient.hasRedeemableSession(),
            onStart = ::beginTrial,
        )
        if (isDirectDistribution) {
            DirectDistributionCard(
                offer = offer,
                subscribed = subscribed,
                subscribedUntil = snapshot?.proUntil ?: snapshot?.validUntil,
                isLoadingOffer = isLoadingOffer,
                isBusy = isRefreshingLicense || isStartingTrial || isRedeemingCode,
                activationCode = activationCode,
                onCodeChange = {
                    activationCode = UiFormValidation.normalizeActivationCode(it.take(128))
                    onMessage(null, false)
                },
                onRedeem = ::redeemDirectCode,
                onRetryOffer = { scope.launch { loadOffer() } },
                onOpenContact = { contactUrl ->
                    val contactUri = Uri.parse(contactUrl)
                    val isSafeContact = contactUri.scheme == "https" &&
                        !contactUri.host.isNullOrBlank() && contactUri.userInfo.isNullOrBlank()
                    val opened = isSafeContact && runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, contactUri))
                    }.isSuccess
                    if (!opened) onMessage((if (LocaleManager.isArabic()) "تعذر فتح قناة التواصل. أعد المحاولة أو تواصل مع الإدارة." else "Could not open the contact channel. Try again or contact the administration."), true)
                },
            )
        } else {
            PlayInformationCard()
        }

        AccountSectionHeader(stringResource(R.string.sub_sec_device))
        DeviceCard(leaseInMemory = licenseInMemory, state = snapshot?.state)

        AccountSectionHeader(stringResource(R.string.sub_sec_help))
        HelpCard(onOpenSupport = onOpenSupport)
        CrashReportCard()

        AccountSectionHeader(stringResource(R.string.sub_sec_privacy))
        PrivacyCard(identity = profile?.identity, onSignOut = onSignOut)
        SabbaqCard(title = stringResource(R.string.sub_delete_title), icon = Icons.Outlined.DeleteForever, destructive = true) {
            Text(
                stringResource(R.string.sub_delete_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SabbaqButton(
                text = stringResource(R.string.sub_delete_btn),
                onClick = { deleteError = null; showDeleteDialog = true },
                style = SabbaqButtonStyle.Secondary,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                icon = Icons.Outlined.DeleteForever,
            )
        }
    }
}

/**
 * Who is signed in and where their access stands, at a glance: avatar, name and masked sign-in,
 * the plan badge, days left with a bar for the period, and when the server last confirmed it.
 */
@Composable
private fun AccountHeader(
    profile: SupabaseClient.AccountProfile?,
    snapshot: SubscriptionSnapshot?,
    leaseInMemory: Boolean,
    periodDays: Int?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val brand = LocalSabbaqBrand.current
    val status = snapshot?.state?.let(::licenseStatePresentation)
    val until = when (snapshot?.state) {
        LicenseState.TRIAL -> snapshot?.trialUntil ?: snapshot?.validUntil
        LicenseState.ACTIVE -> snapshot?.proUntil ?: snapshot?.validUntil
        else -> null
    }
    val now = Instant.now()
    val daysLeft = until?.let { CelebrationCopy.daysLeft(it, now) }
    SabbaqHeroSurface(scene = false) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(brand.signature), contentAlignment = Alignment.Center) {
                val initial = profile?.displayName?.firstOrNull { it.isLetter() }
                if (initial != null) Text(initial.toString(), style = MaterialTheme.typography.headlineSmall, color = brand.onSignature)
                else Icon(Icons.Outlined.Person, contentDescription = null, tint = brand.onSignature, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile?.displayName ?: stringResource(R.string.sub_your_account),
                    style = MaterialTheme.typography.titleLarge,
                    color = brand.onHero,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                profile?.let {
                    val registeredAccountCd = stringResource(R.string.sub_registered_account, it.identity)
                    Text(
                        (if (it.viaGoogle) stringResource(R.string.sub_via_google) else "") + it.identity,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        color = brand.onHeroMuted,
                        modifier = Modifier.semantics { contentDescription = registeredAccountCd },
                    )
                }
            }
            PlanBadge(snapshot?.state)
        }
        Text(
            when {
                snapshot == null -> stringResource(R.string.sub_header_update_hint)
                status?.isOperational == true && leaseInMemory -> CelebrationCopy.validity(until, now) ?: status?.label.orEmpty()
                status?.isOperational == true -> stringResource(R.string.sub_header_unconfirmed)
                else -> status?.label ?: stringResource(R.string.sub_header_no_status)
            },
            style = MaterialTheme.typography.titleMedium,
            color = brand.onHero,
        )
        if (daysLeft != null && periodDays != null && periodDays > 0) {
            val daysLeftCd = stringResource(R.string.sub_days_left_cd, ArabicCount.days(daysLeft.toInt()))
            val fraction = (daysLeft.toFloat() / periodDays).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                    .semantics { contentDescription = daysLeftCd },
                color = if (daysLeft <= RENEWAL_WINDOW_DAYS) brand.signature else brand.heroAccent,
                trackColor = brand.onHero.copy(alpha = 0.18f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                snapshot?.serverTime?.let { stringResource(R.string.sub_last_check, formatSaudiDate(it)) } ?: stringResource(R.string.sub_not_checked),
                style = MaterialTheme.typography.labelSmall,
                color = brand.onHeroMuted,
                modifier = Modifier.weight(1f),
            )
            SabbaqButton(
                text = stringResource(R.string.sub_refresh),
                onClick = onRefresh,
                busy = isRefreshing,
                busyText = stringResource(R.string.sub_refreshing),
                style = SabbaqButtonStyle.Text,
                icon = Icons.Outlined.Refresh,
                fillWidth = false,
                colors = ButtonDefaults.textButtonColors(contentColor = brand.onHero),
            )
        }
    }
}

@Composable
private fun PlanBadge(state: LicenseState?) {
    val brand = LocalSabbaqBrand.current
    val (label, container, ink) = when (state) {
        LicenseState.ACTIVE -> Triple("PRO", brand.signature, brand.onSignature)
        LicenseState.TRIAL -> Triple(stringResource(R.string.sub_plan_trial), brand.onHero.copy(alpha = 0.2f), brand.onHero)
        null -> return
        else -> Triple(stringResource(R.string.sub_plan_inactive), brand.onHero.copy(alpha = 0.12f), brand.onHeroMuted)
    }
    Surface(shape = CircleShape, color = container, contentColor = ink) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun AccountSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp).semantics { heading() },
    )
}

/** One labelled row inside an account card: icon, label, value and an optional trailing action. */
@Composable
private fun AccountRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    valueLtr: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = if (valueLtr) TextDirection.Ltr else TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
        if (onClick != null && trailing == null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** This phone's standing: whether access is confirmed here, the app version and the support id. */
@Composable
private fun DeviceCard(leaseInMemory: Boolean, state: LicenseState?) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val correlationId = TelemetryRepository.currentCorrelationId()
    var copied by rememberSaveable { mutableStateOf(false) }
    SabbaqCard {
        AccountRow(
            icon = Icons.Outlined.Smartphone,
            label = stringResource(R.string.sub_this_phone),
            value = when {
                leaseInMemory -> stringResource(R.string.sub_phone_linked)
                state == LicenseState.DEVICE_MISMATCH -> stringResource(R.string.sub_phone_mismatch)
                else -> stringResource(R.string.sub_phone_unverified)
            },
            trailing = { SabbaqStatusPill(if (leaseInMemory) stringResource(R.string.ss_ready_pill) else stringResource(R.string.sub_unconfirmed), ready = leaseInMemory) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AccountRow(icon = Icons.Outlined.Info, label = stringResource(R.string.sub_app_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", valueLtr = true)
        correlationId?.let { id ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AccountRow(
                icon = Icons.Outlined.Fingerprint,
                label = stringResource(R.string.sub_support_id),
                value = id,
                valueLtr = true,
                trailing = {
                    val copyCd = if (copied) stringResource(R.string.sub_support_id_copied_cd) else stringResource(R.string.sub_support_id_copy_cd)
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(id)); copied = true },
                        modifier = Modifier.semantics { contentDescription = copyCd },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (copied) stringResource(R.string.sub_copied) else stringResource(R.string.sub_copy))
                    }
                },
            )
        }
    }
}

@Composable
private fun HelpCard(onOpenSupport: (() -> Unit)?) {
    if (onOpenSupport == null) return
    SabbaqCard {
        AccountRow(
            icon = Icons.Outlined.SupportAgent,
            label = stringResource(R.string.sub_contact_support),
            value = stringResource(R.string.sub_contact_support_sub),
            onClick = onOpenSupport,
        )
    }
}

@Composable
private fun PrivacyCard(identity: String?, onSignOut: (() -> Unit)?) {
    val context = LocalContext.current
    SabbaqCard {
        AccountRow(
            icon = Icons.Outlined.PrivacyTip,
            label = stringResource(R.string.sub_privacy_policy),
            value = stringResource(R.string.sub_privacy_policy_sub),
            onClick = { PublicLinks.open(context, PublicLinks.PRIVACY_POLICY) },
        )
        identity?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AccountRow(icon = Icons.Outlined.VerifiedUser, label = stringResource(R.string.sub_secure_login), value = stringResource(R.string.sub_secure_login_sub, it))
        }
        onSignOut?.let { signOut ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AccountRow(icon = Icons.AutoMirrored.Outlined.Logout, label = stringResource(R.string.sub_sign_out), value = stringResource(R.string.sub_sign_out_sub), onClick = signOut)
        }
    }
}

/**
 * Appears only after a crash was recorded on this device. Sharing is the driver's explicit
 * choice through the system share sheet; nothing is uploaded automatically.
 */
@Composable
private fun CrashReportCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report ?: return
    SabbaqCard(
        title = stringResource(R.string.sub_crash_title),
        subtitle = stringResource(R.string.sub_crash_sub),
        icon = Icons.Outlined.BugReport,
    ) {
        SabbaqButton(
            text = stringResource(R.string.sub_crash_share),
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, (if (LocaleManager.isArabic()) "تقرير عطل سبّاق" else "Sabbaq crash report"))
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, (if (LocaleManager.isArabic()) "مشاركة التقرير" else "Share the report"))) }
            },
            style = SabbaqButtonStyle.Secondary,
            icon = Icons.Outlined.Share,
        )
        SabbaqButton(
            text = stringResource(R.string.sub_crash_delete),
            onClick = { if (CrashLog.clear(context)) report = null },
            style = SabbaqButtonStyle.Text,
        )
    }
}

/** Typed confirmation keeps a destructive, irreversible action from being a single mis-tap. */
@Composable
private fun DeleteAccountDialog(
    isDeleting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val confirmed = AccountDeletionConfirmation.matches(typed)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.sub_del_dlg_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sub_del_dlg_body))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(32) },
                    enabled = !isDeleting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.sub_del_dlg_type, AccountDeletionConfirmation.WORD)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { SabbaqMessage(it, tone = SabbaqTone.Error) }
            }
        },
        confirmButton = {
            SabbaqButton(
                text = stringResource(R.string.sub_del_dlg_confirm),
                onClick = onConfirm,
                enabled = confirmed,
                busy = isDeleting,
                busyText = stringResource(R.string.sub_deleting),
                style = SabbaqButtonStyle.Destructive,
                fillWidth = false,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ss_cancel)) }
        },
    )
}

/** The word the driver types; normalised so Arabic keyboard variants of the same word match. */
internal object AccountDeletionConfirmation {
    val WORD: String get() = if (LocaleManager.isArabic()) "حذف" else "DELETE"
    fun matches(input: String): Boolean {
        val normalized = input.trim().replace("ـ", "")
        return normalized == "حذف" || normalized.equals("DELETE", ignoreCase = true)
    }
}

private fun formatSaudiDate(value: Instant): String =
    DateTimeFormatter.ofPattern(if (LocaleManager.isArabic()) "dd MMMM yyyy، HH:mm" else "dd MMMM yyyy, HH:mm", sabbaqDateLocale())
        .withZone(ZoneId.of("Asia/Riyadh"))
        .format(value)

@Composable
private fun TrialCard(
    snapshot: SubscriptionSnapshot?,
    isStarting: Boolean,
    isBusy: Boolean,
    hasBoundSession: Boolean,
    onStart: () -> Unit,
) {
    val presentation = CustomerCommerceCopy.trialPresentation(snapshot)
    SabbaqCard(
        title = stringResource(R.string.sub_trial_title),
        subtitle = stringResource(R.string.sub_trial_sub),
        icon = Icons.Outlined.Info,
    ) {
        Text(presentation.detail)
        if (presentation.canStart) {
            SabbaqButton(
                text = stringResource(R.string.sub_trial_start),
                onClick = onStart,
                enabled = !isBusy && hasBoundSession,
                busy = isStarting,
                busyText = stringResource(R.string.sub_trial_starting),
            )
        }
    }
}

@Composable
private fun DirectDistributionCard(
    offer: PublicOffer?,
    subscribed: Boolean,
    subscribedUntil: Instant?,
    isLoadingOffer: Boolean,
    isBusy: Boolean,
    activationCode: String,
    onCodeChange: (String) -> Unit,
    onRedeem: () -> Unit,
    onRetryOffer: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    // Subscribers see their membership, not an offer. Renewal stays one tap away, and opens
    // by itself in the last days so access never lapses by surprise.
    val daysLeft = subscribedUntil?.let { CelebrationCopy.daysLeft(it, Instant.now()) }
    var renewOpen by rememberSaveable { mutableStateOf(false) }
    val showRenewal = !subscribed || renewOpen || (daysLeft != null && daysLeft <= RENEWAL_WINDOW_DAYS)
    if (subscribed && !showRenewal) {
        SabbaqCard {
            AccountRow(
                icon = Icons.Outlined.WorkspacePremium,
                label = stringResource(R.string.sub_renew_early),
                value = stringResource(R.string.sub_renew_early_sub),
                onClick = { renewOpen = true },
            )
        }
        return
    }

    SabbaqCard(
        title = if (subscribed) stringResource(R.string.sub_renew_title) else stringResource(R.string.sub_subscribe_title),
        subtitle = stringResource(R.string.sub_subscribe_sub),
        icon = Icons.Outlined.Info,
    ) {
        when {
            isLoadingOffer -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.sub_preparing_price))
            }
            offer == null -> {
                Text(stringResource(R.string.sub_offer_unavailable))
                TextButton(onClick = onRetryOffer, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ss_retry))
                }
            }
            else -> {
                Text(
                    CustomerCommerceCopy.offerSummary(offer),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!subscribed && offer.trialEnabled && offer.trialDays > 0) Text(
                    stringResource(R.string.sub_trial_maybe, ArabicCount.days(offer.trialDays)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PurchaseSteps(offer = offer, enabled = !isBusy, onOpenPurchase = onOpenContact)
            }
        }
    }

    SabbaqCard(
        title = stringResource(R.string.sub_have_code_title),
        subtitle = stringResource(R.string.sub_have_code_sub),
        icon = Icons.Outlined.VerifiedUser,
    ) {
        ActivationCodeField(
            value = activationCode,
            onValueChange = onCodeChange,
            enabled = !isBusy,
            error = null,
            onDone = onRedeem,
        )
        SabbaqButton(
            text = stringResource(R.string.sub_activate_btn),
            onClick = onRedeem,
            enabled = activationCode.isNotBlank() && !isBusy,
        )
    }
}

private const val RENEWAL_WINDOW_DAYS = 5L

@Composable
private fun PlayInformationCard() {
    SabbaqCard(
        title = stringResource(R.string.sub_play_title),
        subtitle = stringResource(R.string.sub_play_sub),
        icon = Icons.Outlined.Info,
    ) {
        Text(
            stringResource(R.string.sub_play_1),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.sub_play_2),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.sub_play_3),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal data class CustomerTrialPresentation(
    val detail: String,
    val canStart: Boolean,
)

/** نصوص تجارية تقرأ قيم العرض وحالة الحساب كما تصل، من دون قيم بديلة في العميل. */
internal object CustomerCommerceCopy {
    fun offerSummary(offer: PublicOffer): String {
        val currencyLabel = if (offer.currency.equals("SAR", ignoreCase = true)) (if (LocaleManager.isArabic()) "ر.س" else "SAR") else offer.currency
        return if (LocaleManager.isArabic())
            "${formatServerPrice(offer.priceMinor)} $currencyLabel لمدة ${ArabicCount.days(offer.subscriptionDays)}"
        else
            "${formatServerPrice(offer.priceMinor)} $currencyLabel for ${ArabicCount.days(offer.subscriptionDays)}"
    }

    fun trialPresentation(snapshot: SubscriptionSnapshot?): CustomerTrialPresentation = when {
        snapshot == null -> CustomerTrialPresentation(
            detail = if (LocaleManager.isArabic()) "حدّث حالة الاشتراك لمعرفة ما إذا كانت التجربة متاحة لحسابك." else "Refresh the subscription status to see whether a trial is available for your account.",
            canStart = false,
        )
        snapshot.state == LicenseState.TRIAL -> CustomerTrialPresentation(
            detail = snapshot.trialUntil?.let {
                if (LocaleManager.isArabic()) "تجربتك فعّالة حتى ${formatSaudiDate(it)} بتوقيت المملكة العربية السعودية."
                else "Your trial is active until ${formatSaudiDate(it)} Saudi time."
            } ?: (if (LocaleManager.isArabic()) "تجربتك فعّالة. حدّث حالة الوصول لعرض تاريخ انتهائها." else "Your trial is active. Refresh the access status to see its end date."),
            canStart = false,
        )
        snapshot.trialEnabled == false -> CustomerTrialPresentation(
            detail = if (LocaleManager.isArabic()) "لا تتوفر تجربة لحسابك حاليًا." else "No trial is available for your account currently.",
            canStart = false,
        )
        snapshot.trialEligible == false -> CustomerTrialPresentation(
            detail = if (LocaleManager.isArabic()) "لا يتوفر لهذا الحساب استحقاق تجربة جديدة." else "This account is not eligible for a new trial.",
            canStart = false,
        )
        snapshot.trialEnabled != true || snapshot.trialEligible != true || (snapshot.trialDays ?: 0) <= 0 ->
            CustomerTrialPresentation(
                detail = if (LocaleManager.isArabic()) "لا نستطيع تأكيد تفاصيل التجربة لحسابك الآن. حدّث الحالة ثم أعد المحاولة." else "We cannot confirm your account's trial details now. Refresh the status and try again.",
                canStart = false,
            )
        else -> CustomerTrialPresentation(
            detail = if (LocaleManager.isArabic()) "تجربة لمدة ${ArabicCount.days(snapshot.trialDays)}. تبدأ فقط عند ضغط «ابدأ التجربة»، ولا تبدأ تلقائيًا." else "A ${ArabicCount.days(snapshot.trialDays)} trial. It starts only when you press \"Start the trial\", not automatically.",
            canStart = true,
        )
    }
}

private data class LicenseStatePresentation(val label: String, val isOperational: Boolean)

private fun licenseStatePresentation(state: LicenseState): LicenseStatePresentation {
    val ar = LocaleManager.isArabic()
    return when (state) {
        LicenseState.ACTIVE -> LicenseStatePresentation(if (ar) "اشتراك فعّال" else "Active subscription", true)
        LicenseState.TRIAL -> LicenseStatePresentation(if (ar) "تجربة فعّالة" else "Active trial", true)
        LicenseState.PENDING -> LicenseStatePresentation(if (ar) "بانتظار تفعيل الوصول" else "Awaiting access activation", false)
        LicenseState.EXPIRED -> LicenseStatePresentation(if (ar) "انتهى الوصول" else "Access has ended", false)
        LicenseState.DEVICE_MISMATCH -> LicenseStatePresentation(if (ar) "حسابك مفعّل على جهاز آخر؛ سجّل الدخول هنا لنقله" else "Your account is active on another device; sign in here to move it", false)
        LicenseState.BANNED -> LicenseStatePresentation(if (ar) "لا يمكن استخدام الحساب حاليًا" else "The account cannot be used currently", false)
        LicenseState.REJECTED -> LicenseStatePresentation(if (ar) "لا يمكن استخدام الحساب حاليًا" else "The account cannot be used currently", false)
        LicenseState.UNREGISTERED -> LicenseStatePresentation(if (ar) "أكمل بيانات حسابك" else "Complete your account details", false)
    }
}

/** يحول هللات الخادم فقط إلى عرض SAR؛ لا توجد قيمة افتراضية في العميل. */
private fun formatServerPrice(priceMinor: Int): String {
    val whole = priceMinor / 100
    val fraction = priceMinor % 100
    return String.format(Locale.ROOT, "%d.%02d", whole, fraction)
}

private fun subscriptionRequestError(error: Throwable?): String {
    val ar = LocaleManager.isArabic()
    return when (error?.message) {
        "انتهت الجلسة؛ سجّل الدخول من جديد" -> if (ar) "انتهت الجلسة؛ سجّل الدخول من جديد ثم أعد التحقق." else "Your session ended; sign in again, then re-verify."
        "تحقق من الترخيص والجهاز قبل المتابعة" -> if (ar) "لا يمكن تأكيد الحساب على هذا الجهاز." else "The account cannot be confirmed on this device."
        "الحساب مرتبط بجهاز آخر" -> if (ar) "الحساب مرتبط بجهاز آخر" else "The account is linked to another device."
        "تم حظر أو رفض الحساب" -> if (ar) "تم حظر أو رفض الحساب" else "The account has been banned or rejected."
        "يلزم تحديث التطبيق قبل استخدام الخدمة" -> if (ar) "يلزم تحديث التطبيق قبل استخدام الخدمة" else "The app must be updated before using the service."
        else -> if (ar) "تعذر تأكيد حالة الوصول. تحقق من الاتصال ثم أعد المحاولة." else "Could not confirm the access status. Check the connection and try again."
    }
}

private fun directCodeError(error: Throwable?): String {
    val ar = LocaleManager.isArabic()
    return when (error?.message) {
        "الرمز غير صحيح أو استُخدم مسبقًا" -> if (ar) "رمز التفعيل غير صحيح أو استُخدم مسبقًا. راجعه ثم أعد المحاولة." else "The activation code is incorrect or already used. Check it and try again."
        "محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجددًا" -> if (ar) "محاولات كثيرة؛ انتظر قليلًا ثم أعد المحاولة." else "Too many attempts; wait a little, then try again."
        "هذا الحساب مرتبط بجهاز آخر" -> if (ar) "نُقل حسابك إلى جهاز آخر. سجّل الدخول هنا مجددًا لنقله إلى هذا الجهاز." else "Your account moved to another device. Sign in here again to move it to this device."
        "انتهت الجلسة؛ سجّل الدخول من جديد" -> if (ar) "انتهت الجلسة؛ سجّل الدخول من جديد ثم أعد إرسال رمز التفعيل." else "Your session ended; sign in again, then resend the activation code."
        else -> if (ar) "تعذر إرسال رمز التفعيل. تحقق من الرمز والاتصال ثم أعد المحاولة." else "Could not send the activation code. Check the code and the connection, then try again."
    }
}

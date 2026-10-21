package app.sabbaq.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Share
import app.sabbaq.CrashLog
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import app.sabbaq.ui.theme.sabbaqEntrance
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sabbaq.BuildConfig
import app.sabbaq.network.LicenseState
import app.sabbaq.network.PublicOffer
import app.sabbaq.network.SubscriptionSnapshot
import app.sabbaq.network.SupabaseClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
) {
    val scope = rememberCoroutineScope()
    var identity by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
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
                    onFailure = { deleteError = it.message ?: "تعذر حذف الحساب الآن. حاول مجددًا بعد قليل." },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                deleteError = "تعذر حذف الحساب الآن. تحقق من الاتصال ثم أعد المحاولة."
            } finally {
                isDeleting = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // للعرض فقط: فشل القراءة لا يغيّر الوصول ولا يظهر خطأ؛ تبقى البطاقة بلا معرّف.
        identity = SupabaseClient.accountIdentity().getOrNull()
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
                onMessage("سجّل الدخول مجددًا ثم حدّث حالة الوصول.", true)
            }
            return
        }

        isRefreshingLicense = true
        try {
            val result = SupabaseClient.revalidateLicense()
            snapshot = SupabaseClient.subscriptionSnapshot()
            onLicenseChanged(SupabaseClient.hasValidLicense())
            if (showResult) {
                when (result.getOrNull()) {
                    true -> onMessage("تم تحديث حالة الوصول إلى حسابك.", false)
                    false -> onMessage("تغيّرت حالة الوصول، لكنها لا تتيح استخدام الخدمة الآن.", true)
                    null -> onMessage(subscriptionRequestError(result.exceptionOrNull()), true)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snapshot = SupabaseClient.subscriptionSnapshot()
            onLicenseChanged(SupabaseClient.hasValidLicense())
            if (showResult) onMessage("تعذر تحديث حالة الوصول. تحقق من الاتصال ثم أعد المحاولة.", true)
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
                    true -> onMessage("بدأت التجربة. يمكنك الآن استكشاف أدوات سبّاق حتى موعد انتهائها الظاهر في حالة الوصول.", false)
                    false -> onMessage("تغيّرت حالة التجربة، لكنها غير متاحة للاستخدام الآن.", true)
                    null -> onMessage(subscriptionRequestError(result.exceptionOrNull()), true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                onMessage("تعذر بدء التجربة الآن. تحقق من الاتصال ثم أعد المحاولة.", true)
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
                onMessage("سجّل الدخول مجددًا ثم أرسل رمز التفعيل.", true)
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
                    onMessage("تم تفعيل الوصول إلى حسابك.", false)
                } else {
                    onMessage(
                        if (revalidation.isFailure) subscriptionRequestError(revalidation.exceptionOrNull())
                        else "تمت معالجة الرمز، لكن لا يمكن تأكيد الوصول إلى حسابك الآن.",
                        true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                onMessage("تعذر تأكيد رمز التفعيل. تحقق من الاتصال ثم أعد المحاولة.", true)
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

    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SabbaqSectionTitle(
            title = "اشتراكك في سبّاق",
            subtitle = "تابع تجربتك ومدة اشتراكك من مكان واحد.",
        )

        LicenseStatusCard(
            snapshot = snapshot,
            leaseInMemory = licenseInMemory,
            isRefreshing = isRefreshingLicense,
            onRefresh = { scope.launch { refreshLicense(showResult = true) } },
        )

        // A paying subscriber is never shown trial options.
        val subscribed = snapshot?.state == LicenseState.ACTIVE
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
                    if (!opened) onMessage("تعذر فتح قناة التواصل. أعد المحاولة أو تواصل مع الإدارة.", true)
                },
            )
        } else {
            PlayInformationCard()
        }

        SabbaqAnimatedMessage(externalMessage, if (externalMessageIsError) SabbaqTone.Error else SabbaqTone.Info)

        SabbaqCard(title = "معلومات حسابك", icon = Icons.Outlined.PrivacyTip, modifier = Modifier.sabbaqEntrance(3)) {
            identity?.let {
                Text(
                    "تسجيل الدخول بـ: $it",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { contentDescription = "الحساب المسجّل: $it" },
                )
            }
            Text(
                "نستخدم بيانات حسابك لتأكيد هويتك وحماية الوصول إليه.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SabbaqLegalLinks()
        }

        CrashReportCard()

        SabbaqCard(title = "حذف الحساب", icon = Icons.Outlined.DeleteForever, modifier = Modifier.sabbaqEntrance(4), destructive = true) {
            Text(
                "يحذف حسابك واشتراكك وشروطك وبيانات الجهاز المرتبطة نهائيًا. لا يمكن التراجع عن الحذف، ولا تُستعاد مدة اشتراك متبقية.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SabbaqButton(
                text = "حذف حسابي",
                onClick = { deleteError = null; showDeleteDialog = true },
                style = SabbaqButtonStyle.Secondary,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                icon = Icons.Outlined.DeleteForever,
            )
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
        title = "تقرير عطل سابق",
        subtitle = "توقّف التطبيق مرة على هذا الجهاز. التقرير تقني فقط ولا يحتوي بياناتك.",
        icon = Icons.Outlined.BugReport,
    ) {
        SabbaqButton(
            text = "مشاركة التقرير مع الدعم",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "تقرير عطل سبّاق")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { context.startActivity(Intent.createChooser(send, "مشاركة التقرير")) }
            },
            style = SabbaqButtonStyle.Secondary,
            icon = Icons.Outlined.Share,
        )
        SabbaqButton(
            text = "حذف التقرير",
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
        title = { Text("حذف الحساب نهائيًا؟") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("سيُحذف حسابك واشتراكك وكل البيانات المرتبطة به، وسيتوقف سبّاق فورًا. إذا استخدمت التجربة سابقًا فلن تتاح لك تجربة جديدة بنفس الرقم أو الجهاز.")
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(32) },
                    enabled = !isDeleting,
                    singleLine = true,
                    label = { Text("اكتب «${AccountDeletionConfirmation.WORD}» للتأكيد") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { SabbaqMessage(it, tone = SabbaqTone.Error) }
            }
        },
        confirmButton = {
            SabbaqButton(
                text = "حذف نهائي",
                onClick = onConfirm,
                enabled = confirmed,
                busy = isDeleting,
                busyText = "جارٍ الحذف",
                style = SabbaqButtonStyle.Destructive,
                fillWidth = false,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting, modifier = Modifier.heightIn(min = 48.dp)) { Text("إلغاء") }
        },
    )
}

/** The word the driver types; normalised so Arabic keyboard variants of the same word match. */
internal object AccountDeletionConfirmation {
    const val WORD = "حذف"
    fun matches(input: String): Boolean = input.trim().replace("ـ", "") == WORD
}

@Composable
private fun LicenseStatusCard(
    snapshot: SubscriptionSnapshot?,
    leaseInMemory: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val status = snapshot?.state?.let(::licenseStatePresentation)
    SabbaqCard(
        title = "حالة اشتراكك",
        subtitle = "تجربتك واشتراكك في مكان واحد.",
        icon = Icons.Outlined.VerifiedUser,
    ) {
        SabbaqStatusPill(
            text = status?.label ?: "لا تتوفر حالة مؤكدة الآن",
            ready = status?.isOperational == true,
        )
        Text(
            text = when {
                snapshot == null -> "حدّث الحالة لمعرفة مدة اشتراكك وخيارات تجربتك."
                leaseInMemory -> "سبّاق جاهز للاستخدام مع حسابك. اضبط شروطك وانطلق."
                else -> "تعذر تأكيد اشتراكك الآن. حدّث الحالة؛ التواريخ أدناه من آخر تحقق."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (snapshot != null) {
            ServerTimestampRows(snapshot)
        }
        SabbaqButton(
            text = "تحديث الاشتراك",
            onClick = onRefresh,
            busy = isRefreshing,
            busyText = "جارٍ تحديث الحالة",
            style = SabbaqButtonStyle.Secondary,
            icon = Icons.Outlined.Refresh,
        )
    }
}

@Composable
private fun ServerTimestampRows(snapshot: SubscriptionSnapshot) {
    ServerTimestampRow("آخر تحديث للحالة", snapshot.serverTime)
    when (snapshot.state) {
        LicenseState.TRIAL -> ServerTimestampRow("تنتهي التجربة", snapshot.trialUntil)
        LicenseState.ACTIVE -> ServerTimestampRow("ينتهي الاشتراك", snapshot.proUntil)
        else -> Unit
    }
    Text(
        "التواريخ بتوقيت المملكة العربية السعودية.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServerTimestampRow(label: String, value: Instant?) {
    if (value == null) return
    Text(
        "$label: ${formatSaudiDate(value)}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun formatSaudiDate(value: Instant): String =
    DateTimeFormatter.ofPattern("dd MMMM yyyy، HH:mm", Locale("ar", "SA"))
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
        title = "التجربة",
        subtitle = "اكتشف القبول حسب شروطك، ثم قرر إن كان يناسب يومك.",
        icon = Icons.Outlined.Info,
    ) {
        Text(presentation.detail)
        if (presentation.canStart) {
            SabbaqButton(
                text = "ابدأ التجربة",
                onClick = onStart,
                enabled = !isBusy && hasBoundSession,
                busy = isStarting,
                busyText = "جارٍ بدء التجربة",
            )
        }
    }
}

@Composable
private fun DirectDistributionCard(
    offer: PublicOffer?,
    subscribed: Boolean,
    isLoadingOffer: Boolean,
    isBusy: Boolean,
    activationCode: String,
    onCodeChange: (String) -> Unit,
    onRedeem: () -> Unit,
    onRetryOffer: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    SabbaqCard(
        title = if (subscribed) "تجديد اشتراكك" else "اشتراك سبّاق",
        subtitle = "شروطك للأجرة والعائد لكل كم ومسافة الوصول وأوقات العمل، مع قبول تلقائي للطلب المطابق.",
        icon = Icons.Outlined.Info,
    ) {
        when {
            isLoadingOffer -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("نجهّز لك السعر والمدة…")
            }
            offer == null -> {
                Text("تفاصيل الاشتراك غير متاحة الآن. أعد المحاولة.")
                TextButton(onClick = onRetryOffer, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("إعادة المحاولة")
                }
            }
            else -> {
                Text(
                    CustomerCommerceCopy.offerSummary(offer),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!subscribed && offer.trialEnabled && offer.trialDays > 0) Text(
                    "قد تتوفر تجربة لمدة ${ArabicCount.days(offer.trialDays)} للحسابات المؤهلة. راجع بطاقة التجربة لتأكيد أهلية حسابك.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PurchaseSteps(offer = offer, enabled = !isBusy, onOpenPurchase = onOpenContact)
            }
        }
    }

    SabbaqCard(
        title = "لديك رمز تفعيل؟",
        subtitle = "أدخل الرمز الذي استلمته لتفعيل اشتراكك.",
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
            text = "تفعيل اشتراكي",
            onClick = onRedeem,
            enabled = activationCode.isNotBlank() && !isBusy,
        )
    }
}

@Composable
private fun PlayInformationCard() {
    SabbaqCard(
        title = "اشتراكك في التطبيق",
        subtitle = "تابع تجربتك واشتراكك من هنا.",
        icon = Icons.Outlined.Info,
    ) {
        Text(
            "تظهر حالة تجربتك أو اشتراكك أعلاه.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "لاستخدام سبّاق، تحتاج تجربة أو اشتراكًا ساريًا مع ضبط شروطك ومنح الأذونات.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "شراء الاشتراك داخل التطبيق غير متاح حاليًا.",
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
        val currencyLabel = if (offer.currency.equals("SAR", ignoreCase = true)) "ر.س" else offer.currency
        return "${formatServerPrice(offer.priceMinor)} $currencyLabel لمدة ${ArabicCount.days(offer.subscriptionDays)}"
    }

    fun trialPresentation(snapshot: SubscriptionSnapshot?): CustomerTrialPresentation = when {
        snapshot == null -> CustomerTrialPresentation(
            detail = "حدّث حالة الاشتراك لمعرفة ما إذا كانت التجربة متاحة لحسابك.",
            canStart = false,
        )
        snapshot.state == LicenseState.TRIAL -> CustomerTrialPresentation(
            detail = snapshot.trialUntil?.let {
                "تجربتك فعّالة حتى ${formatSaudiDate(it)} بتوقيت المملكة العربية السعودية."
            } ?: "تجربتك فعّالة. حدّث حالة الوصول لعرض تاريخ انتهائها.",
            canStart = false,
        )
        snapshot.trialEnabled == false -> CustomerTrialPresentation(
            detail = "لا تتوفر تجربة لحسابك حاليًا.",
            canStart = false,
        )
        snapshot.trialEligible == false -> CustomerTrialPresentation(
            detail = "لا يتوفر لهذا الحساب استحقاق تجربة جديدة.",
            canStart = false,
        )
        snapshot.trialEnabled != true || snapshot.trialEligible != true || (snapshot.trialDays ?: 0) <= 0 ->
            CustomerTrialPresentation(
                detail = "لا نستطيع تأكيد تفاصيل التجربة لحسابك الآن. حدّث الحالة ثم أعد المحاولة.",
                canStart = false,
            )
        else -> CustomerTrialPresentation(
            detail = "تجربة لمدة ${ArabicCount.days(snapshot.trialDays)}. تبدأ فقط عند ضغط «ابدأ التجربة»، ولا تبدأ تلقائيًا.",
            canStart = true,
        )
    }
}

private data class LicenseStatePresentation(val label: String, val isOperational: Boolean)

private fun licenseStatePresentation(state: LicenseState): LicenseStatePresentation = when (state) {
    LicenseState.ACTIVE -> LicenseStatePresentation("اشتراك فعّال", true)
    LicenseState.TRIAL -> LicenseStatePresentation("تجربة فعّالة", true)
    LicenseState.PENDING -> LicenseStatePresentation("بانتظار تفعيل الوصول", false)
    LicenseState.EXPIRED -> LicenseStatePresentation("انتهى الوصول", false)
    LicenseState.DEVICE_MISMATCH -> LicenseStatePresentation("حسابك مفعّل على جهاز آخر؛ سجّل الدخول هنا لنقله", false)
    LicenseState.BANNED -> LicenseStatePresentation("لا يمكن استخدام الحساب حاليًا", false)
    LicenseState.REJECTED -> LicenseStatePresentation("لا يمكن استخدام الحساب حاليًا", false)
    LicenseState.UNREGISTERED -> LicenseStatePresentation("أكمل بيانات حسابك", false)
}

/** يحول هللات الخادم فقط إلى عرض SAR؛ لا توجد قيمة افتراضية في العميل. */
private fun formatServerPrice(priceMinor: Int): String {
    val whole = priceMinor / 100
    val fraction = priceMinor % 100
    return String.format(Locale.ROOT, "%d.%02d", whole, fraction)
}

private fun subscriptionRequestError(error: Throwable?): String = when (error?.message) {
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> "انتهت الجلسة؛ سجّل الدخول من جديد ثم أعد التحقق."
    "تحقق من الترخيص والجهاز قبل المتابعة" -> "لا يمكن تأكيد الحساب على هذا الجهاز."
    "الحساب مرتبط بجهاز آخر", "تم حظر أو رفض الحساب", "يلزم تحديث التطبيق قبل استخدام الخدمة" -> error.message.orEmpty()
    else -> "تعذر تأكيد حالة الوصول. تحقق من الاتصال ثم أعد المحاولة."
}

private fun directCodeError(error: Throwable?): String = when (error?.message) {
    "الرمز غير صحيح أو استُخدم مسبقًا" -> "رمز التفعيل غير صحيح أو استُخدم مسبقًا. راجعه ثم أعد المحاولة."
    "محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجددًا" -> "محاولات كثيرة؛ انتظر قليلًا ثم أعد المحاولة."
    "هذا الحساب مرتبط بجهاز آخر" -> "نُقل حسابك إلى جهاز آخر. سجّل الدخول هنا مجددًا لنقله إلى هذا الجهاز."
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> "انتهت الجلسة؛ سجّل الدخول من جديد ثم أعد إرسال رمز التفعيل."
    else -> "تعذر إرسال رمز التفعيل. تحقق من الرمز والاتصال ثم أعد المحاولة."
}

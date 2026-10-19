package app.qannas.ui

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
import androidx.compose.material.icons.outlined.Info
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
import app.qannas.BuildConfig
import app.qannas.network.LicenseState
import app.qannas.network.PublicOffer
import app.qannas.network.SubscriptionSnapshot
import app.qannas.network.SupabaseClient
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
 * نسخة Play لا تركّب حقول الأكواد أو رابطاً خارجياً أو سعراً من خادم APK.
 */
@Composable
internal fun SubscriptionScreen(
    licenseInMemory: Boolean,
    externalMessage: String?,
    externalMessageIsError: Boolean,
    onLicenseChanged: (Boolean) -> Unit,
    onMessage: (String?, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
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
                onMessage("سجّل الدخول مجدداً ثم حدّث حالة الوصول.", true)
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
        offer = null // لا نُبقي سعراً أو مدة قديمين عند فشل طلب الخادم.
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
                    true -> onMessage("بدأت التجربة. يمكنك الآن استكشاف أدوات قناص حتى موعد انتهائها الظاهر في حالة الوصول.", false)
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
                onMessage("سجّل الدخول مجدداً ثم أرسل رمز التفعيل.", true)
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
        QannasSectionTitle(
            title = "اشتراكك في قناص",
            subtitle = "خلّ شروطك تعمل لصالحك. تابع تجربتك ومدة اشتراكك من هنا.",
        )

        LicenseStatusCard(
            snapshot = snapshot,
            leaseInMemory = licenseInMemory,
            isRefreshing = isRefreshingLicense,
            onRefresh = { scope.launch { refreshLicense(showResult = true) } },
        )

        TrialCard(
            snapshot = snapshot,
            isStarting = isStartingTrial,
            isBusy = isRefreshingLicense || isRedeemingCode,
            hasBoundSession = SupabaseClient.hasRedeemableSession(),
            onStart = ::beginTrial,
        )

        if (isDirectDistribution) {
            DirectDistributionCard(
                offer = offer,
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

        externalMessage?.let { QannasMessage(it, isError = externalMessageIsError) }

        QannasCard(title = "معلومات حسابك", icon = Icons.Outlined.PrivacyTip) {
            Text(
                "نستخدم بيانات حسابك لتأكيد هويتك وحماية الوصول إليه.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "رقم الجوال محفوظ بأمان في حسابك لطلب رمز الدخول وتأكيد الوصول عند الحاجة.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LicenseStatusCard(
    snapshot: SubscriptionSnapshot?,
    leaseInMemory: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val status = snapshot?.state?.let(::licenseStatePresentation)
    QannasCard(
        title = "حالة اشتراكك",
        subtitle = "تجربتك واشتراكك في مكان واحد.",
        icon = Icons.Outlined.VerifiedUser,
    ) {
        QannasStatusPill(
            text = status?.label ?: "لا تتوفر حالة مؤكدة الآن",
            ready = status?.isOperational == true,
        )
        Text(
            text = when {
                snapshot == null -> "حدّث الحالة لمعرفة مدة اشتراكك وخيارات تجربتك."
                leaseInMemory -> "قناص جاهز للاستخدام مع حسابك. اضبط شروطك وانطلق."
                else -> "تعذر تأكيد اشتراكك الآن. حدّث الحالة؛ التواريخ أدناه من آخر تحقق."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (snapshot != null) {
            ServerTimestampRows(snapshot)
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("جارٍ تحديث الحالة")
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("تحديث الاشتراك")
            }
        }
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
    QannasCard(
        title = "التجربة",
        subtitle = "اكتشف القبول حسب شروطك، ثم قرر إن كان يناسب يومك.",
        icon = Icons.Outlined.Info,
    ) {
        Text(presentation.detail)
        if (presentation.canStart) {
            Button(
                onClick = onStart,
                enabled = !isStarting && !isBusy && hasBoundSession,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("جارٍ بدء التجربة")
                } else {
                    Text("ابدأ التجربة")
                }
            }
        }
    }
}

@Composable
private fun DirectDistributionCard(
    offer: PublicOffer?,
    isLoadingOffer: Boolean,
    isBusy: Boolean,
    activationCode: String,
    onCodeChange: (String) -> Unit,
    onRedeem: () -> Unit,
    onRetryOffer: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    QannasCard(
        title = "اشتراك قناص",
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
                Text(
                    if (offer.trialEnabled && offer.trialDays > 0) "قد تتوفر تجربة لمدة ${offer.trialDays} يومًا للحسابات المؤهلة. راجع بطاقة التجربة لتأكيد أهلية حسابك."
                    else "لا تتوفر تجربة ضمن العرض الحالي.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "راجع السعر والمدة مع الإدارة قبل الدفع. لا يبدأ الاشتراك أو التجربة تلقائياً.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                offer.purchaseContactUrl?.let { contactUrl ->
                    OutlinedButton(
                        onClick = { onOpenContact(contactUrl) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("التواصل مع الإدارة")
                    }
                }
            }
        }
    }

    QannasCard(
        title = "لديك رمز تفعيل؟",
        subtitle = "أدخل الكود الذي استلمته لتفعيل اشتراكك.",
        icon = Icons.Outlined.VerifiedUser,
    ) {
        OutlinedTextField(
            value = activationCode,
            onValueChange = onCodeChange,
            enabled = !isBusy,
            singleLine = true,
            label = { Text("رمز التفعيل") },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onRedeem,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("تفعيل اشتراكي")
        }
    }
}

@Composable
private fun PlayInformationCard() {
    QannasCard(
        title = "اشتراكك في التطبيق",
        subtitle = "تابع تجربتك واشتراكك من هنا.",
        icon = Icons.Outlined.Info,
    ) {
        Text(
            "تظهر حالة تجربتك أو اشتراكك أعلاه.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "لاستخدام قناص، تحتاج تجربة أو اشتراكًا ساريًا مع ضبط شروطك ومنح الأذونات.",
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
        return "${formatServerPrice(offer.priceMinor)} $currencyLabel لمدة ${offer.subscriptionDays} يومًا"
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
            detail = "لا تتوفر تجربة لحسابك حالياً.",
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
            detail = "تجربة لمدة ${snapshot.trialDays} يومًا. تبدأ فقط عند ضغط «ابدأ التجربة»، ولا تبدأ تلقائياً.",
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
    LicenseState.DEVICE_MISMATCH -> LicenseStatePresentation("الحساب مرتبط بجهاز آخر", false)
    LicenseState.BANNED -> LicenseStatePresentation("لا يمكن استخدام الحساب حالياً", false)
    LicenseState.REJECTED -> LicenseStatePresentation("لا يمكن استخدام الحساب حالياً", false)
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
    "الكود غير صحيح أو استُخدم مسبقاً" -> "رمز التفعيل غير صحيح أو استُخدم مسبقاً. راجعه ثم أعد المحاولة."
    "محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجدداً" -> "محاولات كثيرة؛ انتظر قليلاً ثم أعد المحاولة."
    "هذا الحساب مرتبط بجهاز آخر" -> "هذا الحساب مرتبط بجهاز آخر."
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> "انتهت الجلسة؛ سجّل الدخول من جديد ثم أعد إرسال رمز التفعيل."
    else -> "تعذر إرسال رمز التفعيل. تحقق من الرمز والاتصال ثم أعد المحاولة."
}

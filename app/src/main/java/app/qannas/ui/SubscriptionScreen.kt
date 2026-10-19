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
                onMessage("لا تتوفر جلسة مرتبطة بهذا الجهاز لإعادة التحقق من الترخيص.", true)
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
                    true -> onMessage("تم تحديث حالة الترخيص من الخادم.", false)
                    false -> onMessage("حدّث الخادم حالة الترخيص، لكنها لا تمنح تشغيل الخدمة حالياً.", true)
                    null -> onMessage(subscriptionRequestError(result.exceptionOrNull()), true)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            snapshot = SupabaseClient.subscriptionSnapshot()
            onLicenseChanged(SupabaseClient.hasValidLicense())
            if (showResult) onMessage("تعذر تحديث حالة الترخيص. تحقق من الاتصال ثم أعد المحاولة.", true)
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
                    true -> onMessage("بدأت التجربة بعد أن أكد الخادم الترخيص لهذا الجهاز.", false)
                    false -> onMessage("حدّث الخادم الحالة، لكنه لم يمنح ترخيص تجربة صالحاً للتشغيل.", true)
                    null -> onMessage(subscriptionRequestError(result.exceptionOrNull()), true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                onMessage("تعذر بدء التجربة. تحقق من الاتصال ثم أعد المحاولة.", true)
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
                onMessage("لا تتوفر جلسة مرتبطة بهذا الجهاز لإرسال الكود.", true)
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
                    onMessage("أكد الخادم حالة الترخيص لهذا الجهاز.", false)
                } else {
                    onMessage(
                        if (revalidation.isFailure) subscriptionRequestError(revalidation.exceptionOrNull())
                        else "تمت معالجة الكود، لكن الخادم لم يؤكد ترخيصاً صالحاً للتشغيل.",
                        true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = SupabaseClient.subscriptionSnapshot()
                onLicenseChanged(SupabaseClient.hasValidLicense())
                onMessage("تعذر التحقق من الكود. تحقق من الاتصال ثم أعد المحاولة.", true)
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
            title = "الاشتراك والترخيص",
            subtitle = "تابع حالة وصولك، واكتشف الميزات، وراجع موعد انتهاء التجربة أو الاشتراك.",
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
                    if (!opened) onMessage("تعذر فتح قناة التواصل التي أعادها الخادم.", true)
                },
            )
        } else {
            PlayInformationCard()
        }

        externalMessage?.let { QannasMessage(it, isError = externalMessageIsError) }

        QannasCard(title = "الخصوصية والمعلومات المحلية", icon = Icons.Outlined.PrivacyTip) {
            Text("الإصدار ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
            Text(
                "لا تُخزَّن رموز الجلسة أو بيانات الترخيص في الإعدادات المحلية. يبقى الترخيص في الذاكرة ويحتاج إلى إعادة تحقق من الخادم.",
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
        title = "حالة الترخيص",
        subtitle = "نتحقق من صلاحية حسابك قبل تشغيل الخدمة، ويمكنك تحديث الحالة متى احتجت.",
        icon = Icons.Outlined.VerifiedUser,
    ) {
        QannasStatusPill(
            text = status?.label ?: "لم تصل حالة خادم مؤكدة",
            ready = status?.isOperational == true,
        )
        Text(
            text = when {
                snapshot == null -> "اضغط إعادة التحقق لعرض حالة حسابك المؤكدة."
                leaseInMemory -> "وصولك مؤكد حاليًا. نعيد التحقق دوريًا لحماية حسابك."
                else -> "صلاحية التشغيل غير مؤكدة حاليًا. أعد التحقق؛ قد تكون المعلومات أدناه من آخر تحديث."
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
                Text("جارٍ التحقق من الخادم")
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("إعادة التحقق من الترخيص")
            }
        }
    }
}

@Composable
private fun ServerTimestampRows(snapshot: SubscriptionSnapshot) {
    ServerTimestampRow("وقت الخادم", snapshot.serverTime)
    ServerTimestampRow("نهاية صلاحية الترخيص", snapshot.validUntil)
    ServerTimestampRow("نهاية التجربة", snapshot.trialUntil)
    ServerTimestampRow("نهاية الاشتراك", snapshot.proUntil)
    Text(
        "التواريخ بتوقيت المملكة العربية السعودية، من آخر تحقق بالخادم.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ServerTimestampRow(label: String, value: Instant?) {
    if (value == null) return
    Text(
        "$label: ${DateTimeFormatter.ofPattern("dd MMMM yyyy، HH:mm", Locale("ar", "SA")).withZone(ZoneId.of("Asia/Riyadh")).format(value)}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun TrialCard(
    snapshot: SubscriptionSnapshot?,
    isStarting: Boolean,
    isBusy: Boolean,
    hasBoundSession: Boolean,
    onStart: () -> Unit,
) {
    val canStart = snapshot?.trialEligible == true && snapshot.trialEnabled == true
    QannasCard(
        title = "التجربة",
        subtitle = "اكتشف إعداداتك المناسبة قبل الاشتراك. تبدأ التجربة عند اختيارك، ولا تتجدد تلقائيًا.",
        icon = Icons.Outlined.Info,
    ) {
        when {
            snapshot == null -> Text("أعد التحقق من الترخيص لمعرفة أهلية التجربة من الخادم.")
            snapshot.state == LicenseState.TRIAL -> Text("تجربتك فعّالة وفق آخر تحقق. اكتشف المعاينة والمرشحات وراجع موعد نهايتها أعلاه.")
            snapshot.trialEnabled == false -> Text("التجربة معطّلة حالياً وفق سياسة الخادم.")
            snapshot.trialEligible == false -> Text("هذا الحساب غير مؤهل لتجربة جديدة وفق الخادم.")
            snapshot.trialEnabled == null || snapshot.trialEligible == null ->
                Text("لم يرسل الخادم معلومات كافية عن أهلية التجربة؛ لا يمكن بدء تجربة من هذه الحالة.")
            else -> Text(
                snapshot.trialDays?.let { "التجربة متاحة لمدة $it يومًا وفق الخادم." }
                    ?: "التجربة متاحة وفق الخادم؛ لم يرسل الخادم مدة صالحة لعرضها.",
            )
        }
        if (canStart) {
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
                    Text("جارٍ طلب التجربة من الخادم")
                } else {
                    Text("بدء التجربة")
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
        title = "عرض APK المباشر",
        subtitle = "مرشحاتك في مكان واحد، ومعاينة للقرار قبل التشغيل. تفاصيل الاشتراك أدناه من الإدارة.",
        icon = Icons.Outlined.Info,
    ) {
        when {
            isLoadingOffer -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("جارٍ قراءة العرض الحالي من الخادم دون إنشاء اشتراك أو تجربة.")
            }
            offer == null -> {
                Text("تعذر قراءة عرض الخادم. لا نعرض سعراً أو مدة بديلة.")
                TextButton(onClick = onRetryOffer, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("إعادة محاولة جلب العرض")
                }
            }
            else -> {
                Text(
                    "${formatServerPrice(offer.priceMinor)} ${offer.currency} — شهري (${offer.subscriptionDays} يومًا)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (offer.trialEnabled) "التجربة العامة: ${offer.trialDays} أيام للحسابات المؤهلة؛ نؤكد أهليتك قبل البدء."
                    else "التجربة معطّلة حالياً وفق سياسة الخادم.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "لا يوجد تجديد تلقائي بأكواد التفعيل. راجع السعر النهائي والضرائب، إن انطبقت، مع الإدارة قبل الدفع.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                offer.purchaseContactUrl?.let { contactUrl ->
                    OutlinedButton(
                        onClick = { onOpenContact(contactUrl) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("فتح قناة الإدارة")
                    }
                }
            }
        }
    }

    QannasCard(
        title = "إدخال كود الإدارة",
        subtitle = "يرسل الكود مباشرة إلى الخادم ثم يعيد التحقق من الترخيص؛ لا يُحفظ على الجهاز.",
        icon = Icons.Outlined.VerifiedUser,
    ) {
        OutlinedTextField(
            value = activationCode,
            onValueChange = onCodeChange,
            enabled = !isBusy,
            singleLine = true,
            label = { Text("كود الإدارة") },
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
            Text("إرسال الكود والتحقق")
        }
    }
}

@Composable
private fun PlayInformationCard() {
    QannasCard(
        title = "معلومات الترخيص",
        subtitle = "راجع صلاحية وصولك وتفاصيل حسابك من هنا.",
        icon = Icons.Outlined.Info,
    ) {
        Text(
            "يمكنك مراجعة حالة الترخيص أعلاه وإعادة التحقق منها من الخادم عند الحاجة.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "الترخيص الصالح شرط لتشغيل الخدمة، إلى جانب الأذونات والمرشحات المحفوظة.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "الشراء عبر Google Play غير متاح في هذا الإصدار قيد التطوير.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private data class LicenseStatePresentation(val label: String, val isOperational: Boolean)

private fun licenseStatePresentation(state: LicenseState): LicenseStatePresentation = when (state) {
    LicenseState.ACTIVE -> LicenseStatePresentation("اشتراك فعّال وفق الخادم", true)
    LicenseState.TRIAL -> LicenseStatePresentation("تجربة فعّالة وفق الخادم", true)
    LicenseState.PENDING -> LicenseStatePresentation("الترخيص بانتظار إجراء خادمي", false)
    LicenseState.EXPIRED -> LicenseStatePresentation("انتهى الترخيص وفق الخادم", false)
    LicenseState.DEVICE_MISMATCH -> LicenseStatePresentation("الحساب مرتبط بجهاز آخر", false)
    LicenseState.BANNED -> LicenseStatePresentation("الحساب محظور", false)
    LicenseState.REJECTED -> LicenseStatePresentation("الحساب مرفوض", false)
    LicenseState.UNREGISTERED -> LicenseStatePresentation("ملف السائق غير مكتمل", false)
}

/** يحول هللات الخادم فقط إلى عرض SAR؛ لا توجد قيمة افتراضية في العميل. */
private fun formatServerPrice(priceMinor: Int): String {
    val whole = priceMinor / 100
    val fraction = priceMinor % 100
    return String.format(Locale.ROOT, "%d.%02d", whole, fraction)
}

private fun subscriptionRequestError(error: Throwable?): String = when (error?.message) {
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> "انتهت الجلسة؛ سجّل الدخول من جديد ثم أعد التحقق."
    "تحقق من الترخيص والجهاز قبل المتابعة" -> "لا يوجد جهاز مرتبط يمكن للخادم التحقق منه."
    "الحساب مرتبط بجهاز آخر", "تم حظر أو رفض الحساب", "يلزم تحديث التطبيق قبل استخدام الخدمة" -> error.message.orEmpty()
    else -> "تعذر التحقق من حالة الترخيص من الخادم. تحقق من الاتصال ثم أعد المحاولة."
}

private fun directCodeError(error: Throwable?): String = when (error?.message) {
    "الكود غير صحيح أو استُخدم مسبقاً",
    "محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجدداً",
    "هذا الحساب مرتبط بجهاز آخر" -> error.message.orEmpty()
    "انتهت الجلسة؛ سجّل الدخول من جديد" -> "انتهت الجلسة؛ سجّل الدخول من جديد ثم أعد إرسال الكود."
    else -> "تعذر إرسال الكود إلى الخادم. تحقق من الكود والاتصال ثم أعد المحاولة."
}

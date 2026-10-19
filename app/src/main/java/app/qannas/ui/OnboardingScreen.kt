package app.qannas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.qannas.BuildConfig
import app.qannas.network.SupabaseClient
import kotlinx.coroutines.launch

private const val ONBOARDING_PAGES = 3

/**
 * تعريف عربي قصير قبل تسجيل الدخول. لا يغير الحساب أو الإعدادات أو الترخيص؛
 * مالك التنقل وحده يستدعي [OnboardingPreferences.markComplete] بعد [onContinue].
 */
@Composable
fun OnboardingScreen(onContinue: () -> Unit, initialPage: Int = 0) {
    var page by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(0, ONBOARDING_PAGES - 1)) }
    var selectedSignals by remember { mutableStateOf(DecisionSignal.entries.toSet()) }
    var offer by remember { mutableStateOf<OnboardingOffer?>(null) }
    var loadingOffer by remember { mutableStateOf(BuildConfig.DIRECT_DISTRIBUTION) }
    val scope = rememberCoroutineScope()

    suspend fun loadPublicOffer() {
        if (!BuildConfig.DIRECT_DISTRIBUTION) {
            loadingOffer = false
            return
        }
        loadingOffer = true
        // لا نُبقي سعراً أو مدةً قديمين عند فشل إعادة المحاولة.
        offer = null
        val response = runCatching { SupabaseClient.fetchPublicOffer() }.getOrNull()
        offer = response?.getOrNull()?.let {
            OnboardingOffer(
                trialEnabled = it.trialEnabled,
                trialDays = it.trialDays,
                priceMinor = it.priceMinor,
                currency = it.currency,
                subscriptionDays = it.subscriptionDays
            )
        }
        loadingOffer = false
    }

    LaunchedEffect(BuildConfig.DIRECT_DISTRIBUTION) {
        loadPublicOffer()
    }

    val offerCopy = onboardingOfferCopy(
        isDirectDistribution = BuildConfig.DIRECT_DISTRIBUTION,
        offer = offer
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    OnboardingTopBar(
                        page = page,
                        onSkip = onContinue
                    )
                    LinearProgressIndicator(
                        progress = { (page + 1).toFloat() / ONBOARDING_PAGES },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { stateDescription = "الصفحة ${page + 1} من $ONBOARDING_PAGES" }
                    )

                    when (page) {
                        0 -> WelcomePage(
                            loadingOffer = loadingOffer,
                            offerCopy = offerCopy,
                            isDirectDistribution = BuildConfig.DIRECT_DISTRIBUTION,
                            onRetryOffer = { scope.launch { loadPublicOffer() } }
                        )
                        1 -> DecisionPreviewPage(
                            selectedSignals = selectedSignals,
                            onSignalChange = { signal ->
                                selectedSignals = if (signal in selectedSignals) {
                                    selectedSignals - signal
                                } else {
                                    selectedSignals + signal
                                }
                            }
                        )
                        else -> ReadyPage(canShowTrialJourney = offerCopy.canShowTrialJourney)
                    }

                    OnboardingNavigation(
                        page = page,
                        canShowTrialJourney = offerCopy.canShowTrialJourney,
                        onBack = { page = (page - 1).coerceAtLeast(0) },
                        onNext = {
                            if (page == ONBOARDING_PAGES - 1) onContinue()
                            else page += 1
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(page: Int, onSkip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        QannasBrand(compact = true)
        TextButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("تخطي الجولة")
        }
    }
    Text(
        text = "خطوة ${page + 1} من $ONBOARDING_PAGES",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun WelcomePage(
    loadingOffer: Boolean,
    offerCopy: OnboardingOfferCopy,
    isDirectDistribution: Boolean,
    onRetryOffer: () -> Unit
) {
    Text("خلّ طلباتك على مقاسك", style = MaterialTheme.typography.headlineLarge)
    Text("وقت أقل في متابعة الشاشة. شروط أوضح ليوم شغلك.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    QannasCard(
        title = "أنت تحدد، وقناص يتابع",
        subtitle = "يشغّل القبول التلقائي للطلبات التي تطابق شروطك في التطبيقات المدعومة."
    ) {
        DriverValue(
            title = "لا تقبل بأقل من شروطك",
            detail = "حدد أقل أجرة وأقل عائد لكل كم يناسبك."
        )
        DriverValue(
            title = "مسافة الوصول تفرق",
            detail = "اختر حدًا لمسافة الوصول للراكب وطول المشوار."
        )
        DriverValue(
            title = "شغلك، على وقتك",
            detail = "حدد أوقات العمل، وشغّل القبول أو أوقفه متى أردت."
        )
    }

    QannasCard(
        title = if (loadingOffer) "نجهّز لك تفاصيل التجربة" else offerCopy.headline
    ) {
        if (loadingOffer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                Text(
                    "لحظات ونظهر لك الخيارات المتاحة.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Text(offerCopy.detail, style = MaterialTheme.typography.bodyMedium)
            if (isDirectDistribution && !offerCopy.hasServerOffer) {
                TextButton(
                    onClick = onRetryOffer,
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp)
                ) {
                    Text("إعادة التحقق")
                }
            }
        }
    }
    Text("اكتشفه أولًا، ثم قرر إن كان يناسبك.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DriverValue(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReadyPage(canShowTrialJourney: Boolean) {
    QannasCard(
        title = "جاهز ليوم عمل أذكى؟",
        subtitle = "ثلاث خطوات وتكون مستعدًا."
    ) {
        DriverValue(
            title = "١. ادخل بحسابك",
            detail = "باستخدام Google أو رقم جوالك السعودي."
        )
        DriverValue(
            title = "٢. اضبط الطلب اللي يناسبك",
            detail = "حدد الأجرة والمسافة وأوقات العمل."
        )
        DriverValue(
            title = "٣. شغّل قناص وأنت مستعد",
            detail = "بعد منح الإذن، يمكنه قبول الطلب المطابق تلقائيًا. الإيقاف دائمًا بيدك."
        )
        DriverValue(
            title = if (canShowTrialJourney) "جرّبه قبل ما تشترك" else "اكتشف خيارات حسابك",
            detail = if (canShowTrialJourney) {
                "سجّل الدخول لنتحقق من أهليتك. تبدأ التجربة عندما تؤكدها، وليس أثناء هذه الجولة."
            } else {
                "سجّل الدخول لعرض مدة الاشتراك والخيارات المتاحة لك."
            }
        )
    }
    QannasCard(
        title = "صلاحياتك بقرارك"
    ) {
        Text(
            "سنوضح لك ما يقرأه قناص وكيف يستخدمه قبل طلب الإذن. لا تُشغّل الميزة إلا بموافقتك.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DecisionPreviewPage(
    selectedSignals: Set<DecisionSignal>,
    onSignalChange: (DecisionSignal) -> Unit
) {
    QannasCard(
        title = "هل يستاهل هذا المشوار؟",
        subtitle = "مثال توضيحي: أجرة ٤٥ ر.س لمشوار ١٥ كم."
    ) {
        QannasSectionTitle(
            title = "شوف الصورة كاملة",
            subtitle = "اضغط لإظهار أو إخفاء التفاصيل التي تهمك."
        )
        DecisionSignal.entries.forEach { signal ->
            FilterChip(
                selected = signal in selectedSignals,
                onClick = { onSignalChange(signal) },
                label = { Text(signal.label) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            )
        }
    }

    QannasCard(
        title = "تفاصيل المثال",
        subtitle = "أرقام للتوضيح فقط، وليست طلبًا فعليًا."
    ) {
        Text(localDecisionPreview(selectedSignals), style = MaterialTheme.typography.bodyLarge)
        Text("أجرة الطلب ليست ربحًا صافيًا؛ التكاليف تختلف حسب مشوارك.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OnboardingNavigation(
    page: Int,
    canShowTrialJourney: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (page > 0) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 52.dp)
            ) {
                Text("رجوع")
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Button(
            onClick = onNext,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                if (page == ONBOARDING_PAGES - 1) onboardingFinalCta(canShowTrialJourney)
                else if (page == 0) "ورّني كيف" else "أضبط طلباتي"
            )
        }
    }
}

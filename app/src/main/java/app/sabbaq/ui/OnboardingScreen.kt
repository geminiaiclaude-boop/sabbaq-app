package app.sabbaq.ui

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
import app.sabbaq.BuildConfig
import app.sabbaq.network.SupabaseClient
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import app.sabbaq.ui.theme.SabbaqDepthContent
import app.sabbaq.ui.theme.SabbaqMotion
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.ui.theme.sabbaqEntrance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.graphics.vector.ImageVector

private val ONBOARDING_PAGES = OnboardingPage.entries.size

/**
 * تعريف عربي قصير قبل تسجيل الدخول. لا يغير الحساب أو الإعدادات أو الترخيص؛
 * مالك التنقل وحده يستدعي [OnboardingPreferences.markComplete] بعد [onContinue].
 */
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    initialPage: OnboardingPage = OnboardingPage.INTRODUCTION,
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(initialPage.ordinal) }
    val page = OnboardingPage.entries[pageIndex.coerceIn(0, ONBOARDING_PAGES - 1)]
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
        // لا نُبقي سعرًا أو مدةً قديمين عند فشل إعادة المحاولة.
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
                    SabbaqPageDots(
                        page = page.ordinal,
                        count = ONBOARDING_PAGES,
                        modifier = Modifier.semantics { stateDescription = "الصفحة ${page.ordinal + 1} من $ONBOARDING_PAGES" },
                    )

                    SabbaqDepthContent(target = page, order = { it.ordinal }, label = "onboarding-page") { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (shown) {
                        OnboardingPage.INTRODUCTION -> WelcomePage(
                            loadingOffer = loadingOffer,
                            offerCopy = offerCopy,
                            isDirectDistribution = BuildConfig.DIRECT_DISTRIBUTION,
                            onRetryOffer = { scope.launch { loadPublicOffer() } }
                        )
                        OnboardingPage.FEATURES -> FeaturesSubscriptionPage(
                            loadingOffer = loadingOffer,
                            offerCopy = offerCopy,
                            isDirectDistribution = BuildConfig.DIRECT_DISTRIBUTION,
                            onRetryOffer = { scope.launch { loadPublicOffer() } }
                        )
                        OnboardingPage.HOW_IT_WORKS -> DecisionPreviewPage(
                            selectedSignals = selectedSignals,
                            onSignalChange = { signal ->
                                selectedSignals = if (signal in selectedSignals) {
                                    selectedSignals - signal
                                } else {
                                    selectedSignals + signal
                                }
                            }
                        )
                    }
                    }
                    }

                    OnboardingNavigation(
                        page = page,
                        canShowTrialJourney = offerCopy.canShowTrialJourney,
                        onBack = { page.previous()?.let { pageIndex = it.ordinal } },
                        onNext = {
                            page.next()?.let { pageIndex = it.ordinal } ?: onContinue()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(page: OnboardingPage, onSkip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SabbaqBrand(compact = true)
        TextButton(
            onClick = onSkip,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("تخطي الجولة")
        }
    }
    Text(
        text = "خطوة ${page.ordinal + 1} من $ONBOARDING_PAGES",
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
    val brand = LocalSabbaqBrand.current
    SabbaqHeroSurface(Modifier.sabbaqEntrance(0), scene = false) {
        Text("تعرّف على سبّاق", style = MaterialTheme.typography.labelLarge, color = brand.heroAccent)
        Text("طلبات على مقاس شروطك", style = MaterialTheme.typography.headlineMedium)
        Text("تحدد شروطك مرة واحدة، ويتابع سبّاق الطلبات ويقبل ما يطابقها. وقت أقل أمام الشاشة.",
            style = MaterialTheme.typography.bodyLarge, color = brand.onHeroMuted)
        // The product, shown rather than described: example requests and Sabbaq' verdict.
        SabbaqOrderShowcase()
    }
    SabbaqCard(
        title = "يعمل مع تطبيقات الرحلات التي تستخدمها",
        subtitle = "سبّاق يتابع الطلبات داخل هذه التطبيقات ويقبل ما يطابق شروطك.",
        modifier = Modifier.sabbaqEntrance(1),
    ) {
        SupportedPlatformsRow()
    }
}

@Composable
private fun FeaturesSubscriptionPage(
    loadingOffer: Boolean,
    offerCopy: OnboardingOfferCopy,
    isDirectDistribution: Boolean,
    onRetryOffer: () -> Unit,
) {
    SabbaqCard(
        title = "أنت تحدد، وسبّاق يتابع",
        subtitle = "يشغّل القبول التلقائي للطلبات التي تطابق شروطك في التطبيقات المدعومة.",
        modifier = Modifier.sabbaqEntrance(0),
    ) {
        DriverValue(
            icon = Icons.Outlined.Payments,
            title = "لا تقبل بأقل من شروطك",
            detail = "حدد أقل أجرة وأقل عائد لكل كم يناسبك."
        )
        DriverValue(
            icon = Icons.Outlined.NearMe,
            title = "مسافة الوصول محسوبة",
            detail = "اختر حدًا لمسافة الوصول للراكب وطول المشوار."
        )
        DriverValue(
            icon = Icons.Outlined.Schedule,
            title = "عملك في الوقت الذي تختاره",
            detail = "حدد أوقات العمل، وشغّل القبول أو أوقفه متى أردت."
        )
    }

    SabbaqCard(
        title = if (loadingOffer) "نجهّز لك تفاصيل التجربة" else offerCopy.headline,
        modifier = Modifier.sabbaqEntrance(1),
    ) {
        if (loadingOffer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                Text(
                    "لحظات ونعرض لك الخيارات المتاحة.",
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
    Text(
        "اكتشفه أولًا، ثم قرر إن كان يناسبك.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DriverValue(title: String, detail: String, icon: ImageVector? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        icon?.let { SabbaqIconBadge(it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DecisionPreviewPage(
    selectedSignals: Set<DecisionSignal>,
    onSignalChange: (DecisionSignal) -> Unit
) {
    SabbaqHeroSurface(Modifier.sabbaqEntrance(0), scene = false) {
        Text("كيف يعمل سبّاق؟", style = MaterialTheme.typography.headlineSmall)
        Text("ثلاث لحظات في كل طلب، وأنت تحدد القرار مسبقًا.", style = MaterialTheme.typography.bodyMedium,
            color = LocalSabbaqBrand.current.onHeroMuted)
        SabbaqHowItWorksScene()
    }
    SabbaqCard(
        title = "هل يستحق هذا المشوار؟",
        subtitle = "مثال توضيحي: أجرة ٤٥ ر.س لمشوار ١٥ كم.",
        modifier = Modifier.sabbaqEntrance(1),
    ) {
        SabbaqSectionTitle(
            title = "اختر ما يهمك",
            subtitle = "اضغط على أي تفصيل لإظهاره في المثال أو إخفائه."
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

    SabbaqCard(
        title = "تفاصيل المثال",
        subtitle = "أرقام للتوضيح فقط، وليست طلبًا فعليًا.",
        modifier = Modifier.sabbaqEntrance(2),
    ) {
        Text(localDecisionPreview(selectedSignals), style = MaterialTheme.typography.bodyLarge)
        Text("أجرة الطلب ليست ربحًا صافيًا؛ التكاليف تختلف حسب مشوارك.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OnboardingNavigation(
    page: OnboardingPage,
    canShowTrialJourney: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (page.previous() != null) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 52.dp)
            ) {
                Text("رجوع")
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        SabbaqButton(
            text = when (page) {
                OnboardingPage.INTRODUCTION -> "التالي"
                OnboardingPage.FEATURES -> "كيف يعمل؟"
                OnboardingPage.HOW_IT_WORKS -> onboardingFinalCta(canShowTrialJourney)
            },
            onClick = onNext,
            modifier = Modifier.weight(1f),
            fillWidth = false,
        )
    }
}

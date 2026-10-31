package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.sabbaq.BuildConfig
import app.sabbaq.LocaleManager
import app.sabbaq.R
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

    // The system back button walks back through the tour before it can leave the app.
    BackHandler(enabled = pageIndex > 0) { page.previous()?.let { pageIndex = it.ordinal } }

    val direction = if (LocaleManager.isArabic()) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingTopBar(
                page = page,
                onSkip = onContinue,
                modifier = Modifier.widthIn(max = 620.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val pageOf = stringResource(R.string.onboarding_page_of, page.ordinal + 1, ONBOARDING_PAGES)
                    SabbaqPageDots(
                        page = page.ordinal,
                        count = ONBOARDING_PAGES,
                        modifier = Modifier.semantics { stateDescription = pageOf },
                    )

                    SabbaqDepthContent(target = page, order = { it.ordinal }, label = "onboarding-page") { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    OnboardingNavigation(
                        page = page,
                        canShowTrialJourney = offerCopy.canShowTrialJourney,
                        onBack = { page.previous()?.let { pageIndex = it.ordinal } },
                        onNext = {
                            page.next()?.let { pageIndex = it.ordinal } ?: onContinue()
                        },
                        modifier = Modifier.widthIn(max = 620.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(page: OnboardingPage, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val brand = LocalSabbaqBrand.current
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = brand.heroStart,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        // One row at the bar's original height whenever the brand, the language chips and «تخطي»
        // fit at their full width; otherwise (narrow phone, large font) the chips move to a second
        // row. «سبّاق» is never given less than its own width: a narrower slot clipped the word and
        // the shadda above it (2.0.3-20261031.1219 on a 360 dp phone).
        Column(Modifier.background(Brush.horizontalGradient(listOf(brand.heroEnd, brand.heroStart)))) {
            OnboardingBarRow(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                brandName = { SabbaqBrand(compact = true, onHero = true) },
                language = { LanguageSwitch(onHero = true) },
                skip = {
                    OutlinedButton(
                        onClick = onSkip,
                        border = BorderStroke(1.5.dp, brand.signature),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brand.signature),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_skip), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                },
            )
            Box(Modifier.fillMaxWidth().height(3.dp).background(brand.signature))
        }
    }
}

/** Brand at the start, «تخطي» at the end, the language chips between them or on a second row. */
@Composable
private fun OnboardingBarRow(
    brandName: @Composable () -> Unit,
    language: @Composable () -> Unit,
    skip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.ui.layout.Layout(contents = listOf(brandName, language, skip), modifier = modifier) { (b, l, k), constraints ->
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = constraints.maxWidth
        val skipPlaceable = k.first().measure(loose)
        val languagePlaceable = l.first().measure(loose)
        val brandWidth = b.first().maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val layout = onboardingBarLayout(width, brandWidth, languagePlaceable.width, skipPlaceable.width, gap)
        val brandPlaceable = b.first().measure(loose.copy(maxWidth = layout.brandMaxWidth))
        val rowHeight = maxOf(brandPlaceable.height, skipPlaceable.height, if (layout.oneRow) languagePlaceable.height else 0)
        val secondRowGap = 6.dp.roundToPx()
        val height = if (layout.oneRow) rowHeight else rowHeight + secondRowGap + languagePlaceable.height
        layout(width, height) {
            brandPlaceable.placeRelative(0, (rowHeight - brandPlaceable.height) / 2)
            skipPlaceable.placeRelative(width - skipPlaceable.width, (rowHeight - skipPlaceable.height) / 2)
            if (layout.oneRow) {
                languagePlaceable.placeRelative(width - skipPlaceable.width - gap - languagePlaceable.width, (rowHeight - languagePlaceable.height) / 2)
            } else {
                languagePlaceable.placeRelative(0, rowHeight + secondRowGap)
            }
        }
    }
}

internal data class OnboardingBarLayout(val oneRow: Boolean, val brandMaxWidth: Int)

/** One row only when all three fit at full width; the brand always keeps at least its own width when it can. */
internal fun onboardingBarLayout(width: Int, brand: Int, language: Int, skip: Int, gap: Int): OnboardingBarLayout {
    val oneRow = brand + gap + language + gap + skip <= width
    val beside = if (oneRow) language + gap else 0
    return OnboardingBarLayout(oneRow, (width - skip - gap - beside).coerceAtLeast(0))
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
        Text(stringResource(R.string.onboarding_eyebrow), style = MaterialTheme.typography.labelLarge, color = brand.heroAccent)
        Text(stringResource(R.string.onboarding_intro_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_intro_subtitle),
            style = MaterialTheme.typography.bodyMedium, color = brand.onHeroMuted)
        // The product, shown rather than described: example requests and Sabbaq' verdict.
        SabbaqOrderShowcase()
    }
    SabbaqCard(
        title = stringResource(R.string.onboarding_platforms_title),
        subtitle = stringResource(R.string.onboarding_platforms_subtitle),
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
        title = stringResource(R.string.onboarding_auto_title),
        subtitle = stringResource(R.string.onboarding_auto_subtitle),
        modifier = Modifier.sabbaqEntrance(0),
    ) {
        DriverValue(
            icon = Icons.Outlined.Payments,
            title = stringResource(R.string.onboarding_rule_fare_title),
            detail = stringResource(R.string.onboarding_rule_fare_detail)
        )
        DriverValue(
            icon = Icons.Outlined.NearMe,
            title = stringResource(R.string.onboarding_rule_pickup_title),
            detail = stringResource(R.string.onboarding_rule_pickup_detail)
        )
        DriverValue(
            icon = Icons.Outlined.Schedule,
            title = stringResource(R.string.onboarding_rule_schedule_title),
            detail = stringResource(R.string.onboarding_rule_schedule_detail)
        )
    }

    SabbaqCard(
        title = if (loadingOffer) stringResource(R.string.onboarding_offer_loading) else offerCopy.headline(),
        modifier = Modifier.sabbaqEntrance(1),
    ) {
        if (loadingOffer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.onboarding_offer_loading_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Text(offerCopy.detail(), style = MaterialTheme.typography.bodyMedium)
            if (isDirectDistribution && !offerCopy.hasServerOffer) {
                TextButton(
                    onClick = onRetryOffer,
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.onboarding_retry))
                }
            }
        }
    }
    Text(
        stringResource(R.string.onboarding_discover_first),
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
        Text(stringResource(R.string.onboarding_how_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_how_subtitle), style = MaterialTheme.typography.bodyMedium,
            color = LocalSabbaqBrand.current.onHeroMuted)
        SabbaqHowItWorksScene()
    }
    SabbaqCard(
        title = stringResource(R.string.onboarding_worth_title),
        subtitle = stringResource(R.string.onboarding_worth_subtitle),
        modifier = Modifier.sabbaqEntrance(1),
    ) {
        SabbaqSectionTitle(
            title = stringResource(R.string.onboarding_pick_title),
            subtitle = stringResource(R.string.onboarding_pick_subtitle)
        )
        DecisionSignal.entries.forEach { signal ->
            FilterChip(
                selected = signal in selectedSignals,
                onClick = { onSignalChange(signal) },
                label = { Text(stringResource(signal.labelRes)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            )
        }
    }

    SabbaqCard(
        title = stringResource(R.string.onboarding_example_title),
        subtitle = stringResource(R.string.onboarding_example_subtitle),
        modifier = Modifier.sabbaqEntrance(2),
    ) {
        Text(localDecisionPreview(selectedSignals), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.onboarding_example_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OnboardingNavigation(
    page: OnboardingPage,
    canShowTrialJourney: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (page.previous() != null) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 52.dp)
            ) {
                Text(stringResource(R.string.onboarding_back))
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        SabbaqButton(
            text = when (page) {
                OnboardingPage.INTRODUCTION -> stringResource(R.string.onboarding_next)
                OnboardingPage.FEATURES -> stringResource(R.string.onboarding_how)
                OnboardingPage.HOW_IT_WORKS -> onboardingFinalCta(canShowTrialJourney)
            },
            onClick = onNext,
            modifier = Modifier.weight(1f),
            fillWidth = false,
        )
    }
}

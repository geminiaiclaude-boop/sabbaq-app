package app.sabbaq.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sabbaq.R
import java.text.NumberFormat

/** Stable destinations for the onboarding flow; callers must not depend on page numbers. */
enum class OnboardingPage {
    INTRODUCTION,
    FEATURES,
    HOW_IT_WORKS,
}

internal fun OnboardingPage.next(): OnboardingPage? = when (this) {
    OnboardingPage.INTRODUCTION -> OnboardingPage.FEATURES
    OnboardingPage.FEATURES -> OnboardingPage.HOW_IT_WORKS
    OnboardingPage.HOW_IT_WORKS -> null
}

internal fun OnboardingPage.previous(): OnboardingPage? = when (this) {
    OnboardingPage.INTRODUCTION -> null
    OnboardingPage.FEATURES -> OnboardingPage.INTRODUCTION
    OnboardingPage.HOW_IT_WORKS -> OnboardingPage.FEATURES
}

/**
 * A small view-only copy of the public offer. It grants no trial or subscription and is not
 * stored outside the screen composition.
 */
internal data class OnboardingOffer(
    val trialEnabled: Boolean,
    val trialDays: Int,
    val priceMinor: Int,
    val currency: String,
    val subscriptionDays: Int
)

/** Which copy the offer resolves to. Kept language-free so the logic stays unit-testable; the
 *  words are resolved from resources by the composable helpers below. */
internal enum class OnboardingOfferKind { STORE, DISCOVER, TRIAL, SUBSCRIPTION_ONLY }

/**
 * The decided offer copy: a resource-backed state, not display text. [kind] picks the headline
 * and the detail shape; the numeric fields feed the detail. This is pure so [onboardingOfferCopy]
 * can be unit-tested without a resource context.
 */
internal data class OnboardingOfferCopy(
    val kind: OnboardingOfferKind,
    val hasServerOffer: Boolean,
    val canShowTrialJourney: Boolean = false,
    val priceMinor: Int = 0,
    val subscriptionDays: Int = 0,
    val trialDays: Int = 0,
) {
    val headlineRes: Int
        get() = when (kind) {
            OnboardingOfferKind.STORE -> R.string.onboarding_offer_store_headline
            OnboardingOfferKind.DISCOVER -> R.string.onboarding_offer_discover_headline
            OnboardingOfferKind.TRIAL -> R.string.onboarding_offer_trial_headline
            OnboardingOfferKind.SUBSCRIPTION_ONLY -> R.string.onboarding_offer_choose_headline
        }
}

/** The headline text for [copy]. */
@Composable
internal fun OnboardingOfferCopy.headline(): String = stringResource(headlineRes)

/** The detail text for [copy], built from resources and the offer's numbers. */
@Composable
internal fun OnboardingOfferCopy.detail(): String = when (kind) {
    OnboardingOfferKind.STORE -> stringResource(R.string.onboarding_offer_store_detail)
    OnboardingOfferKind.DISCOVER -> stringResource(R.string.onboarding_offer_discover_detail)
    else -> {
        val price = NumberFormat.getNumberInstance(SabbaqLocale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(priceMinor / 100.0)
        val trial = if (kind == OnboardingOfferKind.TRIAL) {
            stringResource(R.string.onboarding_offer_trial_line, pluralStringResource(R.plurals.days_count, trialDays, trialDays))
        } else {
            stringResource(R.string.onboarding_offer_no_trial)
        }
        stringResource(R.string.onboarding_offer_detail, trial, price,
            pluralStringResource(R.plurals.days_count, subscriptionDays, subscriptionDays))
    }
}

/** Final button text; it does not start the trial and does not change licence state. */
@Composable
internal fun onboardingFinalCta(canShowTrialJourney: Boolean): String =
    stringResource(if (canShowTrialJourney) R.string.onboarding_cta_start else R.string.onboarding_cta_continue)

/**
 * Decides which offer copy applies. Pure (no resources), so the Play/direct split, the value
 * bounds and the trial logic stay unit-tested. The network client is the authority on the offer.
 */
internal fun onboardingOfferCopy(
    isDirectDistribution: Boolean,
    offer: OnboardingOffer?
): OnboardingOfferCopy {
    if (!isDirectDistribution) {
        return OnboardingOfferCopy(OnboardingOfferKind.STORE, hasServerOffer = false)
    }
    val validOffer = offer?.takeIf {
        it.currency == "SAR" &&
            it.priceMinor in 1..1_000_000 &&
            it.subscriptionDays in 1..365 &&
            (!it.trialEnabled || it.trialDays in 1..365)
    } ?: return OnboardingOfferCopy(OnboardingOfferKind.DISCOVER, hasServerOffer = false)

    return OnboardingOfferCopy(
        kind = if (validOffer.trialEnabled) OnboardingOfferKind.TRIAL else OnboardingOfferKind.SUBSCRIPTION_ONLY,
        hasServerOffer = true,
        canShowTrialJourney = validOffer.trialEnabled,
        priceMinor = validOffer.priceMinor,
        subscriptionDays = validOffer.subscriptionDays,
        trialDays = validOffer.trialDays,
    )
}

/** Preview items are a local teaching aid, not operating rules or account settings. */
internal enum class DecisionSignal(val labelRes: Int) {
    PICKUP_DISTANCE(R.string.signal_pickup_distance),
    TRIP_DURATION(R.string.signal_trip_duration),
    DISPLAYED_FARE(R.string.signal_displayed_fare)
}

@Composable
internal fun localDecisionPreview(selected: Set<DecisionSignal>): String {
    if (selected.isEmpty()) return stringResource(R.string.preview_empty)
    return buildString {
        append(stringResource(R.string.preview_intro))
        if (DecisionSignal.DISPLAYED_FARE in selected) append(stringResource(R.string.preview_fare))
        if (DecisionSignal.PICKUP_DISTANCE in selected) append(stringResource(R.string.preview_pickup))
        if (DecisionSignal.TRIP_DURATION in selected) append(stringResource(R.string.preview_duration))
        if (DecisionSignal.DISPLAYED_FARE in selected && DecisionSignal.PICKUP_DISTANCE in selected)
            append(stringResource(R.string.preview_per_km))
        append(stringResource(R.string.preview_note))
    }
}

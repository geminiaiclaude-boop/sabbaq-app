package app.sabbaq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offer copy's logic is tested here (which state applies, the value bounds, trial handling).
 * The displayed words live in string resources and are resolved by the composable helpers, so
 * this asserts the decided [OnboardingOfferKind] and numbers, not any language's text.
 */
class OnboardingModelsTest {
    private val availableOffer = OnboardingOffer(
        trialEnabled = true,
        trialDays = 7,
        priceMinor = 1875,
        currency = "SAR",
        subscriptionDays = 30
    )

    @Test
    fun `onboarding destinations form a complete forward and backward chain`() {
        assertTrue(OnboardingPage.INTRODUCTION.next() == OnboardingPage.FEATURES)
        assertTrue(OnboardingPage.FEATURES.next() == OnboardingPage.HOW_IT_WORKS)
        assertTrue(OnboardingPage.HOW_IT_WORKS.next() == null)
        assertTrue(OnboardingPage.HOW_IT_WORKS.previous() == OnboardingPage.FEATURES)
        assertTrue(OnboardingPage.FEATURES.previous() == OnboardingPage.INTRODUCTION)
        assertTrue(OnboardingPage.INTRODUCTION.previous() == null)
    }

    @Test
    fun `direct copy uses the current offer rather than a built-in trial`() {
        val copy = onboardingOfferCopy(isDirectDistribution = true, offer = availableOffer)

        assertEquals(OnboardingOfferKind.TRIAL, copy.kind)
        assertTrue(copy.hasServerOffer)
        assertTrue(copy.canShowTrialJourney)
        assertEquals(7, copy.trialDays)
        assertEquals(30, copy.subscriptionDays)
        assertEquals(1875, copy.priceMinor)
    }

    @Test
    fun `unavailable offer stays general and does not invent a trial`() {
        val copy = onboardingOfferCopy(isDirectDistribution = true, offer = null)

        assertEquals(OnboardingOfferKind.DISCOVER, copy.kind)
        assertFalse(copy.hasServerOffer)
        assertFalse(copy.canShowTrialJourney)
        assertEquals(0, copy.trialDays)
    }

    @Test
    fun `an out-of-bounds offer is rejected and falls back to discover`() {
        assertEquals(OnboardingOfferKind.DISCOVER,
            onboardingOfferCopy(true, availableOffer.copy(currency = "USD")).kind)
        assertEquals(OnboardingOfferKind.DISCOVER,
            onboardingOfferCopy(true, availableOffer.copy(priceMinor = 0)).kind)
        assertEquals(OnboardingOfferKind.DISCOVER,
            onboardingOfferCopy(true, availableOffer.copy(subscriptionDays = 400)).kind)
        assertEquals(OnboardingOfferKind.DISCOVER,
            onboardingOfferCopy(true, availableOffer.copy(trialEnabled = true, trialDays = 0)).kind)
    }

    @Test
    fun `disabled trial is a subscription-only offer, not a duration`() {
        val copy = onboardingOfferCopy(
            isDirectDistribution = true,
            offer = availableOffer.copy(trialEnabled = false, trialDays = 0)
        )

        assertEquals(OnboardingOfferKind.SUBSCRIPTION_ONLY, copy.kind)
        assertTrue(copy.hasServerOffer)
        assertFalse(copy.canShowTrialJourney)
    }

    @Test
    fun `play copy hides the direct price and external activation paths`() {
        val copy = onboardingOfferCopy(isDirectDistribution = false, offer = availableOffer)

        assertEquals(OnboardingOfferKind.STORE, copy.kind)
        assertFalse(copy.hasServerOffer)
        assertFalse(copy.canShowTrialJourney)
        assertEquals(0, copy.priceMinor)
    }
}

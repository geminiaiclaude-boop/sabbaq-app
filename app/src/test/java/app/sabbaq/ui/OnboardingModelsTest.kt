package app.sabbaq.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        assertTrue(copy.hasServerOffer)
        assertTrue(copy.canShowTrialJourney)
        assertTrue(copy.detail.contains("7 أيام"))
        assertTrue(copy.detail.contains("30 يومًا"))
        assertTrue(copy.detail.contains("ر.س"))
        assertTrue(copy.detail.contains("بعد تأكيدك"))
        assertTrue(onboardingFinalCta(copy.canShowTrialJourney).contains("ابدأ تجربتك"))
    }

    @Test
    fun `unavailable offer stays general and does not invent three-day trial`() {
        val copy = onboardingOfferCopy(isDirectDistribution = true, offer = null)

        assertFalse(copy.hasServerOffer)
        assertFalse(copy.canShowTrialJourney)
        assertTrue(copy.detail.contains("بعد تسجيل الدخول"))
        assertFalse(copy.detail.contains("3 أيام"))
        assertFalse(copy.detail.contains("3 يوم"))
    }

    @Test
    fun `disabled trial is stated as server policy and not a duration`() {
        val copy = onboardingOfferCopy(
            isDirectDistribution = true,
            offer = availableOffer.copy(trialEnabled = false, trialDays = 0)
        )

        assertTrue(copy.hasServerOffer)
        assertFalse(copy.canShowTrialJourney)
        assertTrue(copy.detail.contains("التجربة غير متاحة"))
        assertFalse(copy.detail.contains("7 أيام"))
        assertFalse(onboardingFinalCta(copy.canShowTrialJourney).contains("ابدأ تجربتك"))
    }

    @Test
    fun `play copy hides direct price and external activation paths`() {
        val copy = onboardingOfferCopy(isDirectDistribution = false, offer = availableOffer)

        assertFalse(copy.hasServerOffer)
        assertFalse(copy.canShowTrialJourney)
        assertTrue(copy.headline.contains("المتجر"))
        assertTrue(copy.detail.contains("خيارات الاشتراك"))
        assertFalse(copy.detail.contains("رمز"))
        assertFalse(copy.detail.contains("ر.س"))
    }

    @Test
    fun `example preview is labelled and never changes a real setting`() {
        val empty = localDecisionPreview(emptySet())
        val selected = localDecisionPreview(
            setOf(DecisionSignal.PICKUP_DISTANCE, DecisionSignal.DISPLAYED_FARE)
        )

        assertTrue(empty.contains("اختر تفصيلًا"))
        assertTrue(selected.contains("مسافة الوصول"))
        assertTrue(selected.contains("السعر الظاهر"))
        assertTrue(selected.contains("هذا المثال"))
        assertTrue(selected.contains("لا يمثل طلبًا فعليًا"))
        assertTrue(selected.contains("لا يغيّر إعداداتك"))
    }

    @Test
    fun `sample makes the value tangible without treating gross fare as profit`() {
        val full = localDecisionPreview(DecisionSignal.entries.toSet())
        assertTrue(full.contains("45 ر.س"))
        assertTrue(full.contains("3 كم"))
        assertTrue(full.contains("2.5 ر.س قبل التكاليف"))
        assertFalse(localDecisionPreview(setOf(DecisionSignal.TRIP_DURATION)).contains("العائد لكل كم"))
    }

    @Test
    fun `customer copy avoids technical jargon`() {
        val visibleCopy = listOf(
            onboardingOfferCopy(isDirectDistribution = true, offer = availableOffer).headline,
            onboardingOfferCopy(isDirectDistribution = true, offer = availableOffer).detail,
            onboardingOfferCopy(isDirectDistribution = true, offer = null).headline,
            onboardingOfferCopy(isDirectDistribution = true, offer = null).detail,
            onboardingOfferCopy(isDirectDistribution = false, offer = availableOffer).headline,
            onboardingOfferCopy(isDirectDistribution = false, offer = availableOffer).detail,
            localDecisionPreview(emptySet()),
            localDecisionPreview(setOf(DecisionSignal.TRIP_DURATION)),
        )

        listOf("Supabase", "محلية", "قراءة فقط", "خادمي", "الخادم", "API", "قواعد للمطور").forEach { jargon ->
            assertFalse("ظهر مصطلح تقني في النص المرئي: $jargon", visibleCopy.any { it.contains(jargon, ignoreCase = true) })
        }
    }
}

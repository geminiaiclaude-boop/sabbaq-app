package app.qannas.ui

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
    fun `direct copy uses successful server offer rather than a built-in trial`() {
        val copy = onboardingOfferCopy(isDirectDistribution = true, offer = availableOffer)

        assertTrue(copy.hasServerOffer)
        assertTrue(copy.detail.contains("7 يومًا"))
        assertTrue(copy.detail.contains("30 يومًا"))
        assertTrue(copy.detail.contains("ر.س"))
    }

    @Test
    fun `unavailable offer stays general and does not invent three-day trial`() {
        val copy = onboardingOfferCopy(isDirectDistribution = true, offer = null)

        assertFalse(copy.hasServerOffer)
        assertTrue(copy.detail.contains("لا نستطيع عرض تفاصيل التجربة"))
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
        assertTrue(copy.detail.contains("لا توجد تجربة مفعّلة"))
        assertFalse(copy.detail.contains("7 يومًا"))
    }

    @Test
    fun `play copy hides direct price and external activation paths`() {
        val copy = onboardingOfferCopy(isDirectDistribution = false, offer = availableOffer)

        assertFalse(copy.hasServerOffer)
        assertTrue(copy.detail.contains("خيارات المتجر"))
        assertTrue(copy.detail.contains("خارج المتجر"))
        assertFalse(copy.detail.contains("رمز"))
        assertFalse(copy.detail.contains("ر.س"))
    }

    @Test
    fun `local preview never becomes a real decision or persisted rule`() {
        val empty = localDecisionPreview(emptySet())
        val selected = localDecisionPreview(
            setOf(DecisionSignal.PICKUP_DISTANCE, DecisionSignal.DISPLAYED_FARE)
        )

        assertTrue(empty.contains("اختر معلومة"))
        assertTrue(selected.contains("مسافة الوصول"))
        assertTrue(selected.contains("السعر الظاهر"))
        assertTrue(selected.contains("محلية فقط"))
        assertTrue(selected.contains("لا تحفظ قاعدة"))
    }
}

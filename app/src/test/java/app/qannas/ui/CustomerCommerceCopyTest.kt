package app.qannas.ui

import app.qannas.network.LicenseState
import app.qannas.network.PublicOffer
import app.qannas.network.SubscriptionSnapshot
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerCommerceCopyTest {
    @Test
    fun `offer summary keeps the price currency and duration supplied by the offer`() {
        val offer = PublicOffer(
            trialEnabled = true,
            trialDays = 7,
            priceMinor = 12900,
            currency = "SAR",
            subscriptionDays = 30,
        )

        assertEquals("129.00 ر.س لمدة 30 يومًا", CustomerCommerceCopy.offerSummary(offer))
    }

    @Test
    fun `eligible trial names its duration and requires an explicit customer action`() {
        val presentation = CustomerCommerceCopy.trialPresentation(
            snapshot(
                state = LicenseState.EXPIRED,
                trialEligible = true,
                trialEnabled = true,
                trialDays = 7,
            ),
        )

        assertTrue(presentation.canStart)
        assertTrue(presentation.detail.contains("7 يومًا"))
        assertTrue(presentation.detail.contains("تبدأ فقط عند ضغط"))
        assertTrue(presentation.detail.contains("لا تبدأ تلقائياً"))
    }

    @Test
    fun `active trial presents its supplied Saudi date and cannot start again`() {
        val presentation = CustomerCommerceCopy.trialPresentation(
            snapshot(
                state = LicenseState.TRIAL,
                trialEligible = false,
                trialEnabled = true,
                trialDays = 7,
                trialUntil = Instant.parse("2027-01-02T21:30:00Z"),
            ),
        )

        assertFalse(presentation.canStart)
        assertTrue(presentation.detail.contains("تجربتك فعّالة حتى"))
        assertTrue(presentation.detail.contains("03"))
        assertTrue(presentation.detail.contains("00:30"))
    }

    @Test
    fun `missing trial terms never promise a trial`() {
        val presentation = CustomerCommerceCopy.trialPresentation(
            snapshot(
                state = LicenseState.EXPIRED,
                trialEligible = true,
                trialEnabled = true,
                trialDays = null,
            ),
        )

        assertFalse(presentation.canStart)
        assertTrue(presentation.detail.contains("لا نستطيع تأكيد تفاصيل التجربة"))
        assertFalse(presentation.detail.contains("يومًا"))
    }

    private fun snapshot(
        state: LicenseState,
        trialEligible: Boolean?,
        trialEnabled: Boolean?,
        trialDays: Int?,
        trialUntil: Instant? = null,
    ) = SubscriptionSnapshot(
        state = state,
        serverTime = Instant.parse("2027-01-01T00:00:00Z"),
        validUntil = null,
        trialUntil = trialUntil,
        proUntil = null,
        trialEligible = trialEligible,
        trialEnabled = trialEnabled,
        trialDays = trialDays,
    )
}

package app.qannas.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclosureConsentPolicyTest {
    @Test fun allConditionsAreIndependentlyRequired() {
        for (enabled in listOf(false, true)) {
            for (consent in listOf(false, true)) {
                for (licensed in listOf(false, true)) {
                    assertEquals(enabled && consent && licensed,
                        ServicesSafetyPolicy.canProcessOffers(enabled, consent, licensed))
                }
            }
        }
    }

    @Test fun consentDoesNotGrantALicense() {
        assertFalse(ServicesSafetyPolicy.canProcessOffers(true, true, false))
    }

    @Test fun revokedConsentStopsAnOtherwiseLicensedOperation() {
        assertFalse(ServicesSafetyPolicy.canProcessOffers(true, false, true))
        assertTrue(ServicesSafetyPolicy.canProcessOffers(true, true, true))
    }
}

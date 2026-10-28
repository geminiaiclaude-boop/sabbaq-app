package app.sabbaq.ui

import app.sabbaq.network.LicenseState
import app.sabbaq.network.SubscriptionSnapshot
import java.time.Instant
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CelebrationCopyTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private lateinit var previous: Locale

    @Before fun pinArabic() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar"))
    }

    @After fun restoreLocale() {
        Locale.setDefault(previous)
    }

    private fun snapshot(state: LicenseState, proUntil: Instant? = null, trialUntil: Instant? = null) = SubscriptionSnapshot(
        state = state, serverTime = now, validUntil = proUntil ?: trialUntil, trialUntil = trialUntil, proUntil = proUntil,
        trialEligible = null, trialEnabled = null, trialDays = null,
    )

    @Test
    fun only_granted_access_is_celebrated() {
        val paid = CelebrationCopy.eventFor(snapshot(LicenseState.ACTIVE, proUntil = now.plusSeconds(86_400)))
        assertEquals(CelebrationKind.Subscribed, paid?.kind)
        val trial = CelebrationCopy.eventFor(snapshot(LicenseState.TRIAL, trialUntil = now.plusSeconds(3600)))
        assertEquals(CelebrationKind.TrialStarted, trial?.kind)
        listOf(LicenseState.PENDING, LicenseState.EXPIRED, LicenseState.DEVICE_MISMATCH, LicenseState.BANNED).forEach {
            assertNull(CelebrationCopy.eventFor(snapshot(it)))
        }
        assertNull(CelebrationCopy.eventFor(null))
    }

    @Test
    fun days_left_round_up_and_never_go_negative() {
        assertEquals(30L, CelebrationCopy.daysLeft(now.plusSeconds(29L * 86_400 + 3 * 3600), now))
        assertEquals(1L, CelebrationCopy.daysLeft(now.plusSeconds(60), now))
        assertEquals(0L, CelebrationCopy.daysLeft(now.minusSeconds(60), now))
    }

    @Test
    fun validity_names_the_end_date_and_days() {
        val text = CelebrationCopy.validity(Instant.parse("2026-11-20T12:00:00Z"), now)!!
        assertTrue(text, text.startsWith("فعّال حتى "))
        assertTrue(text, "2026" in text && "30" in text)
        assertNull(CelebrationCopy.validity(now.minusSeconds(1), now))
        assertNull(CelebrationCopy.validity(null, now))
    }
}

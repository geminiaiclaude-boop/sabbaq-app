package app.sabbaq.notifications

import app.sabbaq.network.LicenseState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 3_600_000
    private val farFuture = Instant.ofEpochMilli(now + 20 * day)

    private fun facts(state: LicenseState?, proUntil: Instant? = null, trialUntil: Instant? = null, update: Boolean = false) =
        AccountFacts(state, validUntil = proUntil ?: trialUntil, trialUntil = trialUntil, proUntil = proUntil, updateRequired = update)

    private fun titles(previous: AccountMark, facts: AccountFacts) = NotificationRules.accountChanges(previous, facts, now).first.map { it.title }

    @Test
    fun `first look welcomes and records the state without replaying transitions`() {
        val (out, mark) = NotificationRules.accountChanges(AccountMark(), facts(LicenseState.ACTIVE, proUntil = farFuture), now)
        assertEquals(listOf("مرحبًا بك في سبّاق"), out.map { it.title })
        assertTrue(mark.welcomed)
        assertEquals("ACTIVE", mark.state)
        assertTrue(titles(mark, facts(LicenseState.ACTIVE, proUntil = farFuture)).isEmpty())
    }

    @Test
    fun `account transitions produce one notification each`() {
        val seen = AccountMark(state = "PENDING", welcomed = true)
        assertEquals(listOf("بدأت تجربتك المجانية"), titles(seen, facts(LicenseState.TRIAL, trialUntil = farFuture)))
        val onTrial = seen.copy(state = "TRIAL")
        assertEquals(listOf("تم تفعيل اشتراكك"), titles(onTrial, facts(LicenseState.ACTIVE, proUntil = farFuture)))
        val active = seen.copy(state = "ACTIVE", proUntilEpochSec = farFuture.epochSecond)
        assertEquals(listOf("تم تجديد اشتراكك"), titles(active, facts(LicenseState.ACTIVE, proUntil = farFuture.plusSeconds(30 * 86_400))))
        assertEquals(listOf("انتهى اشتراكك"), titles(active, facts(LicenseState.EXPIRED)))
        assertEquals(listOf("انتهت تجربتك المجانية"), titles(onTrial, facts(LicenseState.EXPIRED)))
        assertEquals(listOf("حسابك مرتبط بجوال آخر"), titles(active, facts(LicenseState.DEVICE_MISMATCH)))
        assertEquals(listOf("تم إيقاف حسابك"), titles(active, facts(LicenseState.BANNED)))
        assertTrue(titles(seen.copy(state = "BANNED"), facts(LicenseState.REJECTED)).isEmpty())
        assertTrue(titles(seen.copy(state = "EXPIRED"), facts(LicenseState.EXPIRED)).isEmpty())
    }

    @Test
    fun `update required is announced once until it is resolved`() {
        val seen = AccountMark(state = "ACTIVE", welcomed = true, proUntilEpochSec = farFuture.epochSecond)
        val (out, mark) = NotificationRules.accountChanges(seen, facts(LicenseState.ACTIVE, proUntil = farFuture, update = true), now)
        assertEquals(listOf(NotificationAction.UPDATE_APP), out.map { it.action })
        assertTrue(titles(mark, facts(LicenseState.ACTIVE, proUntil = farFuture, update = true)).isEmpty())
        val resolved = NotificationRules.accountChanges(mark, facts(LicenseState.ACTIVE, proUntil = farFuture), now).second
        assertFalse(resolved.updateNotified)
    }

    @Test
    fun `ending soon only in the last two days of a valid licence`() {
        val soon = Instant.ofEpochMilli(now + day)
        assertNotNull(NotificationRules.endingSoon(LicenseState.ACTIVE, soon, now))
        assertNull(NotificationRules.endingSoon(LicenseState.ACTIVE, Instant.ofEpochMilli(now + 5 * day), now))
        assertNull(NotificationRules.endingSoon(LicenseState.EXPIRED, soon, now))
        assertNull(NotificationRules.endingSoon(LicenseState.ACTIVE, Instant.ofEpochMilli(now - 1), now))
        val trial = NotificationRules.endingSoon(LicenseState.TRIAL, soon, now)!!
        assertEquals("تنتهي تجربتك قريبًا", trial.title)
        assertEquals(NotificationAction.OPEN_ACCOUNT, trial.action)
    }

    @Test
    fun `daily summary once a day and only after a day with offers`() {
        val today = LocalDate.of(2026, 10, 27)
        val (summary, mark) = NotificationRules.dailySummary(AccountMark(), today, yesterdayVisible = 34, yesterdayAccepted = 5, now = now)
        assertEquals("رأى سبّاق 34 طلبًا وقبل 5 طلبات منها.", summary!!.body)
        assertNull(NotificationRules.dailySummary(mark, today, 34, 5, now).first)
        val (quiet, quietMark) = NotificationRules.dailySummary(AccountMark(), today, 0, 0, now)
        assertNull(quiet)
        assertEquals(today.toString(), quietMark.summaryDay)
        val none = NotificationRules.dailySummary(AccountMark(), today, 2, 0, now).first!!
        assertEquals(NotificationAction.OPEN_FILTERS, none.action)
        assertTrue(none.body.startsWith("رأى سبّاق طلبين"))
    }

    @Test
    fun `accepted ride shows the fare and pickup distance it knows`() {
        assertEquals("الأجرة 25.5 ر.س · الوصول 1.2 كم",
            NotificationTemplates.acceptedRide(BigDecimal("25.50"), BigDecimal("1.24"), now).body)
        assertEquals("قبل سبّاق طلبًا يطابق شروطك.", NotificationTemplates.acceptedRide(null, null, now).body)
    }
}

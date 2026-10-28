package app.sabbaq.network

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseLeaseTest {
    @Test
    fun `المشترك يبقى صالحا طوال مدة الاشتراك دون اشتراط فحص دوري`() {
        val clock = FakeClock(1_000L)
        val lease = LicenseLease(clock)
        val day = 24L * 60L * 60L * 1000L
        val status = activeStatus(validForMillis = 30L * day)

        assertTrue(lease.replace(status, appVersionCode = 7, requestStartedAtMillis = clock.nowMillis()))
        // لا يتوقف لأن فحصًا دوريًا لم يصل: يظل صالحًا بعد ساعات ثم أيام من آخر تحقق ناجح.
        clock.now = 1_000L + 60L * 60L * 1000L
        assertTrue(lease.isValid())
        clock.now = 1_000L + 29L * day
        assertTrue(lease.isValid())
    }

    @Test
    fun `الصلاحية تنتهي عند انتهاء مدة الاشتراك التي حددها الخادم`() {
        val clock = FakeClock(10L)
        val lease = LicenseLease(clock)
        val status = activeStatus(validForMillis = 90_000L)

        assertTrue(lease.replace(status, appVersionCode = 7, requestStartedAtMillis = clock.nowMillis()))
        clock.now = 10L + 90_000L - 1L
        assertTrue(lease.isValid())
        clock.now += 1L
        assertFalse(lease.isValid())
    }

    @Test
    fun `ابطال المهلة يوقف الصلاحية فورا حتى داخل مدة الاشتراك`() {
        val clock = FakeClock(1_000L)
        val lease = LicenseLease(clock)
        val status = activeStatus(validForMillis = 30L * 24L * 60L * 60L * 1000L)

        assertTrue(lease.replace(status, appVersionCode = 7, requestStartedAtMillis = clock.nowMillis()))
        lease.invalidate()
        assertFalse(lease.isValid())
    }

    @Test
    fun `الحالات غير الصالحة والتواريخ والاصدارات الناقصة لا تكون مهلة`() {
        val clock = FakeClock(100L)
        val lease = LicenseLease(clock)

        assertFalse(lease.replace(activeStatus(validForMillis = 60_000L).copy(minimumVersionCode = 8), 7))
        assertFalse(lease.isValid())
        assertFalse(lease.replace(activeStatus(validForMillis = 60_000L).copy(validUntil = null), 7))
        assertFalse(lease.replace(activeStatus(validForMillis = 60_000L).copy(state = LicenseState.PENDING), 7))
        assertFalse(lease.replace(activeStatus(validForMillis = 0L), 7))
    }

    @Test
    fun `رد الترخيص المتاخر لا يمنح الجلسة الجديدة مهلة`() {
        val clock = FakeClock(1_000L)
        val store = AuthSessionStore(clock)
        val firstAttempt = store.beginAuthentication()
        assertTrue(store.activate(firstAttempt, "old-token"))
        assertTrue(store.bindDevice("old-token", "device-aaaaaaaa"))

        val secondAttempt = store.beginAuthentication()
        assertTrue(store.activate(secondAttempt, "new-token"))
        assertTrue(store.bindDevice("new-token", "device-bbbbbbbb"))

        assertNull(store.replaceLicenseIfMatches(
            token = "old-token",
            status = activeStatus(validForMillis = 60_000L),
            appVersionCode = 7,
            requestStartedAt = clock.nowMillis()
        ))
        assertFalse(store.hasValidLicense())
        assertTrue(store.snapshot()?.token == "new-token")
    }

    @Test
    fun `مسح الجلسة يبطل المهلة ونتيجة مسح قديمة لا تمس الجلسة الجديدة`() {
        val clock = FakeClock(1_000L)
        val store = AuthSessionStore(clock)
        val firstAttempt = store.beginAuthentication()
        assertTrue(store.activate(firstAttempt, "first-token"))
        assertTrue(store.bindDevice("first-token", "device-aaaaaaaa"))
        assertTrue(store.replaceLicenseIfMatches(
            token = "first-token",
            status = activeStatus(validForMillis = 60_000L),
            appVersionCode = 7,
            requestStartedAt = clock.nowMillis()
        ) == true)
        assertTrue(store.hasValidLicense())

        val secondAttempt = store.beginAuthentication()
        assertTrue(store.activate(secondAttempt, "second-token"))
        assertTrue(store.bindDevice("second-token", "device-bbbbbbbb"))
        store.clearIfMatches("first-token")
        assertTrue(store.snapshot()?.token == "second-token")
        assertFalse(store.hasValidLicense())

        store.clearIfMatches("second-token")
        assertNull(store.snapshot())
        assertFalse(store.hasValidLicense())
    }

    private fun activeStatus(validForMillis: Long): LicenseStatus {
        val serverTime = Instant.parse("2026-10-15T12:00:00Z")
        return LicenseStatus(
            state = LicenseState.ACTIVE,
            serverTime = serverTime,
            validUntil = serverTime.plusMillis(validForMillis),
            minimumVersionCode = 7
        )
    }

    private class FakeClock(var now: Long) : MonotonicClock {
        override fun nowMillis(): Long = now
    }
}

package app.sabbaq.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAuthenticationContractTest {
    @Test
    fun `رقم الجوال السعودي يخرج دائمًا بصيغة E164 فقط`() {
        assertEquals("+966512345678", NetworkInputValidator.saudiE164("+966 512 345 678"))
        assertEquals("+966512345678", NetworkInputValidator.saudiE164("0512345678"))
        assertEquals("+966512345678", NetworkInputValidator.saudiE164("٠٥١٢٣٤٥٦٧٨"))
        assertNull(NetworkInputValidator.saudiE164("+971501234567"))
        assertNull(NetworkInputValidator.saudiE164("+966412345678"))
        assertNull(NetworkInputValidator.saudiE164("+96651234567"))
    }

    @Test
    fun `رمز OTP ستة ارقام فقط ويطبع الادخال العربي إلى ASCII`() {
        assertEquals("123456", NetworkInputValidator.otpCode("١٢٣٤٥٦"))
        assertEquals("123456", NetworkInputValidator.otpCode("۱۲۳۴۵۶"))
        assertNull(NetworkInputValidator.otpCode("12345"))
        assertNull(NetworkInputValidator.otpCode("1234567"))
        assertNull(NetworkInputValidator.otpCode("123 456"))
    }

    @Test
    fun `nonce هو SHA256 hex للنص الاصلي ولا يقبل Client ID فارغًا`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleNonce.sha256Hex("abc")
        )
        val nonce = GoogleNonce.create()
        assertEquals(43, nonce.raw.length)
        assertEquals(64, nonce.sha256.length)
        assertNull(NetworkInputValidator.googleWebClientId("  "))
        assertEquals("client.apps.googleusercontent.com", NetworkInputValidator.googleWebClientId(" client.apps.googleusercontent.com "))
    }

    @Test
    fun `OTP واللقطة يرتبطان بجيل الجلسة ويمسحان عند الالغاء`() {
        val store = AuthSessionStore()
        val attempt = store.beginAuthentication()
        assertTrue(store.markPhoneOtpRequested(attempt, "+966512345678"))
        assertEquals(attempt, store.phoneOtpAttempt("+966512345678"))
        assertTrue(store.activate(attempt, "session-token"))
        assertNull(store.phoneOtpAttempt("+966512345678"))

        assertTrue(store.updateSubscriptionIfMatches(
            "session-token",
            LicenseStatus(state = LicenseState.TRIAL, trialEligible = false, trialEnabled = true, trialDays = 3)
        ))
        assertEquals(LicenseState.TRIAL, store.subscriptionSnapshot()?.state)
        assertEquals(3, store.subscriptionSnapshot()?.trialDays)

        store.clear()
        assertNull(store.subscriptionSnapshot())
        assertFalse(store.updateSubscriptionIfMatches(
            "session-token",
            LicenseStatus(state = LicenseState.TRIAL)
        ))
    }
}

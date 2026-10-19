package app.qannas.network

import org.junit.Assert.*
import org.junit.Test

class CustomerErrorMessagesTest {
    @Test fun `unknown server detail never leaks to customers`() {
        val secret = "SQL relation users failed token=private HTTP 400 Supabase"
        val message = CustomerErrorMessages.forResponse(400, null, secret, "تبادل هوية Google")
        assertFalse(message.contains(secret))
        listOf("SQL", "HTTP", "Supabase", "token", "تبادل هوية").forEach { assertFalse(message.contains(it)) }
        assertTrue(message.contains("[G-TOKEN-400]"))
    }
    @Test fun `expired code suggests a new code`() {
        val message = CustomerErrorMessages.forResponse(403, "otp_expired", null, "")
        assertTrue(message.contains("اطلب رمزًا جديدًا"))
        assertTrue(message.contains("[OTP-EXPIRED]"))
    }
    @Test fun `disabled phone suggests Google instead`() {
        val message = CustomerErrorMessages.forResponse(400, "phone_provider_disabled", null, "")
        assertTrue(message.contains("Google"))
        assertTrue(message.contains("[PHONE-DISABLED]"))
    }
    @Test fun `known code maps without guessing arbitrary detail`() {
        assertTrue(CustomerErrorMessages.forResponse(400, null, "bad_code", "").contains("كود التفعيل"))
        assertFalse(CustomerErrorMessages.forResponse(400, null, "unknown bad_code details", "").contains("كود التفعيل"))
    }
    @Test fun `rate limit and outage are actionable not sales prompts`() {
        assertTrue(CustomerErrorMessages.forResponse(429, null, null, "").contains("انتظر"))
        assertTrue(CustomerErrorMessages.forResponse(503, null, null, "").contains("مؤقتًا"))
    }
}

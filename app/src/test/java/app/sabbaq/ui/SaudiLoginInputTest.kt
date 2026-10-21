package app.sabbaq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaudiLoginInputTest {
    @Test
    fun `accepts only common Saudi mobile representations`() {
        listOf(
            "0501234567",
            "501234567",
            "+966501234567",
            "00966501234567",
            "٩٦٦ ٥٠١ ٢٣٤ ٥٦٧"
        ).forEach { value -> assertTrue("expected Saudi number: $value", SaudiLoginInput.isSaudiMobile(value)) }

        listOf("051234567", "+966401234567", "+971501234567", "05012345678", "not-a-phone").forEach { value ->
            assertFalse("must reject: $value", SaudiLoginInput.isSaudiMobile(value))
        }
    }

    @Test
    fun `normalizes Arabic OTP digits and requires exactly six digits`() {
        assertEquals("123456", SaudiLoginInput.normalizeOtp("١٢٣٤٥٦"))
        assertTrue(SaudiLoginInput.isSixDigitOtp("۱۲۳۴۵۶"))
        assertFalse(SaudiLoginInput.isSixDigitOtp("12345"))
        assertFalse(SaudiLoginInput.isSixDigitOtp("1234567"))
    }
}

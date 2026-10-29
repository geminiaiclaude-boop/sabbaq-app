package app.sabbaq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProductionHardeningUiFormValidationTest {
    @Test
    fun `login rejects blank password and malformed email locally`() {
        assertEquals("أدخل بريدًا إلكترونيًا صحيحًا.", UiFormValidation.loginError("غير صحيح", "password"))
        assertEquals("أدخل كلمة المرور.", UiFormValidation.loginError("driver@example.com", "   "))
        assertNull(UiFormValidation.loginError(" driver@example.com ", "password"))
    }

    @Test
    fun `sign up requires identity fields before a network request`() {
        assertEquals(
            "أدخل الاسم الكامل.",
            UiFormValidation.signUpError(" ", "0500000000", "driver@example.com", "password")
        )
        assertEquals(
            "أدخل رقم الهاتف.",
            UiFormValidation.signUpError("سائق", " ", "driver@example.com", "password")
        )
        assertNull(UiFormValidation.signUpError("سائق", "0500000000", "driver@example.com", "password"))
    }

    @Test
    fun `activation code is normalized without locale dependent casing`() {
        assertEquals("QAN-AB12", UiFormValidation.normalizeActivationCode(" qan-ab12 "))
        assertEquals("أدخل رمز التفعيل أولًا.", UiFormValidation.activationCodeError(" \n"))
        assertNotNull(UiFormValidation.activationCodeError("QAN-AB12"))
        assertNull(UiFormValidation.activationCodeError("0123-4567-89AB-CDEF"))
        assertNull(UiFormValidation.activationCodeError("0123456789ABCDEF"))
    }


}

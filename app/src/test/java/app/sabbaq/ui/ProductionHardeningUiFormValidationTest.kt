package app.sabbaq.ui

import app.sabbaq.core.Filters
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

    @Test
    fun `filters reject inverted ranges and invalid schedules`() {
        assertNotNull(UiFormValidation.filtersError(Filters(minFare = "20", maxFare = "10")))
        assertNotNull(UiFormValidation.filtersError(Filters(minTripKm = "10", maxTripKm = "5")))
        assertNotNull(UiFormValidation.filtersError(Filters(scheduleEnabled = true, scheduleStartMinute = 1440)))
        assertNull(UiFormValidation.filtersError(Filters(minFare = "10", scheduleEnabled = true, scheduleStartMinute = 1320, scheduleEndMinute = 240)))
    }

    @Test
    fun `schedule input handles midnight and rejects overflow`() {
        assertEquals(510, parseScheduleMinute("08:30", false))
        assertEquals(1440, parseScheduleMinute("24:00", true))
        assertNull(parseScheduleMinute("24:00", false))
        assertNull(parseScheduleMinute("12:60", true))
        assertNull(parseScheduleMinute("-1:00", true))
    }
}

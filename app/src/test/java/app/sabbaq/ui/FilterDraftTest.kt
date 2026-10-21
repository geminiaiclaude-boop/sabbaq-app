package app.sabbaq.ui

import app.sabbaq.core.Filters
import app.sabbaq.core.PerKmBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterDraftTest {
    @Test
    fun `المسودة الفارغة صالحة وقرار بلا مرشحات يبقى لوقت التشغيل`() {
        val validation = FilterDraft.from(Filters()).validate()

        assertTrue(validation.isValid)
        assertNotNull(validation.filters)
        assertFalse(validation.filters!!.anyActive())
        assertFalse(validation.filters!!.acceptWhenNoFilters)
    }

    @Test
    fun `تقبل الارقام العربية والفارسية والفاصلة العشرية ضمن النطاق`() {
        val validation = FilterDraft.from(Filters()).copy(
            minFare = "٣،٥",
            maxFare = "۱٬۲۵۰٫۵۰",
            minFarePerKm = "٠٫٧٥",
            maxPickupMinutes = "۲۱۴۷۴۸۳۶۴۷",
            maxTripMinutes = "٣٠",
        ).validate()

        assertTrue(validation.isValid)
        assertEquals("٣،٥", validation.filters!!.minFare)
        assertEquals("۱٬۲۵۰٫۵۰", validation.filters!!.maxFare)
        assertEquals(Int.MAX_VALUE, validation.filters!!.maxPickupMinutes)
        assertEquals(30, validation.filters!!.maxTripMinutes)
    }

    @Test
    fun `الصيغة الرقمية كاملة ولا تقبل exponent او NaN او جزءا صالحا`() {
        listOf("1e3", "NaN", "12km", "1,,23", "-1", "9".repeat(33)).forEach { input ->
            val validation = FilterDraft.from(Filters()).copy(minFare = input).validate()

            assertFalse("يجب رفض: $input", validation.isValid)
            assertTrue(validation.errors.containsKey("minFare"))
            assertNull(validation.filters)
        }
    }

    @Test
    fun `الدقائق ترفض الكسور والاسس والتجاوز فوق Int`() {
        listOf("1.5", "1e2", "2147483648", "00000000001").forEach { input ->
            val validation = FilterDraft.from(Filters()).copy(maxPickupMinutes = input).validate()

            assertFalse("يجب رفض: $input", validation.isValid)
            assertTrue(validation.errors.containsKey("maxPickupMinutes"))
        }
    }

    @Test
    fun `المدى المعكوس يعيد اخطاء مربوطة بالحقول لا باسم مدى عام`() {
        val validation = FilterDraft.from(Filters()).copy(
            minFare = "10",
            maxFare = "3",
            minTripKm = "4",
            maxTripKm = "2",
        ).validate()

        assertFalse(validation.isValid)
        assertTrue(validation.errors.containsKey("minFare"))
        assertTrue(validation.errors.containsKey("maxFare"))
        assertTrue(validation.errors.containsKey("minTripKm"))
        assertTrue(validation.errors.containsKey("maxTripKm"))
        assertFalse(validation.errors.containsKey("fareRange"))
        assertFalse(validation.errors.containsKey("tripKmRange"))
    }

    @Test
    fun `التوقيت العربي يسمح 24 للنهاية فقط`() {
        val valid = FilterDraft.from(Filters()).copy(
            scheduleEnabled = true,
            scheduleStart = "٢٣:٣٠",
            scheduleEnd = "٢٤:٠٠",
        ).validate()
        assertTrue(valid.isValid)
        assertEquals(23 * 60 + 30, valid.filters!!.scheduleStartMinute)
        assertEquals(24 * 60, valid.filters!!.scheduleEndMinute)

        val invalidStart = FilterDraft.from(Filters()).copy(
            scheduleEnabled = true,
            scheduleStart = "24:00",
            scheduleEnd = "23:00",
        ).validate()
        assertTrue(invalidStart.errors.containsKey("scheduleStart"))

        val invalidEnd = FilterDraft.from(Filters()).copy(
            scheduleEnabled = true,
            scheduleStart = "08:00",
            scheduleEnd = "24:01",
        ).validate()
        assertTrue(invalidEnd.errors.containsKey("scheduleEnd"))
    }

    @Test
    fun `حقول التوقيت النصية لا تتحقق عند تعطيل الجدول وتحفظ الحدود الاصلية`() {
        val source = Filters(scheduleStartMinute = 90, scheduleEndMinute = 1_110)
        val validation = FilterDraft.from(source).copy(
            scheduleStart = "999:99",
            scheduleEnd = "ليس وقتا صالحا",
            scheduleEnabled = false,
        ).validate()

        assertTrue(validation.isValid)
        assertEquals(90, validation.filters!!.scheduleStartMinute)
        assertEquals(1_110, validation.filters!!.scheduleEndMinute)
    }

    @Test
    fun `نسخ المسودة يحفظ الحقول غير المعروضة وعقد القائمة الاصلي`() {
        val source = Filters(
            excludeKeywords = listOf("a,b", "رفض"),
            perKmBasis = PerKmBasis.TOTAL,
            scheduleStartMinute = 420,
            scheduleEndMinute = 1_200,
            acceptWhenNoFilters = true,
        )
        val draft = FilterDraft.from(source)
        val changed = draft.copy(minFare = "10").validate()

        assertFalse(draft.isDirtyComparedTo(source))
        assertTrue(draft.copy(minFare = "10").isDirtyComparedTo(source))
        assertTrue(changed.isValid)
        assertEquals("10", changed.filters!!.minFare)
        assertEquals(source.excludeKeywords, changed.filters!!.excludeKeywords)
        assertEquals(source.perKmBasis, changed.filters!!.perKmBasis)
        assertEquals(source.scheduleStartMinute, changed.filters!!.scheduleStartMinute)
        assertEquals(source.scheduleEndMinute, changed.filters!!.scheduleEndMinute)
        assertTrue(changed.filters!!.acceptWhenNoFilters)
    }
}

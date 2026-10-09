package app.qannas.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TextTest {
    @Test
    fun arabicIndicDigitsAndBidiMarksAreNormalised() {
        assertEquals("3.75 د.أ", Text.normalize("‏٣٫٧٥ د.أ"))
        assertEquals("12 km", Text.normalize("۱۲  KM"))
    }

    @Test
    fun numbersHonourThousandsAndDecimalCommas() {
        assertEquals(BigDecimal("1250.50"), Text.parseNumber("1,250.50"))
        assertEquals(BigDecimal("1250"), Text.parseNumber("1,250"))
        assertEquals(BigDecimal("3.5"), Text.parseNumber("3,5"))
        assertEquals(BigDecimal("0.750"), Text.parseNumber("0,750"))
        assertNull(Text.parseNumber(""))
    }

    @Test
    fun wordMatchingRejectsPartialWords() {
        assertTrue(Text.containsWord("accept offer", "accept"))
        assertFalse(Text.containsWord("accepted", "accept"))
        assertTrue(Text.containsWord("قبول الطلب", "قبول"))
    }
}

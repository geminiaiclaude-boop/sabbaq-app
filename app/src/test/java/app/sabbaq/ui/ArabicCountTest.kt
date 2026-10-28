package app.sabbaq.ui

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ArabicCountTest {
    private lateinit var previous: Locale

    @Before fun pinArabic() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar"))
    }

    @After fun restoreLocale() {
        Locale.setDefault(previous)
    }

    @Test
    fun `condition counts agree with the number`() {
        assertEquals("شرط واحد", ArabicCount.conditions(1))
        assertEquals("شرطان", ArabicCount.conditions(2))
        assertEquals("7 شروط", ArabicCount.conditions(7))
        assertEquals("11 شرطًا", ArabicCount.conditions(11))
    }

    @Test
    fun `object form is used after a verb`() {
        assertEquals("شرطًا واحدًا", ArabicCount.conditionsObject(1))
        assertEquals("شرطين", ArabicCount.conditionsObject(2))
        assertEquals("4 شروط", ArabicCount.conditionsObject(4))
    }

    @Test
    fun `step counts agree with the number`() {
        assertEquals("خطوة واحدة", ArabicCount.steps(1))
        assertEquals("خطوتان", ArabicCount.steps(2))
        assertEquals("3 خطوات", ArabicCount.steps(3))
    }

    @Test
    fun `english locale uses plain singular and plural`() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("1 condition", ArabicCount.conditions(1))
        assertEquals("7 conditions", ArabicCount.conditions(7))
        assertEquals("1 day", ArabicCount.days(1))
        assertEquals("30 days", ArabicCount.days(30))
    }
}

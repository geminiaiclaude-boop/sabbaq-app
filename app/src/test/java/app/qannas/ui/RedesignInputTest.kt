package app.qannas.ui

import app.qannas.core.Filters
import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RedesignInputTest {
    @Test fun `preview accepts full localized decimals only`() {
        assertEquals(BigDecimal("12.5"), parsePreviewDecimal("١٢٫٥"))
        listOf("", "12 km", "1e3", "NaN", "-1", "x12", "1".repeat(33)).forEach {
            assertNull("must reject $it", parsePreviewDecimal(it))
        }
    }
    @Test fun `preview integer bounds and malformed inputs are rejected`() {
        assertEquals(8, parsePreviewMinutes("٨"))
        assertEquals(Int.MAX_VALUE, parsePreviewMinutes(Int.MAX_VALUE.toString()))
        listOf("-1", "3.5", "2147483648", "+12", "12 minutes").forEach { assertNull(parsePreviewMinutes(it)) }
    }
    @Test fun `draft snapshot preserves invalid input and original non UI fields`() {
        val original = Filters(acceptWhenNoFilters = true, minFare = "10")
        val edited = FilterDraft.from(original).copy(minFare = "invalid", maxFare = "15", scheduleStart = "٢٣:٠٠")
        val restored = Json.decodeFromString<FilterDraft>(Json.encodeToString(edited))
        assertEquals("invalid", restored.minFare)
        assertEquals("٢٣:٠٠", restored.scheduleStart)
        assertFalse(restored.validate().isValid)
        val valid = restored.copy(minFare = "12").validate().filters!!
        assertTrue(valid.acceptWhenNoFilters)
        assertEquals("15", valid.maxFare)
    }
}

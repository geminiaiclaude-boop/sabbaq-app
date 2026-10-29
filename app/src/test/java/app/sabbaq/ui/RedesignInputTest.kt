package app.sabbaq.ui

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
}

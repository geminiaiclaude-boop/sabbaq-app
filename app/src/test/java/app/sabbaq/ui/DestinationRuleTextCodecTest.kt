package app.sabbaq.ui

import app.sabbaq.core.FilterCombination
import app.sabbaq.core.Filters
import app.sabbaq.core.ZoneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationRuleTextCodecTest {
    @Test
    fun `تُحلل قواعد المناطق والوجهة والممر ثم تحفظ وتستعاد دون فقد`() {
        val draft = FilterDraft.from(Filters()).copy(
            combination = FilterCombination.ANY,
            destinationZones = "include|وسط البلد|31.95|35.91|2500\nexclude|المطار|31.72|35.99|1200",
            destinationAliases = "وسط البلد|31.95|35.91\nالمطار|31.72|35.99",
            smartDestination = "31.95,35.91|3000",
            destinationCorridor = "800|31.90,35.80;31.95,35.91;32.00,36.00",
        )
        val filters = draft.validate().filters
        assertTrue(draft.validate().errors.toString(), filters != null)
        assertEquals(FilterCombination.ANY, filters!!.combination)
        assertEquals(listOf(ZoneMode.INCLUDE, ZoneMode.EXCLUDE), filters.destinationZones.map { it.mode })
        assertEquals(2, filters.destinationAliases.size)
        assertEquals(3, filters.destinationCorridor!!.points.size)

        val restored = FilterDraft.from(filters).validate().filters!!
        assertEquals(filters, restored)
    }

    @Test
    fun `ترفض الإحداثيات خارج WGS84 والصيغ الملتبسة ونصف القطر غير الموجب`() {
        val invalid = FilterDraft.from(Filters()).copy(
            destinationZones = "include|مكان|91|0|1000",
            destinationAliases = "عبارة|31|181",
            smartDestination = "31,35|0",
            destinationCorridor = "250|31,35;91,35",
        ).validate()
        assertFalse(invalid.isValid)
        assertTrue(invalid.errors.containsKey("destinationZones"))
        assertTrue(invalid.errors.containsKey("destinationAliases"))
        assertTrue(invalid.errors.containsKey("smartDestination"))
        assertTrue(invalid.errors.containsKey("destinationCorridor"))
    }

    @Test
    fun `ترفض العبارات المتعارضة التي تحل إلى إحداثيات مختلفة`() {
        val invalid = FilterDraft.from(Filters()).copy(
            destinationAliases = "وجهة|31.95|35.91\nوجهة|32.00|36.00",
        ).validate()
        assertFalse(invalid.isValid)
        assertTrue(invalid.errors.getValue("destinationAliases").contains("متعارضة"))
    }
}

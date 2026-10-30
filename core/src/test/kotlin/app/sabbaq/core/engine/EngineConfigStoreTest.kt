package app.sabbaq.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No condition is on at first install, and an update never changes a driver's saved settings. */
class EngineConfigStoreTest {
    @Test fun a_fresh_install_has_no_condition_on_and_accepts_every_offer() {
        val c = EngineConfig()
        assertFalse(c.priceEnabled); assertFalse(c.timeEnabled)
        assertFalse(c.destCheckEnabled); assertFalse(c.destExcludeEnabled)
        assertFalse(c.destIncludeRejectOnUnknown); assertFalse(c.destZonesRejectOnUnknown)
        assertFalse(c.anyFilterActive)
        val noData = ParsedRide(price = null, minutes = 7)
        assertTrue(DecisionEngine.decide(noData, c).accept)
        assertTrue(DecisionEngine.decide(ParsedRide(price = 999.0, minutes = 90), c).accept)
    }

    @Test fun settings_saved_by_older_versions_keep_their_conditions_on() {
        // Up to 2.0.3-20261030 fields equal to the default were not written: priceEnabled = true
        // was simply absent from a driver's saved limits.
        val old = """{"minPrice":20.0,"maxTime":8,"logicMode":"AND"}"""
        val c = EngineConfigStore.decode(old)!!
        assertTrue(c.priceEnabled); assertTrue(c.timeEnabled)
        assertTrue(c.destIncludeRejectOnUnknown); assertTrue(c.destZonesRejectOnUnknown)
        assertEquals(20.0, c.minPrice, 0.0)
        assertFalse(DecisionEngine.decide(ParsedRide(price = 15.0, minutes = 5), c).accept)
        // A switch the driver had turned off was written, and stays off.
        assertFalse(EngineConfigStore.decode("""{"priceEnabled":false}""")!!.priceEnabled)
    }

    @Test fun every_field_is_written_so_a_later_default_change_cannot_touch_saved_settings() {
        val written = EngineConfigStore.encode(EngineConfig())
        listOf("\"priceEnabled\":false", "\"timeEnabled\":false", "\"destIncludeRejectOnUnknown\":false", "\"destZonesRejectOnUnknown\":false")
            .forEach { assertTrue(it, it in written) }
        assertEquals(EngineConfig(), EngineConfigStore.decode(written))
        val mine = EngineConfig(priceEnabled = true, minPrice = 25.0, timeEnabled = false)
        assertEquals(mine, EngineConfigStore.decode(EngineConfigStore.encode(mine)))
        assertNull(EngineConfigStore.decode("not json"))
    }
}

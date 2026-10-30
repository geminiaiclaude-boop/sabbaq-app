package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference engine's post-accept exclude warning (`Mb`). */
class ExcludeZoneWatchTest {
    private val olaya = """[{"name":"العليا","active":true,"points":[[24.68,46.67],[24.68,46.69],[24.70,46.69],[24.70,46.67]]}]"""
    private val inside = doubleArrayOf(24.69, 46.68)
    private val outside = doubleArrayOf(24.80, 46.80)
    private var now = 10_000_000L
    private val watch = ExcludeZoneWatch { now }
    private val on = EngineConfig(destExcludeZonesEnabled = true, destExcludeZonesJson = olaya)

    @Test fun nothing_applies_without_enabled_exclude_zones() {
        assertEquals(ExcludeZoneWatch.Step.Done, watch.attempt(EngineConfig(destExcludeZonesJson = olaya), "شارع التحلية", 0, inside))
        assertEquals(ExcludeZoneWatch.Step.Done, watch.attempt(EngineConfig(destExcludeZonesEnabled = true), "شارع التحلية", 0, inside))
    }

    @Test fun inside_an_exclude_zone_warns_once_per_half_hour() {
        assertEquals(ExcludeZoneWatch.Step.Warn, watch.attempt(on, "شارع التحلية", 0, inside))
        assertEquals(ExcludeZoneWatch.Step.Done, watch.attempt(on, "شارع التحلية", 1, inside))
        now += ExcludeZoneWatch.WARN_DEDUPE_MS
        assertEquals(ExcludeZoneWatch.Step.Warn, watch.attempt(on, "شارع التحلية", 0, inside))
    }

    @Test fun outside_every_zone_stops_without_a_warning() {
        assertEquals(ExcludeZoneWatch.Step.Done, watch.attempt(on, "حي النخيل", 0, outside))
    }

    @Test fun an_unplaced_destination_retries_on_the_reference_schedule_then_stops() {
        val delays = (0 until 4).map { (watch.attempt(on, "عنوان مجهول", it, null) as ExcludeZoneWatch.Step.Retry).delayMs }
        assertEquals(listOf(20_000L, 40_000L, 120_000L, 120_000L), delays)
        assertEquals(ExcludeZoneWatch.Step.Done, watch.attempt(on, "عنوان مجهول", 4, null))
    }

    @Test fun the_geocoder_path_needs_a_recent_accept_of_the_same_address() {
        assertFalse(watch.onPlaced(on, "شارع التحلية", inside[0], inside[1]))
        watch.recordAccepted("شارع  التحليه")
        assertTrue("same address after folding", watch.onPlaced(on, "شارع التحلية", inside[0], inside[1]))
        watch.recordAccepted("حي العقيق")
        now += ExcludeZoneWatch.ACCEPT_WINDOW_MS + 1
        assertFalse("older than two hours", watch.onPlaced(on, "حي العقيق", inside[0], inside[1]))
    }

    @Test fun global_zones_apply_unless_the_driver_opted_out() {
        val global = EngineConfig(destExcludeZonesEnabled = true, globalDestExcludeZonesJson = olaya)
        assertEquals(ExcludeZoneWatch.Step.Warn, watch.attempt(global, "طريق الملك فهد", 0, inside))
        val optedOut = global.copy(globalDestExcludeOptOut = true)
        assertEquals(ExcludeZoneWatch.Step.Done, watch.attempt(optedOut, "طريق العليا", 0, inside))
    }
}

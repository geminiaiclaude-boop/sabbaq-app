package app.sabbaq.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference engine's decision truth table (reference engine notes). */
class DecisionEngineTest {
    private val node = FakeNode(text = "قبول")
    private fun ride(price: Double? = 20.0, minutes: Int? = 5, destination: String? = null, ambiguous: Boolean = destination == null,
                     addresses: List<String> = listOfNotNull(destination), pickup: String? = null) =
        ParsedRide(price = price, minutes = minutes, destination = destination, acceptNode = node,
            addressAmbiguous = ambiguous, addresses = addresses, pickup = pickup)

    @Test
    fun default_settings_accept_every_offer() {
        assertTrue(DecisionEngine.decide(ride(), EngineConfig()).accept)
        assertTrue(DecisionEngine.decide(ride(price = null), EngineConfig()).accept)
    }

    @Test
    fun missing_trip_data_never_rejects_because_it_is_never_asked_for() {
        // The old engine rejected these as trip_km_missing / pickup_km_missing / destination_missing.
        assertTrue(DecisionEngine.decide(ride(destination = null), EngineConfig(minPrice = 10.0, maxTime = 8)).accept)
    }

    @Test
    fun price_and_time_limits_with_and_or() {
        val cfg = EngineConfig(minPrice = 25.0, maxTime = 6)
        val cheapNear = ride(price = 20.0, minutes = 3)
        assertFalse(DecisionEngine.decide(cheapNear, cfg).accept)
        assertEquals("failed_price", DecisionEngine.decide(cheapNear, cfg).reason)
        assertTrue(DecisionEngine.decide(cheapNear, cfg.copy(logicMode = LogicMode.OR)).accept)
        assertEquals("failed_price_time", DecisionEngine.decide(ride(price = 10.0, minutes = 9), cfg).reason)
    }

    @Test
    fun zero_bounds_mean_no_limit() {
        assertTrue(DecisionEngine.decide(ride(price = 1.0, minutes = 99), EngineConfig(minPrice = 0.0, maxTime = 0)).accept)
    }

    @Test
    fun high_price_ignores_the_time_limit() {
        val cfg = EngineConfig(maxTime = 5, bypassTimeEnabled = true, bypassTimePrice = 40.0)
        assertTrue(DecisionEngine.decide(ride(price = 45.0, minutes = 12), cfg).accept)
        assertFalse(DecisionEngine.decide(ride(price = 30.0, minutes = 12), cfg).accept)
    }

    @Test
    fun excluded_destination_rejects_and_unknown_destination_passes_by_default() {
        val cfg = EngineConfig(destHomeMode = DestinationMode.EXCLUDE, destExcludeEnabled = true, destExcludeKeywords = "المطار, الدرعية")
        val airport = ride(destination = "طريق المطار الدولي", ambiguous = false, addresses = listOf("حي النرجس", "طريق المطار الدولي"), pickup = "حي النرجس")
        assertEquals("dest_excluded", DecisionEngine.decide(airport, cfg).reason)
        assertTrue(DecisionEngine.decide(ride(destination = null), cfg).accept)
        assertEquals("exclude_no_dest_safe_reject",
            DecisionEngine.decide(ride(destination = null), cfg.copy(destExcludeRejectOnUnknown = true)).reason)
    }

    @Test
    fun included_destination_must_match_when_readable() {
        val cfg = EngineConfig(destCheckEnabled = true, destKeywords = "العليا")
        val olaya = ride(destination = "شارع العليا العام", ambiguous = false, addresses = listOf("حي الملقا", "شارع العليا العام"))
        val other = ride(destination = "حي السويدي", ambiguous = false, addresses = listOf("حي الملقا", "حي السويدي"))
        assertTrue(DecisionEngine.decide(olaya, cfg).accept)
        assertEquals("failed_destination", DecisionEngine.decide(other, cfg).reason)
        assertTrue("unknown destination passes while sniping without destination is on",
            DecisionEngine.decide(ride(destination = null), cfg).accept)
    }

    @Test
    fun arabic_folding_matches_hamza_and_ta_marbuta_variants() {
        assertEquals(DecisionEngine.fold("الرياض الجديدة أ"), DecisionEngine.fold("الرياض الجديده ا"))
    }
}

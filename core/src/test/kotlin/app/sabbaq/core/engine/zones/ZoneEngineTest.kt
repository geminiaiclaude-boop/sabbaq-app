package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.DecisionEngine
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.ParsedRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reference engine's geographic stage (b9 smali 3340–7000) and its helpers. */
class ZoneEngineTest {
    /** A ~2 km square around Riyadh's Olaya district. */
    private val olaya = """[{"name":"العليا","active":true,"points":[[24.68,46.67],[24.68,46.69],[24.70,46.69],[24.70,46.67]]}]"""
    /** A square around the airport, north of the city. */
    private val airport = """[{"name":"المطار","active":true,"points":[[24.94,46.69],[24.94,46.73],[24.97,46.73],[24.97,46.69]]}]"""
    private val cache = PoiIndex.parse(
        """{"برج المملكه":{"lat":24.711,"lng":46.674},"شارع التحليه العليا":{"lat":24.69,"lng":46.68},""" +
            """"مطار الملك خالد الدولي":{"lat":24.957,"lng":46.698},"rrrd2929":{"lat":24.69,"lng":46.675},""" +
            """"جنوب العليا":{"lat":24.65,"lng":46.68},"حي النخيل الغربي":{"lat":24.76,"lng":46.62}}""",
    )!!

    private inner class Env(var loc: DoubleArray? = doubleArrayOf(24.75, 46.70)) : ZoneEnvironment {
        val queued = ArrayList<String>()
        var now = 1_000_000L
        var index: PoiIndex? = cache
        override fun driverLocation() = loc
        override fun poiIndex() = index
        override fun requestGeocode(text: String) { queued += text }
        override fun nowMs() = now
    }

    private fun ride(dest: String?, ambiguous: Boolean = dest == null) =
        ParsedRide(price = 20.0, minutes = 4, destination = dest, addressAmbiguous = ambiguous)

    private val includeCfg = EngineConfig(destZonesEnabled = true, destZonesJson = olaya, priceEnabled = false, timeEnabled = false)
    private val excludeCfg = EngineConfig(destExcludeZonesEnabled = true, destExcludeZonesJson = airport, priceEnabled = false, timeEnabled = false)

    @Test
    fun polygon_containment_and_edge_distance() {
        assertTrue(ZoneGeo.insideAny(24.69, 46.68, olaya))
        assertFalse(ZoneGeo.insideAny(24.75, 46.70, olaya))
        val e = ZoneGeo.edgeDistance(24.69, 46.68, olaya)!!
        assertTrue(e.inside)
        assertEquals(1.0, e.km, 0.05)
        assertEquals("[]", ZoneGeo.nearDriver(airport, 21.5, 39.2, 50.0))
        assertTrue(ZoneGeo.hasZones(ZoneGeo.nearDriver(airport, 24.75, 46.70, 50.0)))
        assertFalse(ZoneGeo.hasZones("""[{"active":false,"points":[[1,1],[1,2],[2,2]]}]"""))
    }

    @Test
    fun plus_codes_full_short_and_national_address() {
        val full = PlusCodes.resolve(0.0, 0.0, "الوجهة 8FVC9G8F+6X زيورخ")!!
        assertEquals(47.3655625, full.lat, 1e-9)
        assertEquals(8.5249375, full.lng, 1e-9)
        assertEquals(15.0, full.precisionM, 0.0)
        val short = PlusCodes.resolve(47.37, 8.53, "9G8F+6X")!!
        assertEquals(47.3655625, short.lat, 1e-9)
        assertEquals(8.5249375, short.lng, 1e-9)
        assertNull("a short code needs the driver", PlusCodes.resolve(0.0, 0.0, "9G8F+6X"))
        assertEquals("rrrd2929", PlusCodes.nationalCode("حي العليا RRRD2929"))
        assertNull(PlusCodes.nationalCode("RRRD29290"))
    }

    @Test
    fun place_cache_exact_numbers_dropped_and_fuzzy() {
        val exact = cache.resolve("برج المملكة", 24.75, 46.70, 60.0)
        assertEquals("exact", exact.matchType)
        assertEquals(120.0, exact.precisionM, 0.0)
        assertEquals("exact", cache.resolve("شارع التحلية العليا", 24.75, 46.70, 60.0).matchType)
        val fuzzy = cache.resolve("مطار الملك خالد", 24.75, 46.70, 60.0)
        assertEquals("fuzzy", fuzzy.matchType)
        assertNotNull(fuzzy.point)
        val far = cache.resolve("برج المملكة", 21.5, 39.2, 60.0)
        assertNull("outside the driver's city and radius", far.point)
        assertTrue(PoiIndex.withinEdits(1, "التحليه", "التحلبه"))
        assertFalse(PoiIndex.withinEdits(1, "التحليه", "الملك"))
    }

    @Test
    fun include_zone_accepts_inside_rejects_outside() {
        val env = Env()
        val zones = ZoneEngine(env)
        assertNull(zones.evaluate(ride("شارع التحلية العليا"), includeCfg, true, null).rejectReason)
        assertEquals("outside_dest_zone", zones.evaluate(ride("مطار الملك خالد الدولي"), includeCfg, true, null).rejectReason)
    }

    @Test
    fun include_zone_unknown_destination_follows_its_switch() {
        val env = Env()
        val zones = ZoneEngine(env)
        assertEquals("dest_zone_unknown_reject", zones.evaluate(ride(null), includeCfg, true, null).rejectReason)
        assertEquals("dest_zone_unknown_reject", zones.evaluate(ride("مكان غير معروف تماما"), includeCfg, true, null).rejectReason)
        assertTrue("unknown address is queued for geocoding", "مكان غير معروف تماما" in env.queued)
        val lenient = includeCfg.copy(destZonesRejectOnUnknown = false)
        assertNull(zones.evaluate(ride("مكان غير معروف تماما"), lenient, true, null).rejectReason)
    }

    @Test
    fun on_the_way_rescues_a_destination_past_the_zone() {
        val beyond = ride("جنوب العليا")
        val onTheWay = includeCfg.copy(destZonesOnTheWayEnabled = true)
        assertNull("driver north, zone between, destination south", ZoneEngine(Env(doubleArrayOf(24.75, 46.68))).evaluate(beyond, onTheWay, true, null).rejectReason)
        assertEquals("outside_dest_zone", ZoneEngine(Env(doubleArrayOf(24.75, 46.68))).evaluate(beyond, includeCfg, true, null).rejectReason)
        assertEquals("zone is not on the way from the east", "outside_dest_zone",
            ZoneEngine(Env(doubleArrayOf(24.65, 46.90))).evaluate(beyond, onTheWay, true, null).rejectReason)
    }

    @Test
    fun exclude_zone_rejects_exact_point_inside_and_fails_open_on_fuzzy() {
        val env = Env()
        val zones = ZoneEngine(env)
        val hit = zones.evaluate(ride("مطار الملك خالد الدولي"), excludeCfg, true, null)
        assertEquals("dest_excluded_zone", hit.rejectReason)
        assertEquals("user", hit.evidence!!.source)
        val fuzzy = ZoneEngine(Env()).evaluate(ride("مطار الملك خالد"), excludeCfg, true, null)
        assertNull("a fuzzy match is not proof", fuzzy.rejectReason)
        assertEquals("exclude_zone_unknown_fail_open", fuzzy.diagnostic)
        assertNull(zones.evaluate(ride("برج المملكة"), excludeCfg, true, null).rejectReason)
        val fuzzyOutside = ZoneEngine(Env()).evaluate(ride("النخيل الغربي الرياض"), excludeCfg, true, null)
        assertNull(fuzzyOutside.rejectReason)
        assertEquals("a fuzzy point outside is not a clear pass either", "exclude_zone_unknown_fail_open", fuzzyOutside.diagnostic)
    }

    @Test
    fun exclude_unknown_rejects_only_with_switch_and_zone_nearby() {
        val strict = excludeCfg.copy(destExcludeZonesRejectOnUnknown = true)
        assertEquals("dest_exclude_zone_unknown_reject",
            ZoneEngine(Env(doubleArrayOf(24.88, 46.71))).evaluate(ride("مكان مجهول جدا"), strict, true, null).rejectReason)
        assertNull("airport zone is 21 km away, beyond 15 km", ZoneEngine(Env()).evaluate(ride("مكان مجهول جدا"), strict, true, null).rejectReason)
        assertNull("airport is >15 km from Jeddah", ZoneEngine(Env(doubleArrayOf(21.5, 39.2))).evaluate(ride("مكان مجهول جدا"), strict, true, null).rejectReason)
        assertNull(ZoneEngine(Env()).evaluate(ride("مكان مجهول جدا"), excludeCfg, true, null).rejectReason)
    }

    @Test
    fun exclude_zones_need_the_driver_position() {
        assertNull(ZoneEngine(Env(null)).evaluate(ride("مطار الملك خالد الدولي"), excludeCfg, true, null).rejectReason)
    }

    @Test
    fun exclude_verdict_is_remembered_for_ten_minutes() {
        val env = Env()
        val zones = ZoneEngine(env)
        assertEquals("dest_excluded_zone", zones.evaluate(ride("مطار الملك خالد الدولي"), excludeCfg, true, null).rejectReason)
        env.index = null
        assertEquals("cached while fresh", "dest_excluded_zone", zones.evaluate(ride("مطار الملك خالد الدولي"), excludeCfg, true, null).rejectReason)
        env.now += ZoneEngine.VERDICT_TTL_MS
        assertNull("expired: unknown point fails open", zones.evaluate(ride("مطار الملك خالد الدولي"), excludeCfg, true, null).rejectReason)
    }

    @Test
    fun smart_destination_rejects_the_wrong_direction() {
        val home = EngineConfig(smartDestActive = true, smartDestLat = 24.96, smartDestLng = 46.70, priceEnabled = false, timeEnabled = false)
        val zones = ZoneEngine(Env(doubleArrayOf(24.75, 46.70)))
        assertNull(zones.evaluate(ride("مطار الملك خالد الدولي"), home, true, null).rejectReason)
        assertEquals("wrong_direction", zones.evaluate(ride("برج المملكة"), home, true, null).rejectReason)
    }

    @Test
    fun national_address_resolves_from_cache_and_queues_when_missing() {
        val env = Env()
        val zones = ZoneEngine(env)
        assertNull(zones.evaluate(ride("RRRD2929"), includeCfg, true, null).rejectReason)
        zones.evaluate(ride("ABCD1234"), includeCfg.copy(destZonesRejectOnUnknown = false), true, null)
        assertTrue("ABCD1234" in env.queued)
    }

    @Test
    fun map_markers_give_the_direction_when_the_address_is_unknown() {
        val markers = listOf(intArrayOf(500, 1000), intArrayOf(510, 1005), intArrayOf(505, 995), intArrayOf(500, 400))
        val north = ParsedRide(price = 20.0, minutes = 4, destination = "مكان مجهول", markers = markers)
        val env = Env(doubleArrayOf(24.75, 46.70))
        assertNull("the map points north, the airport zone is north", ZoneEngine(env).evaluate(north, includeCfg.copy(destZonesJson = airport), true, null).rejectReason)
        assertEquals("outside_dest_zone_bearing", ZoneEngine(env).evaluate(north, includeCfg, true, null).rejectReason)
    }

    @Test
    fun decision_runs_the_zone_stage_and_skips_it_when_off() {
        val zones = ZoneEngine(Env())
        val d = DecisionEngine.decide(ride("مطار الملك خالد الدولي"), includeCfg, zones = zones)
        assertFalse(d.accept)
        assertEquals("outside_dest_zone", d.reason)
        assertTrue(DecisionEngine.decide(ride("مطار الملك خالد الدولي"), EngineConfig(priceEnabled = false, timeEnabled = false), zones = zones).accept)
        assertTrue(DecisionEngine.decide(ride("شارع التحلية العليا"), includeCfg, zones = zones).accept)
    }
}

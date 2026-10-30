package app.sabbaq.core.engine.zones

import app.sabbaq.core.engine.DecisionEngine
import app.sabbaq.core.engine.EngineConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zone warming, the names inside a zone, keyword suggestions, the zone limit and the zone diagnosis. */
class ZoneCacheWarmTest {
    private val olaya = listOf(doubleArrayOf(24.68, 46.67), doubleArrayOf(24.68, 46.69), doubleArrayOf(24.70, 46.69), doubleArrayOf(24.70, 46.67))
    private val olayaJson = """[{"name":"العليا","active":true,"points":[[24.68,46.67],[24.68,46.69],[24.70,46.69],[24.70,46.67]]}]"""

    @Test fun junk_names_are_skipped_like_the_reference() {
        listOf("ab", "Street", "طريق", "بدون اسم", "7Q8M+2X", "7q8m +2x", "1234", "١٢٣", "12 34").forEach { assertTrue(it, ZoneCacheWarm.junk(it)) }
        listOf("شارع التحلية", "Olaya St", "طريق 15", "مول الرياض").forEach { assertFalse(it, ZoneCacheWarm.junk(it)) }
    }

    @Test fun the_road_to_a_zone_is_sampled_like_the_reference() {
        val step = ZoneCacheWarm.corridorStepKm(1.5)
        assertEquals(3.0, step, 1e-9)
        assertEquals(1.0, ZoneCacheWarm.corridorStepKm(0.2), 1e-9)
        val c = ZoneCacheWarm.centroid(olaya)
        assertEquals(24.69, c[0], 1e-9); assertEquals(46.68, c[1], 1e-9)
        // ~9.9 km south of the centre: points at 3, 6 and 9 km; the 9 km one lies inside the zone.
        val pts = ZoneCacheWarm.corridorPoints(24.60, 46.68, c[0], c[1], olayaJson, step)
        assertEquals(2, pts.size)
        assertEquals(24.60 + 0.09 * 3.0 / (110.54 * 0.09), pts[0][0], 1e-9)
        assertTrue(pts.none { ZoneGeo.insideAny(it[0], it[1], olayaJson) })
        assertTrue(ZoneCacheWarm.corridorPoints(24.675, 46.68, c[0], c[1], olayaJson, step).isEmpty())
        assertEquals(20, ZoneCacheWarm.corridorPoints(20.0, 46.68, c[0], c[1], olayaJson, step).size)
        val box = ZoneCacheWarm.box(24.6, 46.68, 1.5)
        assertEquals(24.6 - 1.5 / 110.54, box[0][0], 1e-12)
        assertTrue(ZoneCacheWarm.inside(24.6, 46.68, box))
    }

    @Test fun the_road_is_warmed_again_only_after_a_2km_move_or_a_zone_change() {
        val a = ZoneCacheWarm.corridorSignature(olayaJson, 24.600, 46.680)
        assertEquals(a, ZoneCacheWarm.corridorSignature(olayaJson, 24.6005, 46.6805))
        assertFalse(a == ZoneCacheWarm.corridorSignature(olayaJson, 24.63, 46.68))
        assertFalse(a == ZoneCacheWarm.corridorSignature(olayaJson, 24.600, 46.71))
        assertFalse(a == ZoneCacheWarm.corridorSignature(olayaJson.replace("العليا", "الملز"), 24.600, 46.680))
    }

    @Test fun the_query_names_every_reference_feature_inside_the_polygon() {
        val q = ZoneCacheWarm.overpassQuery(olaya)
        assertTrue(q.contains("way[\"highway\"][\"name\"](poly:\"24.68 46.67 24.68 46.69 24.7 46.69 24.7 46.67\")"))
        listOf("node[\"amenity\"]", "way[\"shop\"]", "node[\"tourism\"]", "node[\"office\"]", "out geom 1500").forEach { assertTrue(it, q.contains(it)) }
    }

    @Test fun overpass_names_keep_only_their_points_inside_the_zone() {
        val body = """{"elements":[
            {"type":"way","tags":{"name":"شارع التحلية","name:en":"Tahlia St"},"geometry":[{"lat":24.69,"lon":46.68},{"lat":24.75,"lon":46.68},{"lat":24.695,"lon":46.685}]},
            {"type":"node","lat":24.69,"lon":46.675,"tags":{"name":"مقهى الورد","amenity":"cafe"}},
            {"type":"node","lat":24.80,"lon":46.80,"tags":{"name":"خارج المنطقة"}},
            {"type":"way","geometry":[{"lat":24.69,"lon":46.68}]}
        ]}"""
        val names = ZoneCacheWarm.parseOverpass(body, olaya)
        assertEquals(listOf("شارع التحلية", "Tahlia St", "مقهى الورد"), names.map { it.name })
        assertEquals(2, names[0].points.size)
        assertTrue(ZoneCacheWarm.parseOverpass("not json", olaya).isEmpty())
    }

    @Test fun new_entries_skip_junk_cached_and_repeated_names() {
        val found = listOf(
            ZoneName("شارع التحلية", listOf(doubleArrayOf(24.69, 46.68))),
            ZoneName("شارع  التحليه", listOf(doubleArrayOf(24.69, 46.68))),
            ZoneName("طريق", listOf(doubleArrayOf(24.69, 46.68))),
            ZoneName("مول الرياض", listOf(doubleArrayOf(24.69, 46.68))),
            ZoneName("مقهى", emptyList()),
        )
        val out = ZoneCacheWarm.newEntries(found, setOf(DecisionEngine.fold("مول الرياض")))
        assertEquals(listOf("شارع التحلية"), out.values.map { it.name })
    }

    @Test fun the_grid_stays_inside_the_zone_and_is_capped() {
        val g = ZoneCacheWarm.grid(olaya)
        assertTrue(g.isNotEmpty() && g.size <= ZoneCacheWarm.GRID_MAX)
        assertTrue(g.all { ZoneCacheWarm.inside(it[0], it[1], olaya) })
        val tiny = listOf(doubleArrayOf(24.0, 46.0), doubleArrayOf(24.0, 46.0001), doubleArrayOf(24.0001, 46.0001), doubleArrayOf(24.0001, 46.0))
        assertEquals(1, ZoneCacheWarm.grid(tiny).size)
    }

    @Test fun names_inside_and_suggestions_leave_out_existing_keywords() {
        val cache = Json.parseToJsonElement("""{
            "${DecisionEngine.fold("شارع التحلية")}":{"name":"شارع التحلية","points":[{"lat":24.69,"lng":46.68}]},
            "${DecisionEngine.fold("مول الرياض")}":{"points":[{"lat":24.695,"lng":46.675}]},
            "${DecisionEngine.fold("حي النخيل")}":{"lat":24.80,"lng":46.80}
        }""").jsonObject
        val inside = ZoneCacheWarm.namesInside(cache, olaya)
        assertEquals(setOf("شارع التحلية", DecisionEngine.fold("مول الرياض")), inside.map { it.name }.toSet())
        assertEquals(listOf(DecisionEngine.fold("مول الرياض")), ZoneCacheWarm.suggestions(inside, "شارع التحليه، المطار"))
        assertTrue(ZoneCacheWarm.suggestions(inside, "شارع التحلية, مول الرياض").isEmpty())
    }

    @Test fun the_zone_limit_counts_switched_on_zones_of_both_lists() {
        val off = """[{"name":"x","active":false,"points":[[1,1],[1,2],[2,2]]}]"""
        assertEquals(1, ZoneLimit.activeCount(olayaJson, off))
        assertTrue(ZoneLimit.canActivate(1, 2))
        assertFalse(ZoneLimit.canActivate(2, 2))
        assertTrue("0 = no limit", ZoneLimit.canActivate(50, 0))
        assertTrue(ZoneGeo.hasDrawn(off))
        assertFalse(ZoneGeo.hasDrawn("[]"))
    }

    @Test fun the_diagnosis_reports_the_map_and_the_engine() {
        val cache = PoiIndex(Json.parseToJsonElement("""{"${DecisionEngine.fold("مستشفى الحبيب")}":{"lat":24.69,"lng":46.68}}""").jsonObject)
        val me = doubleArrayOf(24.70, 46.70)
        val exclude = EngineConfig(destExcludeZonesEnabled = true, destExcludeZonesJson = olayaJson)
        ZoneDiagnosis.diagnose(exclude, "مستشفى الحبيب", cache, null, me).let {
            assertEquals(ZoneDiagnosis.Where.INSIDE_EXCLUDE, it.where)
            assertEquals("العليا", it.zoneName)
            assertTrue(it.fromCache)
            assertEquals("dest_excluded_zone", it.engineReject)
        }
        val include = EngineConfig(destZonesEnabled = true, destZonesJson = olayaJson, destZonesRejectOnUnknown = true)
        ZoneDiagnosis.diagnose(include, "مقهى الورد", cache, doubleArrayOf(24.69, 46.675), me).let {
            assertEquals(ZoneDiagnosis.Where.INSIDE_INCLUDE, it.where)
            assertFalse(it.fromCache)
            assertTrue("rejected now only because the address is not cached", it.rejectedForNoPoint)
        }
        ZoneDiagnosis.diagnose(include, "حي بعيد", cache, doubleArrayOf(24.90, 46.90), me).let {
            assertEquals(ZoneDiagnosis.Where.OUTSIDE_INCLUDE, it.where)
            assertFalse(it.rejectedForNoPoint)
        }
        ZoneDiagnosis.diagnose(EngineConfig(), "أي مكان", cache, null, me).let {
            assertEquals(ZoneDiagnosis.Where.NO_ZONES, it.where)
            assertNull(it.engineReject)
        }
    }
}

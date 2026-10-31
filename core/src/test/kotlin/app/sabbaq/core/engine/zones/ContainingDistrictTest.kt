package app.sabbaq.core.engine.zones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «استخدم حدود العليا» said "no boundary" because search knows Al Olaya only as a point (a node);
 * the boundary is the district relation around it, shaped like this Overpass answer (2026-10-31).
 */
class ContainingDistrictTest {
    private val body = """
        {"elements":[
          {"type":"relation","id":8835054,"tags":{"admin_level":"9","boundary":"administrative","name":"بلدية العليا"},
           "members":[{"type":"way","role":"outer","geometry":[{"lat":24.60,"lon":46.60},{"lat":24.60,"lon":46.80},{"lat":24.80,"lon":46.80},{"lat":24.80,"lon":46.60},{"lat":24.60,"lon":46.60}]}]},
          {"type":"relation","id":13254417,"tags":{"admin_level":"10","boundary":"administrative","name":"حي العليا","name:en":"Al Olaya District"},
           "members":[
             {"type":"way","role":"outer","geometry":[{"lat":24.68,"lon":46.67},{"lat":24.68,"lon":46.70},{"lat":24.71,"lon":46.70}]},
             {"type":"way","role":"outer","geometry":[{"lat":24.68,"lon":46.67},{"lat":24.71,"lon":46.67},{"lat":24.71,"lon":46.70}]},
             {"type":"node","role":"label","lat":24.69,"lon":46.68}
           ]}
        ]}
    """.trimIndent()

    @Test fun the_deepest_district_is_chosen_and_its_ways_joined_into_one_ring() {
        val (name, ring) = CityIndex.containingDistrict(body)!!
        assertEquals("حي العليا", name)
        // The second way runs the other way round: joined reversed, the closing corner not repeated.
        assertEquals(4, ring.size)
        assertTrue(ring.any { it[0] == 24.71 && it[1] == 46.67 })
        assertTrue(ring.any { it[0] == 24.68 && it[1] == 46.70 })
    }

    @Test fun no_district_around_the_point_gives_nothing() {
        assertNull(CityIndex.containingDistrict("""{"elements":[]}"""))
        assertNull(CityIndex.containingDistrict("not json"))
    }

    @Test fun the_query_asks_for_district_levels_around_the_point() {
        val q = CityIndex.containingDistrictQuery(24.693086, 46.684508)
        assertTrue(q.contains("is_in(24.693086,46.684508)"))
        assertTrue(q.contains("out geom"))
    }
}

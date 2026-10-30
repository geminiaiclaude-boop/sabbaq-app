package app.sabbaq.core.engine.zones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The driver's city index: download parsing, type-ahead, boundaries and storage. */
class CityIndexTest {
    private val overpass = """{"elements":[
        {"type":"relation","id":51,"center":{"lat":24.69,"lon":46.68},"tags":{"place":"suburb","name":"حي العليا","name:en":"Al Olaya","alt_name":"العليا"}},
        {"type":"node","id":7,"lat":24.84,"lon":46.64,"tags":{"place":"neighbourhood","name":"حي النرجس","name:en":"An Narjis"}},
        {"type":"way","id":90,"center":{"lat":24.71,"lon":46.67},"tags":{"highway":"primary","name":"طريق الملك فهد","name:en":"King Fahd Road"}},
        {"type":"way","id":91,"center":{"lat":24.75,"lon":46.65},"tags":{"highway":"primary","name":"طريق الملك فهد"}},
        {"type":"way","id":12,"center":{"lat":24.72,"lon":46.64},"tags":{"amenity":"hospital","name":"مستشفى الحبيب"}},
        {"type":"node","id":13,"lat":24.70,"lon":46.60,"tags":{"amenity":"school","name":"12"}},
        {"type":"node","id":14,"lat":24.70,"lon":46.60,"tags":{"amenity":"school"}}
    ]}"""
    private val riyadh = doubleArrayOf(24.70, 46.67)

    @Test fun the_download_keeps_every_spelling_and_joins_road_stretches() {
        val places = CityIndex.parse(overpass)
        assertEquals(4, places.size)
        val olaya = places.first { it.name == "حي العليا" }
        assertEquals(CityIndex.DISTRICT, olaya.kind)
        assertEquals(listOf("حي العليا", "Al Olaya", "العليا"), olaya.names)
        assertEquals("R51", olaya.osm)
        val road = places.first { it.kind == CityIndex.ROAD }
        assertEquals(2, road.points.size)
        assertNull(road.osm)
        assertEquals(CityIndex.LANDMARK, places.first { it.name == "مستشفى الحبيب" }.kind)
        assertTrue(CityIndex.parse("nope").isEmpty())
    }

    @Test fun suggestions_appear_while_typing_with_prefixes_articles_and_typos() {
        val places = CityIndex.parse(overpass)
        assertEquals("حي العليا", CityIndex.suggest(places, "العل", riyadh).first().name)
        assertEquals("حي العليا", CityIndex.suggest(places, "عليا", riyadh).first().name)
        assertEquals("حي النرجس", CityIndex.suggest(places, "حي النر", riyadh).first().name)
        assertEquals("حي النرجس", CityIndex.suggest(places, "narj", riyadh).first().name)
        assertEquals("طريق الملك فهد", CityIndex.suggest(places, "الملك", riyadh).first().name)
        // One typo forgiven (ـه/ـة fold, a swapped letter).
        assertEquals("حي النرجس", CityIndex.suggest(places, "النجرس", riyadh).first().name)
        assertEquals("مستشفى الحبيب", CityIndex.suggest(places, "مستشفي الحبيب", riyadh).first().name)
        assertTrue(CityIndex.suggest(places, "ع", riyadh).isEmpty())
        assertTrue(CityIndex.suggest(places, "زززز", riyadh).isEmpty())
    }

    @Test fun nearer_places_come_first_within_a_rank() {
        val two = listOf(
            CityPlace(listOf("مول النخيل"), CityIndex.LANDMARK, listOf(doubleArrayOf(24.90, 46.90))),
            CityPlace(listOf("مول النخيل"), CityIndex.LANDMARK, listOf(doubleArrayOf(24.701, 46.671))),
        )
        assertEquals(24.701, CityIndex.suggest(two, "مول", riyadh).first().points[0][0], 1e-9)
    }

    @Test fun both_the_kind_word_and_the_article_are_dropped_before_matching() {
        val places = listOf(
            CityPlace(listOf("ملزم مول"), CityIndex.LANDMARK, listOf(doubleArrayOf(24.70, 46.67))),
            CityPlace(listOf("حي الملز"), CityIndex.DISTRICT, listOf(doubleArrayOf(24.66, 46.73))),
        )
        assertEquals("ملز", CityIndex.core("حي الملز"))
        assertEquals("حي الملز", CityIndex.suggest(places, "ملز", riyadh).first().name)
    }

    @Test fun the_city_box_comes_from_nominatim_and_is_never_too_small() {
        val body = """{"name":"الرياض","address":{"city":"الرياض","state":"منطقة الرياض"},"boundingbox":["24.30","25.10","46.30","47.10"]}"""
        val (name, box) = CityIndex.cityFromReverse(body)!!
        assertEquals("الرياض", name)
        assertTrue(box.contains(24.7, 46.7) && box.contains(24.29, 46.29))
        assertFalse(box.contains(26.0, 46.7))
        val small = CityIndex.cityFromReverse("""{"address":{"town":"x"},"boundingbox":["24.00","24.01","46.00","46.01"]}""")!!.second
        assertTrue((small.north - small.south) * 110.54 >= 29.0)
        assertNull(CityIndex.cityFromReverse("{}"))
    }

    @Test fun a_district_outline_becomes_zone_corners() {
        val ring = (0 until 200).joinToString(",") { i ->
            val a = Math.toRadians(i * 360.0 / 200)
            "[${46.68 + 0.01 * Math.cos(a)},${24.69 + 0.01 * Math.sin(a)}]"
        }
        val geo = Json.parseToJsonElement("""{"type":"MultiPolygon","coordinates":[[[[46.0,24.0],[46.001,24.0],[46.0,24.001],[46.0,24.0]]],[[$ring]]]}""").jsonObject
        val corners = CityIndex.outline(geo, 60)
        assertEquals(60, corners.size)
        assertTrue(corners.all { CityIndex.distanceKm(24.69, 46.68, it[0], it[1]) in 1.0..1.2 })
        assertTrue(ZoneCacheWarm.inside(24.69, 46.68, corners))
        assertTrue(CityIndex.outline(null).isEmpty())
        assertEquals(3, CityIndex.simplify(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 2.0), doubleArrayOf(1.0, 1.0)), 3).size)
    }

    @Test fun photon_answers_become_labelled_places() {
        val body = """{"features":[
            {"geometry":{"coordinates":[46.64,24.84]},"properties":{"name":"حي النرجس","city":"الرياض","osm_key":"place","osm_type":"R","osm_id":99}},
            {"geometry":{"coordinates":[46.67,24.71]},"properties":{"name":"برج المملكة","district":"العليا","osm_key":"building","osm_type":"W","osm_id":5}},
            {"geometry":{"coordinates":[46.67]},"properties":{"name":"bad"}}]}"""
        val places = CityIndex.parsePhoton(body)
        assertEquals(2, places.size)
        assertEquals("حي النرجس، الرياض", places[0].name)
        assertEquals("R99", places[0].osm)
        assertNull(places[1].osm)
        assertEquals(24.71, places[1].points[0][0], 1e-9)
    }

    @Test fun the_index_is_stored_reloaded_and_refreshed() {
        val places = CityIndex.parse(overpass)
        val box = GeoBox(24.3, 46.3, 25.1, 47.1)
        val saved = CityIndex.decode(CityIndex.encode("الرياض", box, 1_000L, places))
        assertNotNull(saved)
        assertEquals(places.map { it.names }, saved!!.places.map { it.names })
        assertEquals("R51", saved.places.first().osm)
        assertFalse(CityIndex.stale(saved, 24.7, 46.7, 1_000L + 1000))
        assertTrue(CityIndex.stale(saved, 24.7, 46.7, 1_000L + CityIndex.MAX_AGE_MS + 1))
        assertTrue(CityIndex.stale(saved, 21.5, 39.2, 2_000L))
        assertTrue(CityIndex.stale(null, 24.7, 46.7, 0L))
    }

    @Test fun the_city_adds_its_names_to_the_address_cache_districts_first() {
        val entries = CityIndex.cacheEntries(CityIndex.parse(overpass), setOf(app.sabbaq.core.engine.DecisionEngine.fold("Al Olaya")))
        assertEquals("حي العليا", entries.values.first().name)
        assertFalse(entries.values.any { it.name == "Al Olaya" })
        assertTrue(entries.values.any { it.name == "King Fahd Road" })
    }
}

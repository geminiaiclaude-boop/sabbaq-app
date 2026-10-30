package app.sabbaq.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The GeoJSON Sabbaq draws on the map: zones, the zone being drawn, its corners and pins. */
class MapStylesTest {
    private val a = GeoPoint(24.68, 46.67)
    private val b = GeoPoint(24.68, 46.69)
    private val c = GeoPoint(24.70, 46.69)

    private fun features(json: String) = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray

    @Test fun a_zone_is_a_closed_polygon_in_lng_lat_order_with_its_colours() {
        val f = features(MapStyles.areas(listOf(MapArea(listOf(a, b, c), "#1e9650", "#148246"), MapArea(listOf(a, b), "#000000", "#000000"))))
        assertEquals(1, f.size)
        val ring = f[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(4, ring.size)
        assertEquals(46.67, ring[0].jsonArray[0].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(24.68, ring[0].jsonArray[1].jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(ring.first(), ring.last())
        assertEquals("#1e9650", f[0].jsonObject["properties"]!!.jsonObject["fill"]!!.jsonPrimitive.content)
    }

    @Test fun the_draft_grows_from_a_line_to_a_polygon_and_its_corners_are_numbered() {
        assertTrue(features(MapStyles.draft(listOf(a), "#f", "#l")).isEmpty())
        assertEquals("LineString", features(MapStyles.draft(listOf(a, b), "#f", "#l"))[0].jsonObject["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Polygon", features(MapStyles.draft(listOf(a, b, c), "#f", "#l"))[0].jsonObject["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val corners = features(MapStyles.corners(listOf(a, b, c), "#l"))
        assertEquals(listOf("0", "1", "2"), corners.map { it.jsonObject["properties"]!!.jsonObject["i"]!!.jsonPrimitive.content })
        assertEquals("#00f", features(MapStyles.pins(listOf(a to "#00f")))[0].jsonObject["properties"]!!.jsonObject["color"]!!.jsonPrimitive.content)
    }
}

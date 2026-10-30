package app.sabbaq.core.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline city map list: validation, which city a driver gets, and the monthly refresh. */
class MapManifestTest {
    private val sha = "a".repeat(64)
    private fun pack(id: String, bbox: String, file: String = "$id-20261030.pmtiles", bytes: Long = 19_000_000, s: String = sha) =
        """{"id":"$id","name_ar":"م","name_en":"c","bbox":[$bbox],"file":"$file","bytes":$bytes,"sha256":"$s"}"""
    private fun manifest(vararg packs: String, schema: Int = 1) =
        """{"schema":$schema,"build":"20261030","generated_at":"2026-10-30T10:00:00Z","attribution":"© OpenStreetMap","packs":[${packs.joinToString(",")}],"extra":1}"""

    private val riyadh = pack("riyadh", "46.35,24.40,47.10,25.10")
    private val kharj = pack("kharj", "47.20,24.05,47.45,24.25")
    private val dammam = pack("dammam", "49.85,26.15,50.30,26.65")

    @Test fun a_well_formed_list_is_read() {
        val m = MapPacks.parse(manifest(riyadh, kharj, dammam))
        assertNotNull(m)
        assertEquals(3, m!!.packs.size)
        assertEquals(46.35, m.packs[0].west, 0.0)
    }

    @Test fun any_bad_pack_rejects_the_whole_list() {
        assertNull(MapPacks.parse(manifest(riyadh, schema = 2)))
        assertNull(MapPacks.parse(manifest()))
        assertNull(MapPacks.parse(manifest(riyadh, pack("x/../y", "1,1,2,2"))))
        assertNull(MapPacks.parse(manifest(riyadh, pack("bad", "1,1,2,2", file = "../etc.pmtiles"))))
        assertNull(MapPacks.parse(manifest(pack("bad", "1,1,2,2", s = "zz"))))
        assertNull(MapPacks.parse(manifest(pack("bad", "2,1,1,2"))))
        assertNull(MapPacks.parse(manifest(pack("bad", "1,1,2"))))
        assertNull(MapPacks.parse(manifest(pack("bad", "1,1,2,2", bytes = 0))))
        assertNull(MapPacks.parse(manifest(riyadh, riyadh)))
        assertNull(MapPacks.parse("not json"))
    }

    @Test fun the_driver_gets_the_city_they_are_in_or_the_nearest() {
        val m = MapPacks.parse(manifest(riyadh, kharj, dammam))!!
        assertEquals("riyadh", MapPacks.packFor(m, 24.71, 46.67)?.id)
        assertEquals("kharj", MapPacks.packFor(m, 24.15, 47.30)?.id)
        // 20 km south of Riyadh's box: still Riyadh.
        assertEquals("riyadh", MapPacks.packFor(m, 24.22, 46.70)?.id)
        // In the desert, far from every city: none.
        assertNull(MapPacks.packFor(m, 23.00, 45.00))
    }

    @Test fun the_smallest_city_wins_where_boxes_overlap() {
        val m = MapPacks.parse(manifest(riyadh, pack("diriyah", "46.50,24.70,46.60,24.80")))!!
        assertEquals("diriyah", MapPacks.packFor(m, 24.75, 46.55)?.id)
    }

    @Test fun a_stored_city_is_replaced_only_by_a_new_build_after_a_month() {
        val p = MapPacks.parse(manifest(riyadh))!!.packs[0]
        val now = 100L * 24 * 60 * 60 * 1000
        assertTrue(MapPacks.needsDownload(p, null, null, now))
        assertFalse(MapPacks.needsDownload(p, sha, 0L, now))
        assertFalse(MapPacks.needsDownload(p, "b".repeat(64), now - MapPacks.REFRESH_MS + 1, now))
        assertTrue(MapPacks.needsDownload(p, "b".repeat(64), now - MapPacks.REFRESH_MS, now))
    }
}

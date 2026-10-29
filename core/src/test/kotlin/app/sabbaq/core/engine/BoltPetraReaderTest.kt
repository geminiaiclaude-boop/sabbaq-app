package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** FareSniper's Bolt (`Y2`) and PetraRide (`Et`) readers and its package routing. */
class BoltPetraReaderTest {
    private fun bolt(extra: List<FakeNode> = emptyList()): Pair<FakeNode, FakeNode> {
        val accept = FakeNode(text = "قبول", viewId = "ee.mtakso.driver:id/acceptButton", isClickable = true, rect = Bounds(60, 2150, 1020, 2300))
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "21.84 ر.س", viewId = "ee.mtakso.driver:id/categoryPriceInfo", rect = row(1300)),
            FakeNode(text = "3 دقيقة", rect = row(1400)),
            FakeNode(text = "حي النرجس، الرياض", viewId = "ee.mtakso.driver:id/newOrderAddressText", rect = row(1500)),
            FakeNode(text = "مطار الملك خالد، الرياض", viewId = "ee.mtakso.driver:id/newOrderAddressText", rect = row(1600)),
            FakeNode(text = "4.7★", viewId = "ee.mtakso.driver:id/riderInfoText", rect = row(1700)),
        ) + extra + accept)
        return root to accept
    }

    @Test
    fun bolt_offer_reads_price_minutes_addresses_rating_and_accept() {
        val (root, accept) = bolt()
        val ride = BoltScreenReader(EngineConfig()).read(root, 2400)!!
        assertEquals(21.84, ride.price!!, 1e-9)
        assertEquals(3, ride.minutes)
        assertEquals("مطار الملك خالد، الرياض", ride.destination)
        assertEquals("حي النرجس، الرياض", ride.pickup)
        assertEquals(4.7, ride.rating!!, 1e-9)
        assertSame(accept, ride.acceptNode)
    }

    @Test
    fun bolt_wait_and_save_and_active_trip() {
        val (root, _) = bolt(listOf(FakeNode(text = "أنتظر ووفر", rect = row(1800))))
        assertTrue(BoltScreenReader(EngineConfig()).read(root, 2400)!!.waitAndSave)
        val trip = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(FakeNode(viewId = "ee.mtakso.driver:id/slideText", rect = row(2000))))
        assertTrue(BoltScreenReader(EngineConfig()).read(trip, 2400)!!.isActiveRide)
    }

    @Test
    fun petra_offer_reads_ids_and_rounds_minutes() {
        val accept = FakeNode(text = "قبول", viewId = "com.PetraRide_Captain:id/accept_button", isClickable = true, rect = Bounds(560, 2150, 1020, 2300))
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "1.68 د.أ", viewId = "com.PetraRide_Captain:id/estimated_trip_price", rect = row(1300)),
            FakeNode(text = "3.95 دقيقة", viewId = "com.PetraRide_Captain:id/estimated_time_to_pick_up", rect = row(1400)),
            FakeNode(text = "شارع اليرموك, المنارة, الأردن", rect = row(1500)),
            FakeNode(text = "عمان، الصويفية", viewId = "com.PetraRide_Captain:id/drop_off_location_title_label", rect = row(1600)),
            FakeNode(text = "رفض", viewId = "com.PetraRide_Captain:id/reject_button", isClickable = true, rect = Bounds(60, 2150, 500, 2300)),
            accept,
        ))
        val ride = PetraScreenReader().read(root, 2400)!!
        assertEquals(1.68, ride.price!!, 1e-9)
        assertEquals(4, ride.minutes)
        assertEquals("عمان، الصويفية", ride.destination)
        assertEquals("شارع اليرموك, المنارة, الأردن", ride.pickup)
        assertSame(accept, ride.acceptNode)
    }

    @Test
    fun petra_cancel_screen_is_not_an_offer() {
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "إلغاء", viewId = "com.PetraRide_Captain:id/cancel_trip_button", rect = row(2000))))
        assertNull(PetraScreenReader().read(root, 2400))
    }

    @Test
    fun routing_follows_the_target_app() {
        assertEquals(RideApp.JEENY, RideApps.of("me.com.easytaxista", TargetApp.JEENY))
        assertNull(RideApps.of("ee.mtakso.driver", TargetApp.JEENY))
        assertEquals(RideApp.BOLT, RideApps.of("ee.mtakso.driver", TargetApp.BOTH))
        assertEquals(RideApp.PETRA, RideApps.of("com.PetraRide_Captain", TargetApp.PETRA))
        assertNull(RideApps.of("app.sabbaq.driver", TargetApp.BOTH))
    }

    @Test
    fun latin_units_match_in_any_case_as_faresniper_compiles_them() {
        val accept = FakeNode(text = "قبول", viewId = "ee.mtakso.driver:id/acceptButton", isClickable = true, rect = Bounds(60, 2150, 1020, 2300))
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "SAR 21.84", rect = row(1300)),
            FakeNode(text = "3 Min", rect = row(1400)),
            accept,
        ))
        val ride = BoltScreenReader(EngineConfig()).read(root, 2400)!!
        assertEquals(21.84, ride.price!!, 1e-9)
        assertEquals(3, ride.minutes)
        assertTrue(PetraScreenReader.PRICE.matcher("JOD 1.68").find())
        assertTrue(PetraScreenReader.CITY.matcher("Abdoun, Amman").find())
    }

    @Test
    fun the_service_also_listens_to_sabbaq_for_the_live_accept_test() {
        val pk = RideApps.packages(TargetApp.JEENY, "app.sabbaq.driver")
        assertTrue("me.com.easytaxista" in pk && "app.sabbaq.driver" in pk)
        assertEquals("bolt", RideApps.read(RideApp.BOLT, FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "21 ر.س", rect = row(1300)), FakeNode(text = "3 دقيقة", rect = row(1400)),
            FakeNode(text = "قبول", isClickable = true, rect = Bounds(60, 2150, 1020, 2300)))), 2400, EngineConfig(), null, { 0L })!!.provider)
    }
}

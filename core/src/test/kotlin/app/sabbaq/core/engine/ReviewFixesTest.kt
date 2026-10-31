package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import app.sabbaq.core.DiagnosticEntry
import app.sabbaq.core.DiagnosticStage
import app.sabbaq.core.OfferStatsDeduplicator
import app.sabbaq.core.RuntimeStatusStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine items of the 2026-10-30 engineering review, each failing on the code before the fix. */
class ReviewFixesTest {
    private val height = 2400

    private fun card(price: String, time: String): FakeNode {
        val label = FakeNode(text = "قبول العرض", rect = Bounds(100, 2200, 980, 2290))
        val frame = FakeNode(className = "android.widget.FrameLayout", isClickable = true, rect = Bounds(80, 2180, 1000, 2310), children = listOf(label))
        return FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = time, rect = row(1400)),
            FakeNode(text = price, rect = row(1500)),
            frame,
        ))
    }

    @Test fun h03_decimal_comma_bidi_marks_and_persian_digits_are_read() {
        val reader = JeenyScreenReader(EngineConfig()) { 0L }
        assertEquals(25.5, reader.read(card("25,50 ر.س", "يبعد 4 دقائق"), height)!!.price!!, 0.0)
        assertEquals(25.5, reader.read(card("ر.س 25,50", "يبعد 4 دقائق"), height)!!.price!!, 0.0)
        val bidi = reader.read(card("٢٥٫٥٠‏ ر.س", "يبعد ٤‏ دقائق"), height)!!
        assertEquals(4, bidi.minutes)
        assertEquals(25.5, bidi.price!!, 0.0)
        assertEquals(7, reader.read(card("⃁ 30", "يبعد ۷ دقائق"), height)!!.minutes)
        assertEquals("1250", JeenyLexicon.latinDigits("۱۲۵۰"))
        assertEquals("25.50", JeenyLexicon.firstNumber("25.50 ر.س."))
    }

    @Test fun c03_a_price_check_without_bounds_never_rejects_an_unread_price() {
        val noBounds = EngineConfig(priceEnabled = true)
        assertFalse(noBounds.priceFilterOn)
        val known = ParsedRide(price = null, minutes = 5, destination = "حي النرجس", addressAmbiguous = false, addresses = listOf("حي العليا", "حي النرجس"))
        assertTrue(DecisionEngine.decide(known, noBounds).accept)
        assertFalse("a real bound still rejects an unread price on a known destination",
            DecisionEngine.decide(known, EngineConfig(priceEnabled = true, minPrice = 20.0)).accept)
    }

    @Test fun c02_a_payment_filter_never_takes_the_learned_id_shortcut() {
        assertTrue(EngineConfig(paymentFilterMode = PaymentMode.CASH).needsFullTree)
        assertFalse(EngineConfig().needsFullTree)
        val ids = LearnedOfferIds("me.com.easytaxista:id/tvAccept", "me.com.easytaxista:id/tvPrice", "me.com.easytaxista:id/tvTime")
        val root = FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "يبعد 4 دقائق", viewId = ids.timeId, rect = row(1400)),
            FakeNode(text = "⃁ 25.50", viewId = ids.priceId, rect = row(1500)),
            FakeNode(text = "بطاقة", rect = row(1600)),
            FakeNode(text = "قبول العرض", viewId = ids.acceptId, isClickable = true, rect = Bounds(100, 2200, 980, 2290)),
        ))
        assertNull(JeenyScreenReader(EngineConfig(paymentFilterMode = PaymentMode.CASH)) { 0L }.readByLearnedIds(root, ids))
    }

    @Test fun h04_the_phone_clock_never_moves_the_newest_seen_time_back() {
        val now = 1_800_000_000_000L
        val seen = now + 110 * 60_000L // 1 h 50 min ahead: under the 2 h tamper line
        val first = SnipingGate.check(true, true, now + 86_400_000L, seen, now)
        assertEquals("the newest time is kept, not lowered to the turned-back clock", seen, first.newestSeenMs)
        // Turning it back again by the same amount now crosses the line.
        val again = SnipingGate.check(true, true, now + 86_400_000L, first.newestSeenMs, now - 110 * 60_000L)
        assertFalse(again.allowed)
        assertEquals("clock_tampered", again.reason)
    }

    @Test fun m02_runtime_diagnostics_redact_what_telemetry_redacts() {
        val status = RuntimeStatusStore(5)
        status.diagnostic(DiagnosticEntry(DiagnosticStage.REJECTED, 1, "address 12 Main Street"))
        assertFalse(status.snapshot().diagnostics.any { it.reason?.contains("Main") == true })
    }

    @Test fun m03_the_same_price_and_minutes_hours_later_is_another_offer() {
        val d = OfferStatsDeduplicator()
        assertTrue(d.firstVisible("15.0|4||540,2245", "2026-10-31", 9 * 3_600_000L))
        assertFalse("seconds later it is the same card", d.firstVisible("15.0|4||540,2245", "2026-10-31", 9 * 3_600_000L + 5_000L))
        assertTrue("hours later it is a new offer", d.firstVisible("15.0|4||540,2245", "2026-10-31", 14 * 3_600_000L))
    }

    @Test fun m06_two_equal_pin_groups_give_no_direction() {
        val markers = listOf(intArrayOf(100, 100), intArrayOf(110, 105), intArrayOf(900, 900), intArrayOf(905, 910))
        assertEquals(app.sabbaq.core.engine.zones.DirectionVerdict.INCONCLUSIVE,
            app.sabbaq.core.engine.zones.ZoneGeo.directionFromMarkers(24.60, 46.60, markers, AIRPORT_ZONE, 25.0))
        // With a third pin the larger group is the start and the direction is read again.
        val clear = markers + listOf(intArrayOf(102, 98))
        assertTrue(app.sabbaq.core.engine.zones.ZoneGeo.directionFromMarkers(24.60, 46.60, clear, AIRPORT_ZONE, 25.0) !=
            app.sabbaq.core.engine.zones.DirectionVerdict.INCONCLUSIVE)
    }

    private companion object {
        const val AIRPORT_ZONE = """[{"name":"المطار","active":true,"points":[[24.94,46.69],[24.94,46.73],[24.97,46.73],[24.97,46.69]]}]"""
    }
}

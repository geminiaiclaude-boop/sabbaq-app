package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FareSniper's payment, rating and wait-&-save filters (QANNAS_ENGINE_FACTS.md §14). */
class PaymentRatingWaitSaveTest {
    private fun ride(payment: Payment = Payment.UNKNOWN, rating: Double? = null, waitAndSave: Boolean = false) =
        ParsedRide(price = 20.0, minutes = 4, acceptNode = FakeNode(text = "قبول"), addressAmbiguous = true,
            payment = payment, rating = rating, waitAndSave = waitAndSave)

    @Test
    fun cash_only_rejects_card_and_passes_unknown_unless_asked() {
        val cash = EngineConfig(paymentFilterMode = PaymentMode.CASH)
        assertTrue(DecisionEngine.decide(ride(Payment.CASH), cash).accept)
        assertEquals("payment_not_cash", DecisionEngine.decide(ride(Payment.CARD), cash).reason)
        assertTrue(DecisionEngine.decide(ride(Payment.UNKNOWN), cash).accept)
        assertEquals("payment_unknown_reject", DecisionEngine.decide(ride(), cash.copy(paymentRejectOnUnknown = true)).reason)
    }

    @Test
    fun card_only_rejects_cash() {
        assertEquals("payment_not_card", DecisionEngine.decide(ride(Payment.CASH), EngineConfig(paymentFilterMode = PaymentMode.CARD)).reason)
    }

    @Test
    fun rating_and_wait_and_save_apply_to_bolt_only() {
        val cfg = EngineConfig(ratingFilterEnabled = true, minRating = 4.5, waitSaveFilterMode = WaitSaveMode.EXCLUDE)
        assertTrue("Jeeny never reads them", DecisionEngine.decide(ride(rating = 3.0, waitAndSave = true), cfg).accept)
        assertEquals("rating_below_min", DecisionEngine.decide(ride(rating = 3.0), cfg, isBolt = true).reason)
        assertEquals("wait_save_filtered", DecisionEngine.decide(ride(rating = 4.8, waitAndSave = true), cfg, isBolt = true).reason)
        assertTrue(DecisionEngine.decide(ride(rating = null), cfg, isBolt = true).accept)
        assertEquals("rating_unknown_reject", DecisionEngine.decide(ride(), cfg.copy(ratingRejectOnUnknown = true), isBolt = true).reason)
    }

    @Test
    fun the_reader_detects_payment_only_when_a_payment_filter_is_on() {
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "يبعد 3 دقائق", rect = row(1400)),
            FakeNode(text = "⃁ 20", rect = row(1500)),
            FakeNode(text = "نقدًا", rect = row(1600)),
            FakeNode(text = "قبول العرض", isClickable = true, rect = row(2200)),
        ))
        assertEquals(Payment.UNKNOWN, JeenyScreenReader(EngineConfig()) { 0L }.read(root, 2400)!!.payment)
        assertEquals(Payment.CASH, JeenyScreenReader(EngineConfig(paymentFilterMode = PaymentMode.CASH)) { 0L }.read(root, 2400)!!.payment)
        assertNull(JeenyScreenReader(EngineConfig()) { 0L }.read(root, 2400)!!.rating)
    }
}

package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import app.sabbaq.core.LiveAcceptProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live accept test's card must be read like a real Jeeny offer, or the test proves nothing. */
class LiveAcceptCardTest {
    @Test
    fun the_mock_card_is_read_decided_and_tapped_like_a_jeeny_offer() {
        var top = 900
        val lines = LiveAcceptProbe.MOCK_CARD_LINES.map { FakeNode(text = it, rect = row(top).also { top += 100 }) }
        val accept = FakeNode(className = "android.view.View", isClickable = true, rect = Bounds(60, 2200, 1020, 2320),
            children = listOf(FakeNode(text = LiveAcceptProbe.ACCEPT_LABEL, rect = Bounds(300, 2230, 780, 2290))))
        val root = FakeNode(rect = Bounds(0, 0, 1080, 2400), children = lines + accept)
        val ride = JeenyScreenReader(EngineConfig()) { 0L }.read(root, 2400)!!
        assertEquals(25.5, ride.price!!, 1e-9)
        assertEquals(4, ride.minutes)
        assertSame(accept, ride.acceptNode)
        assertTrue(DecisionEngine.decide(ride, EngineConfig()).accept)
        assertEquals(540, ride.clickX)
    }
}

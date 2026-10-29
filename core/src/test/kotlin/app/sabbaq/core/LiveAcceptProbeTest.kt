package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class LiveAcceptProbeTest {
    private val jeeny = Profiles.bundled().first { it.id == "jeeny" }
    private val probe = LiveAcceptProbe.profile(jeeny, "app.sabbaq.driver")
    private var nextId = 0

    private fun node(text: String? = null, clickable: Boolean = false, children: List<ScreenNode> = emptyList()) =
        ScreenNode(id = nextId++, text = text, clickable = clickable, children = children)

    /** The mock offer screen exactly as the test activity draws it. */
    private fun offerScreen(confirmed: Boolean = false) = node(children = listOf(
        node("طلب تجريبي من سبّاق"),
        node("25 ر.س"),
        node("الوصول 1.2 كم · 4 دقائق"),
        node("الرحلة 8.5 كم · 15 دقيقة"),
        node("شارع الملك فهد"),
        node("طريق الأمير محمد بن سلمان"),
        if (confirmed) node(LiveAcceptProbe.CONFIRM_LABEL)
        else node(clickable = true, children = listOf(node(LiveAcceptProbe.ACCEPT_LABEL))),
    ))

    @Test
    fun `probe profile keeps the live Jeeny reading rules but only its own labels`() {
        assertEquals(listOf("app.sabbaq.driver"), probe.packages)
        assertEquals(jeeny.currencyTokens, probe.currencyTokens)
        assertEquals(listOf(LiveAcceptProbe.ACCEPT_LABEL), probe.acceptLabels)
        assertTrue(probe.acceptViewIds.isEmpty())
        assertTrue(probe.weakConfirmLabels.isEmpty())
    }




    @Test
    fun `diagnosis names the first missing step`() {
        val all = LiveAcceptProbe.Step.entries.toSet()
        assertNull(LiveAcceptProbe.diagnosis(all, LiveAcceptProbe.Method.SEMANTIC))
        assertTrue(LiveAcceptProbe.diagnosis(emptySet(), null)!!.contains("لم تصل أحداث الشاشة"))
        val upToDispatch = setOf(LiveAcceptProbe.Step.EVENT, LiveAcceptProbe.Step.READ, LiveAcceptProbe.Step.PARSED,
            LiveAcceptProbe.Step.ACCEPT_FOUND, LiveAcceptProbe.Step.DISPATCHED)
        assertTrue(LiveAcceptProbe.diagnosis(upToDispatch, LiveAcceptProbe.Method.GESTURE)!!.contains("لمسة"))
    }

    @Test
    fun `summary fits the telemetry detail contract`() {
        val line = LiveAcceptProbe.summary(LiveAcceptProbe.Step.entries.toSet(), LiveAcceptProbe.Method.SEMANTIC, 840)
        assertEquals("probe:1111111,m=semantic,ms=840", line)
        assertTrue(Regex("^[a-z0-9_.:=,-]{1,96}$").matches(line))
    }
}

package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import app.sabbaq.core.SharedLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shadow words are tested on the real screen without tapping; only a word that completes the offer counts. */
class ShadowCheckTest {
    private val height = 2400

    /** A Jeeny card whose arrival time uses a word the reader does not know yet («دقايق»). */
    private fun card(time: String = "يبعد 4 دقايق", accept: String = "قبول العرض"): FakeNode {
        val label = FakeNode(text = accept, rect = Bounds(100, 2200, 980, 2290))
        val frame = FakeNode(className = "android.widget.FrameLayout", isClickable = true, rect = Bounds(80, 2180, 1000, 2310), children = listOf(label))
        return FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "طلب جديد", rect = row(1200)),
            FakeNode(text = time, rect = row(1400)),
            FakeNode(text = "⃁ 25.50", rect = row(1500)),
            frame,
        ))
    }

    private fun read(root: FakeNode) = { cfg: EngineConfig -> JeenyScreenReader(cfg) { 0L }.read(root, height) }

    @Test
    fun the_unknown_time_word_misses_today() {
        val reader = JeenyScreenReader(EngineConfig()) { 0L }
        assertNull(reader.read(card(), height))
        assertEquals("no_minutes", reader.lastMiss)
    }

    @Test
    fun the_word_that_completes_the_offer_is_reported_by_its_slot() {
        val shadow = SharedLexicon(version = 7, time = listOf("بعد قليل", "دقايق"))
        val hit = ShadowCheck.find(EngineConfig(), shadow, "no_minutes", read(card()))
        assertEquals(ShadowCheck.Hit(7, 't', 1), hit)
        assertEquals("lv=7,c=t,i=1", hit!!.detail)
        assertEquals("shadow_hit:7:t1", hit.reason)
    }

    @Test
    fun a_word_that_does_not_complete_the_offer_is_not_a_hit() {
        assertNull(ShadowCheck.find(EngineConfig(), SharedLexicon(version = 7, time = listOf("بعد قليل")), "no_minutes", read(card())))
        assertNull("only the list matching the miss is tried",
            ShadowCheck.find(EngineConfig(), SharedLexicon(version = 7, currency = listOf("دقايق")), "no_minutes", read(card())))
        assertNull("no published shadow list", ShadowCheck.find(EngineConfig(), SharedLexicon(time = listOf("دقايق")), "no_minutes", read(card())))
    }

    @Test
    fun a_new_accept_label_is_found_without_tapping() {
        val root = card(time = "يبعد 4 دقائق", accept = "خذ الرحلة")
        val shadow = SharedLexicon(version = 3, accept = listOf("خذ الرحلة"))
        assertEquals(ShadowCheck.Hit(3, 'a', 0), ShadowCheck.find(EngineConfig(), shadow, "no_accept", read(root)))
        fun taps(n: FakeNode): Int = n.clicks + n.kids.sumOf { taps(it) }
        assertEquals("shadow reading never taps", 0, taps(root))
    }
}

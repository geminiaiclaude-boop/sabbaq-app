package app.sabbaq.core.engine

import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unread offer screens leave the phone as structure only: no address, name or phone number. */
class ScreenSamplerTest {
    private val known = ScreenSampler.vocabulary()

    private fun screen(address: String = "حي النرجس، شارع الأمير سلطان", button: String = "انطلق الآن") = FakeNode(
        className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = listOf(
            FakeNode(text = "يبعد 4 دقائق", viewId = "me.com.easytaxista:id/tvTime", rect = row(1200)),
            FakeNode(text = "⃁ 25.50", viewId = "me.com.easytaxista:id/tvPrice", rect = row(1300)),
            FakeNode(text = address, rect = row(1400)),
            FakeNode(text = "اتصل 0551234567", rect = row(1500)),
            FakeNode(text = button, className = "android.widget.Button", isClickable = true,
                viewId = "me.com.easytaxista:id/btnGo", rect = row(2200)),
        ))

    @Test
    fun addresses_names_and_phones_never_leave_the_phone() {
        val sample = ScreenSampler.capture(screen(), known)
        val texts = sample.nodes.mapNotNull { it.text }
        assertTrue(texts.none { "النرجس" in it || "سلطان" in it || "الأمير" in it })
        assertTrue("address shape kept", texts.any { it.startsWith("اا ااااا") })
        assertTrue(texts.none { "0551234567" in it })
        assertTrue("phone replaced", texts.any { it.endsWith("##########") })
        assertTrue(texts.all { it.length <= ScreenSampler.MAX_TEXT })
        assertTrue(texts.none { t -> t.count { it.isDigit() } >= 7 })
    }

    /** Security review 2026-10-29: separators must not let a phone number through. */
    @Test
    fun spaced_or_dashed_phone_numbers_are_masked() {
        for (phone in listOf("055 123 4567", "055-123-4567", "+966 55 123 4567", "0 5 5 1 2 3 4 5 6 7")) {
            val out = ScreenSampler.redact("اتصل $phone", clickable = false, known = known)!!
            assertTrue("$phone -> $out", out.count { it.isDigit() } == 0)
        }
        assertEquals("⃁ 25.50", ScreenSampler.redact("⃁ 25.50", clickable = false, known = known))
    }

    /** A clickable row that is not a button (a rider card) never sends its text verbatim. */
    @Test
    fun only_button_type_views_keep_their_label() {
        val row = FakeNode(className = "android.widget.LinearLayout", isClickable = true, text = "محمد العتيبي", rect = row(500))
        val button = FakeNode(className = "com.google.android.material.button.MaterialButton", isClickable = true, text = "انطلق الآن", rect = row(600))
        val root = FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400), children = listOf(row, button))
        val texts = ScreenSampler.capture(root, known).nodes.mapNotNull { it.text }
        assertTrue(texts.none { "محمد" in it || "العتيبي" in it })
        assertTrue("انطلق الآن" in texts)
    }

    @Test
    fun numbers_known_words_and_button_labels_stay_readable() {
        val texts = ScreenSampler.capture(screen(), known).nodes.mapNotNull { it.text }
        assertTrue("known time word", texts.any { it.endsWith("4 دقائق") })
        assertTrue("price and currency", "⃁ 25.50" in texts)
        assertTrue("new button label is what the reader must learn", "انطلق الآن" in texts)
    }

    @Test
    fun structure_is_kept() {
        val nodes = ScreenSampler.capture(screen(), known).nodes
        assertEquals(6, nodes.size)
        val button = nodes.last()
        assertEquals("Button", button.className)
        assertEquals("btngo", button.viewId)
        assertTrue(button.clickable)
        assertEquals(1, button.depth)
        assertEquals(listOf(40, 2200, 1040, 2290), button.bounds.toList())
    }

    @Test
    fun the_signature_follows_the_layout_not_the_text() {
        val a = ScreenSampler.capture(screen(address = "حي الملقا"), known).signature
        val b = ScreenSampler.capture(screen(address = "حي العليا", button = "ابدأ"), known).signature
        assertEquals(a, b)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(a))
        val other = FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400),
            children = listOf(FakeNode(text = "x", rect = row(100))))
        assertNotEquals(a, ScreenSampler.capture(other, known).signature)
    }

    @Test
    fun emails_links_and_long_labels_are_dropped() {
        assertNull(ScreenSampler.redact("me@x.com", clickable = true, known = known))
        assertNull(ScreenSampler.redact("https://jeeny.me", clickable = false, known = known))
        assertEquals("a long button text is shaped", "aaaaa aaaa aaaa aaaaa", ScreenSampler.redact("Hello from your rider", clickable = true, known = known))
        assertFalse(ScreenSampler.redact("١٢٣٤٥٦٧٨", clickable = false, known = known)!!.any { it.isDigit() })
    }

    @Test
    fun at_most_150_nodes() {
        val wide = FakeNode(className = "android.widget.FrameLayout", rect = Bounds(0, 0, 1080, 2400),
            children = List(400) { FakeNode(text = "x$it", rect = row(it)) })
        assertEquals(ScreenSampler.MAX_NODES, ScreenSampler.capture(wide, known).nodes.size)
    }
}

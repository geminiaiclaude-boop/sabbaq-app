package app.sabbaq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Typing «حي العليا» into the destination words: after the first word and its space are saved,
 * the field keeps the space (the saved value is trimmed, the typed text is not written back), and
 * the draft does not count as unsaved, so autosave and «ابدأ» are not held up (owner, 2026-10-31).
 */
class DestinationWordsTypingTest {
    @Test fun a_trailing_space_is_saved_trimmed_and_the_draft_is_not_dirty() {
        val typing = FilterDraft.from(FilterDraft().validate().config!!).copy(destCheckEnabled = true, destKeywords = "حي ")
        val saved = typing.validate().config!!
        assertEquals("حي", saved.destKeywords)
        assertFalse(typing.isDirtyComparedTo(saved))
        assertEquals("حي ", typing.destKeywords)
        val next = typing.copy(destKeywords = "حي العليا")
        assertEquals("حي العليا", next.validate(saved).config!!.destKeywords)
    }
}

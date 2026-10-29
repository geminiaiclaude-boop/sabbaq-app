package app.sabbaq.ui

import app.sabbaq.core.engine.DestinationMode
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.LogicMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conditions page edits FareSniper's settings; empty means "no limit", never "reject". */
class ConditionsDraftTest {
    @Test
    fun a_fresh_install_saves_faresniper_defaults() {
        assertEquals(EngineConfig(), FilterDraft.from(EngineConfig()).validate().config)
    }

    @Test
    fun limits_and_modes_round_trip() {
        val config = EngineConfig(
            minPrice = 12.5, maxPrice = 80.0, minTime = 2, maxTime = 9, logicMode = LogicMode.OR,
            destHomeMode = DestinationMode.EXCLUDE, destExcludeEnabled = true, destExcludeKeywords = "المطار",
            bypassTimeEnabled = true, bypassTimePrice = 40.0, smartDelayEnabled = true, smartDelayMinMs = 200, smartDelayMaxMs = 900,
        )
        assertEquals(config, FilterDraft.from(config).validate(config).config)
    }

    @Test
    fun arabic_digits_and_inverted_ranges() {
        assertEquals(25.5, FilterDraft(minPrice = "٢٥٫٥").validate().config!!.minPrice, 0.0)
        val inverted = FilterDraft(minPrice = "30", maxPrice = "10").validate()
        assertFalse(inverted.isValid)
        assertTrue("minPrice" in inverted.errors && "maxPrice" in inverted.errors)
    }

    @Test
    fun the_draft_survives_process_death_with_invalid_text() {
        val edited = FilterDraft(minPrice = "abc", destKeywords = "العليا")
        val restored = Json.decodeFromString<FilterDraft>(Json.encodeToString(edited))
        assertEquals(edited, restored)
        assertFalse(restored.validate().isValid)
    }

    @Test
    fun extra_keyword_lists_the_page_does_not_show_are_kept() {
        val saved = EngineConfig(acceptKeywordsExtra = "اقبل")
        assertEquals("اقبل", FilterDraft.from(saved).copy(minPrice = "5").validate(saved).config!!.acceptKeywordsExtra)
        assertFalse(FilterDraft.from(saved).isDirtyComparedTo(saved))
    }
}

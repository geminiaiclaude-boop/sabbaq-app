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

/** The conditions page edits the reference engine's settings; empty means "no limit", never "reject". */
class ConditionsDraftTest {
    @Test
    fun a_fresh_install_saves_reference_defaults() {
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

    @Test
    fun zones_and_smart_destination_round_trip() {
        val zone = """[{"name":"العليا","active":true,"points":[[24.68,46.67],[24.68,46.69],[24.7,46.69]]}]"""
        val config = EngineConfig(
            destZonesEnabled = true, destZonesJson = zone, destZonesRejectOnUnknown = false, destZonesOnTheWayEnabled = true,
            destExcludeZonesEnabled = true, destExcludeZonesJson = zone, destExcludeZonesRejectOnUnknown = true,
            smartDestActive = true, smartDestLat = 24.96, smartDestLng = 46.7, smartDestAngle = 30.0,
        )
        assertEquals(config, FilterDraft.from(config).validate(config).config)
    }

    @Test
    fun zones_need_a_drawn_zone_and_home_needs_a_point() {
        val v = FilterDraft(zonesEnabled = true, excludeZonesEnabled = true, smartDestActive = true).validate()
        assertFalse(v.isValid)
        assertTrue("zones" in v.errors && "excludeZones" in v.errors && "smartDest" in v.errors)
        assertTrue("smartDestAngle" in FilterDraft(smartDestActive = true, smartDestLat = 1.0, smartDestAngle = "200").validate().errors)
    }

    @Test
    fun saving_the_page_keeps_fields_it_does_not_edit() {
        val base = EngineConfig(globalDestExcludeZonesJson = "[1]", globalZoneProximityKm = 40.0, acceptKeywordsExtra = "x")
        val saved = FilterDraft(minPrice = "10").validate(base).config!!
        assertEquals("[1]", saved.globalDestExcludeZonesJson)
        assertEquals(40.0, saved.globalZoneProximityKm, 0.0)
        assertEquals("x", saved.acceptKeywordsExtra)
    }

    @Test
    fun fine_zone_settings_round_trip_and_stay_in_range() {
        val config = EngineConfig(destZonesBearingEnabled = false, destZonesBearingMarginDeg = 40.0, travelAngleTolDeg = 30.0,
            travelOvershootKm = 3.5, corridorHalfWidthKm = 0.8, destExcludeProximityMaxKm = 120.0)
        assertEquals(config, FilterDraft.from(config).validate(config).config)
        val defaults = FilterDraft(zonesBearingMargin = "", travelOvershootKm = "").validate().config!!
        assertEquals(25.0, defaults.destZonesBearingMarginDeg, 0.0)
        assertEquals(2.0, defaults.travelOvershootKm, 0.0)
        val bad = FilterDraft(corridorHalfWidthKm = "50", excludeProximityKm = "1").validate()
        assertFalse(bad.isValid)
        assertTrue("corridorHalfWidthKm" in bad.errors && "excludeProximityKm" in bad.errors)
    }
}

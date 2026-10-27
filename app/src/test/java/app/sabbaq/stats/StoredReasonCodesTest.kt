package app.sabbaq.stats

import app.sabbaq.core.OfferStatsDay
import app.sabbaq.core.ReasonCode
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredReasonCodesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `retired codes in daily stats keep the rest of the day`() {
        val raw = """[{"date":"2026-10-20","visibleOffers":4,"rejectedOffers":2,""" +
            """"rejectionsByReason":{"FARE_TOO_LOW":1,"DESTINATION_ZONE_EXCLUDED":1}}]"""
        val days = json.decodeFromString<List<OfferStatsDay>>(StoredReasonCodes.dropRetired(json, raw, "rejectionsByReason"))
        assertEquals(4L, days.single().visibleOffers)
        assertEquals(mapOf(ReasonCode.FARE_TOO_LOW to 1L), days.single().rejectionsByReason)
    }

    @Test
    fun `retired codes in the decision log keep the entry`() {
        val raw = """[{"timestampEpochMs":1,"profileId":"jeeny","outcome":"REJECTED",""" +
            """"reasons":["SMART_DESTINATION_MISMATCH","FARE_TOO_LOW"]}]"""
        val entries = json.decodeFromString<List<OfferDecisionLogEntry>>(StoredReasonCodes.dropRetired(json, raw, "reasons"))
        assertEquals(listOf(ReasonCode.FARE_TOO_LOW), entries.single().reasons)
    }
}

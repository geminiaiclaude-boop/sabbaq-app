package app.sabbaq.core

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server refuses a whole batch when one row breaks its contract, and the device drops a
 * batch refused three times. A stage or field added on one side only would therefore lose
 * telemetry silently in production, so both sides are compared here.
 */
class TelemetryServerContractTest {
    private val migrations = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "supabase/migrations") }
        .first { it.isDirectory }
    private val latestTelemetryMigration = migrations.listFiles()!!
        .filter { it.name.endsWith(".sql") && it.readText().contains("offer_telemetry_events_stage_check") }
        .maxBy { it.name }
        .readText()

    @Test fun everyDeviceStageIsAcceptedByTheServer() {
        val block = latestTelemetryMigration.substringAfter("offer_telemetry_events_stage_check check (stage in (")
            .substringBefore("))")
        val serverStages = Regex("'([A-Z_]+)'").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(TelemetryStage.entries.map { it.name }.toSet(), serverStages)
    }

    @Test fun everySerializedFieldIsReadByTheIngestFunction() {
        val event = OfferTelemetryEvent(
            eventId = "e", occurredAtEpochMs = 1, correlationId = "c", appId = "a", appVersion = "v",
            androidApi = 30, provider = "jeeny", stage = TelemetryStage.SIGNAL_MISS, reasonCode = "r",
            decisionScore = 1, latencyMs = 1, latencyBucket = "b", configVersion = "c", profileVersion = "p",
            installCohort = "i", eventCount = 2, detail = "d", targetAppVersion = "t",
        )
        val keys = Json.parseToJsonElement(Json.encodeToString(event)).let { it as kotlinx.serialization.json.JsonObject }.keys - "schemaVersion"
        keys.forEach { key -> assertTrue("ingest ignores $key", latestTelemetryMigration.contains("x->>'$key'")) }
    }
}

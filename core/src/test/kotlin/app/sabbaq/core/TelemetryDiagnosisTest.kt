package app.sabbaq.core

import app.sabbaq.core.TelemetryDiagnosis.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryDiagnosisTest {
    private fun row(stage: TelemetryStage, reason: String? = null, total: Long = 1, at: Long = 1) =
        TelemetryAggregator.FunnelRow(TelemetryAggregator.Key(stage, reason, "jeeny"), total, at, null)

    @Test fun nothingObservedMeansNoEvents() = assertEquals(Verdict.NO_EVENTS, TelemetryDiagnosis.diagnose(emptyList()))

    @Test fun latestGateWinsOverOlderProgress() {
        val rows = listOf(row(TelemetryStage.PACKAGE_SEEN, at = 1), row(TelemetryStage.GATE_BLOCKED, "license_invalid", at = 5))
        assertEquals(Verdict.LICENSE_INVALID, TelemetryDiagnosis.diagnose(rows))
    }

    @Test fun overlayWindowThatCannotBeReadIsNamed() {
        val rows = listOf(row(TelemetryStage.PACKAGE_SEEN, total = 40), row(TelemetryStage.ROOT_UNAVAILABLE, "active_other", total = 30))
        assertEquals(Verdict.WINDOW_NOT_READABLE, TelemetryDiagnosis.diagnose(rows))
    }

    @Test fun readableScreenWithoutRecognisedOfferIsNamed() {
        val rows = listOf(row(TelemetryStage.PACKAGE_SEEN, total = 40), row(TelemetryStage.SIGNAL_MISS, "cls_none", total = 40))
        assertEquals(Verdict.OFFER_NOT_RECOGNISED, TelemetryDiagnosis.diagnose(rows))
    }

    @Test fun offersSeenButAllRejectedPointsAtRules() {
        val rows = listOf(row(TelemetryStage.PACKAGE_SEEN), row(TelemetryStage.OFFER_DETECTED, "complete"), row(TelemetryStage.REJECTED, "fare_too_low"))
        assertEquals(Verdict.OFFERS_REJECTED_BY_RULES, TelemetryDiagnosis.diagnose(rows))
    }

    @Test fun acceptedButNeverClickedIsNamed() {
        val rows = listOf(row(TelemetryStage.PACKAGE_SEEN), row(TelemetryStage.OFFER_DETECTED, "complete"),
            row(TelemetryStage.DECISION_ACCEPTED, "rules_passed"), row(TelemetryStage.CLICK_RESULT, "not_dispatched"))
        assertEquals(Verdict.ACCEPT_NOT_DISPATCHED, TelemetryDiagnosis.diagnose(rows))
    }

    @Test fun clickedAndConfirmedIsWorking() {
        val rows = listOf(row(TelemetryStage.PACKAGE_SEEN), row(TelemetryStage.OFFER_DETECTED, "complete"),
            row(TelemetryStage.DECISION_ACCEPTED, "rules_passed"), row(TelemetryStage.CLICK_RESULT, "semantic"),
            row(TelemetryStage.CONFIRMATION_CONFIRMED))
        assertEquals(Verdict.WORKING, TelemetryDiagnosis.diagnose(rows))
    }
}

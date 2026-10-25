package app.sabbaq.core

import org.junit.Assert.*
import org.junit.Test

class ProductionGuardsTest {
    private val profile = Profiles.bundled().first { it.id == "jeeny" }
    private fun screen(text: String, second: String? = null) = ScreenNode(
        1, children = listOf(ScreenNode(2, text = text, className = "TextView"),
            ScreenNode(3, text = second, clickable = true, className = "Button")),
    )

    @Test fun fingerprintIsDeterministicAndIncludesContextWithoutReturningText() {
        val root = screen("SAR 5.50", "قبول العرض")
        val a = ScreenChangeFingerprint.compute("me.com.easytaxista", "7", root)
        assertEquals(a, ScreenChangeFingerprint.compute("me.com.easytaxista", "7", root))
        assertNotEquals(a, ScreenChangeFingerprint.compute("me.com.easytaxista", "8", root))
        assertFalse(a.contains("5.50"))
    }

    @Test fun adaptersKeepJeenySpecialAndOtherProfilesDefault() {
        assertTrue(RideAppAdapters.forProfile(profile) is JeenyRideAppAdapter)
        val bolt = Profiles.bundled().first { it.id == "bolt" }
        assertTrue(RideAppAdapters.forProfile(bolt) is DefaultRideAppAdapter)
        assertTrue(RideAppAdapters.forProfile(profile).requiresPreflight)
    }

    @Test fun everyBundledPlatformSatisfiesAdapterContract() {
        listOf("jeeny", "bolt", "petra").forEach { id ->
            val adapter = RideAppAdapters.forProfile(Profiles.bundled().first { it.id == id })
            assertTrue("$id must declare a package", adapter.packageNames.isNotEmpty())
            assertTrue("$id must declare accept labels or ids", adapter.acceptLabelFamilies.isNotEmpty() || adapter.profile.acceptViewIds.isNotEmpty())
            assertTrue(adapter.confirmationStrategy == ConfirmationStrategy.PROFILE_LABELS_AND_ACTIVE_CUES)
            assertEquals(NodeResolutionStrategy.LABEL_THEN_ID_THEN_ANCESTOR, adapter.nodeResolutionStrategy)
        }
    }

    @Test fun preflightBudgetIsFixedAndBounded() {
        val adapter = RideAppAdapters.forProfile(profile)
        assertTrue(adapter.signalConfig.maxNodes <= 128)
        assertTrue(adapter.signalConfig.maxTextChars <= 4096)
        assertTrue(adapter.signalConfig.minimumIndependentFeatures >= 3)
    }

    @Test fun diagnosticsAreBoundedAndReasonsAreRedacted() {
        val diagnostics = BoundedDiagnostics(2)
        diagnostics.record(DiagnosticStage.SIGNAL_MISS, 1, "address 12 Main Street")
        diagnostics.record(DiagnosticStage.REJECTED, 2, "fare=5.50")
        diagnostics.record(DiagnosticStage.WATCHDOG, 3, "third")
        assertEquals(2, diagnostics.snapshot().size)
        assertEquals(DiagnosticStage.REJECTED, diagnostics.snapshot().first().stage)
        assertFalse(diagnostics.snapshot().any { it.reason?.contains("Main") == true })
        assertFalse(diagnostics.snapshot().any { it.reason?.contains("5.50") == true })
    }

    @Test fun livenessReportsPausedAndStaleWithoutBackgroundLoop() {
        val state = LivenessState(staleAfterMs = 10)
        state.accessibilityEvent(100)
        state.targetEvent(101)
        assertFalse(state.snapshot(105).stale)
        state.pause("license_expired")
        val snapshot = state.snapshot(105)
        assertTrue(snapshot.stale)
        assertEquals("license_expired", snapshot.pausedReason)
        assertEquals(101L, snapshot.lastTargetEventMs)
    }

    @Test fun decisionReportUsesOnlyAvailableSignalsAndDoesNotGateVerdict() {
        val offer = Offer("jeeny", null, null, null, null, null, null, emptyList(), emptyList())
        val verdict = OfferEvaluator.evaluate(offer, Filters(acceptWhenNoFilters = true, acceptNoDestination = true), 600)
        assertTrue(verdict.accept)
        assertEquals(0, verdict.report.score)
        assertTrue(verdict.report.availableSignals.isEmpty())
        val rich = offer.copy(fare = java.math.BigDecimal("5"), tripKm = java.math.BigDecimal("10"), tripMinutes = 20, addresses = listOf("A", "B"))
        val richVerdict = OfferEvaluator.evaluate(rich, Filters(acceptWhenNoFilters = true, acceptNoDestination = true), 600)
        assertTrue(richVerdict.accept)
        assertTrue(richVerdict.report.score > 0)
        assertTrue("fare" in richVerdict.report.availableSignals)
    }
}

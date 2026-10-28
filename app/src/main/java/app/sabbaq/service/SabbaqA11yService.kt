package app.sabbaq.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import app.sabbaq.Settings
import app.sabbaq.core.BoundedDiagnostics
import app.sabbaq.core.DecisionReport
import app.sabbaq.core.DiagnosticEntry
import app.sabbaq.core.DiagnosticStage
import app.sabbaq.core.LiveAcceptProbe
import app.sabbaq.core.LivenessSnapshot
import app.sabbaq.core.LivenessState
import app.sabbaq.core.TelemetryStage
import app.sabbaq.core.engine.Decision
import app.sabbaq.core.engine.DecisionEngine
import app.sabbaq.core.engine.JeenyScreenReader
import app.sabbaq.core.engine.ParsedRide
import app.sabbaq.core.engine.PendingOutcome
import app.sabbaq.core.engine.PendingRide
import app.sabbaq.network.SupabaseClient
import app.sabbaq.notifications.NotificationCenter
import app.sabbaq.service.engine.EngineHost
import app.sabbaq.service.engine.LiveNode
import app.sabbaq.stats.DecisionLogOutcome
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.stats.OfferStatsStore
import app.sabbaq.telemetry.TelemetryRepository
import app.sabbaq.voice.OfferEvents
import java.math.BigDecimal

/**
 * Gates every event (consent, monitoring switch, licence) and hands Jeeny's events to
 * [EngineHost], which reads, decides and taps exactly like FareSniper
 * (docs/engine-parity/SABBAQ_ENGINE_DESIGN.md). This class only reports what the engine did.
 */
class SabbaqA11yService : AccessibilityService(), EngineHost.Listener {

    private lateinit var host: EngineHost
    private val diagnostics = BoundedDiagnostics()
    private val liveness = LivenessState()
    private val runtimeStatus = RuntimeStatusRepository

    override fun onServiceConnected() {
        super.onServiceConnected()
        liveness.pause(null)
        runtimeStatus.lifecycle(Settings.isActive, null)
        runCatching { OfferStatsStore.init(applicationContext) }
        runCatching { OfferDecisionLogStore.init(applicationContext) }
        TelemetryRepository.serviceConnected = true
        host = EngineHost(this, { Settings.engineConfig }, ::canProcess, this)
        host.onConnected()
        TelemetryRepository.trace(TelemetryStage.SERVICE_CONNECTED, reasonCode = "ready", detail = "engine=faresniper")
    }

    private fun canProcess(): Boolean =
        Settings.a11yDisclosureAccepted && Settings.isActive && SupabaseClient.hasValidLicense()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        liveness.accessibilityEvent(now)
        runtimeStatus.event(now)
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) {
            if (LiveAcceptTest.isArmed(now)) handleLiveAcceptTest(now)
            return
        }
        if (!isJeeny(packageName)) return
        record(DiagnosticStage.EVENT_RECEIVED, now)
        when {
            !Settings.a11yDisclosureAccepted -> {
                Settings.isActive = false
                liveness.pause("disclosure_required")
                runtimeStatus.lifecycle(false, "disclosure_required")
                record(DiagnosticStage.GATE_BLOCKED, now, "disclosure_required")
                return
            }
            !Settings.isActive -> {
                liveness.pause("service_paused")
                runtimeStatus.lifecycle(false, "service_paused")
                record(DiagnosticStage.GATE_BLOCKED, now, "service_paused")
                return
            }
            !SupabaseClient.hasValidLicense() -> {
                liveness.pause("license_expired")
                runtimeStatus.lifecycle(false, "license_expired")
                record(DiagnosticStage.GATE_BLOCKED, now, "license_invalid")
                return
            }
        }
        liveness.targetEvent(now)
        if (::host.isInitialized) runCatching { host.onEvent(event) }.onFailure {
            record(DiagnosticStage.TREE_REJECTED, now, it.javaClass.simpleName.lowercase().take(40))
        }
    }

    /** FareSniper's Jeeny routing (§5): the package itself or a name containing its keywords. */
    private fun isJeeny(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p == JEENY_PACKAGE || "jeeny" in p || "easytaxi" in p
    }

    override fun onOffer(ride: ParsedRide, source: String) {
        val now = SystemClock.elapsedRealtime()
        liveness.offerDetected(now)
        runtimeStatus.offer(now)
        record(DiagnosticStage.OFFER_DETECTED, now, if (ride.price == null) "fare_missing" else "complete", detail = "src=$source,n=${ride.nodesVisited}")
        runCatching { OfferStatsStore.recordVisible(fingerprint(ride)) }
    }

    override fun onRejected(ride: ParsedRide, decision: Decision, firstTime: Boolean) {
        if (!firstTime) return
        val now = SystemClock.elapsedRealtime()
        liveness.decision(now)
        runtimeStatus.decision(now, report(ride, decision))
        record(DiagnosticStage.REJECTED, now, decision.reason?.take(40))
        val reason = decision.reason.orEmpty()
        runCatching { OfferDecisionLogStore.recordDecision(fingerprint(ride), JEENY_PROVIDER, reason) }
        runCatching { OfferStatsStore.recordRejected(fingerprint(ride), reason) }
    }

    override fun onTap(ride: ParsedRide, decision: Decision, pending: PendingRide, method: String, latencyMs: Long) {
        val now = SystemClock.elapsedRealtime()
        liveness.decision(now)
        liveness.click(now)
        runtimeStatus.decision(now, report(ride, decision))
        runtimeStatus.click(now)
        TelemetryRepository.trace(TelemetryStage.DECISION_ACCEPTED, "rules_passed", provider = JEENY_PROVIDER)
        record(DiagnosticStage.CLICK_PATH, now, method, latencyMs = latencyMs.coerceAtLeast(0))
        runCatching { OfferDecisionLogStore.recordDecision(fingerprint(ride), JEENY_PROVIDER, "") }
        runCatching {
            OfferDecisionLogStore.recordOutcome(JEENY_PROVIDER,
                if (method == "gesture") DecisionLogOutcome.GESTURE_CLICK_SENT else DecisionLogOutcome.SEMANTIC_CLICK_SENT)
        }
    }

    override fun onPendingOutcome(outcome: PendingOutcome) {
        val now = SystemClock.elapsedRealtime()
        val p = outcome.pending
        when (outcome) {
            is PendingOutcome.Confirmed -> {
                record(DiagnosticStage.CONFIRMATION_CONFIRMED, now, "post_click_confirmed")
                runCatching { OfferDecisionLogStore.recordOutcome(JEENY_PROVIDER, DecisionLogOutcome.ACCEPTED_CONFIRMED) }
                runCatching { OfferStatsStore.recordAcceptedConfirmed() }
                val fare = p.price?.let { BigDecimal.valueOf(it) }
                runCatching { NotificationCenter.postAcceptedRide(fare, null) }
                OfferEvents.accepted(fare, null)
            }
            is PendingOutcome.NewOfferAppeared -> record(DiagnosticStage.CONFIRMATION_TIMEOUT, now, "new_offer_appeared")
            is PendingOutcome.ConfirmationTimeout -> {
                record(DiagnosticStage.CONFIRMATION_TIMEOUT, now, "confirmation_timeout")
                runCatching { OfferDecisionLogStore.recordOutcome(JEENY_PROVIDER, DecisionLogOutcome.CONFIRMATION_TIMEOUT) }
            }
            is PendingOutcome.Disappeared -> {
                record(DiagnosticStage.CONFIRMATION_TIMEOUT, now, "offer_disappeared_after_click")
                runCatching { OfferDecisionLogStore.recordOutcome(JEENY_PROVIDER, DecisionLogOutcome.CONFIRMATION_TIMEOUT) }
            }
        }
    }

    override fun onIdle(reason: String) {
        if (reason == "no_offer") return
        record(DiagnosticStage.STALE, SystemClock.elapsedRealtime(), reason)
    }

    /**
     * The live acceptance test: Sabbaq's own mock card goes through the same reader, the driver's
     * own settings and the same tap ladder as a Jeeny offer.
     */
    private fun handleLiveAcceptTest(now: Long) {
        LiveAcceptTest.reach(LiveAcceptProbe.Step.EVENT, now)
        val root = rootInActiveWindow ?: return
        val live = LiveNode(root)
        LiveAcceptTest.reach(LiveAcceptProbe.Step.READ)
        if (hasText(live, LiveAcceptProbe.CONFIRM_LABEL)) {
            LiveAcceptTest.reach(LiveAcceptProbe.Step.CONFIRMED)
            return
        }
        val config = Settings.engineConfig
        val ride = JeenyScreenReader(config) { SystemClock.uptimeMillis() }.read(live, resources.displayMetrics.heightPixels) ?: return
        LiveAcceptTest.reach(LiveAcceptProbe.Step.PARSED)
        val accept = ride.acceptNode ?: return
        LiveAcceptTest.reach(LiveAcceptProbe.Step.ACCEPT_FOUND)
        if (LiveAcceptTest.hasDispatched() || !DecisionEngine.decide(ride, config).accept || !::host.isInitialized) return
        when (host.clickLadder(accept)) {
            "semantic", "parent" -> LiveAcceptTest.reach(LiveAcceptProbe.Step.DISPATCHED, method = LiveAcceptProbe.Method.SEMANTIC)
            "gesture" -> LiveAcceptTest.reach(LiveAcceptProbe.Step.DISPATCHED, method = LiveAcceptProbe.Method.GESTURE)
            else -> LiveAcceptTest.refuseDispatch()
        }
    }

    private fun hasText(node: LiveNode, label: String): Boolean {
        val queue = ArrayDeque<app.sabbaq.core.engine.UiNode>()
        queue.addLast(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val n = queue.removeFirst()
            visited++
            if (n.text?.contains(label) == true || n.description?.contains(label) == true) return true
            for (i in 0 until n.childCount) n.child(i)?.let(queue::addLast)
        }
        return false
    }

    private fun fingerprint(ride: ParsedRide): String =
        "${ride.price}|${ride.minutes}|${ride.destination.orEmpty()}|${ride.clickX},${ride.clickY}"

    private fun report(ride: ParsedRide, decision: Decision): DecisionReport = DecisionReport(
        score = (if (ride.price != null) 50 else 0) + (if (ride.minutes != null) 30 else 0) + (if (ride.destination != null) 20 else 0),
        availableSignals = listOfNotNull("fare".takeIf { ride.price != null }, "time".takeIf { ride.minutes != null },
            "destination".takeIf { ride.destination != null }),
        reasons = emptyList(),
    ).also { if (!decision.accept) Log.i(TAG, "rejected: ${decision.reason}") }

    override fun onInterrupt() {
        liveness.pause("interrupted")
        runtimeStatus.lifecycle(false, "interrupted")
        TelemetryRepository.trace(TelemetryStage.SERVICE_INTERRUPTED, "interrupted")
    }

    /** Privacy-safe metadata for a UI or local log; no screen content is included. */
    fun livenessSnapshot(nowMs: Long = SystemClock.elapsedRealtime()): LivenessSnapshot = liveness.snapshot(nowMs).also {
        if (it.stale) runtimeStatus.lifecycle(false, "stale")
    }

    fun diagnosticSnapshot(): List<DiagnosticEntry> = diagnostics.snapshot()

    private fun record(stage: DiagnosticStage, nowMs: Long, reason: String? = null, detail: String? = null, latencyMs: Long? = null) {
        if (stage != DiagnosticStage.EVENT_RECEIVED) {
            diagnostics.record(stage, nowMs, reason)
            runtimeStatus.diagnostic(DiagnosticEntry(stage, nowMs, reason))
        }
        val telemetryStage = when (stage) {
            DiagnosticStage.EVENT_RECEIVED -> TelemetryStage.EVENT_RECEIVED
            DiagnosticStage.PACKAGE_SEEN -> TelemetryStage.PACKAGE_SEEN
            DiagnosticStage.SIGNAL_FOUND -> TelemetryStage.SIGNAL_FOUND
            DiagnosticStage.SIGNAL_MISS -> TelemetryStage.SIGNAL_MISS
            DiagnosticStage.FRAME_INCOMPLETE -> TelemetryStage.FRAME_INCOMPLETE
            DiagnosticStage.FARE_MISSING, DiagnosticStage.OFFER_DETECTED -> TelemetryStage.OFFER_DETECTED
            DiagnosticStage.OFFER_NOT_PARSED -> TelemetryStage.OFFER_NOT_PARSED
            DiagnosticStage.REJECTED -> TelemetryStage.REJECTED
            DiagnosticStage.ACCEPT_NODE_FOUND -> TelemetryStage.ACCEPT_NODE
            DiagnosticStage.CLICK_PATH -> TelemetryStage.CLICK_RESULT
            DiagnosticStage.CONFIRMATION_CONFIRMED -> TelemetryStage.CONFIRMATION_CONFIRMED
            DiagnosticStage.CONFIRMATION_TIMEOUT -> TelemetryStage.CONFIRMATION_TIMEOUT
            DiagnosticStage.STALE -> TelemetryStage.STALE
            DiagnosticStage.WATCHDOG -> TelemetryStage.PAUSED
            DiagnosticStage.GATE_BLOCKED -> TelemetryStage.GATE_BLOCKED
            DiagnosticStage.ROOT_UNAVAILABLE -> TelemetryStage.ROOT_UNAVAILABLE
            DiagnosticStage.TREE_REJECTED -> TelemetryStage.TREE_REJECTED
        }
        TelemetryRepository.trace(telemetryStage, reasonCode = reason, detail = detail, provider = JEENY_PROVIDER, latencyMs = latencyMs)
    }

    override fun onDestroy() {
        LiveAcceptTest.onArmChanged = null
        TelemetryRepository.serviceConnected = false
        TelemetryRepository.trace(TelemetryStage.SERVICE_DESTROYED, "destroyed")
        TelemetryRepository.flushNow()
        if (::host.isInitialized) host.onDestroy()
        liveness.pause("service_destroyed")
        runtimeStatus.reset()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SabbaqA11y"
        const val JEENY_PROVIDER = "jeeny"
        const val JEENY_PACKAGE = "me.com.easytaxista"
    }
}

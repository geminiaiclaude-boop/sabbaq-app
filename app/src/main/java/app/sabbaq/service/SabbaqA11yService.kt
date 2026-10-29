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
import app.sabbaq.core.engine.RideApps
import app.sabbaq.core.engine.SnipingGate
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
 * [EngineHost], which reads, decides and taps exactly like the reference engine
 * (engine design notes). This class only reports what the engine did.
 */
class SabbaqA11yService : AccessibilityService(), EngineHost.Listener {

    private lateinit var host: EngineHost
    /** Ride app of the offer being reported ("jeeny", "bolt", "petra"). */
    private var provider = "jeeny"
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
        DiagnosisRepository.funnel.serviceConnected(true)
        host = EngineHost(this, { Settings.engineConfig }, ::canProcess, this)
        host.onConnected()
        instance = this
        applyTargetPackages()
        TelemetryRepository.trace(TelemetryStage.SERVICE_CONNECTED, reasonCode = "ready", detail = "engine=v3")
    }

    /**
     * The reference engine's gate: the driver's own switch, consent and the saved subscription end date.
     * A network failure, a lost session or a process restart never stops a tap.
     */
    private fun gate(): SnipingGate.Result {
        val result = SnipingGate.check(
            monitoringWanted = Settings.monitoringWanted,
            disclosureAccepted = Settings.a11yDisclosureAccepted,
            subscriptionEndsAtMs = Settings.subscriptionEndsAtMs,
            newestSeenMs = maxOf(Settings.newestSeenMs, persistedNewestSeenMs),
            nowMs = System.currentTimeMillis(),
        )
        if (result.newestSeenMs - persistedNewestSeenMs >= NEWEST_SEEN_WRITE_STEP_MS || result.newestSeenMs < persistedNewestSeenMs) {
            Settings.newestSeenMs = result.newestSeenMs
            persistedNewestSeenMs = result.newestSeenMs
        }
        return result
    }

    /** Last value written; the gate advances it on every event, the disk only once a minute. */
    private var persistedNewestSeenMs = Settings.newestSeenMs

    private fun canProcess(): Boolean = gate().allowed

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        liveness.accessibilityEvent(now)
        runtimeStatus.event(now)
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) {
            if (LiveAcceptTest.isArmed(now)) handleLiveAcceptTest(now)
            return
        }
        if (RideApps.of(packageName, Settings.engineConfig.targetApp) == null) return
        record(DiagnosticStage.EVENT_RECEIVED, now)
        DiagnosisRepository.funnel.event()
        val gate = gate()
        if (!gate.allowed) {
            DiagnosisRepository.funnel.gateBlocked(gate.reason)
            DiagnosisRepository.refresh()
            liveness.pause(gate.reason)
            runtimeStatus.lifecycle(false, gate.reason)
            record(DiagnosticStage.GATE_BLOCKED, now, gate.reason)
            return
        }
        runtimeStatus.lifecycle(true, null)
        liveness.targetEvent(now)
        if (::host.isInitialized) runCatching { host.onEvent(event) }.onFailure {
            record(DiagnosticStage.TREE_REJECTED, now, it.javaClass.simpleName.lowercase().take(40))
        }
    }

    /** Called on the main thread after the driver saves settings. */
    fun onConfigSaved() {
        applyTargetPackages()
        if (::host.isInitialized) host.onConfigSaved()
    }

    /** The reference engine's `applyTargetAppRouting`: listen only to the ride apps the driver chose (and Sabbaq's own test card). */
    private fun applyTargetPackages() {
        val info = serviceInfo ?: return
        val wanted = RideApps.packages(Settings.engineConfig.targetApp, applicationContext.packageName)
        if (info.packageNames?.contentEquals(wanted) == true) return
        info.packageNames = wanted
        runCatching { serviceInfo = info }
    }

    override fun onOffer(ride: ParsedRide, source: String) {
        val now = SystemClock.elapsedRealtime()
        provider = ride.provider
        liveness.offerDetected(now)
        runtimeStatus.offer(now)
        record(DiagnosticStage.OFFER_DETECTED, now, if (ride.price == null) "fare_missing" else "complete", detail = "src=$source,n=${ride.nodesVisited}")
        DiagnosisRepository.funnel.offer()
        runCatching { OfferStatsStore.recordVisible(fingerprint(ride)) }
    }

    override fun onRejected(ride: ParsedRide, decision: Decision, firstTime: Boolean) {
        if (!firstTime) return
        DiagnosisRepository.funnel.rejected(decision.reason ?: "rejected")
        DiagnosisRepository.refresh()
        val now = SystemClock.elapsedRealtime()
        liveness.decision(now)
        runtimeStatus.decision(now, report(ride, decision))
        record(DiagnosticStage.REJECTED, now, decision.reason?.take(40))
        val reason = decision.reason.orEmpty()
        provider = ride.provider
        runCatching { OfferDecisionLogStore.recordDecision(fingerprint(ride), ride.provider, reason) }
        runCatching { OfferStatsStore.recordRejected(fingerprint(ride), reason) }
    }

    override fun onTap(ride: ParsedRide, decision: Decision, pending: PendingRide, method: String, latencyMs: Long) {
        val now = SystemClock.elapsedRealtime()
        liveness.decision(now)
        liveness.click(now)
        runtimeStatus.decision(now, report(ride, decision))
        runtimeStatus.click(now)
        provider = ride.provider
        if (method.startsWith("not_dispatched")) DiagnosisRepository.funnel.notDispatched(method.substringAfter(':', "unknown"))
        else DiagnosisRepository.funnel.tapped()
        DiagnosisRepository.refresh()
        TelemetryRepository.trace(TelemetryStage.DECISION_ACCEPTED, "rules_passed", provider = provider)
        record(DiagnosticStage.CLICK_PATH, now, method, latencyMs = latencyMs.coerceAtLeast(0))
        runCatching { OfferDecisionLogStore.recordDecision(fingerprint(ride), provider, "") }
        if (!method.startsWith("not_dispatched")) runCatching {
            OfferDecisionLogStore.recordOutcome(provider,
                if (method == "gesture" || method == "turbo") DecisionLogOutcome.GESTURE_CLICK_SENT else DecisionLogOutcome.SEMANTIC_CLICK_SENT)
        }
    }

    override fun onPendingOutcome(outcome: PendingOutcome) {
        val now = SystemClock.elapsedRealtime()
        val p = outcome.pending
        provider = p.provider
        when (outcome) {
            is PendingOutcome.Confirmed -> {
                DiagnosisRepository.funnel.confirmed()
                if (p.tapX > 0 && p.tapY > 0) { Settings.lastConfirmedTapX = p.tapX; Settings.lastConfirmedTapY = p.tapY }
                record(DiagnosticStage.CONFIRMATION_CONFIRMED, now, "post_click_confirmed")
                runCatching { OfferDecisionLogStore.recordOutcome(provider, DecisionLogOutcome.ACCEPTED_CONFIRMED) }
                runCatching { OfferStatsStore.recordAcceptedConfirmed() }
                val fare = p.reportPrice?.let { BigDecimal.valueOf(it) }
                runCatching { NotificationCenter.postAcceptedRide(fare, null) }
                OfferEvents.accepted(fare, null)
            }
            is PendingOutcome.NewOfferAppeared -> {
                DiagnosisRepository.funnel.unconfirmed("new_offer_appeared")
                record(DiagnosticStage.CONFIRMATION_TIMEOUT, now, "new_offer_appeared")
            }
            is PendingOutcome.ConfirmationTimeout -> {
                DiagnosisRepository.funnel.unconfirmed("confirmation_timeout")
                record(DiagnosticStage.CONFIRMATION_TIMEOUT, now, "confirmation_timeout")
                runCatching { OfferDecisionLogStore.recordOutcome(provider, DecisionLogOutcome.CONFIRMATION_TIMEOUT) }
            }
            is PendingOutcome.Disappeared -> {
                DiagnosisRepository.funnel.unconfirmed("offer_disappeared")
                record(DiagnosticStage.CONFIRMATION_TIMEOUT, now, "offer_disappeared_after_click")
                runCatching { OfferDecisionLogStore.recordOutcome(provider, DecisionLogOutcome.CONFIRMATION_TIMEOUT) }
            }
        }
    }

    override fun onIdle(reason: String) {
        if (reason == "no_offer") return
        if (reason in HELD_REASONS) DiagnosisRepository.funnel.held(reason)
        record(DiagnosticStage.STALE, SystemClock.elapsedRealtime(), reason)
    }

    override fun onReadMiss(kind: String, reason: String, detail: String?) {
        val now = SystemClock.elapsedRealtime()
        if (kind == "failed") {
            DiagnosisRepository.funnel.readFailed(reason)
            record(DiagnosticStage.ROOT_UNAVAILABLE, now, reason)
        } else {
            DiagnosisRepository.funnel.readMiss(reason)
            // A plain map or menu screen is normal; only half-read offers go to telemetry.
            if (reason != "not_offer_screen") record(DiagnosticStage.OFFER_NOT_PARSED, now, reason, detail = detail)
        }
        DiagnosisRepository.refresh()
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
        TelemetryRepository.trace(telemetryStage, reasonCode = reason, detail = detail, provider = provider, latencyMs = latencyMs)
    }

    override fun onDestroy() {
        LiveAcceptTest.onArmChanged = null
        TelemetryRepository.serviceConnected = false
        DiagnosisRepository.funnel.serviceConnected(false)
        DiagnosisRepository.refresh()
        TelemetryRepository.trace(TelemetryStage.SERVICE_DESTROYED, "destroyed")
        TelemetryRepository.flushNow()
        if (instance === this) instance = null
        if (::host.isInitialized) host.onDestroy()
        liveness.pause("service_destroyed")
        runtimeStatus.reset()
        super.onDestroy()
    }

    companion object {
        /** The connected service, so a changed target app is applied without waiting for an event. */
        @Volatile var instance: SabbaqA11yService? = null
        private const val TAG = "SabbaqA11y"
        private const val NEWEST_SEEN_WRITE_STEP_MS = 60_000L
        private val HELD_REASONS = setOf("post_click_suppressed", "same_ride_cooldown", "global_click_cooldown")
    }
}

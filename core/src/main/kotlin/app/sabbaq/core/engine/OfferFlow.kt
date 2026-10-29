package app.sabbaq.core.engine

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** A tap the engine decided on and is now waiting to see confirmed (FareSniper `PendingRideCapture`). */
class PendingRide(
    val price: Double?,
    val minutes: Int?,
    val destination: String?,
    val identity: Int,
    val detectedAtMs: Long,
    val clickedAtMs: Long,
    val delayMs: Long,
    /** Where the tap landed; the saved turbo point when tapped blind. */
    val tapX: Int = 0,
    val tapY: Int = 0,
    /** Ride app of the tapped offer ("jeeny", "bolt", "petra"). */
    val provider: String = "jeeny",
) {
    /** Filled by the late capture after the tap when the frame lacked them (FareSniper `late*`). */
    @Volatile var latePrice: Double? = null
    @Volatile var lateMinutes: Int? = null
    @Volatile var lateDestination: String? = null
    val reportPrice: Double? get() = price ?: latePrice
    val reportMinutes: Int? get() = minutes ?: lateMinutes
    val reportDestination: String? get() = destination ?: lateDestination
}

/** What the service must do after one frame. */
sealed class FlowAction {
    /** Nothing to act on; [reason] is for telemetry. */
    data class Idle(val reason: String) : FlowAction()
    data class Rejected(val ride: ParsedRide, val decision: Decision, val firstTimeForThisOffer: Boolean) : FlowAction()
    data class Tap(val ride: ParsedRide, val decision: Decision, val pending: PendingRide) : FlowAction()
}

/** Outcome of a pending tap, reported when known. */
sealed class PendingOutcome {
    abstract val pending: PendingRide
    data class Confirmed(override val pending: PendingRide) : PendingOutcome()
    data class NewOfferAppeared(override val pending: PendingRide) : PendingOutcome()
    data class ConfirmationTimeout(override val pending: PendingRide, val elapsedMs: Long) : PendingOutcome()
    data class Disappeared(override val pending: PendingRide) : PendingOutcome()
}

/**
 * The per-frame state machine of FareSniper's `processOfferFrame` (QANNAS_ENGINE_FACTS.md §11):
 * duplicate and cool-down rules, the pending-tap verification and the learned view ids. Pure:
 * the service reads the screen, calls [onFrame], then taps or reports what it returns.
 */
class OfferFlow(
    private val nowMs: () -> Long,
    private val random: Random = Random.Default,
    /** FareSniper's geographic stage; null when the host has no position or place cache. */
    private val zones: app.sabbaq.core.engine.zones.ZoneEngine? = null,
) {
    var pending: PendingRide? = null
        private set
    var learnedIds: LearnedOfferIds? = null
        private set
    var lastRootReadWasActiveRide = false
        private set
    /** Content-event throttle, adapted to the device: 1.5 × mean of the last 5 reads, 25..60 ms. */
    var eventThrottleMs: Long = 25L
        private set

    private var currentRideHash = 0
    private var lastHandledRideHash = 0
    private var lastHandledWasAccepted = false
    private var lastClickTime = 0L
    private var clickedOfferIdentity = 0
    private var suppressIdentity = 0
    private var suppressUntilMs = 0L
    private var rideDetectedAtMs = 0L
    private var fastPathMisses = 0
    private val parseTimes = LongArray(5)
    private var parseIndex = 0
    private var parseCount = 0

    /**
     * One frame. [fromRoot] is true for a whole-window read; [verify] reads the post-tap outcome
     * from the same window and is only called while a tap is pending; [onPendingOutcome] receives
     * the pending tap's resolution.
     */
    fun onFrame(
        parsed: ParsedRide?,
        config: EngineConfig,
        fromRoot: Boolean,
        isJeeny: Boolean,
        parseMs: Long,
        frameDetectedMs: Long,
        verify: () -> PostAcceptOutcome,
        isBolt: Boolean = false,
        onPendingOutcome: (PendingOutcome) -> Unit,
    ): FlowAction {
        if (isJeeny) learnOfferIds(parsed)
        recordParseTime(parseMs)
        if (fromRoot) lastRootReadWasActiveRide = parsed?.isActiveRide == true

        if (pending == null && parsed?.isActiveRide == true) {
            currentRideHash = 0
            rideDetectedAtMs = 0L
            lastHandledWasAccepted = true
            return FlowAction.Idle("active_ride")
        }

        val now = nowMs()
        pending?.let { p ->
            val elapsed = now - p.clickedAtMs
            val stillSameOffer = parsed?.acceptNode != null &&
                (parsed.screenHash == currentRideHash || (config.turboModeEnabled && elapsed < TURBO_SAME_OFFER_MS)) &&
                elapsed <= POST_ACCEPT_TIMEOUT_MS
            if (!stillSameOffer) {
                val outcome = when {
                    !isJeeny -> if (parsed?.isActiveRide == true) PostAcceptOutcome.Confirmed(parsed.destination) else PostAcceptOutcome.Inconclusive
                    fromRoot -> verify()
                    else -> PostAcceptOutcome.Inconclusive
                }
                when (outcome) {
                    is PostAcceptOutcome.Confirmed -> {
                        pending = null
                        suppressIdentity = 0
                        onPendingOutcome(PendingOutcome.Confirmed(p))
                        return FlowAction.Idle("confirmed")
                    }
                    PostAcceptOutcome.StillOffer -> {
                        val identity = parsed?.identity
                        if (identity != null && identity != clickedOfferIdentity) {
                            pending = null
                            suppressIdentity = 0
                            onPendingOutcome(PendingOutcome.NewOfferAppeared(p))
                        }
                    }
                    PostAcceptOutcome.Inconclusive -> if (elapsed > POST_ACCEPT_TIMEOUT_MS) {
                        if (parsed?.acceptNode != null && parsed.screenHash == currentRideHash) {
                            pending = null
                            suppressIdentity = 0
                            onPendingOutcome(PendingOutcome.ConfirmationTimeout(p, elapsed))
                        } else {
                            pending = null
                            onPendingOutcome(PendingOutcome.Disappeared(p))
                            return FlowAction.Idle("offer_disappeared_after_click")
                        }
                    }
                }
            }
        }

        if (parsed == null || (parsed.acceptNode == null && !config.hasBlindCoords)) {
            if (now - lastClickTime > 1000L) {
                currentRideHash = 0
                rideDetectedAtMs = 0L
            }
            return FlowAction.Idle("no_offer")
        }

        val hash = parsed.screenHash
        val sinceLastClick = now - lastClickTime
        if (hash != currentRideHash) {
            currentRideHash = hash
            rideDetectedAtMs = frameDetectedMs
        } else if (sinceLastClick > SAME_RIDE_COOLDOWN_MS) {
            rideDetectedAtMs = frameDetectedMs
        }
        if (isPostClickSuppressed(parsed.identity, now)) return FlowAction.Idle("post_click_suppressed")
        if (hash == lastHandledRideHash && lastHandledWasAccepted && sinceLastClick < SAME_RIDE_COOLDOWN_MS) {
            return FlowAction.Idle("same_ride_cooldown")
        }
        if (sinceLastClick < GLOBAL_CLICK_COOLDOWN_MS) return FlowAction.Idle("global_click_cooldown")

        val decision = DecisionEngine.decide(parsed, config, isBolt, zones)
        if (!decision.accept) {
            val first = hash != lastHandledRideHash
            if (first) {
                lastHandledRideHash = hash
                lastHandledWasAccepted = false
            }
            return FlowAction.Rejected(parsed, decision, first)
        }

        lastClickTime = now
        lastHandledRideHash = hash
        lastHandledWasAccepted = true
        clickedOfferIdentity = parsed.identity
        val delay = smartDelay(config)
        val blind = config.hasBlindCoords
        val created = PendingRide(
            price = parsed.price, minutes = parsed.minutes, destination = parsed.destination,
            identity = parsed.identity, detectedAtMs = rideDetectedAtMs, clickedAtMs = now + delay, delayMs = delay,
            tapX = if (blind) config.clickX!! else parsed.clickX, tapY = if (blind) config.clickY!! else parsed.clickY,
            provider = parsed.provider,
        )
        pending?.let { old -> onPendingOutcome(PendingOutcome.Disappeared(old)) }
        pending = created
        suppressIdentity = clickedOfferIdentity
        suppressUntilMs = now + POST_CLICK_SUPPRESS_MS
        return FlowAction.Tap(parsed, decision, created)
    }

    /**
     * Turbo's instant tap (`handleJeenyEvent` turbo branch): an offer word was seen, nothing is
     * pending and 100 ms passed since the last tap. Returns the pending tap to dispatch, or null.
     */
    fun onTurboSignal(config: EngineConfig, frameDetectedMs: Long, provider: String = "jeeny"): PendingRide? {
        if (!config.instantTurbo || pending != null) return null
        val now = nowMs()
        if (now - lastClickTime < GLOBAL_CLICK_COOLDOWN_MS) return null
        lastClickTime = now
        lastHandledWasAccepted = true
        val created = PendingRide(
            price = null, minutes = null, destination = null, identity = 0, detectedAtMs = frameDetectedMs,
            clickedAtMs = now, delayMs = 0L, tapX = config.clickX!!, tapY = config.clickY!!, provider = provider,
        )
        pending = created
        return created
    }

    /** Watchdog fired 6250 ms after the tap: still unresolved means the offer vanished. */
    fun onWatchdog(target: PendingRide): PendingOutcome? {
        if (pending !== target) return null
        pending = null
        return PendingOutcome.Disappeared(target)
    }

    fun reset() {
        pending = null
        currentRideHash = 0
        lastHandledRideHash = 0
        lastHandledWasAccepted = false
        suppressIdentity = 0
        learnedIds = null
        fastPathMisses = 0
        lastRootReadWasActiveRide = false
    }

    private fun isPostClickSuppressed(identity: Int, now: Long): Boolean =
        suppressIdentity != 0 && identity == suppressIdentity && now < suppressUntilMs

    private fun learnOfferIds(parsed: ParsedRide?) {
        val a = parsed?.acceptViewId
        val p = parsed?.priceViewId
        val t = parsed?.timeViewId
        if (!a.isNullOrBlank() && !p.isNullOrBlank() && !t.isNullOrBlank()) {
            fastPathMisses = 0
            val ids = LearnedOfferIds(a, p, t)
            if (learnedIds != ids) learnedIds = ids
            return
        }
        if (learnedIds != null && parsed?.acceptNode == null) {
            fastPathMisses++
            if (fastPathMisses >= FAST_PATH_MISS_LIMIT) {
                learnedIds = null
                fastPathMisses = 0
            }
        }
    }

    private fun recordParseTime(ms: Long) {
        parseTimes[parseIndex] = ms
        parseIndex = (parseIndex + 1) % parseTimes.size
        if (parseCount < parseTimes.size) parseCount++
        val mean = parseTimes.take(parseCount).sum() / max(parseCount, 1)
        eventThrottleMs = (mean * 1.5).toLong().coerceIn(25L, 60L)
    }

    /** Gaussian delay inside [min, max] (FareSniper smart delay), 0 when off. */
    private fun smartDelay(config: EngineConfig): Long {
        val min = max(0, config.smartDelayMinMs).toLong()
        val maxDelay = max(min, config.smartDelayMaxMs.toLong())
        if (!config.smartDelayEnabled || maxDelay <= 0L) return 0L
        val mean = (min + maxDelay) / 2.0
        val sigma = (maxDelay - min) / 4.0
        repeat(1000) {
            val u1 = random.nextDouble().coerceAtLeast(1e-9)
            val u2 = random.nextDouble()
            val sample = (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2) * sigma + mean).toLong()
            if (sample in min..maxDelay) return sample
        }
        return mean.toLong()
    }

    companion object {
        const val GLOBAL_CLICK_COOLDOWN_MS = 100L
        const val SAME_RIDE_COOLDOWN_MS = 2500L
        const val POST_CLICK_SUPPRESS_MS = 4000L
        const val POST_ACCEPT_TIMEOUT_MS = 6000L
        const val WATCHDOG_MS = POST_ACCEPT_TIMEOUT_MS + 250L
        const val FAST_PATH_MISS_LIMIT = 3
        const val SUBTREE_PARSE_BUDGET_MS = 15L
        const val PROBE_BUDGET_MS = 10L
        const val BOOKED_READ_IDLE_BUDGET_MS = 30L
        const val MAX_BOOKED_READ_ABANDONS = 1
        const val MIN_TAP_HOLD_MS = 40L
        const val LATE_CAPTURE_DELAY_MS = 60L
        const val LATE_CAPTURE_BUDGET_MS = 80L
        const val TURBO_SAME_OFFER_MS = 350L
    }
}

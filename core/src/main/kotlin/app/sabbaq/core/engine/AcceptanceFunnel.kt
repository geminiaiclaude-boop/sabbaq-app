package app.sabbaq.core.engine

/** Where along the way an offer stopped. */
enum class FunnelStep { SERVICE, GATE, EVENTS, READ, DECISION, TAP, CONFIRM, WORKING }

/** The funnel's answer to "why are no orders accepted?": a stable code, its step and the main reason. */
data class FunnelVerdict(val code: String, val step: FunnelStep, val reason: String? = null)

/** Counts over the last hour, keyed "event", "miss:<r>", "reject:<r>", "held:<r>", "tap", "nd:<r>", "confirm", "unconfirmed:<r>", "gate:<r>". */
data class FunnelSnapshot(
    val counts: Map<String, Int>,
    val serviceConnected: Boolean,
    val lastEventAtMs: Long,
    val lastGateBlockAtMs: Long,
    val lastGateReason: String?,
    val lastTapAtMs: Long,
    /** A trip is in progress (confirmed accept or trip screen, no new offer since). */
    val onTrip: Boolean = false,
) {
    fun count(key: String): Int = counts[key] ?: 0
    fun sum(prefix: String): Int = counts.entries.sumOf { if (it.key.startsWith(prefix)) it.value else 0 }
    fun top(prefix: String): String? = counts.entries.filter { it.key.startsWith(prefix) && it.value > 0 }
        .maxByOrNull { it.value }?.key?.removePrefix(prefix)

    val events get() = count("event")
    val offers get() = count("offer")
    val rejected get() = sum("reject:")
    val held get() = sum("held:")
    val taps get() = count("tap")
    val notSent get() = sum("nd:")
    val confirmed get() = count("confirm")
    val unconfirmed get() = sum("unconfirmed:")
    /** Screens that looked like a half-read offer (not a plain map or menu). */
    val readMisses get() = sum("miss:") - count("miss:not_offer_screen")
}

/**
 * Follows every ride-app screen to its end — read, decided, tapped, confirmed — or to the
 * point where it stopped, and turns the last hour into one verdict. Pure and thread-safe;
 * the service feeds it, the UI and telemetry read it.
 */
class AcceptanceFunnel(private val nowMs: () -> Long) {
    private val lock = Any()
    private val buckets = Array(WINDOW_MINUTES) { HashMap<String, Int>() }
    private val bucketMinute = LongArray(WINDOW_MINUTES) { -1L }
    private var serviceConnected = false
    private var lastEventAtMs = 0L
    private var lastGateBlockAtMs = 0L
    private var lastGateReason: String? = null
    private var lastTapAtMs = 0L
    /** Set by a confirmed accept or a trip screen; cleared by the next offer read. */
    private var tripSinceMs = 0L
    private var lastOfferKey: String? = null
    private var lastOfferAtMs = 0L

    fun serviceConnected(connected: Boolean) = synchronized(lock) { serviceConnected = connected }

    fun event() = synchronized(lock) { lastEventAtMs = nowMs(); add("event") }
    fun gateBlocked(reason: String?) = synchronized(lock) {
        lastGateBlockAtMs = nowMs(); lastGateReason = reason; add("gate:" + (reason ?: "unknown"))
    }
    /**
     * A screen of the ride app gave no offer; [reason] from the reader ("no_accept", "not_offer_screen"…).
     * During a trip (after a confirmed accept, until the next offer) such screens are trip screens,
     * not offers that failed to read.
     */
    fun readMiss(reason: String): Boolean = synchronized(lock) {
        val onTrip = onTripLocked()
        add(if (onTrip) "trip_screen" else "miss:$reason")
        onTrip
    }

    /** The reader saw a trip in progress. */
    fun activeRide() = synchronized(lock) { if (tripSinceMs == 0L) tripSinceMs = nowMs(); add("trip_screen") }
    /** The window could not be read at all ("root_unavailable", "other_app", "read_aborted"). */
    fun readFailed(reason: String) = synchronized(lock) { add("miss:$reason") }
    /** An offer was read; [key] identifies it so its repeated frames count once. */
    fun offer(key: String? = null) = synchronized(lock) {
        tripSinceMs = 0L
        val now = nowMs()
        // M-03: the same key only within a card's lifetime is the same offer.
        if (key != null && key == lastOfferKey && now - lastOfferAtMs in 0..60_000L) return@synchronized
        lastOfferKey = key
        lastOfferAtMs = now
        add("offer")
    }
    fun rejected(reason: String) = synchronized(lock) { add("reject:$reason") }
    /** The flow held a decided offer back (cooldowns, post-tap suppression). */
    fun held(reason: String) = synchronized(lock) { add("held:$reason") }
    fun tapped() = synchronized(lock) { lastTapAtMs = nowMs(); add("tap") }
    fun notDispatched(cause: String) = synchronized(lock) { add("nd:$cause") }
    fun confirmed() = synchronized(lock) { tripSinceMs = nowMs(); add("confirm") }
    fun unconfirmed(kind: String) = synchronized(lock) { add("unconfirmed:$kind") }

    fun snapshot(): FunnelSnapshot = synchronized(lock) {
        val current = nowMs() / 60_000L
        val total = HashMap<String, Int>()
        for (i in 0 until WINDOW_MINUTES) {
            if (bucketMinute[i] < 0 || current - bucketMinute[i] >= WINDOW_MINUTES) continue
            for ((k, v) in buckets[i]) total[k] = (total[k] ?: 0) + v
        }
        FunnelSnapshot(total, serviceConnected, lastEventAtMs, lastGateBlockAtMs, lastGateReason, lastTapAtMs, onTripLocked())
    }

    private fun onTripLocked(): Boolean = tripSinceMs != 0L && nowMs() - tripSinceMs < TRIP_MAX_MS

    private fun add(key: String) {
        val minute = nowMs() / 60_000L
        val i = (minute % WINDOW_MINUTES).toInt()
        if (bucketMinute[i] != minute) { buckets[i].clear(); bucketMinute[i] = minute }
        buckets[i][key] = (buckets[i][key] ?: 0) + 1
    }

    companion object {
        const val WINDOW_MINUTES = 60
        /** No ride-app event for this long means the ride app is not reaching Sabbaq. */
        const val QUIET_MS = 15 * 60_000L
        const val GATE_RECENT_MS = 2 * 60_000L
        /** Longest trip assumed; afterwards misses count again even without a new offer. */
        const val TRIP_MAX_MS = 3 * 60 * 60_000L

        /**
         * The first step, in the order an offer travels, that explains why nothing was accepted
         * in the last hour. "working" once a tap was confirmed; "waiting_for_offers" when every
         * step is healthy and Jeeny simply showed no offer.
         */
        fun diagnose(s: FunnelSnapshot, nowMs: Long): FunnelVerdict {
            if (!s.serviceConnected) return FunnelVerdict("service_off", FunnelStep.SERVICE)
            if (s.lastGateBlockAtMs > 0 && nowMs - s.lastGateBlockAtMs < GATE_RECENT_MS && s.lastGateBlockAtMs >= s.lastTapAtMs) {
                return FunnelVerdict("gate_" + (s.lastGateReason ?: "blocked"), FunnelStep.GATE, s.lastGateReason)
            }
            if (s.onTrip) return FunnelVerdict("on_trip", FunnelStep.WORKING)
            if (s.confirmed > 0) return FunnelVerdict("working", FunnelStep.WORKING)
            if (s.events == 0 || s.lastEventAtMs == 0L || nowMs - s.lastEventAtMs > QUIET_MS) {
                return FunnelVerdict("no_ride_app_events", FunnelStep.EVENTS)
            }
            if (s.taps > 0 && s.unconfirmed > 0) {
                return FunnelVerdict("tap_not_confirmed", FunnelStep.CONFIRM, s.top("unconfirmed:"))
            }
            if (s.notSent > 0) return FunnelVerdict("tap_not_sent", FunnelStep.TAP, s.top("nd:"))
            if (s.offers > 0 && s.taps == 0) {
                if (s.rejected > 0 && s.rejected >= s.held) return FunnelVerdict("all_rejected", FunnelStep.DECISION, s.top("reject:"))
                if (s.held > 0) return FunnelVerdict("held_back", FunnelStep.DECISION, s.top("held:"))
            }
            if (s.offers == 0 && s.readMisses > 0) {
                val reason = s.counts.entries.filter { it.key.startsWith("miss:") && it.key != "miss:not_offer_screen" }
                    .maxByOrNull { it.value }?.key?.removePrefix("miss:")
                return FunnelVerdict("offer_not_read", FunnelStep.READ, reason)
            }
            if (s.taps > 0) return FunnelVerdict("tap_pending", FunnelStep.CONFIRM)
            return FunnelVerdict("waiting_for_offers", FunnelStep.EVENTS)
        }
    }
}

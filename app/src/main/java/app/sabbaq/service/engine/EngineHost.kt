package app.sabbaq.service.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.sabbaq.core.engine.Decision
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.FlowAction
import app.sabbaq.core.engine.JeenyScreenReader
import app.sabbaq.core.engine.RideApp
import app.sabbaq.core.engine.RideApps
import app.sabbaq.core.engine.OfferFlow
import app.sabbaq.core.engine.ParsedRide
import app.sabbaq.core.engine.PendingOutcome
import app.sabbaq.core.engine.PendingRide
import app.sabbaq.core.engine.PostAcceptOutcome
import app.sabbaq.core.engine.PostAcceptVerifier
import app.sabbaq.core.engine.UiNode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reference engine's accessibility pipeline for Jeeny (reference engine notes):
 * content events are coalesced into one root read on a background reader, a cheap keyword probe
 * gates the throttled ones, a subtree is read within 15 ms when that is enough, and the decided
 * tap goes to the very node that was read — accessibility click, then its parent, then a 40 ms
 * gesture at its centre. Everything here runs on the main thread except the booked read.
 */
class EngineHost(
    private val service: AccessibilityService,
    private val config: () -> EngineConfig,
    private val allowed: () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onOffer(ride: ParsedRide, source: String)
        fun onRejected(ride: ParsedRide, decision: Decision, firstTime: Boolean)
        fun onTap(ride: ParsedRide, decision: Decision, pending: PendingRide, method: String, latencyMs: Long)
        fun onPendingOutcome(outcome: PendingOutcome)
        fun onIdle(reason: String)
        /**
         * A ride-app window gave no offer: [kind] "miss" (read, no offer) or "failed" (not readable).
         * [screen] and [packageName] are the window that was read, for redacted sampling.
         */
        fun onReadMiss(kind: String, reason: String, detail: String?, screen: UiNode? = null, packageName: String? = null)
    }

    private val main = Handler(Looper.getMainLooper())
    private val zones = app.sabbaq.core.engine.zones.ZoneEngine(app.sabbaq.service.zones.ZoneStore)
    private val flow = OfferFlow({ System.currentTimeMillis() }, zones = zones)
    private val locationTracker = app.sabbaq.service.zones.DriverLocationTracker(service)
    private var corridorJob: kotlinx.coroutines.Job? = null
    private val readerPool: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread({ Process.setThreadPriority(-4); runnable.run() }, "sabbaq-reader").apply { isDaemon = true }
    }
    private val bookingLock = Any()
    private var bookedFrame: Frame? = null
    private val inFlight = ArrayList<AtomicBoolean>()
    private var newestDecidedReadStart = 0L
    private var lastEventProcessedMs = 0L
    private var toneGenerator: ToneGenerator? = null

    private data class Frame(val detectedAtMs: Long, val abandons: Int)
    private class BookedResult(val root: AccessibilityNodeInfo?, val parsed: ParsedRide?, val startUptime: Long, val elapsedMs: Long, val aborted: Boolean)

    /** The driver saved settings: forget zone verdicts made with the old lists, follow the location need. */
    fun onConfigSaved() {
        zones.clear()
        locationTracker.update(config())
    }

    fun onConnected() {
        locationTracker.update(config())
        corridorJob?.cancel()
        corridorJob = app.sabbaq.service.zones.ZoneWarmer.corridorLoop(config, allowed)
        runCatching { toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100) }
        warmUpGesture()
    }

    fun onDestroy() {
        locationTracker.stop()
        corridorJob?.cancel()
        corridorJob = null
        main.removeCallbacksAndMessages(null)
        synchronized(bookingLock) { inFlight.forEach { it.set(true) }; inFlight.clear(); bookedFrame = null }
        readerPool.shutdownNow()
        main.removeCallbacksAndMessages(null)
        app.sabbaq.service.AlarmBoost.releaseAll(service)
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        flow.reset()
    }

    /** `handleJeenyEvent` (§4). */
    fun onEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val now = System.currentTimeMillis()
        val contentChange = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (contentChange && flow.pending == null && synchronized(bookingLock) { bookedFrame != null }) return
        if (contentChange && flow.pending == null && !flow.lastRootReadWasActiveRide) {
            if (!allowed()) return
            if (!config().instantTurbo) {
                bookRootRead(Frame(now, 0))
                return
            }
        }
        var probe: Probe? = null
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && now - lastEventProcessedMs < flow.eventThrottleMs) {
            event.source?.let { src ->
                probe = probeOfferSignal(src)
                if (probe == Probe.NO_SIGNAL) return
            }
        }
        lastEventProcessedMs = now
        if (!allowed()) return
        val cfg = config()
        if (cfg.instantTurbo && flow.pending == null) {
            val src = event.source
            if (src != null && quickScan(src, 0)) {
                val turboApp = RideApps.of(event.packageName?.toString(), cfg.targetApp)
                val pending = flow.onTurboSignal(cfg, now, turboApp?.name?.lowercase() ?: "jeeny")
                if (pending != null) {
                    turboTap(pending, cfg, event.packageName?.toString())
                    return
                }
            }
        }
        var subtree: AccessibilityNodeInfo? = null
        if (contentChange && flow.pending == null) {
            val src = event.source
            if (src != null) {
                val cls = src.className?.toString()?.lowercase().orEmpty()
                if (NOISE_WIDGETS.any { it in cls }) return
                if (src.text == null && src.contentDescription == null && src.childCount == 0) return
                val signal = probe ?: probeOfferSignal(src)
                if (signal == Probe.HAS_SIGNAL && !config().needsFullTree) subtree = src
            }
        }
        processFrame(now, subtree, allowAsyncBook = contentChange && flow.pending == null, booked = null)
    }

    private enum class Probe { HAS_SIGNAL, NO_SIGNAL, UNKNOWN }

    /** `probeOfferSignal` (§6): offer words within depth 4, 8 children per node, 10 ms. */
    private fun probeOfferSignal(node: AccessibilityNodeInfo): Probe {
        val deadline = SystemClock.uptimeMillis() + OfferFlow.PROBE_BUDGET_MS
        var timedOut = false
        fun scan(n: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 4) return false
            if (SystemClock.uptimeMillis() > deadline) { timedOut = true; return false }
            val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").lowercase()
            if (label.isNotBlank() && PROBE_WORDS.any { it in label }) return true
            for (i in 0 until minOf(n.childCount, 8)) {
                if (timedOut) return false
                val child = runCatching { n.getChild(i) }.getOrNull() ?: continue
                if (scan(child, depth + 1)) return true
            }
            return false
        }
        return when {
            scan(node, 0) -> Probe.HAS_SIGNAL
            timedOut -> Probe.UNKNOWN
            else -> Probe.NO_SIGNAL
        }
    }

    /** `bookRootRead` (§7): one pending frame; a newer booking aborts reads in flight. */
    private fun bookRootRead(frame: Frame) {
        val abort = AtomicBoolean(false)
        val cfg = config()
        val height = service.resources.displayMetrics.heightPixels
        synchronized(bookingLock) {
            if (bookedFrame != null) return
            bookedFrame = frame
            inFlight.forEach { it.set(true) }
            inFlight.add(abort)
        }
        try {
            readerPool.execute { runBookedRead(frame, abort, cfg, height) }
        } catch (_: RejectedExecutionException) {
            synchronized(bookingLock) { bookedFrame = null; inFlight.remove(abort) }
        }
    }

    private fun runBookedRead(frame: Frame, abort: AtomicBoolean, cfg: EngineConfig, height: Int) {
        val start = SystemClock.uptimeMillis()
        synchronized(bookingLock) { bookedFrame = null }
        var root: AccessibilityNodeInfo? = null
        var parsed: ParsedRide? = null
        if (!abort.get()) {
            root = runCatching { service.rootInActiveWindow }.getOrNull()
            val app = root?.let { appOf(it.packageName?.toString(), cfg) }
            if (root != null && app != null) {
                val idle = if (frame.abandons < OfferFlow.MAX_BOOKED_READ_ABANDONS) start + OfferFlow.BOOKED_READ_IDLE_BUDGET_MS else 0L
                parsed = runCatching {
                    RideApps.read(app, LiveNode(root), height, cfg, flow.learnedIds, { SystemClock.uptimeMillis() },
                        idleDeadline = idle, abort = abort,
                        onMiss = { r, d ->
                            if (!abort.get()) {
                                val screen = LiveNode(root)
                                val pkg = root.packageName?.toString()
                                main.post { listener.onReadMiss("miss", r, d, screen, pkg) }
                            }
                        })
                }.getOrNull()
            } else {
                val why = if (root == null) "root_unavailable" else "other_app"
                main.post { listener.onReadMiss("failed", why, null) }
                root = null
            }
        }
        val aborted = abort.get() || parsed?.aborted == true
        synchronized(bookingLock) { inFlight.remove(abort) }
        val result = BookedResult(root, parsed, start, SystemClock.uptimeMillis() - start, aborted)
        main.post { finishBookedRead(frame, result) }
    }

    /** `finishBookedRead` (§7): drop stale reads, re-book an aborted one once, else decide. */
    private fun finishBookedRead(frame: Frame, result: BookedResult) {
        if (result.root == null) return
        if (!result.aborted) {
            if (result.startUptime < newestDecidedReadStart) return
            newestDecidedReadStart = result.startUptime
            if (!allowed()) return
            processFrame(frame.detectedAtMs, null, allowAsyncBook = false, booked = result)
            return
        }
        bookRootRead(Frame(frame.detectedAtMs, frame.abandons + 1))
    }

    /** `processOfferFrame` (§11): read, then let the flow decide, then act. */
    private fun processFrame(detectedAtMs: Long, subtree: AccessibilityNodeInfo?, allowAsyncBook: Boolean, booked: BookedResult?) {
        val cfg = config()
        val height = service.resources.displayMetrics.heightPixels
        val started = SystemClock.uptimeMillis()
        var app: RideApp? = null
        var root: AccessibilityNodeInfo? = null
        var parsed: ParsedRide? = null
        var source = "root"
        if (booked != null) {
            root = booked.root
            parsed = booked.parsed
            app = root?.let { appOf(it.packageName?.toString(), cfg) }
        } else if (subtree != null) {
            source = "subtree"
            app = appOf(subtree.packageName?.toString(), cfg) ?: return
            parsed = RideApps.read(app, LiveNode(subtree), height, cfg, flow.learnedIds, { SystemClock.uptimeMillis() },
                hardDeadline = SystemClock.uptimeMillis() + OfferFlow.SUBTREE_PARSE_BUDGET_MS)
            if (parsed?.aborted == true || (parsed?.acceptNode == null && parsed?.isActiveRide != true)) parsed = null
        }
        if (parsed == null && booked == null) {
            if (allowAsyncBook) {
                bookRootRead(Frame(detectedAtMs, 0))
                lastEventProcessedMs = System.currentTimeMillis()
                return
            }
            newestDecidedReadStart = maxOf(newestDecidedReadStart, SystemClock.uptimeMillis())
            val active = runCatching { service.rootInActiveWindow }.getOrNull()
            val activeApp = active?.let { appOf(it.packageName?.toString(), cfg) }
            if (active == null || activeApp == null) {
                listener.onReadMiss("failed", if (active == null) "root_unavailable" else "other_app", null)
                lastEventProcessedMs = System.currentTimeMillis()
                return
            }
            root = active
            app = activeApp
            parsed = RideApps.read(activeApp, LiveNode(active), height, cfg, flow.learnedIds, { SystemClock.uptimeMillis() },
                onMiss = { r, d -> listener.onReadMiss("miss", r, d, LiveNode(active), active.packageName?.toString()) })
        }
        if (parsed?.fromFastIds == true) source = "fast_ids"
        val parseMs = booked?.elapsedMs ?: (SystemClock.uptimeMillis() - started)
        if (parsed != null && parsed.acceptNode != null) listener.onOffer(parsed, source)
        val verifyRoot = root
        val action = flow.onFrame(
            parsed = parsed, config = cfg, fromRoot = verifyRoot != null, isJeeny = app == RideApp.JEENY, parseMs = parseMs,
            frameDetectedMs = detectedAtMs,
            verify = { verifyRoot?.let { PostAcceptVerifier.verify(LiveNode(it)) } ?: PostAcceptOutcome.Inconclusive },
            isBolt = app == RideApp.BOLT,
            onPendingOutcome = ::handleOutcome,
        )
        lastEventProcessedMs = System.currentTimeMillis()
        when (action) {
            is FlowAction.Idle -> listener.onIdle(action.reason)
            is FlowAction.Rejected -> listener.onRejected(action.ride, action.decision, action.firstTimeForThisOffer)
            is FlowAction.Tap -> scheduleTap(action, detectedAtMs, cfg, height, app == RideApp.JEENY)
        }
    }

    private fun scheduleTap(action: FlowAction.Tap, detectedAtMs: Long, cfg: EngineConfig, height: Int, isJeeny: Boolean) {
        val pending = action.pending
        val target = action.ride.acceptNode
        if (target == null && !cfg.hasBlindCoords) return
        main.postDelayed({ flow.onWatchdog(pending)?.let(::handleOutcome) }, OfferFlow.WATCHDOG_MS + pending.delayMs)
        val tap = Runnable {
            val point = if (cfg.hasBlindCoords) pending.tapX to pending.tapY else null
            val method = when {
                !allowed() -> "not_dispatched:gate"
                target != null && !isRideAppNode(target) -> "not_dispatched:foreign_node"
                else -> clickLadder(target, point)
            }
            listener.onTap(action.ride, action.decision, pending, method, System.currentTimeMillis() - detectedAtMs)
            if (isJeeny && action.ride.destination == null) lateCapture(pending, cfg, height)
        }
        if (pending.delayMs > 0) main.postDelayed(tap, pending.delayMs) else tap.run()
    }

    /** `executeFallbackClicks` (§8): click, parent click, then a 40 ms tap at the node's centre. */
    fun clickLadder(node: UiNode?, point: Pair<Int, Int>? = null): String {
        val b = node?.bounds()
        if (point == null && (b == null || b.isEmpty)) return "not_dispatched:empty_bounds"
        if (node != null) {
            if (node.click()) return "semantic"
            if (runCatching { node.parent()?.click() }.getOrNull() == true) return "parent"
        }
        val x = point?.first ?: b!!.centerX
        val y = point?.second ?: b!!.centerY
        return if (gestureAt(x.toFloat(), y.toFloat())) "gesture" else "not_dispatched:gesture_refused"
    }

    /** Turbo's instant tap: the saved point, 40 ms hold, then the late capture fills the report. */
    private fun turboTap(pending: PendingRide, cfg: EngineConfig, packageName: String?) {
        main.postDelayed({ flow.onWatchdog(pending)?.let(::handleOutcome) }, OfferFlow.WATCHDOG_MS)
        val method = if (gestureAt(pending.tapX.toFloat(), pending.tapY.toFloat())) "turbo" else "not_dispatched:gesture_refused"
        val ride = ParsedRide(clickX = pending.tapX, clickY = pending.tapY, provider = pending.provider)
        listener.onTap(ride, Decision(true, true, true, true, null), pending, method, System.currentTimeMillis() - pending.detectedAtMs)
        val app = RideApps.of(packageName, cfg.targetApp)
        if (app == RideApp.JEENY) lateCapture(pending, cfg, service.resources.displayMetrics.heightPixels)
    }

    private fun gestureAt(x: Float, y: Float, holdMs: Long = OfferFlow.MIN_TAP_HOLD_MS): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, holdMs)).build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /** `quickScanForOfferSignal`: an offer word within depth 4, 8 children per node, no time budget. */
    private fun quickScan(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 4) return false
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
        if (label.isNotBlank() && PROBE_WORDS.any { it in label }) return true
        for (i in 0 until minOf(node.childCount, 8)) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            if (quickScan(child, depth + 1)) return true
        }
        return false
    }

    /** Late capture (§10): 60 ms after the tap, a bounded re-read fills the destination for the report. */
    private fun lateCapture(pending: PendingRide, cfg: EngineConfig, height: Int) {
        main.postDelayed({
            val root = runCatching { service.rootInActiveWindow }.getOrNull()
                ?.takeIf { appOf(it.packageName?.toString(), cfg) == RideApp.JEENY } ?: return@postDelayed
            val deadline = SystemClock.uptimeMillis() + OfferFlow.LATE_CAPTURE_BUDGET_MS
            val late = runCatching {
                JeenyScreenReader(cfg) { SystemClock.uptimeMillis() }.read(LiveNode(root), height, collectAddresses = true, hardDeadline = deadline)
            }.getOrNull() ?: return@postDelayed
            if (pending.latePrice == null) pending.latePrice = late.price
            if (pending.lateMinutes == null) pending.lateMinutes = late.minutes
            if (pending.lateDestination == null) pending.lateDestination = late.destination
        }, OfferFlow.LATE_CAPTURE_DELAY_MS)
    }

    private fun handleOutcome(outcome: PendingOutcome) {
        if (outcome is PendingOutcome.Confirmed) playAcceptTone()
        listener.onPendingOutcome(outcome)
    }

    /** The reference engine's accept tone: alarm stream raised to max for the tone, then restored. */
    private fun playAcceptTone() {
        app.sabbaq.service.AlarmBoost.raise(service)
        runCatching {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, 100).also { toneGenerator = it }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 800)
        }
        main.postDelayed(::restoreAlarmVolume, 1000)
    }

    private fun restoreAlarmVolume() = app.sabbaq.service.AlarmBoost.release(service)

    private fun warmUpGesture() {
        val gesture = runCatching {
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(1f, 1f) }, 0L, 1L)).build()
        }.getOrNull() ?: return
        main.post { runCatching { service.dispatchGesture(gesture, null, null) } }
    }

    /** Only the watched ride apps' own windows are read or tapped (the reference engine's routing, §5). */
    private fun appOf(packageName: String?, cfg: EngineConfig): RideApp? = RideApps.of(packageName, cfg.targetApp)

    private fun isRideAppNode(node: UiNode): Boolean =
        (node as? LiveNode)?.let { appOf(it.packageName, config()) != null } ?: true

    private companion object {
        val NOISE_WIDGETS = listOf("scrollbar", "edittext", "progressbar", "seekbar", "switch", "toast")
        val PROBE_WORDS = listOf("jod", "sar", "$", "د.أ", "﷼", "ريال", "mins", "min ", "دقيق", "away",
            "accept", "reject", "قبول", "رفض", "عرض")
    }
}

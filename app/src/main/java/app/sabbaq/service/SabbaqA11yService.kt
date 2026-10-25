package app.sabbaq.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.sabbaq.Settings
import app.sabbaq.core.*
import app.sabbaq.network.SupabaseClient
import app.sabbaq.stats.DecisionLogOutcome
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.stats.OfferStatsStore
import app.sabbaq.voice.OfferEvents
import java.math.BigDecimal
import java.util.Calendar

class SabbaqA11yService : AccessibilityService() {

    private val clickGate = ClickGate(perOfferSuppressMs = 20000, minIntervalMs = 500)
    private val acceptTracker = AcceptTracker(confirmWindowMs = 5000)
    private var pendingVoiceDetails: Pair<BigDecimal?, BigDecimal?>? = null
    private var pendingHistoryProfileId: String? = null
    private var parsersByPackage: Map<String, List<OfferParser>> = emptyMap()
    private var adaptersByProfile: Map<String, RideAppAdapter> = emptyMap()
    private var lastParsedPackage: String? = null
    private var lastParseTimeMs: Long? = null
    private val lateReadHandler = Handler(Looper.getMainLooper())
    private var jeenyHints: JeenyAcceptIdStore? = null
    private var lateReadGeneration = 0
    private val jeenyPipeline = JeenyOfferPipeline()
    private val diagnostics = BoundedDiagnostics()
    private val liveness = LivenessState()
    private var lastJeenyFingerprint: String? = null
    private var lastJeenyEventMs = 0L
    private var lastJeenyWindowId: Int? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        liveness.pause(null)
        jeenyHints = runCatching { JeenyAcceptIdStore(applicationContext) }.getOrNull()
        runCatching { OfferStatsStore.init(applicationContext) }
            .onFailure { Log.w(TAG, "تعذر تهيئة الإحصاءات المحلية") }
        runCatching { OfferDecisionLogStore.init(applicationContext) }
            .onFailure { Log.w(TAG, "تعذر تهيئة سجل القرارات المحلي") }
        parsersByPackage = runCatching {
            val profiles = Profiles.bundled().filter { it.enabled }
            adaptersByProfile = profiles.associate { it.id to RideAppAdapters.forProfile(it) }
            profiles
                .asSequence()
                .flatMap { profile ->
                    profile.packages.asSequence()
                        .filter { it.isNotBlank() }
                        .map { packageName ->
                            val hints = if (packageName == JEENY_PACKAGE) jeenyHints?.snapshot().orEmpty() else emptySet()
                            packageName to (adaptersByProfile[profile.id] ?: DefaultRideAppAdapter(profile)).parser(hints)
                        }
                }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        }.getOrElse {
            Log.e(TAG, "تعذر تحميل ملفات تعريف التطبيقات المدعومة")
            emptyMap()
        }

        if (parsersByPackage.isEmpty()) {
            Log.w(TAG, "لا توجد ملفات تعريف صالحة لخدمة الإتاحة")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventNow = SystemClock.elapsedRealtime()
        liveness.accessibilityEvent(eventNow)
        diagnostics.record(DiagnosticStage.EVENT_RECEIVED, eventNow)
        if (!Settings.a11yDisclosureAccepted) {
            liveness.pause("disclosure_required")
            Settings.isActive = false
            return
        }
        if (!Settings.isActive) {
            liveness.pause("service_paused")
            return
        }
        if (!ServicesSafetyPolicy.isRelevantAccessibilityEvent(event.eventType)) return
        if (!SupabaseClient.hasValidLicense()) {
            liveness.pause("license_expired")
            Settings.isActive = false
            return
        }
        val packageName = event.packageName?.toString() ?: return
        if (!ServicesSafetyPolicy.isSupportedPackage(packageName, parsersByPackage.keys)) return
        diagnostics.record(DiagnosticStage.PACKAGE_SEEN, eventNow)
        if (packageName == JEENY_PACKAGE) {
            liveness.targetEvent(eventNow)
            handleJeenyEvent(event)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val previous = lastParseTimeMs.takeIf { lastParsedPackage == packageName }
        if (!ServicesSafetyPolicy.shouldParse(event.eventType, now, previous)) return
        processPackage(packageName)
    }

    /** Jeeny has its own cheap signal -> full frame -> two late capture pipeline. */
    private fun handleJeenyEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now >= lastJeenyEventMs && now - lastJeenyEventMs < JEENY_EVENT_THROTTLE_MS) return
        lastJeenyEventMs = now
        val previousWindowId = lastJeenyWindowId
        if (previousWindowId != null && previousWindowId != event.windowId) {
            acceptTracker.reset()
            clickGate.reset()
            pendingHistoryProfileId = null
            pendingVoiceDetails = null
        }
        lastJeenyWindowId = event.windowId
        val token = jeenyPipeline.begin(JEENY_PACKAGE, event.windowId.toString())
        if (previousWindowId != event.windowId) {
            lastJeenyFingerprint = null
        }
        processPackage(JEENY_PACKAGE, requireJeenySignal = true, generation = token)
        if (!jeenyPipeline.hasActionPending()) {
            scheduleJeenyLateReads(JEENY_PACKAGE, token)
        } else {
            lateReadGeneration++
            lateReadHandler.removeCallbacksAndMessages(LATE_READ_TOKEN)
        }
    }

    /** Re-reads the active window after progressive UI rendering, at most twice. */
    private fun scheduleJeenyLateReads(packageName: String, pipelineGeneration: Long) {
        val generation = ++lateReadGeneration
        lateReadHandler.removeCallbacksAndMessages(LATE_READ_TOKEN)
        LATE_READ_DELAYS_MS.forEachIndexed { index, delay ->
            lateReadHandler.postAtTime({
                if (generation == lateReadGeneration && jeenyPipeline.isCurrent(pipelineGeneration) &&
                    jeenyPipeline.allowLateCapture() && Settings.isActive &&
                    Settings.a11yDisclosureAccepted && SupabaseClient.hasValidLicense() &&
                    jeenyPipeline.markLateCapture()) {
                    processPackage(packageName, requireJeenySignal = true, generation = pipelineGeneration)
                }
            }, LATE_READ_TOKEN, SystemClock.uptimeMillis() + delay)
        }
    }

    private fun processPackage(packageName: String, requireJeenySignal: Boolean = false, generation: Long = 0L): Boolean {
        val parsers = parsersByPackage[packageName] ?: return false
        val parseTimeMs = SystemClock.elapsedRealtime()
        val rootNode = rootInActiveWindow ?: return false
        try {
            if (rootNode.packageName?.toString() != packageName) return false
            lastParsedPackage = packageName
            lastParseTimeMs = parseTimeMs
            val screenNode = rootNode.toScreenNode()
            if (packageName == JEENY_PACKAGE && requireJeenySignal) {
                val fingerprint = ScreenChangeFingerprint.compute(packageName, lastJeenyWindowId?.toString(), screenNode)
                if (fingerprint == lastJeenyFingerprint) {
                    diagnostics.record(DiagnosticStage.STALE, parseTimeMs, "unchanged_frame")
                    return false
                }
                lastJeenyFingerprint = fingerprint
            }
            for (parser in parsers) {
                val adapter = adaptersByProfile[parser.profileId]
                    ?: if (packageName == JEENY_PACKAGE) JeenyRideAppAdapter(Profiles.bundled().first { it.id == parser.profileId })
                    else DefaultRideAppAdapter(Profiles.bundled().first { it.id == parser.profileId })
                val hints = if (packageName == JEENY_PACKAGE) jeenyHints?.snapshot()?.toSet().orEmpty() else emptySet()
                val assessment = adapter.classify(screenNode, hints)
                if (assessment?.classification == JeenyFrameClass.INCOMPLETE) {
                    diagnostics.record(DiagnosticStage.FRAME_INCOMPLETE, parseTimeMs, "bounded_preflight")
                }
                val activeParser = if (packageName == JEENY_PACKAGE) {
                    adapter.parser(hints)
                } else adapter.parser()
                if (requireJeenySignal && !jeenyPipeline.hasActionPending() && activeParser.probeOfferSignal(screenNode) == null) {
                    diagnostics.record(DiagnosticStage.SIGNAL_MISS, parseTimeMs, "preflight")
                    return false
                }
                if (requireJeenySignal) diagnostics.record(DiagnosticStage.SIGNAL_FOUND, parseTimeMs)
                val parsed = activeParser.parse(screenNode)
                val nowMs = SystemClock.elapsedRealtime()
                val acceptOutcome = acceptTracker.observe(activeParser.profileId.takeIf { parsed.confirmed }, nowMs)
                when (acceptOutcome) {
                    is AcceptTracker.Outcome.Confirmed -> {
                        diagnostics.record(DiagnosticStage.CONFIRMATION_CONFIRMED, nowMs)
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.markConfirmationResolved()
                        pendingHistoryProfileId?.let { profileId -> runCatching { OfferDecisionLogStore.recordOutcome(profileId, DecisionLogOutcome.ACCEPTED_CONFIRMED) } }
                        runCatching { OfferStatsStore.recordAcceptedConfirmed() }
                        pendingVoiceDetails?.let { (fare, pickupKm) -> OfferEvents.accepted(fare, pickupKm) }
                        pendingVoiceDetails = null
                    }
                    is AcceptTracker.Outcome.NotConfirmed -> {
                        diagnostics.record(DiagnosticStage.CONFIRMATION_TIMEOUT, nowMs)
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.markConfirmationResolved()
                        pendingHistoryProfileId?.let { profileId -> runCatching { OfferDecisionLogStore.recordOutcome(profileId, DecisionLogOutcome.CONFIRMATION_TIMEOUT) } }
                        Log.i(TAG, "offer confirmation timeout")
                        pendingVoiceDetails = null
                    }
                    null -> Unit
                }
                if (acceptOutcome != null) pendingHistoryProfileId = null
                if (parsed.confirmed) continue
                val offer = parsed.offer ?: continue
                liveness.offerDetected(nowMs)
                if (offer.fare == null) diagnostics.record(DiagnosticStage.FARE_MISSING, nowMs)
                if (packageName == JEENY_PACKAGE && !jeenyPipeline.shouldProcess(offer.fingerprint)) {
                    Log.i(TAG, "jeeny frame skipped: same-offer")
                    return false
                }
                val verdict = OfferEvaluator.evaluate(offer, Settings.filters, Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) })
                runCatching { OfferDecisionLogStore.recordDecision(offer.fingerprint, offer.profileId, if (verdict.accept) emptyList() else verdict.reasons.map { it.code }) }
                liveness.decision(nowMs)
                runCatching { OfferStatsStore.recordVisible(offer.fingerprint) }
                if (!verdict.accept) {
                    diagnostics.record(DiagnosticStage.REJECTED, nowMs, verdict.reasons.firstOrNull()?.code?.name ?: "rule")
                    runCatching { OfferStatsStore.recordRejected(offer.fingerprint, verdict.reasons.map { it.code }) }
                }
                val acceptNode = parsed.acceptNode ?: continue
                diagnostics.record(DiagnosticStage.ACCEPT_NODE_FOUND, nowMs)
                // Learn only from a node that the parser verified through a matching label.
                if (packageName == JEENY_PACKAGE && acceptNode.viewId != null && hasMatchingAcceptLabel(acceptNode)) {
                    jeenyHints?.learnVerified(acceptNode.viewId)
                }
                val display = resources.displayMetrics
                if (verdict.accept && ServicesSafetyPolicy.hasSafeGestureBounds(acceptNode.bounds, display.widthPixels, display.heightPixels) && clickGate.tryAcquire(offer.fingerprint, nowMs)) {
                    val clickResult = dispatchAcceptClick(rootNode, packageName, acceptNode)
                    diagnostics.record(DiagnosticStage.CLICK_PATH, nowMs, clickResult.name.lowercase())
                    if (clickResult == ClickResult.NOT_DISPATCHED) {
                        clickGate.release(offer.fingerprint, nowMs)
                        Log.i(TAG, "accept dispatch rejected by safety gate")
                    } else {
                        runCatching { OfferDecisionLogStore.recordOutcome(offer.profileId, if (clickResult == ClickResult.SEMANTIC) DecisionLogOutcome.SEMANTIC_CLICK_SENT else DecisionLogOutcome.GESTURE_CLICK_SENT) }
                        acceptTracker.tapped(offer.fingerprint, offer.profileId, SystemClock.elapsedRealtime())
                        liveness.click(SystemClock.elapsedRealtime())
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.markActionPending()
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.remember(offer.fingerprint)
                        pendingHistoryProfileId = offer.profileId
                        pendingVoiceDetails = offer.fare to offer.pickupKm
                        Log.i(TAG, "accept dispatched; confirmation pending")
                    }
                }
                return true
            }
        } catch (_: Exception) {
            Log.i(TAG, "supported app tree rejected or exceeded safety budget")
        } finally {
            rootNode.recycle()
        }
        return false
    }

    private fun hasMatchingAcceptLabel(target: ScreenNode): Boolean {
        var found = false
        target.walk { node ->
            val label = Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }
            if (label == "accept" || label.contains("قبول") || label.contains("accept")) found = true
        }
        return found
    }

    private enum class ClickResult { SEMANTIC, GESTURE, NOT_DISPATCHED }

    /**
     * Re-resolve the exact candidate from this event's live tree using its child path.
     * A successful ACTION_CLICK is never followed by a gesture: it is not proof of ride
     * acceptance, but retrying could accept twice. Gesture fallback is only for an
     * explicit ACTION_CLICK=false response from a still-matching live node.
     */
    private fun dispatchAcceptClick(root: AccessibilityNodeInfo, packageName: String, target: ScreenNode): ClickResult {
        if (!ServicesSafetyPolicy.canProcessOffers(Settings.isActive, Settings.a11yDisclosureAccepted, SupabaseClient.hasValidLicense())) return ClickResult.NOT_DISPATCHED
        val exact = resolveLiveNode(root, target)
        val exactResult = exact?.let { node ->
            try {
                if (isSafeClickable(node, root, packageName)) {
                    if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) return ClickResult.SEMANTIC
                }
            } finally { node.recycle() }
            null
        }
        // A labelled child may be exposed while its clickable parent owns ACTION_CLICK.
        val ancestor = resolveLiveClickableAncestor(root, target)
        if (ancestor != null) {
            try {
                if (isSafeClickable(ancestor, root, packageName) &&
                    runCatching { ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                    return ClickResult.SEMANTIC
                }
            } finally { ancestor.recycle() }
        }
        // Coordinate fallback is last and uses the verified live target bounds only.
        if (!ServicesSafetyPolicy.hasSafeGestureBounds(target.bounds, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)) return ClickResult.NOT_DISPATCHED
        return if (performGesture(target.bounds)) ClickResult.GESTURE else ClickResult.NOT_DISPATCHED
    }

    private fun isSafeClickable(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo, packageName: String): Boolean =
        root.packageName?.toString() == packageName && node.packageName?.toString() == packageName &&
            node.isVisibleToUser && node.isEnabled && node.isClickable &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
            node.boundsInScreen().let { rect ->
                ServicesSafetyPolicy.hasSafeGestureBounds(
                    Bounds(rect.left, rect.top, rect.right, rect.bottom),
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels,
                )
            }

    private fun AccessibilityNodeInfo.boundsInScreen(): Rect = Rect().also { getBoundsInScreen(it) }

    private fun resolveLiveClickableAncestor(root: AccessibilityNodeInfo, target: ScreenNode): AccessibilityNodeInfo? {
        if (target.nodePath.isEmpty()) return null
        var current = root
        var owned = false
        try {
            for (index in target.nodePath.dropLast(1)) {
                if (index !in 0 until current.childCount) return null
                val child = current.getChild(index) ?: return null
                if (owned) current.recycle()
                current = child
                owned = true
            }
            val bounds = target.bounds
            if (!current.isClickable || current.packageName?.toString() != root.packageName?.toString()) return null
            val rect = current.boundsInScreen()
            if (rect.left > bounds.left || rect.top > bounds.top || rect.right < bounds.right || rect.bottom < bounds.bottom) return null
            return current.also { owned = false }
        } finally { if (owned) current.recycle() }
    }

    /** The returned node is a temporary child owned by this method's caller. */
    private fun resolveLiveNode(root: AccessibilityNodeInfo, target: ScreenNode): AccessibilityNodeInfo? {
        if (target.nodePath.isEmpty()) return null
        var current = root
        var owned = false
        try {
            for (index in target.nodePath) {
                if (index !in 0 until current.childCount) return null
                val child = current.getChild(index) ?: return null
                if (owned) current.recycle()
                current = child
                owned = true
            }
            val rect = Rect()
            current.getBoundsInScreen(rect)
            val sameLabel = Text.normalize(current.text?.toString()).ifEmpty {
                Text.normalize(current.contentDescription?.toString())
            } == Text.normalize(target.text).ifEmpty { Text.normalize(target.description) }
            if (current.viewIdResourceName != target.viewId || !sameLabel ||
                Bounds(rect.left, rect.top, rect.right, rect.bottom) != target.bounds) return null
            return current.also { owned = false }
        } finally {
            if (owned) current.recycle()
        }
    }

    private fun performGesture(bounds: Bounds): Boolean {
        if (!ServicesSafetyPolicy.canProcessOffers(Settings.isActive,
                Settings.a11yDisclosureAccepted, SupabaseClient.hasValidLicense())) return false
        val path = Path().apply {
            moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 10, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        liveness.pause("interrupted")
        diagnostics.record(DiagnosticStage.WATCHDOG, SystemClock.elapsedRealtime(), "interrupted")
    }

    /** Privacy-safe metadata for a UI or local log; no screen content is included. */
    fun livenessSnapshot(nowMs: Long = SystemClock.elapsedRealtime()): LivenessSnapshot = liveness.snapshot(nowMs).also {
        if (it.stale) diagnostics.record(DiagnosticStage.WATCHDOG, nowMs, "stale")
    }

    fun diagnosticSnapshot(): List<DiagnosticEntry> = diagnostics.snapshot()

    override fun onDestroy() {
        lateReadGeneration++
        lateReadHandler.removeCallbacksAndMessages(LATE_READ_TOKEN)
        jeenyPipeline.reset()
        lastJeenyWindowId = null
        clickGate.reset()
        pendingHistoryProfileId = null
        pendingVoiceDetails = null
        parsersByPackage = emptyMap()
        adaptersByProfile = emptyMap()
        liveness.pause("service_destroyed")
        super.onDestroy()
    }

    private fun AccessibilityNodeInfo.toScreenNode(
        depth: Int = 0,
        budget: NodeBudget = NodeBudget(),
        nodePath: List<Int> = emptyList(),
    ): ScreenNode {
        check(++budget.visited <= MAX_TOTAL_NODES) { "Accessibility tree exceeds safe budget" }
        val rect = Rect()
        this.getBoundsInScreen(rect)
        val b = Bounds(rect.left, rect.top, rect.right, rect.bottom)

        val children = ArrayList<ScreenNode>(minOf(childCount, MAX_CHILDREN_PER_NODE))
        if (depth < MAX_TREE_DEPTH) {
            for (i in 0 until minOf(childCount, MAX_CHILDREN_PER_NODE)) {
                val child = getChild(i) ?: continue
                try {
                    children += child.toScreenNode(depth + 1, budget, nodePath + i)
                } finally {
                    child.recycle()
                }
            }
        }

        return ScreenNode(
            id = this.hashCode(),
            text = this.text?.toString(),
            description = this.contentDescription?.toString(),
            viewId = this.viewIdResourceName,
            className = this.className?.toString(),
            clickable = this.isClickable,
            bounds = b,
            children = children,
            nodePath = nodePath
        )
    }

    private class NodeBudget(var visited: Int = 0)

    private companion object {
        const val TAG = "SabbaqA11y"
        const val MAX_TREE_DEPTH = 60
        const val MAX_CHILDREN_PER_NODE = 200
        const val MAX_TOTAL_NODES = 3000
        const val JEENY_PACKAGE = "me.com.easytaxista"
        const val JEENY_EVENT_THROTTLE_MS = 25L
        val LATE_READ_DELAYS_MS = longArrayOf(120L, 420L)
        val LATE_READ_TOKEN = Any()
    }
}

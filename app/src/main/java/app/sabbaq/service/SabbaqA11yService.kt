package app.sabbaq.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import app.sabbaq.notifications.NotificationCenter
import app.sabbaq.stats.DecisionLogOutcome
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.stats.OfferStatsStore
import app.sabbaq.telemetry.TelemetryRepository
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
    private val runtimeStatus = RuntimeStatusRepository
    private val dispatchRetry = BoundedDispatchRetry()
    private var lastJeenyFingerprint: String? = null
    private var lastJeenyEventMs = 0L
    private var lastJeenyWindowId: Int? = null
    private var profileRevision = -1
    private var firstSeenFingerprint: String? = null
    private var firstSeenAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        liveness.pause(null)
        runtimeStatus.lifecycle(Settings.isActive, null)
        jeenyHints = runCatching { JeenyAcceptIdStore(applicationContext) }.getOrNull()
        runCatching { OfferStatsStore.init(applicationContext) }
            .onFailure { Log.w(TAG, "تعذر تهيئة الإحصاءات المحلية") }
        runCatching { OfferDecisionLogStore.init(applicationContext) }
            .onFailure { Log.w(TAG, "تعذر تهيئة سجل القرارات المحلي") }
        TelemetryRepository.serviceConnected = true
        LiveAcceptTest.onArmChanged = { lateReadHandler.post { applyPackageFilter() } }
        loadProfiles()
        TelemetryRepository.trace(
            TelemetryStage.SERVICE_CONNECTED,
            reasonCode = if (parsersByPackage.isEmpty()) "no_profiles" else "ready",
            detail = "packages=${parsersByPackage.size},windows=${if (canReadWindows()) 1 else 0}",
        )
    }

    /** Builds parsers from the profiles in effect; re-run when the administration publishes new ones. */
    private fun loadProfiles() {
        profileRevision = ProfileRepository.revision
        parsersByPackage = runCatching {
            val profiles = ProfileRepository.current().filter { it.enabled }
            adaptersByProfile = profiles.associate { it.id to RideAppAdapters.forProfile(it) }
            profiles
                .asSequence()
                .flatMap { profile ->
                    profile.packages.asSequence()
                        .filter { it.isNotBlank() }
                        .map { packageName ->
                            val hints = if (packageName == JEENY_PACKAGE) jeenyHints?.snapshot().orEmpty() else emptySet()
                            packageName to adaptersByProfile.getValue(profile.id).parser(hints)
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
        applyPackageFilter()
    }

    /**
     * Narrows the events Android delivers to the packages of the profiles in effect, so a package
     * the administration adds over the air takes effect without a release (the manifest list is
     * only the starting point), plus Sabbaq's own window while a live acceptance test runs. An
     * empty set would mean "every app", so it is never applied.
     */
    private fun applyPackageFilter() {
        val info = serviceInfo ?: return
        val packages = parsersByPackage.keys +
            (if (LiveAcceptTest.isArmed()) setOf(applicationContext.packageName) else emptySet())
        if (packages.isEmpty()) return
        val wanted = packages.sorted().toTypedArray()
        if (info.packageNames?.sorted()?.toTypedArray()?.contentEquals(wanted) == true) return
        info.packageNames = wanted
        runCatching { serviceInfo = info }.onFailure { Log.w(TAG, "تعذر تحديث تطبيقات المتابعة") }
    }

    private fun canReadWindows(): Boolean =
        serviceInfo?.let { (it.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0 } == true

    private fun providerOf(packageName: String?): String =
        packageName?.let { parsersByPackage[it]?.firstOrNull()?.profileId } ?: SERVICE_PROVIDER

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventNow = SystemClock.elapsedRealtime()
        if (profileRevision != ProfileRepository.revision) loadProfiles()
        liveness.accessibilityEvent(eventNow)
        runtimeStatus.event(eventNow)
        val eventPackage = event.packageName?.toString()
        val provider = providerOf(eventPackage)
        recordDiagnostic(DiagnosticStage.EVENT_RECEIVED, eventNow, provider = provider)
        if (!Settings.a11yDisclosureAccepted) {
            liveness.pause("disclosure_required")
            runtimeStatus.lifecycle(false, "disclosure_required")
            Settings.isActive = false
            recordDiagnostic(DiagnosticStage.GATE_BLOCKED, eventNow, "disclosure_required", provider = provider)
            return
        }
        // The live acceptance test runs on Sabbaq's own window, with or without monitoring on.
        if (eventPackage == applicationContext.packageName) {
            if (LiveAcceptTest.isArmed(eventNow)) handleLiveAcceptTest(eventNow)
            return
        }
        if (!Settings.isActive) {
            liveness.pause("service_paused")
            runtimeStatus.lifecycle(false, "service_paused")
            recordDiagnostic(DiagnosticStage.GATE_BLOCKED, eventNow, "service_paused", provider = provider)
            return
        }
        if (!ServicesSafetyPolicy.isRelevantAccessibilityEvent(event.eventType)) {
            recordDiagnostic(DiagnosticStage.GATE_BLOCKED, eventNow, "event_type", provider = provider)
            return
        }
        if (!SupabaseClient.hasValidLicense()) {
            liveness.pause("license_expired")
            runtimeStatus.lifecycle(false, "license_expired")
            Settings.isActive = false
            recordDiagnostic(DiagnosticStage.GATE_BLOCKED, eventNow, "license_invalid", provider = provider)
            return
        }
        val packageName = eventPackage ?: run {
            recordDiagnostic(DiagnosticStage.GATE_BLOCKED, eventNow, "no_package")
            return
        }
        if (!ServicesSafetyPolicy.isSupportedPackage(packageName, parsersByPackage.keys)) {
            recordDiagnostic(DiagnosticStage.GATE_BLOCKED, eventNow,
                if (parsersByPackage.isEmpty()) "no_profiles" else "package_unsupported")
            return
        }
        recordDiagnostic(DiagnosticStage.PACKAGE_SEEN, eventNow, provider = provider)
        if (packageName == JEENY_PACKAGE) {
            liveness.targetEvent(eventNow)
            handleJeenyEvent(event)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val previous = lastParseTimeMs.takeIf { lastParsedPackage == packageName }
        if (!ServicesSafetyPolicy.shouldParse(event.eventType, now, previous)) {
            recordDiagnostic(DiagnosticStage.STALE, now, "throttled", provider = provider)
            return
        }
        processPackage(packageName)
    }

    /**
     * One step of the live acceptance test: the real live-tree read, the parser with the Jeeny
     * rules in effect, the real tap path and confirmation detection, on the mock offer that
     * Sabbaq draws. It never touches the production pipeline state, statistics or history.
     */
    private fun handleLiveAcceptTest(now: Long) {
        LiveAcceptTest.reach(LiveAcceptProbe.Step.EVENT, now)
        val own = applicationContext.packageName
        val (root, _) = targetRoot(own)
        if (root == null) return
        try {
            val screen = root.toScreenNode()
            LiveAcceptTest.reach(LiveAcceptProbe.Step.READ)
            val profile = LiveAcceptProbe.profile(ProfileRepository.current().firstOrNull { it.id == JEENY_PROVIDER }, own)
            val parsed = OfferParser(profile).parse(screen)
            if (parsed.confirmed) {
                LiveAcceptTest.reach(LiveAcceptProbe.Step.CONFIRMED)
                return
            }
            val offer = parsed.offer
            if (offer?.fare != null && offer.pickupKm != null) LiveAcceptTest.reach(LiveAcceptProbe.Step.PARSED)
            val accept = parsed.acceptNode ?: return
            LiveAcceptTest.reach(LiveAcceptProbe.Step.ACCEPT_FOUND)
            if (LiveAcceptProbe.Step.PARSED !in LiveAcceptTest.state.value.reached || LiveAcceptTest.hasDispatched()) return
            when (dispatchAcceptClick(root, own, accept, liveTest = true)) {
                ClickResult.SEMANTIC -> LiveAcceptTest.reach(LiveAcceptProbe.Step.DISPATCHED, method = LiveAcceptProbe.Method.SEMANTIC)
                ClickResult.GESTURE -> LiveAcceptTest.reach(LiveAcceptProbe.Step.DISPATCHED, method = LiveAcceptProbe.Method.GESTURE)
                ClickResult.NOT_DISPATCHED -> LiveAcceptTest.refuseDispatch()
            }
        } catch (_: Exception) {
            Log.i(TAG, "live acceptance test frame rejected")
        } finally {
            root.recycle()
        }
    }

    /** Jeeny has its own cheap signal -> full frame -> two late capture pipeline. */
    private fun handleJeenyEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now >= lastJeenyEventMs && now - lastJeenyEventMs < JEENY_EVENT_THROTTLE_MS) {
            recordDiagnostic(DiagnosticStage.STALE, now, "throttled", provider = JEENY_PROVIDER)
            return
        }
        lastJeenyEventMs = now
        val previousWindowId = lastJeenyWindowId
        // ClickGate (20 s per offer) and AcceptTracker (5 s) are already time-bounded. Resetting
        // them here re-armed a second tap on the same offer whenever events alternated between
        // the offer overlay and the main window, and dropped the confirmation, which Jeeny shows
        // in a different window from the offer.
        if (previousWindowId != null && previousWindowId != event.windowId) {
            dispatchRetry.onConfirmationOrWindowChange()
        }
        lastJeenyWindowId = event.windowId
        val token = jeenyPipeline.begin(JEENY_PACKAGE, event.windowId.toString())
        jeenyPipeline.beginBurst()
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
        val provider = providerOf(packageName)
        val parsers = parsersByPackage[packageName] ?: run {
            recordDiagnostic(DiagnosticStage.GATE_BLOCKED, SystemClock.elapsedRealtime(), "no_parser", provider = provider)
            return false
        }
        val parseTimeMs = SystemClock.elapsedRealtime()
        val (rootNode, rootSource) = targetRoot(packageName)
        if (rootNode == null) {
            recordDiagnostic(DiagnosticStage.ROOT_UNAVAILABLE, parseTimeMs, rootSource, provider = provider)
            return false
        }
        if (rootSource == ROOT_FROM_WINDOW_LIST) {
            recordDiagnostic(DiagnosticStage.PACKAGE_SEEN, parseTimeMs, "overlay_window", provider = provider)
        }
        try {
            lastParsedPackage = packageName
            lastParseTimeMs = parseTimeMs
            val screenNode = rootNode.toScreenNode()
            if (packageName == JEENY_PACKAGE && requireJeenySignal) {
                val fingerprint = ScreenChangeFingerprint.compute(packageName, lastJeenyWindowId?.toString(), screenNode)
                if (fingerprint == lastJeenyFingerprint) {
                    recordDiagnostic(DiagnosticStage.STALE, parseTimeMs, "unchanged_frame", provider = provider)
                    return false
                }
                lastJeenyFingerprint = fingerprint
            }
            for (parser in parsers) {
                val adapter = adaptersByProfile[parser.profileId] ?: run {
                    recordDiagnostic(DiagnosticStage.GATE_BLOCKED, parseTimeMs, "no_adapter", provider = provider)
                    return false
                }
                val hints = if (packageName == JEENY_PACKAGE) jeenyHints?.snapshot()?.toSet().orEmpty() else emptySet()
                val assessment = adapter.classify(screenNode, hints)
                if (assessment?.classification == JeenyFrameClass.INCOMPLETE) {
                    recordDiagnostic(DiagnosticStage.FRAME_INCOMPLETE, parseTimeMs, "bounded_preflight", assessment?.flags, provider)
                }
                val activeParser = if (packageName == JEENY_PACKAGE) {
                    adapter.parser(hints)
                } else adapter.parser()
                if (requireJeenySignal && !jeenyPipeline.hasActionPending() && activeParser.probeOfferSignal(screenNode) == null) {
                    val frameClass = assessment?.classification
                    val reason = when (frameClass) {
                        null -> "preflight"
                        JeenyFrameClass.OFFER -> "no_accept_control"
                        else -> "cls_" + frameClass?.name?.lowercase()
                    }
                    recordDiagnostic(DiagnosticStage.SIGNAL_MISS, parseTimeMs, reason, assessment?.flags, provider)
                    return false
                }
                if (requireJeenySignal) recordDiagnostic(DiagnosticStage.SIGNAL_FOUND, parseTimeMs, detail = assessment?.flags, provider = provider)
                val parsed = activeParser.parse(screenNode)
                val nowMs = SystemClock.elapsedRealtime()
                val acceptOutcome = acceptTracker.observe(activeParser.profileId.takeIf { parsed.confirmed }, nowMs)
                when (acceptOutcome) {
                    is AcceptTracker.Outcome.Confirmed -> {
                        dispatchRetry.onConfirmationOrWindowChange()
                        recordDiagnostic(DiagnosticStage.CONFIRMATION_CONFIRMED, nowMs)
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.markConfirmationResolved()
                        pendingHistoryProfileId?.let { profileId -> runCatching { OfferDecisionLogStore.recordOutcome(profileId, DecisionLogOutcome.ACCEPTED_CONFIRMED) } }
                        runCatching { OfferStatsStore.recordAcceptedConfirmed() }
                        runCatching { NotificationCenter.postAcceptedRide(pendingVoiceDetails?.first, pendingVoiceDetails?.second) }
                        pendingVoiceDetails?.let { (fare, pickupKm) -> OfferEvents.accepted(fare, pickupKm) }
                        pendingVoiceDetails = null
                    }
                    is AcceptTracker.Outcome.NotConfirmed -> {
                        dispatchRetry.onConfirmationOrWindowChange()
                        recordDiagnostic(DiagnosticStage.CONFIRMATION_TIMEOUT, nowMs)
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.markConfirmationResolved()
                        pendingHistoryProfileId?.let { profileId -> runCatching { OfferDecisionLogStore.recordOutcome(profileId, DecisionLogOutcome.CONFIRMATION_TIMEOUT) } }
                        Log.i(TAG, "offer confirmation timeout")
                        pendingVoiceDetails = null
                    }
                    null -> Unit
                }
                if (acceptOutcome != null) pendingHistoryProfileId = null
                if (parsed.confirmed) continue
                val offer = parsed.offer ?: run {
                    if (requireJeenySignal || assessment != null) {
                        recordDiagnostic(DiagnosticStage.OFFER_NOT_PARSED, nowMs, "parser_empty", assessment?.flags, provider)
                    }
                    null
                } ?: continue
                liveness.offerDetected(nowMs)
                runtimeStatus.offer(nowMs)
                if (offer.fingerprint != firstSeenFingerprint) {
                    firstSeenFingerprint = offer.fingerprint
                    firstSeenAtMs = parseTimeMs
                }
                recordDiagnostic(
                    if (offer.fare == null) DiagnosticStage.FARE_MISSING else DiagnosticStage.OFFER_DETECTED, nowMs,
                    if (offer.fare == null) "fare_missing" else "complete", assessment?.flags, provider,
                )
                if (packageName == JEENY_PACKAGE && !jeenyPipeline.shouldProcess(offer.fingerprint)) {
                    Log.i(TAG, "jeeny frame skipped: same-offer")
                    recordDiagnostic(DiagnosticStage.STALE, nowMs, "same_offer", provider = provider)
                    return false
                }
                val verdict = OfferEvaluator.evaluate(offer, Settings.filters, Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) })
                runCatching { OfferDecisionLogStore.recordDecision(offer.fingerprint, offer.profileId, if (verdict.accept) emptyList() else verdict.reasons.map { it.code }) }
                liveness.decision(nowMs)
                runtimeStatus.decision(nowMs, verdict.report)
                runCatching { OfferStatsStore.recordVisible(offer.fingerprint) }
                if (!verdict.accept) {
                    recordDiagnostic(DiagnosticStage.REJECTED, nowMs, verdict.reasons.firstOrNull()?.code?.name ?: "rule", provider = provider)
                    runCatching { OfferStatsStore.recordRejected(offer.fingerprint, verdict.reasons.map { it.code }) }
                } else {
                    TelemetryRepository.trace(TelemetryStage.DECISION_ACCEPTED, "rules_passed", provider = provider, score = verdict.report.score)
                }
                val acceptNode = parsed.acceptNode ?: run {
                    recordDiagnostic(DiagnosticStage.ACCEPT_NODE_FOUND, nowMs, "missing", provider = provider)
                    null
                } ?: continue
                recordDiagnostic(DiagnosticStage.ACCEPT_NODE_FOUND, nowMs, "found", provider = provider)
                // Learn only from a node that the parser verified through a matching label.
                if (packageName == JEENY_PACKAGE && acceptNode.viewId != null && hasMatchingAcceptLabel(acceptNode)) {
                    jeenyHints?.learnVerified(acceptNode.viewId)
                }
                val display = resources.displayMetrics
                val windowToken = lastJeenyWindowId?.toString() ?: packageName
                dispatchRetry.begin(offer.fingerprint, windowToken)
                val acquired = clickGate.tryAcquire(offer.fingerprint, nowMs) ||
                    dispatchRetry.consumeRetry(offer.fingerprint, windowToken, nowMs)
                val safeBounds = ServicesSafetyPolicy.hasSafeGestureBounds(acceptNode.bounds, display.widthPixels, display.heightPixels)
                if (verdict.accept && (!safeBounds || !acquired)) {
                    recordDiagnostic(DiagnosticStage.CLICK_PATH, nowMs, if (!safeBounds) "unsafe_bounds" else "gate_suppressed", provider = provider)
                }
                if (verdict.accept && safeBounds && acquired) {
                    val clickResult = dispatchAcceptClick(rootNode, packageName, acceptNode)
                    // Offer first seen -> tap dispatched: the number that decides whether another
                    // driver's app wins the same request.
                    val responseMs = (SystemClock.elapsedRealtime() - firstSeenAtMs).takeIf { firstSeenFingerprint == offer.fingerprint && it >= 0 }
                    recordDiagnostic(DiagnosticStage.CLICK_PATH, nowMs, clickResult.name.lowercase(), provider = provider, latencyMs = responseMs)
                    if (clickResult == ClickResult.NOT_DISPATCHED) {
                        clickGate.release(offer.fingerprint, nowMs)
                        dispatchRetry.onDispatchResult(DispatchFailure.NOT_DISPATCHED, nowMs)
                        Log.i(TAG, "accept dispatch rejected by safety gate")
                    } else {
                        dispatchRetry.onDispatchResult(if (clickResult == ClickResult.SEMANTIC) DispatchFailure.SEMANTIC_SUCCESS else DispatchFailure.GESTURE_SUCCESS, nowMs)
                        runCatching { OfferDecisionLogStore.recordOutcome(offer.profileId, if (clickResult == ClickResult.SEMANTIC) DecisionLogOutcome.SEMANTIC_CLICK_SENT else DecisionLogOutcome.GESTURE_CLICK_SENT) }
                        acceptTracker.tapped(offer.fingerprint, offer.profileId, SystemClock.elapsedRealtime())
                        liveness.click(SystemClock.elapsedRealtime())
                        runtimeStatus.click(SystemClock.elapsedRealtime())
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.markActionPending()
                        if (packageName == JEENY_PACKAGE) jeenyPipeline.remember(offer.fingerprint)
                        pendingHistoryProfileId = offer.profileId
                        pendingVoiceDetails = offer.fare to offer.pickupKm
                        Log.i(TAG, "accept dispatched; confirmation pending")
                    }
                }
                return true
            }
        } catch (error: Exception) {
            Log.i(TAG, "supported app tree rejected or exceeded safety budget")
            val reason = if (error is IllegalStateException && error.message == TREE_BUDGET_MESSAGE) "tree_budget"
                else error.javaClass.simpleName.lowercase().ifBlank { "unknown" }.take(40)
            recordDiagnostic(DiagnosticStage.TREE_REJECTED, SystemClock.elapsedRealtime(), reason, provider = provider)
        } finally {
            rootNode.recycle()
        }
        return false
    }

    /**
     * Jeeny can show an offer in an overlay or dialog that is not the focused window, in which
     * case [rootInActiveWindow] belongs to another app. Falls back to the interactive window list
     * (FLAG_RETRIEVE_INTERACTIVE_WINDOWS) and returns the root plus where it came from, or the
     * reason none was found as a coarse category that never names a third-party app.
     */
    @Suppress("DEPRECATION")
    private fun targetRoot(packageName: String): Pair<AccessibilityNodeInfo?, String> {
        val active = rootInActiveWindow
        val activePackage = active?.packageName?.toString()
        if (active != null && activePackage == packageName) return active to ROOT_FROM_ACTIVE
        active?.recycle()
        val windowList = runCatching { windows }.getOrNull().orEmpty()
        try {
            for (window in windowList) {
                val root = runCatching { window.root }.getOrNull() ?: continue
                if (root.packageName?.toString() == packageName) return root to ROOT_FROM_WINDOW_LIST
                root.recycle()
            }
        } finally {
            windowList.forEach { runCatching { it.recycle() } }
        }
        return null to when {
            active == null -> "active_null"
            windowList.isEmpty() -> "active_" + packageCategory(activePackage) + "_no_windows"
            else -> "active_" + packageCategory(activePackage)
        }
    }

    private fun packageCategory(packageName: String?): String = when {
        packageName == null -> "unnamed"
        packageName == applicationContext.packageName -> "sabbaq"
        packageName == "com.android.systemui" -> "systemui"
        packageName in parsersByPackage.keys -> "supported"
        packageName.contains("launcher") -> "launcher"
        else -> "other"
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
    private fun dispatchAcceptClick(root: AccessibilityNodeInfo, packageName: String, target: ScreenNode, liveTest: Boolean = false): ClickResult {
        // The live test taps only Sabbaq's own mock button, so it skips the monitoring and licence gate.
        if (!liveTest && !ServicesSafetyPolicy.canProcessOffers(Settings.isActive, Settings.a11yDisclosureAccepted, SupabaseClient.hasValidLicense())) return ClickResult.NOT_DISPATCHED
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
        runtimeStatus.lifecycle(false, "interrupted")
        recordDiagnostic(DiagnosticStage.WATCHDOG, SystemClock.elapsedRealtime(), "interrupted")
        TelemetryRepository.trace(TelemetryStage.SERVICE_INTERRUPTED, "interrupted")
    }

    /** Privacy-safe metadata for a UI or local log; no screen content is included. */
    fun livenessSnapshot(nowMs: Long = SystemClock.elapsedRealtime()): LivenessSnapshot = liveness.snapshot(nowMs).also {
        if (it.stale) {
            recordDiagnostic(DiagnosticStage.WATCHDOG, nowMs, "stale")
            runtimeStatus.lifecycle(false, "stale")
        }
    }

    fun diagnosticSnapshot(): List<DiagnosticEntry> = diagnostics.snapshot()

    private fun recordDiagnostic(
        stage: DiagnosticStage,
        nowMs: Long,
        reason: String? = null,
        detail: String? = null,
        provider: String = SERVICE_PROVIDER,
        latencyMs: Long? = null,
    ) {
        // Every event is counted by telemetry; keeping them out of the 12-slot local ring
        // stops "last stages" on the dashboard from showing nothing but EVENT_RECEIVED.
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
        TelemetryRepository.trace(TelemetryStage.SERVICE_DESTROYED, "destroyed")
        TelemetryRepository.flushNow()
        lateReadGeneration++
        lateReadHandler.removeCallbacksAndMessages(LATE_READ_TOKEN)
        jeenyPipeline.reset()
        lastJeenyWindowId = null
        clickGate.reset()
        pendingHistoryProfileId = null
        pendingVoiceDetails = null
        dispatchRetry.onConfirmationOrWindowChange()
        parsersByPackage = emptyMap()
        adaptersByProfile = emptyMap()
        liveness.pause("service_destroyed")
        runtimeStatus.reset()
        super.onDestroy()
    }

    private fun AccessibilityNodeInfo.toScreenNode(
        depth: Int = 0,
        budget: NodeBudget = NodeBudget(),
        nodePath: List<Int> = emptyList(),
    ): ScreenNode {
        check(++budget.visited <= MAX_TOTAL_NODES) { TREE_BUDGET_MESSAGE }
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
        const val TREE_BUDGET_MESSAGE = "Accessibility tree exceeds safe budget"
        const val SERVICE_PROVIDER = "sabbaq"
        const val JEENY_PROVIDER = "jeeny"
        const val ROOT_FROM_ACTIVE = "active"
        const val ROOT_FROM_WINDOW_LIST = "window_list"
        const val JEENY_PACKAGE = "me.com.easytaxista"
        // Map animations emit content changes continuously and each read walks up to 3000 nodes
        // on the service thread. 100 ms keeps that bounded; a card rendered inside the window is
        // still read by the late reads (120/420 ms) scheduled by the last processed event.
        const val JEENY_EVENT_THROTTLE_MS = 100L
        val LATE_READ_DELAYS_MS = longArrayOf(120L, 420L)
        val LATE_READ_TOKEN = Any()
    }
}

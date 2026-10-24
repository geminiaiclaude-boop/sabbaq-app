package app.sabbaq.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
    private var lastParsedPackage: String? = null
    private var lastParseTimeMs: Long? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { OfferStatsStore.init(applicationContext) }
            .onFailure { Log.w(TAG, "تعذر تهيئة الإحصاءات المحلية") }
        runCatching { OfferDecisionLogStore.init(applicationContext) }
            .onFailure { Log.w(TAG, "تعذر تهيئة سجل القرارات المحلي") }
        parsersByPackage = runCatching {
            Profiles.bundled()
                .asSequence()
                .filter { it.enabled }
                .flatMap { profile ->
                    profile.packages.asSequence()
                        .filter { it.isNotBlank() }
                        .map { packageName -> packageName to OfferParser(profile) }
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
        if (!Settings.a11yDisclosureAccepted) {
            Settings.isActive = false
            return
        }
        if (!Settings.isActive || !ServicesSafetyPolicy.isRelevantAccessibilityEvent(event.eventType)) return
        if (!SupabaseClient.hasValidLicense()) {
            Settings.isActive = false
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (!ServicesSafetyPolicy.isSupportedPackage(packageName, parsersByPackage.keys)) return
        val parsers = parsersByPackage[packageName] ?: return

        val parseTimeMs = SystemClock.elapsedRealtime()
        val previousParseMs = lastParseTimeMs.takeIf { lastParsedPackage == packageName }
        if (!ServicesSafetyPolicy.shouldParse(event.eventType, parseTimeMs, previousParseMs)) return

        val rootNode = rootInActiveWindow ?: return
        try {
            // قد تتغير النافذة النشطة بعد تسليم الحدث؛ لا تنفذ إيماءة على نافذة أخرى.
            if (rootNode.packageName?.toString() != packageName) return
            lastParsedPackage = packageName
            lastParseTimeMs = parseTimeMs

            val screenNode = rootNode.toScreenNode()
            for (parser in parsers) {
                val parsed = parser.parse(screenNode)
                val nowMs = SystemClock.elapsedRealtime()
                val acceptOutcome = acceptTracker.observe(
                    confirmedScreenForProfile = parser.profileId.takeIf { parsed.confirmed },
                    nowMs = nowMs,
                )
                when (acceptOutcome) {
                    is AcceptTracker.Outcome.Confirmed -> {
                        pendingHistoryProfileId?.let { profileId -> runCatching {
                            OfferDecisionLogStore.recordOutcome(profileId, DecisionLogOutcome.ACCEPTED_CONFIRMED)
                        } }
                        runCatching { OfferStatsStore.recordAcceptedConfirmed() }
                            .onFailure { Log.w(TAG, "تعذر تحديث الإحصاءات المحلية") }
                        pendingVoiceDetails?.let { (fare, pickupKm) -> OfferEvents.accepted(fare, pickupKm) }
                        pendingVoiceDetails = null
                    }
                    is AcceptTracker.Outcome.NotConfirmed -> {
                        pendingHistoryProfileId?.let { profileId -> runCatching {
                            OfferDecisionLogStore.recordOutcome(profileId, DecisionLogOutcome.CONFIRMATION_TIMEOUT)
                        } }
                        Log.i(TAG, "لم تظهر شاشة تأكيد القبول خلال المهلة")
                        pendingVoiceDetails = null
                    }
                    null -> Unit
                }
                if (acceptOutcome != null) pendingHistoryProfileId = null
                if (parsed.confirmed) continue

                parsed.offer?.let { offer ->
                    val cal = Calendar.getInstance()
                    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    val verdict = OfferEvaluator.evaluate(offer, Settings.filters, minuteOfDay)
                    runCatching {
                        OfferDecisionLogStore.recordDecision(
                            offer.fingerprint,
                            offer.profileId,
                            if (verdict.accept) emptyList() else verdict.reasons.map { it.code },
                        )
                    }.onFailure { Log.w(TAG, "تعذر تسجيل القرار محليًا") }
                    runCatching { OfferStatsStore.recordVisible(offer.fingerprint) }
                        .onFailure { Log.w(TAG, "تعذر تحديث الإحصاءات المحلية") }
                    if (!verdict.accept) {
                        runCatching {
                            OfferStatsStore.recordRejected(offer.fingerprint, verdict.reasons.map { it.code })
                        }.onFailure { Log.w(TAG, "تعذر تحديث الإحصاءات المحلية") }
                    }
                    val acceptNode = parsed.acceptNode
                    val display = resources.displayMetrics
                    if (
                        verdict.accept &&
                        acceptNode != null &&
                        ServicesSafetyPolicy.hasSafeGestureBounds(
                            acceptNode.bounds,
                            display.widthPixels,
                            display.heightPixels,
                        ) &&
                        clickGate.tryAcquire(offer.fingerprint, nowMs)
                    ) {
                        val clickResult = dispatchAcceptClick(rootNode, packageName, acceptNode)
                        if (clickResult == ClickResult.NOT_DISPATCHED) {
                            // A rejected dispatch did not tap the target; release only this
                            // offer so it can be reconsidered after the global safety interval.
                            clickGate.release(offer.fingerprint, nowMs)
                            Log.w(TAG, "تعذر إرسال إجراء القبول")
                        } else {
                            runCatching {
                                OfferDecisionLogStore.recordOutcome(
                                    offer.profileId,
                                    if (clickResult == ClickResult.SEMANTIC) DecisionLogOutcome.SEMANTIC_CLICK_SENT
                                    else DecisionLogOutcome.GESTURE_CLICK_SENT,
                                )
                            }
                            acceptTracker.tapped(offer.fingerprint, offer.profileId, SystemClock.elapsedRealtime())
                            pendingHistoryProfileId = offer.profileId
                            pendingVoiceDetails = offer.fare to offer.pickupKm
                            Log.i(TAG, if (clickResult == ClickResult.SEMANTIC) "تم إرسال إجراء القبول الدلالي؛ بانتظار تأكيد التطبيق" else "تم إرسال إيماءة القبول؛ بانتظار تأكيد التطبيق")
                        }
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "تعذر تحليل واجهة تطبيق مدعوم")
        } finally {
            rootNode.recycle()
        }
    }

    private enum class ClickResult { SEMANTIC, GESTURE, NOT_DISPATCHED }

    /**
     * Re-resolve the exact candidate from this event's live tree using its child path.
     * A successful ACTION_CLICK is never followed by a gesture: it is not proof of ride
     * acceptance, but retrying could accept twice. Gesture fallback is only for an
     * explicit ACTION_CLICK=false response from a still-matching live node.
     */
    private fun dispatchAcceptClick(root: AccessibilityNodeInfo, packageName: String, target: ScreenNode): ClickResult {
        if (!ServicesSafetyPolicy.canProcessOffers(Settings.isActive,
                Settings.a11yDisclosureAccepted, SupabaseClient.hasValidLicense())) return ClickResult.NOT_DISPATCHED
        val liveNode = resolveLiveNode(root, target) ?: return ClickResult.NOT_DISPATCHED
        try {
            if (root.packageName?.toString() != packageName ||
                liveNode.packageName?.toString() != packageName ||
                !liveNode.isVisibleToUser || !liveNode.isEnabled || !liveNode.isClickable ||
                liveNode.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                return ClickResult.NOT_DISPATCHED
            }
            val actionResult = runCatching { liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                .getOrElse { return ClickResult.NOT_DISPATCHED }
            if (actionResult) return ClickResult.SEMANTIC
            if (!ServicesSafetyPolicy.hasSafeGestureBounds(
                    target.bounds, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
                )) return ClickResult.NOT_DISPATCHED
            return if (performGesture(target.bounds)) ClickResult.GESTURE else ClickResult.NOT_DISPATCHED
        } finally {
            liveNode.recycle()
        }
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        clickGate.reset()
        pendingHistoryProfileId = null
        pendingVoiceDetails = null
        parsersByPackage = emptyMap()
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
    }
}

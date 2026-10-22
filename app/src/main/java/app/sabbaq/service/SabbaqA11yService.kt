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
import app.sabbaq.voice.OfferEvents
import java.math.BigDecimal
import java.util.Calendar

class SabbaqA11yService : AccessibilityService() {

    private val clickGate = ClickGate(perOfferSuppressMs = 20000, minIntervalMs = 500)
    private val acceptTracker = AcceptTracker(confirmWindowMs = 5000)
    private var pendingVoiceDetails: Pair<BigDecimal?, BigDecimal?>? = null
    private var parsersByPackage: Map<String, List<OfferParser>> = emptyMap()
    private var lastParsedPackage: String? = null
    private var lastParseTimeMs: Long? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
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
                when (val outcome = acceptTracker.observe(
                    confirmedScreenForProfile = parser.profileId.takeIf { parsed.confirmed },
                    nowMs = nowMs,
                )) {
                    is AcceptTracker.Outcome.Confirmed -> {
                        pendingVoiceDetails?.let { (fare, pickupKm) -> OfferEvents.accepted(fare, pickupKm) }
                        pendingVoiceDetails = null
                    }
                    is AcceptTracker.Outcome.NotConfirmed -> {
                        Log.i(TAG, "لم تظهر شاشة التأكيد بعد إرسال الإيماءة")
                        pendingVoiceDetails = null
                    }
                    null -> Unit
                }
                if (parsed.confirmed) continue

                parsed.offer?.let { offer ->
                    val cal = Calendar.getInstance()
                    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    val verdict = OfferEvaluator.evaluate(offer, Settings.filters, minuteOfDay)
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
                        if (!performClick(acceptNode.bounds)) {
                            Log.w(TAG, "رفض النظام تنفيذ إيماءة القبول")
                        } else {
                            acceptTracker.tapped(offer.fingerprint, offer.profileId, SystemClock.elapsedRealtime())
                            pendingVoiceDetails = offer.fare to offer.pickupKm
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

    private fun performClick(bounds: Bounds): Boolean {
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
        parsersByPackage = emptyMap()
        super.onDestroy()
    }

    private fun AccessibilityNodeInfo.toScreenNode(depth: Int = 0, budget: NodeBudget = NodeBudget()): ScreenNode {
        check(++budget.visited <= MAX_TOTAL_NODES) { "Accessibility tree exceeds safe budget" }
        val rect = Rect()
        this.getBoundsInScreen(rect)
        val b = Bounds(rect.left, rect.top, rect.right, rect.bottom)

        val children = ArrayList<ScreenNode>(minOf(childCount, MAX_CHILDREN_PER_NODE))
        if (depth < MAX_TREE_DEPTH) {
            for (i in 0 until minOf(childCount, MAX_CHILDREN_PER_NODE)) {
                val child = getChild(i) ?: continue
                try {
                    children += child.toScreenNode(depth + 1, budget)
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
            children = children
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

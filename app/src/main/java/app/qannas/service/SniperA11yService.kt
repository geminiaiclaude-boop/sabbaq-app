package app.qannas.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.qannas.Settings
import app.qannas.core.*
import java.util.Calendar

class SniperA11yService : AccessibilityService() {

    private lateinit var profiles: List<RideProfile>
    private val clickGate = ClickGate(perOfferSuppressMs = 20000, minIntervalMs = 500)

    override fun onServiceConnected() {
        super.onServiceConnected()
        profiles = Profiles.bundled()
        Log.d("SniperA11y", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!Settings.isActive) return

        val rootNode = rootInActiveWindow ?: return
        
        try {
            val screenNode = rootNode.toScreenNode()
            
            for (profile in profiles) {
                val parser = OfferParser(profile)
                val parsed = parser.parse(screenNode)

                parsed.offer?.let { offer ->
                    val cal = Calendar.getInstance()
                    val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    
                    val verdict = OfferEvaluator.evaluate(offer, Settings.filters, minuteOfDay)
                    Log.d("SniperA11y", "Offer: ${offer.fare} | Verdict: $verdict")
                    
                    if (verdict.accept && clickGate.tryAcquire(offer.fingerprint, System.currentTimeMillis())) {
                        parsed.acceptNode?.let { acceptNode ->
                            performClick(acceptNode.bounds)
                        }
                    }
                    return // We processed an offer, no need to check other profiles
                }
            }
        } catch (e: Exception) {
            Log.e("SniperA11y", "Parse error", e)
        } finally {
            rootNode.recycle()
        }
    }

    private fun performClick(bounds: Bounds) {
        val path = Path().apply {
            moveTo(bounds.centerX.toFloat(), bounds.centerY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 10, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d("SniperA11y", "Clicked at ${bounds.centerX}, ${bounds.centerY}")
    }

    override fun onInterrupt() {}

    private fun AccessibilityNodeInfo.toScreenNode(): ScreenNode {
        val rect = Rect()
        this.getBoundsInScreen(rect)
        val b = Bounds(rect.left, rect.top, rect.right, rect.bottom)
        
        val children = mutableListOf<ScreenNode>()
        for (i in 0 until this.childCount) {
            val child = this.getChild(i)
            if (child != null) {
                children.add(child.toScreenNode())
                child.recycle()
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
}

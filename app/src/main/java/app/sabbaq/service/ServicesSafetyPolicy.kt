package app.sabbaq.service

import android.view.accessibility.AccessibilityEvent
import app.sabbaq.core.Bounds

/**
 * قرارات حارسة لا تعتمد على حالة واجهة المستخدم. أي مدخل غير مكتمل أو غير
 * متوقع يرفض بدلًا من تنفيذ إيماءة أو إبقاء خدمة أمامية غير مرئية للمستخدم.
 */
internal object ServicesSafetyPolicy {
    private const val POST_NOTIFICATIONS_API = 33
    private const val CONTENT_CHANGE_MIN_INTERVAL_MS = 25L
    private const val MIN_TAP_PX = 16

    fun canProcessOffers(
        isExplicitlyEnabled: Boolean,
        hasDisclosureConsent: Boolean,
        hasValidLicense: Boolean,
    ): Boolean = isExplicitlyEnabled && hasDisclosureConsent && hasValidLicense

    fun isRelevantAccessibilityEvent(eventType: Int): Boolean =
        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

    fun shouldParse(eventType: Int, nowMs: Long, lastParseMs: Long?): Boolean {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return false
        if (lastParseMs == null || nowMs < lastParseMs) return true
        return nowMs - lastParseMs >= CONTENT_CHANGE_MIN_INTERVAL_MS
    }

    fun isSupportedPackage(packageName: CharSequence?, supportedPackages: Set<String>): Boolean =
        packageName?.toString()?.let(supportedPackages::contains) == true

    /**
     * A node is a valid target for a semantic ACTION_CLICK when some of it is on the real screen:
     * the action goes to the node itself, so a button still sliding in or touching the system bars
     * is clickable.
     */
    fun isOnScreen(bounds: Bounds, screenWidth: Int, screenHeight: Int): Boolean =
        screenWidth > 0 &&
            screenHeight > 0 &&
            !bounds.isEmpty &&
            bounds.right > 0 &&
            bounds.bottom > 0 &&
            bounds.left < screenWidth &&
            bounds.top < screenHeight

    /**
     * Where a coordinate tap lands: the centre of the part of [bounds] that is on the real screen,
     * or null when too little of it is visible to hit reliably. Never a point off the screen.
     */
    fun visibleTapPoint(bounds: Bounds, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        if (!isOnScreen(bounds, screenWidth, screenHeight)) return null
        val left = bounds.left.coerceAtLeast(0)
        val top = bounds.top.coerceAtLeast(0)
        val right = bounds.right.coerceAtMost(screenWidth)
        val bottom = bounds.bottom.coerceAtMost(screenHeight)
        if (right - left < MIN_TAP_PX || bottom - top < MIN_TAP_PX) return null
        return (left + (right - left) / 2) to (top + (bottom - top) / 2)
    }

    fun canRunForegroundService(
        isExplicitlyEnabled: Boolean,
        sdkInt: Int,
        notificationPermissionGranted: Boolean,
    ): Boolean = isExplicitlyEnabled &&
        (sdkInt < POST_NOTIFICATIONS_API || notificationPermissionGranted)
}

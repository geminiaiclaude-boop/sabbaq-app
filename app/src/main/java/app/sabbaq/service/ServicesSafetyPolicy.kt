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
     * Where a coordinate tap lands: the centre of the part of [bounds] inside both the real screen
     * and the target app's own [window], or null when too little of it is there to hit reliably.
     * The window excludes the system bars, so a button running under the navigation bar is never
     * tapped through to Home or Back.
     */
    fun visibleTapPoint(bounds: Bounds, window: Bounds, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        if (!isOnScreen(bounds, screenWidth, screenHeight) || window.isEmpty) return null
        val left = maxOf(bounds.left, window.left, 0)
        val top = maxOf(bounds.top, window.top, 0)
        val right = minOf(bounds.right, window.right, screenWidth)
        val bottom = minOf(bounds.bottom, window.bottom, screenHeight)
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

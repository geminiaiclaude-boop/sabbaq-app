package app.sabbaq.service

import android.view.accessibility.AccessibilityEvent
import app.sabbaq.core.Bounds

/**
 * قرارات حارسة لا تعتمد على حالة واجهة المستخدم. أي مدخل غير مكتمل أو غير
 * متوقع يرفض بدلًا من تنفيذ إيماءة أو إبقاء خدمة أمامية غير مرئية للمستخدم.
 */
internal object ServicesSafetyPolicy {
    private const val POST_NOTIFICATIONS_API = 33
    private const val CONTENT_CHANGE_MIN_INTERVAL_MS = 120L

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

    fun hasSafeGestureBounds(bounds: Bounds, screenWidth: Int, screenHeight: Int): Boolean =
        screenWidth > 0 &&
            screenHeight > 0 &&
            !bounds.isEmpty &&
            bounds.left >= 0 &&
            bounds.top >= 0 &&
            bounds.right <= screenWidth &&
            bounds.bottom <= screenHeight

    fun canRunForegroundService(
        isExplicitlyEnabled: Boolean,
        sdkInt: Int,
        notificationPermissionGranted: Boolean,
    ): Boolean = isExplicitlyEnabled &&
        (sdkInt < POST_NOTIFICATIONS_API || notificationPermissionGranted)
}

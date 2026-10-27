package app.sabbaq.service

import android.view.accessibility.AccessibilityEvent
import app.sabbaq.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesSafetyPolicyTest {
    @Test
    fun allowsOnlyConfiguredAccessibilityEvents() {
        assertTrue(ServicesSafetyPolicy.isRelevantAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(ServicesSafetyPolicy.isRelevantAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(ServicesSafetyPolicy.isRelevantAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED))
    }

    @Test
    fun throttlesContentChangesButNeverWindowStateChanges() {
        val contentChanged = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val windowChanged = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        assertTrue(ServicesSafetyPolicy.shouldParse(contentChanged, 1_000, null))
        assertFalse(ServicesSafetyPolicy.shouldParse(contentChanged, 1_024, 1_000))
        assertTrue(ServicesSafetyPolicy.shouldParse(contentChanged, 1_025, 1_000))
        assertTrue(ServicesSafetyPolicy.shouldParse(windowChanged, 1_001, 1_000))
        assertTrue(ServicesSafetyPolicy.shouldParse(windowChanged, 999, 1_000))
        assertFalse(ServicesSafetyPolicy.shouldParse(AccessibilityEvent.TYPE_VIEW_CLICKED, 2_000, null))
    }

    @Test
    fun rejectsMissingOrUnconfiguredPackage() {
        val supported = setOf("ee.mtakso.driver")

        assertTrue(ServicesSafetyPolicy.isSupportedPackage("ee.mtakso.driver", supported))
        assertFalse(ServicesSafetyPolicy.isSupportedPackage(null, supported))
        assertFalse(ServicesSafetyPolicy.isSupportedPackage("com.example.other", supported))
    }

    @Test
    fun gestureTapsTheCentreOfTheVisiblePartOnly() {
        assertEquals(60 to 70, ServicesSafetyPolicy.visibleTapPoint(Bounds(10, 20, 110, 120), Bounds(0, 0, 200, 300), 200, 300))
        // Partly below the screen: the tap moves up into the visible part instead of being refused.
        assertEquals(540 to 2350, ServicesSafetyPolicy.visibleTapPoint(Bounds(40, 2300, 1040, 2470), Bounds(0, 0, 1080, 2400), 1080, 2400))
        // Only a 10px sliver on screen: too little to hit, so no tap.
        assertNull(ServicesSafetyPolicy.visibleTapPoint(Bounds(-90, 20, 10, 120), Bounds(0, 0, 200, 300), 200, 300))
        assertNull(ServicesSafetyPolicy.visibleTapPoint(Bounds(10, 2395, 110, 2500), Bounds(0, 0, 200, 2400), 200, 2400))
        assertNull(ServicesSafetyPolicy.visibleTapPoint(Bounds(10, 20, 10, 120), Bounds(0, 0, 200, 300), 200, 300))
        assertNull(ServicesSafetyPolicy.visibleTapPoint(Bounds(10, 300, 110, 400), Bounds(0, 0, 200, 300), 200, 300))
    }

    @Test
    fun semanticTargetNeedsOnlyToBeOnTheRealScreen() {
        // A Jeeny accept button at the very bottom of a 1080x2400 screen: below the app area that
        // displayMetrics reports (2400 minus the navigation bar), still on the real screen.
        val bottomButton = Bounds(40, 2210, 1040, 2380)
        assertTrue(ServicesSafetyPolicy.isOnScreen(bottomButton, 1080, 2400))
        assertEquals(540 to 2295, ServicesSafetyPolicy.visibleTapPoint(bottomButton, Bounds(0, 0, 1080, 2400), 1080, 2400))
        // The same button running under a 126px navigation bar: the tap stays inside Jeeny's window.
        assertEquals(540 to 2242, ServicesSafetyPolicy.visibleTapPoint(bottomButton, Bounds(0, 0, 1080, 2274), 1080, 2400))
        assertNull(ServicesSafetyPolicy.visibleTapPoint(Bounds(40, 2280, 1040, 2400), Bounds(0, 0, 1080, 2274), 1080, 2400))
        assertNull(ServicesSafetyPolicy.visibleTapPoint(bottomButton, Bounds.EMPTY, 1080, 2400))
        assertTrue(ServicesSafetyPolicy.isOnScreen(Bounds(40, 2300, 1040, 2470), 1080, 2400))
        assertFalse(ServicesSafetyPolicy.isOnScreen(Bounds(40, 2400, 1040, 2570), 1080, 2400))
        assertFalse(ServicesSafetyPolicy.isOnScreen(Bounds(-200, 10, 0, 60), 1080, 2400))
        assertFalse(ServicesSafetyPolicy.isOnScreen(Bounds(10, 20, 10, 120), 1080, 2400))
        assertFalse(ServicesSafetyPolicy.isOnScreen(Bounds(10, 20, 110, 120), 0, 2400))
    }

    @Test
    fun foregroundServiceRequiresExplicitEnablementAndVisibleNotificationOnApi33() {
        assertTrue(ServicesSafetyPolicy.canRunForegroundService(true, 32, false))
        assertTrue(ServicesSafetyPolicy.canRunForegroundService(true, 33, true))
        assertFalse(ServicesSafetyPolicy.canRunForegroundService(true, 33, false))
        assertFalse(ServicesSafetyPolicy.canRunForegroundService(false, 36, true))
    }
}

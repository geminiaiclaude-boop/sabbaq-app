package app.qannas.service

import android.view.accessibility.AccessibilityEvent
import app.qannas.core.Bounds
import org.junit.Assert.assertFalse
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
    fun rejectsMissingOrUnconfiguredPackage() {
        val supported = setOf("ee.mtakso.driver")

        assertTrue(ServicesSafetyPolicy.isSupportedPackage("ee.mtakso.driver", supported))
        assertFalse(ServicesSafetyPolicy.isSupportedPackage(null, supported))
        assertFalse(ServicesSafetyPolicy.isSupportedPackage("com.example.other", supported))
    }

    @Test
    fun acceptsOnlyBoundsWhollyInsideTheScreen() {
        assertTrue(ServicesSafetyPolicy.hasSafeGestureBounds(Bounds(10, 20, 110, 120), 200, 300))
        assertFalse(ServicesSafetyPolicy.hasSafeGestureBounds(Bounds(-1, 20, 110, 120), 200, 300))
        assertFalse(ServicesSafetyPolicy.hasSafeGestureBounds(Bounds(10, 20, 201, 120), 200, 300))
        assertFalse(ServicesSafetyPolicy.hasSafeGestureBounds(Bounds(10, 20, 10, 120), 200, 300))
    }

    @Test
    fun foregroundServiceRequiresExplicitEnablementAndVisibleNotificationOnApi33() {
        assertTrue(ServicesSafetyPolicy.canRunForegroundService(true, 32, false))
        assertTrue(ServicesSafetyPolicy.canRunForegroundService(true, 33, true))
        assertFalse(ServicesSafetyPolicy.canRunForegroundService(true, 33, false))
        assertFalse(ServicesSafetyPolicy.canRunForegroundService(false, 36, true))
    }
}

package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcceptTrackerTest {
    @Test
    fun confirmsWithinWindowForMatchingProfile() {
        val tracker = AcceptTracker(confirmWindowMs = 5_000)
        tracker.tapped("offer-1", "bolt", 1_000)

        assertEquals(AcceptTracker.Outcome.Confirmed("offer-1"), tracker.observe("bolt", 5_999))
        assertNull(tracker.observe("bolt", 6_000))
    }

    @Test
    fun expiresAtConfirmationDeadline() {
        val tracker = AcceptTracker(confirmWindowMs = 5_000)
        tracker.tapped("offer-1", "bolt", 1_000)

        assertEquals(AcceptTracker.Outcome.NotConfirmed("offer-1"), tracker.observe(null, 6_000))
        assertNull(tracker.observe("bolt", 6_001))
    }

    @Test
    fun confirmationForAnotherProfileDoesNotCount() {
        val tracker = AcceptTracker(confirmWindowMs = 5_000)
        tracker.tapped("offer-1", "bolt", 1_000)

        assertNull(tracker.observe("jeeny", 2_000))
        assertEquals(AcceptTracker.Outcome.Confirmed("offer-1"), tracker.observe("bolt", 2_001))
    }

    @Test
    fun secondTapReplacesFirst() {
        val tracker = AcceptTracker(confirmWindowMs = 5_000)
        tracker.tapped("offer-1", "bolt", 1_000)
        tracker.tapped("offer-2", "jeeny", 2_000)

        assertNull(tracker.observe("bolt", 2_100))
        assertEquals(AcceptTracker.Outcome.Confirmed("offer-2"), tracker.observe("jeeny", 2_101))
    }

    @Test
    fun backwardsClockInvalidatesPendingTap() {
        val tracker = AcceptTracker(confirmWindowMs = 5_000)
        tracker.tapped("offer-1", "bolt", 1_000)

        assertNull(tracker.observe("bolt", 999))
        assertNull(tracker.observe("bolt", 1_001))
    }
}

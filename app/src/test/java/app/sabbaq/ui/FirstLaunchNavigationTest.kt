package app.sabbaq.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstLaunchNavigationTest {
    @Test fun firstLaunchShowsWelcomeAndRotationDoesNotResetIt() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(showWelcome = true, hasSavedSession = false)
        assertEquals(AppState.WELCOME, navigation.route)
        navigation.initialize(showWelcome = false, hasSavedSession = false)
        assertEquals(AppState.WELCOME, navigation.route)
        navigation.welcomeFinished(hasSavedSession = false)
        assertEquals(AppState.LOGIN, navigation.route)
    }

    @Test fun completedWelcomeWithoutSavedSessionStartsAtLogin() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(showWelcome = false, hasSavedSession = false)
        assertEquals(AppState.LOGIN, navigation.route)
        navigation.authenticated(false)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        navigation.initialize(showWelcome = true, hasSavedSession = false)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        navigation.signedOut()
        assertEquals(AppState.LOGIN, navigation.route)
    }

    @Test fun savedSessionOpensDashboardImmediatelyWhileRestoreRunsInBackground() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(showWelcome = false, hasSavedSession = true)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        assertEquals(SessionRestoreStatus.RESTORING, navigation.sessionRestoreStatus)
        navigation.sessionRestoreFinished(restored = true)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        assertEquals(SessionRestoreStatus.NONE, navigation.sessionRestoreStatus)
    }

    @Test fun transientRestoreFailureKeepsKnownAccountAndOffersRetry() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(showWelcome = false, hasSavedSession = true)
        navigation.sessionRestoreUnavailable()
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        assertEquals(SessionRestoreStatus.RETRYABLE_ERROR, navigation.sessionRestoreStatus)
        navigation.retrySessionRestore()
        assertEquals(SessionRestoreStatus.RESTORING, navigation.sessionRestoreStatus)
        navigation.sessionRestoreFinished(restored = false)
        assertEquals(AppState.LOGIN, navigation.route)
    }

    @Test fun savedSessionAfterWelcomeEntersDashboardWithoutLogin() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(showWelcome = true, hasSavedSession = true)
        assertEquals(AppState.WELCOME, navigation.route)
        assertEquals(SessionRestoreStatus.NONE, navigation.sessionRestoreStatus)
        navigation.welcomeFinished(hasSavedSession = true)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        assertEquals(SessionRestoreStatus.RESTORING, navigation.sessionRestoreStatus)
    }

    @Test fun aNewProcessShowsKnownDashboardButDoesNotAssumeAuthorization() {
        val oldNavigation = SessionNavigationViewModel()
        oldNavigation.initialize(showWelcome = false, hasSavedSession = true)
        oldNavigation.sessionRestoreFinished(restored = true)
        assertEquals(AppState.DRIVER_PANEL, oldNavigation.route)

        val newNavigation = SessionNavigationViewModel()
        newNavigation.initialize(showWelcome = false, hasSavedSession = true)
        assertEquals(AppState.DRIVER_PANEL, newNavigation.route)
        assertEquals(SessionRestoreStatus.RESTORING, newNavigation.sessionRestoreStatus)
    }
}

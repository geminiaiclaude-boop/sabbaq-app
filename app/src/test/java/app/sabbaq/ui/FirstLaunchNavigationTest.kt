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

    @Test fun savedSessionNeverStartsOnLoginBeforeRestoreCompletes() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(showWelcome = false, hasSavedSession = true)
        assertEquals(AppState.RESTORING_SESSION, navigation.route)
        navigation.sessionRestoreFinished(restored = true)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
    }

    @Test fun transientRestoreFailureOffersRetryWithoutShowingLogin() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(showWelcome = false, hasSavedSession = true)
        navigation.sessionRestoreUnavailable()
        assertEquals(AppState.SESSION_RESTORE_ERROR, navigation.route)
        navigation.retrySessionRestore()
        assertEquals(AppState.RESTORING_SESSION, navigation.route)
        navigation.sessionRestoreFinished(restored = false)
        assertEquals(AppState.LOGIN, navigation.route)
    }

    @Test fun aNewProcessDoesNotAssumeAuthorizationBeforeRestoringSavedSession() {
        val oldNavigation = SessionNavigationViewModel()
        oldNavigation.initialize(showWelcome = false, hasSavedSession = true)
        oldNavigation.sessionRestoreFinished(restored = true)
        assertEquals(AppState.DRIVER_PANEL, oldNavigation.route)

        val newNavigation = SessionNavigationViewModel()
        newNavigation.initialize(showWelcome = false, hasSavedSession = true)
        assertEquals(AppState.RESTORING_SESSION, newNavigation.route)
        newNavigation.sessionRestoreFinished(restored = true)
        assertEquals(AppState.DRIVER_PANEL, newNavigation.route)
    }
}

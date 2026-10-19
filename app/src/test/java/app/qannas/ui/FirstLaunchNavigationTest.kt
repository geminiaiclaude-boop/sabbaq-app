package app.qannas.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstLaunchNavigationTest {
    @Test fun firstLaunchShowsWelcomeAndRotationDoesNotResetIt() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(true)
        assertEquals(AppState.WELCOME, navigation.route)
        navigation.initialize(false)
        assertEquals(AppState.WELCOME, navigation.route)
        navigation.welcomeFinished()
        assertEquals(AppState.LOGIN, navigation.route)
    }

    @Test fun completedWelcomeStillRequiresAuthentication() {
        val navigation = SessionNavigationViewModel()
        navigation.initialize(false)
        assertEquals(AppState.LOGIN, navigation.route)
        navigation.authenticated(false)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        navigation.initialize(true)
        assertEquals(AppState.DRIVER_PANEL, navigation.route)
        navigation.signedOut()
        assertEquals(AppState.LOGIN, navigation.route)
    }

    @Test fun newProcessDoesNotRestoreAuthorization() {
        val oldNavigation = SessionNavigationViewModel()
        oldNavigation.initialize(false)
        oldNavigation.authenticated(true)
        val newNavigation = SessionNavigationViewModel()
        newNavigation.initialize(false)
        assertEquals(AppState.LOGIN, newNavigation.route)
    }
}

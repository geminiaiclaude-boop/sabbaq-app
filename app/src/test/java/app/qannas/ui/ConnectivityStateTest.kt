package app.qannas.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityStateTest {
    @Test
    fun `online only when the network has internet and the OS validated it`() {
        assertTrue(ConnectivityState.hasInternet(internet = true, validated = true))
        assertFalse(ConnectivityState.hasInternet(internet = true, validated = false)) // captive portal
        assertFalse(ConnectivityState.hasInternet(internet = false, validated = false))
    }
}

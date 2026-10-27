package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideAppDiscoveryTest {
    private val profiles = Profiles.bundled()

    @Test
    fun `listed packages are known whatever their label`() {
        val found = RideAppDiscovery.classify("me.com.easytaxista", "Jeeny - for Drivers", profiles)!!
        assertEquals("jeeny", found.provider)
        assertTrue(found.known)
    }

    @Test
    fun `a ride app under another package is found by its name`() {
        val found = RideAppDiscovery.classify("sa.jeeny.captain", "جيني للسائقين", profiles)!!
        assertEquals("jeeny", found.provider)
        assertFalse(found.known)
        assertEquals("bolt", RideAppDiscovery.classify("ee.mtakso.driver.beta", "Bolt Driver", profiles)!!.provider)
    }

    @Test
    fun `passenger apps and unrelated apps are not candidates`() {
        assertNull(RideAppDiscovery.classify("me.com.easytaxi", "Jeeny", profiles))
        assertNull(RideAppDiscovery.classify("ee.mtakso.client", "Bolt", profiles))
        assertNull(RideAppDiscovery.classify("com.whatsapp", "WhatsApp", profiles))
        assertNull(RideAppDiscovery.classify("", "Jeeny", profiles))
    }

    @Test
    fun `package detail stays inside the telemetry contract`() {
        assertEquals("com.petraride_captain", RideAppDiscovery.detailFor("com.PetraRide_Captain"))
        assertNull(RideAppDiscovery.detailFor("com.example.phonebook"))
        assertTrue(Regex("^[a-z0-9_.:=,-]{1,96}$").matches(RideAppDiscovery.detailFor("me.com.easytaxista")!!))
    }

    @Test
    fun `heartbeat names a ride app in another profile or under an unknown package`() {
        val base = DeviceHealth(true, true, true, true, true, true, false, true, false, true, false, true)
        assertEquals("target_not_installed", base.primaryIssue())
        assertEquals("target_other_profile", base.copy(targetInOtherProfile = true).primaryIssue())
        assertEquals("target_unknown_package", base.copy(targetUnknownPackage = true).primaryIssue())
        assertTrue(base.copy(targetInOtherProfile = true).flags().endsWith("oth=1,unk=0"))
        assertTrue(base.flags().length <= OfferTelemetryContract.MAX_DETAIL_LENGTH)
    }
}

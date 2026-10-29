package app.sabbaq.core

import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.SnipingGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetPoliciesTest {
    @Test
    fun shared_words_extend_the_drivers_own_keywords() {
        val own = EngineConfig(acceptKeywordsExtra = "خذ الطلب", timeKeywordsExtra = "")
        val shared = SharedLexicon(version = 3, accept = listOf("انطلق", "خذ الطلب", "bad1", ""), time = listOf("يصل خلال"))
        val merged = shared.applyTo(own)
        assertEquals("خذ الطلب,انطلق", merged.acceptKeywordsExtra)
        assertEquals("يصل خلال", merged.timeKeywordsExtra)
        assertTrue("the reader matches a learned accept word", merged.lexicon().accept.matcher("انطلق").find())
    }

    @Test
    fun no_published_lexicon_leaves_the_reference_vocabulary_untouched() {
        val own = EngineConfig()
        assertSame(own, SharedLexicon().applyTo(own))
    }

    @Test
    fun update_states() {
        val release = AppRelease(latestCode = 3_650_000, minCode = 3_640_000, apkUrl = "https://x.supabase.co/r/s.apk",
            apkSha256 = "ab".repeat(32), apkSize = 30_000_000)
        assertEquals(UpdateState.REQUIRED, release.state(3_630_000))
        assertEquals(UpdateState.AVAILABLE, release.state(3_645_000))
        assertEquals(UpdateState.NONE, release.state(3_650_000))
        assertEquals(UpdateState.NONE, AppRelease().state(14))
        assertTrue(release.downloadable)
        assertFalse(release.copy(apkUrl = "http://x/s.apk").downloadable)
        assertFalse(release.copy(apkSha256 = "AB".repeat(32)).downloadable)
    }

    @Test
    fun below_the_minimum_no_offer_is_tapped() {
        val r = SnipingGate.check(monitoringWanted = true, disclosureAccepted = true, subscriptionEndsAtMs = 2_000,
            newestSeenMs = 1_000, nowMs = 1_000, updateRequired = true)
        assertFalse(r.allowed)
        assertEquals("update_required", r.reason)
        assertTrue(SnipingGate.check(true, true, 2_000, 1_000, 1_000).allowed)
    }

    @Test
    fun exit_reasons() {
        assertEquals("low_memory", ProcessExit.reason(3))
        assertEquals("crash", ProcessExit.reason(5))
        assertEquals("unknown", ProcessExit.reason(99))
        assertTrue(ProcessExit.isSystemKill("low_memory"))
        assertTrue(ProcessExit.isSystemKill("excessive_resource"))
        assertFalse(ProcessExit.isSystemKill("user_requested"))
        assertFalse(ProcessExit.isSystemKill("package_updated"))
    }

    @Test
    fun standby_buckets() {
        assertEquals("a", StandbyBucket.letter(10))
        assertEquals("r", StandbyBucket.letter(40))
        assertEquals("x", StandbyBucket.letter(45))
        assertTrue(StandbyBucket.atRisk(45))
        assertFalse(StandbyBucket.atRisk(20))
    }

    @Test
    fun health_names_the_new_risks() {
        val ok = DeviceHealth(true, true, true, true, true, true, true, true, false, true, false, true)
        assertEquals("healthy", ok.primaryIssue())
        assertEquals("hibernation_on", ok.copy(hibernationOn = true).primaryIssue())
        assertEquals("standby_restricted", ok.copy(standbyRestricted = true).primaryIssue())
        assertEquals("update_required", ok.copy(updateRequired = true, hibernationOn = true).primaryIssue())
        assertEquals(ok.flags(), ok.copy(hibernationOn = true).flags())
    }
}

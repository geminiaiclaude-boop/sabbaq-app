package app.qannas.voice

import app.qannas.network.LicenseState
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePolicyTest {
    private var now = 0L
    private val gate = VoiceGate { now }
    private val quiet = PhoneAudioState(inCall = false, ringerSilent = false)

    @Test
    fun the_same_sentence_is_not_repeated_within_its_window() {
        val prefs = VoicePreferences()
        assertTrue(gate.shouldSpeak(VoiceKind.Status, "أ", prefs, quiet))
        now += VoiceKind.Status.repeatWindowMillis - 1
        assertFalse(gate.shouldSpeak(VoiceKind.Status, "أ", prefs, quiet))
        assertTrue("a different sentence is spoken", gate.shouldSpeak(VoiceKind.Status, "ب", prefs, quiet))
        now += 1
        assertTrue(gate.shouldSpeak(VoiceKind.Status, "أ", prefs, quiet))
    }

    @Test
    fun never_over_a_call_and_quiet_in_silent_mode_unless_allowed() {
        val call = PhoneAudioState(inCall = true, ringerSilent = false)
        val silent = PhoneAudioState(inCall = false, ringerSilent = true)
        assertFalse(gate.shouldSpeak(VoiceKind.Problem, "x", VoicePreferences(evenWhenSilent = true), call))
        assertFalse(gate.shouldSpeak(VoiceKind.Problem, "x", VoicePreferences(), silent))
        assertTrue(gate.shouldSpeak(VoiceKind.Problem, "x", VoicePreferences(evenWhenSilent = true), silent))
    }

    @Test
    fun each_switch_silences_only_its_kind_and_the_master_switch_silences_all() {
        val noConnection = VoicePreferences(connection = false)
        assertFalse(noConnection.allows(VoiceKind.Connection))
        assertTrue(noConnection.allows(VoiceKind.Problem))
        VoiceKind.entries.forEach { assertFalse(VoicePreferences(enabled = false).allows(it)) }
        assertFalse(VoicePreferences(status = false).allows(VoiceKind.Status))
        assertFalse(VoicePreferences(problems = false).allows(VoiceKind.Problem))
        assertFalse(VoicePreferences(subscription = false).allows(VoiceKind.Subscription))
        assertFalse(VoicePreferences(offers = false).allows(VoiceKind.Offer))
    }

    @Test
    fun stop_cause_tells_the_driver_what_to_fix_first() {
        assertEquals(StopCause.Notifications, VoiceRules.stopCause(false, true, LicenseState.EXPIRED))
        assertEquals(StopCause.SignIn, VoiceRules.stopCause(true, false, LicenseState.EXPIRED))
        assertEquals(StopCause.DeviceMoved, VoiceRules.stopCause(true, true, LicenseState.DEVICE_MISMATCH))
        assertEquals(StopCause.LicenseEnded, VoiceRules.stopCause(true, true, LicenseState.EXPIRED))
        // The server still says valid (or never answered): verification failed, payment is not the issue.
        assertEquals(StopCause.LicenseUnverified, VoiceRules.stopCause(true, true, LicenseState.ACTIVE))
        assertEquals(StopCause.LicenseUnverified, VoiceRules.stopCause(true, true, null))
        StopCause.entries.forEach { assertTrue(VoiceRules.line(it).startsWith("توقف قناص.")) }
    }

    @Test
    fun subscription_is_announced_a_day_before_and_in_the_last_hour() {
        val hour = 60 * 60 * 1000L
        assertEquals(0, VoiceRules.subscriptionWarningLevel(25 * hour))
        assertEquals(1, VoiceRules.subscriptionWarningLevel(24 * hour))
        assertEquals(2, VoiceRules.subscriptionWarningLevel(hour))
        assertEquals(0, VoiceRules.subscriptionWarningLevel(0))
        assertEquals(1L, VoiceRules.hoursLeft(59 * 60 * 1000L))
        assertEquals("ينتهي اشتراكك خلال ساعة. جدّده لتستمر المتابعة.", VoiceLines.subscriptionEnding(1))
        assertEquals("ينتهي اشتراكك خلال ساعتين. جدّده لتستمر المتابعة.", VoiceLines.subscriptionEnding(2))
        assertEquals("ينتهي اشتراكك خلال 5 ساعات. جدّده لتستمر المتابعة.", VoiceLines.subscriptionEnding(5))
        assertEquals("ينتهي اشتراكك خلال يوم. جدّده لتستمر المتابعة.", VoiceLines.subscriptionEnding(24))
    }

    @Test
    fun offer_sentence_reads_only_what_is_known() {
        assertEquals("ضغطتُ قبول طلب بأجرة 45.5 ريال، الوصول 3 كيلومتر.",
            VoiceLines.offerAccepted(BigDecimal("45.50"), BigDecimal("3.0")))
        assertEquals("ضغطتُ قبول طلب.", VoiceLines.offerAccepted(null, null))
    }

    @Test
    fun volume_stays_audible() {
        assertEquals(0.2f, VoiceRules.clampVolume(0f))
        assertEquals(1f, VoiceRules.clampVolume(3f))
        assertEquals(1f, VoiceRules.clampVolume(Float.NaN))
    }

    /** Without this <queries> entry Android 11+ hides the TTS engine and the app stays mute. */
    @Test
    fun tts_engines_are_visible_to_the_app() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("<action android:name=\"android.intent.action.TTS_SERVICE\" />" in manifest)
    }
}

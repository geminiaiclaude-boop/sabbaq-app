package app.qannas.voice

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.qannas.Settings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spoken Arabic alerts. A short tone marks the kind of alert, then the phone's text-to-speech
 * says the sentence while other audio (navigation, music) is ducked, not stopped. It never
 * talks over a call, stays quiet in silent / do-not-disturb mode unless the driver allowed it,
 * and does not repeat itself. Without an Arabic voice it plays the tone only and never reads
 * Arabic with a foreign voice. The engine is started on demand and released when idle.
 * All state lives on the main thread; [announce] may be called from any thread.
 */
object VoiceAnnouncer {
    enum class Readiness { Unknown, Preparing, Ready, NoArabicVoice, Unavailable }

    private val readinessState = MutableStateFlow(Readiness.Unknown)
    val readiness: StateFlow<Readiness> = readinessState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val gate = VoiceGate { SystemClock.elapsedRealtime() }
    private var appContext: Context? = null
    private var engine: TextToSpeech? = null
    private var engineReady = false
    private val waiting = ArrayList<Pair<String, Float>>()
    private val speaking = HashSet<String>()
    private var nextId = 0L
    private var focus: AudioFocusRequest? = null
    private val releaseWhenIdle = Runnable { releaseEngine() }

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Speaks [text] if the driver's choices and the phone's state allow it. */
    fun announce(kind: VoiceKind, text: String) {
        main.post { speak(kind, text, test = false) }
    }

    /** The «جرّب الصوت» button: ignores the repeat window and silent mode, never a call. */
    fun test() {
        main.post { speak(VoiceKind.Status, VoiceLines.TEST, test = true) }
    }

    /** Starts the engine without speaking, so settings can show whether an Arabic voice exists. */
    fun probe() {
        main.post {
            if (engine == null) {
                startEngine()
                scheduleRelease()
            }
        }
    }

    private fun speak(kind: VoiceKind, text: String, test: Boolean) {
        val context = appContext ?: return
        val prefs = Settings.voicePreferences
        val phone = phoneState(context)
        val allowed = if (test) !phone.inCall else gate.shouldSpeak(kind, text, prefs, phone)
        if (!allowed) return
        playTone(kind, prefs.volume)
        if (engineReady) say(text, prefs.volume) else {
            waiting += text to prefs.volume
            if (engine == null) startEngine()
        }
    }

    private fun startEngine() {
        val context = appContext ?: return
        main.removeCallbacks(releaseWhenIdle)
        readinessState.value = Readiness.Preparing
        engine = try {
            TextToSpeech(context) { status -> main.post { onEngineStarted(status) } }
        } catch (e: RuntimeException) {
            Log.w(TAG, "text-to-speech unavailable", e)
            readinessState.value = Readiness.Unavailable
            waiting.clear()
            null
        }
    }

    private fun onEngineStarted(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            readinessState.value = Readiness.Unavailable
            waiting.clear()
            releaseEngine()
            return
        }
        val arabic = ARABIC_LOCALES.firstOrNull { locale ->
            runCatching { tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        }
        if (arabic == null || runCatching { tts.setLanguage(arabic) }.getOrDefault(TextToSpeech.ERROR) < TextToSpeech.LANG_AVAILABLE) {
            // The tone already played; reading Arabic with a foreign voice would be noise.
            readinessState.value = Readiness.NoArabicVoice
            waiting.clear()
            scheduleRelease()
            return
        }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { finished(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { finished(utteranceId) } }
            override fun onError(utteranceId: String?, errorCode: Int) { main.post { finished(utteranceId) } }
        })
        engineReady = true
        readinessState.value = Readiness.Ready
        val queued = waiting.toList()
        waiting.clear()
        if (queued.isEmpty()) scheduleRelease() else queued.forEach { (text, volume) -> say(text, volume) }
    }

    private fun say(text: String, volume: Float) {
        val tts = engine ?: return
        main.removeCallbacks(releaseWhenIdle)
        requestFocus()
        val id = "qannas-${nextId++}"
        speaking += id
        // A short pause lets the tone finish before the voice starts.
        tts.playSilentUtterance(TONE_MILLIS + 120L, TextToSpeech.QUEUE_ADD, null)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VoiceRules.clampVolume(volume)) }
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, params, id) == TextToSpeech.ERROR) finished(id)
    }

    private fun finished(id: String?) {
        if (id != null) speaking -= id
        if (speaking.isEmpty()) {
            abandonFocus()
            scheduleRelease()
        }
    }

    private fun scheduleRelease() {
        main.removeCallbacks(releaseWhenIdle)
        main.postDelayed(releaseWhenIdle, IDLE_RELEASE_MILLIS)
    }

    private fun releaseEngine() {
        if (speaking.isNotEmpty()) return
        runCatching { engine?.shutdown() }
        engine = null
        engineReady = false
        abandonFocus()
    }

    private fun requestFocus() {
        if (focus != null) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        // Speak even if focus is refused: an alert about monitoring must not be lost.
        runCatching { audio.requestAudioFocus(request) }
        focus = request
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        runCatching { appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    private fun playTone(kind: VoiceKind, volume: Float) {
        val tone = when (kind) {
            VoiceKind.Status -> ToneGenerator.TONE_PROP_ACK
            VoiceKind.Problem -> ToneGenerator.TONE_PROP_NACK
            VoiceKind.Connection, VoiceKind.Subscription -> ToneGenerator.TONE_PROP_BEEP2
            VoiceKind.Offer -> ToneGenerator.TONE_PROP_PROMPT
        }
        val generator = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, (VoiceRules.clampVolume(volume) * 80).toInt())
        }.getOrNull() ?: return
        runCatching { generator.startTone(tone, TONE_MILLIS) }
        main.postDelayed({ runCatching { generator.release() } }, TONE_MILLIS + 400L)
    }

    private fun phoneState(context: Context): PhoneAudioState {
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = audio?.mode ?: AudioManager.MODE_NORMAL
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
        val ringerQuiet = audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val doNotDisturb = runCatching {
            val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
            filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }.getOrDefault(false)
        return PhoneAudioState(inCall = inCall, ringerSilent = ringerQuiet || doNotDisturb)
    }

    private const val TAG = "VoiceAnnouncer"
    private const val TONE_MILLIS = 180
    private const val IDLE_RELEASE_MILLIS = 90_000L
    private val ARABIC_LOCALES = listOf(Locale("ar", "SA"), Locale("ar"))
}

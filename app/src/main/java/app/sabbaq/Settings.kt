package app.sabbaq

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.voice.VoicePreferences
import app.sabbaq.voice.VoiceRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** إعدادات غير حساسة فقط؛ رموز الجلسات ومعرّفات الأجهزة لا تُخزَّن هنا. */
object Settings {
    @Volatile
    private var preferences: SharedPreferences? = null
    @Volatile
    private var cachedConfig: EngineConfig? = null

    /** Saved settings may carry fields a later release removed; they must not reset the rest. */
    private val storedJson = Json { ignoreUnknownKeys = true }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        synchronized(this) {
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            cachedConfig = null
        }
    }

    /**
     * لا تكون صلاحية التشغيل قابلة للاستئناف بعد قتل العملية: جلسة الشبكة ذاكرية فقط،
     * لذا تبقى الخدمة معطلة إلى أن يمر المستخدم بالمصادقة والتحقق من الترخيص مجددًا.
     */
    fun clearProcessAuthorizationState() {
        isActive = false
    }

    /** The driver's acceptance settings, the reference engine's field set and defaults (engine notes). */
    var engineConfig: EngineConfig
        get() = synchronized(this) {
            cachedConfig ?: run {
                val stored = prefs().getString(KEY_ENGINE_CONFIG, null)
                // EngineConfigStore reads settings saved by older versions with their defaults.
                val value = stored?.let { app.sabbaq.core.engine.EngineConfigStore.decode(it) } ?: EngineConfig()
                cachedConfig = value
                value
            }
        }
        set(value) {
            synchronized(this) {
                prefs().edit().putString(KEY_ENGINE_CONFIG, app.sabbaq.core.engine.EngineConfigStore.encode(value)).apply()
                cachedConfig = null
            }
        }

    var isActive: Boolean
        get() = prefs().getBoolean(KEY_IS_ACTIVE, false)
        set(value) {
            // The accessibility service re-asserts false on every blocked event; writing only
            // on change keeps that hot path from queuing a disk write per event.
            val current = prefs()
            if (current.getBoolean(KEY_IS_ACTIVE, false) != value || !current.contains(KEY_IS_ACTIVE)) {
                current.edit().putBoolean(KEY_IS_ACTIVE, value).apply()
            }
        }

    /**
     * The driver's own choice to keep monitoring on, kept across restarts. [isActive] is the
     * runtime gate the accessibility service reads; it is only re-opened after the license is
     * confirmed again, while this flag survives so monitoring resumes on its own.
     */
    var monitoringWanted: Boolean
        get() = runCatching { prefs().getBoolean(KEY_MONITORING_WANTED, false) }.getOrDefault(false)
        set(value) { runCatching { prefs().edit().putBoolean(KEY_MONITORING_WANTED, value).apply() } }

    /**
     * Subscription end (wall-clock epoch ms) saved from the last licence answer; 0 = not known
     * yet. With [newestSeenMs] it is all the tap gate needs (the reference engine's isSnipingAllowedNow).
     */
    var subscriptionEndsAtMs: Long
        get() = runCatching { prefs().getLong(KEY_SUBSCRIPTION_ENDS_AT, 0L) }.getOrDefault(0L)
        set(value) { runCatching { prefs().edit().putLong(KEY_SUBSCRIPTION_ENDS_AT, value).apply() } }

    /** Screen point of the last tap the ride app confirmed; turbo can reuse it. -1 = none. */
    var lastConfirmedTapX: Int
        get() = runCatching { prefs().getInt("last_confirmed_tap_x", -1) }.getOrDefault(-1)
        set(value) { runCatching { prefs().edit().putInt("last_confirmed_tap_x", value).apply() } }
    var lastConfirmedTapY: Int
        get() = runCatching { prefs().getInt("last_confirmed_tap_y", -1) }.getOrDefault(-1)
        set(value) { runCatching { prefs().edit().putInt("last_confirmed_tap_y", value).apply() } }

    /** An invite code waiting to be claimed once the account exists (none is typed at sign-in any more). */
    var pendingReferralCode: String?
        get() = runCatching { prefs().getString("pending_referral_code", null) }.getOrNull()
        set(value) { runCatching { prefs().edit().apply { if (value.isNullOrBlank()) remove("pending_referral_code") else putString("pending_referral_code", value) }.apply() } }

    /** The post-sign-in invite page («هل دعاك زميل؟») was answered or is not due for this account. */
    var referralPromptDone: Boolean
        get() = runCatching { prefs().getBoolean(KEY_REFERRAL_PROMPT_DONE, false) }.getOrDefault(false)
        set(value) { runCatching { prefs().edit().putBoolean(KEY_REFERRAL_PROMPT_DONE, value).apply() } }

    /** Result of claiming that code ("ok" or the server's reason), shown once on the referral page. */
    var referralClaimOutcome: String?
        get() = runCatching { prefs().getString("referral_claim_outcome", null) }.getOrNull()
        set(value) { runCatching { prefs().edit().apply { if (value == null) remove("referral_claim_outcome") else putString("referral_claim_outcome", value) }.apply() } }

    /** Newest wall-clock time seen, to refuse a clock set back to extend the subscription. */
    var newestSeenMs: Long
        get() = runCatching { prefs().getLong(KEY_NEWEST_SEEN, 0L) }.getOrDefault(0L)
        set(value) { runCatching { prefs().edit().putLong(KEY_NEWEST_SEEN, value).apply() } }

    /**
     * The floating control is shown (FloatingBubble). Like the reference it is off until the driver
     * presses «الزر العائم» on the dashboard, and «إغلاق» on the control turns it off again.
     */
    var floatingBubble: Boolean
        get() = runCatching { prefs().getBoolean("floating_control_shown", false) }.getOrDefault(false)
        set(value) { runCatching { prefs().edit().putBoolean("floating_control_shown", value).apply() } }

    var floatingBubbleX: Int
        get() = runCatching { prefs().getInt("floating_bubble_x", 0) }.getOrDefault(0)
        set(value) { runCatching { prefs().edit().putInt("floating_bubble_x", value).apply() } }

    /** The last sign-in was an administrator (shows «الأدمن» on the floating control). */
    var lastSignedInAdmin: Boolean
        get() = runCatching { prefs().getBoolean("last_signed_in_admin", false) }.getOrDefault(false)
        set(value) { runCatching { prefs().edit().putBoolean("last_signed_in_admin", value).apply() } }

    var floatingBubbleY: Int
        get() = runCatching { prefs().getInt("floating_bubble_y", 200) }.getOrDefault(200)
        set(value) { runCatching { prefs().edit().putInt("floating_bubble_y", value).apply() } }

    /** The driver confirmed the phone maker's auto-start setting (it cannot be read back). */
    var autostartConfirmed: Boolean
        get() = runCatching { prefs().getBoolean(KEY_AUTOSTART_CONFIRMED, false) }.getOrDefault(false)
        set(value) { runCatching { prefs().edit().putBoolean(KEY_AUTOSTART_CONFIRMED, value).apply() } }

    /** Consent is independent from licensing and never grants account access. */
    var a11yDisclosureAccepted: Boolean
        get() = prefs().getInt(KEY_A11Y_DISCLOSURE_VERSION, 0) == A11Y_DISCLOSURE_VERSION
        set(value) {
            prefs().edit().putInt(KEY_A11Y_DISCLOSURE_VERSION,
                if (value) A11Y_DISCLOSURE_VERSION else 0).apply()
            if (!value) isActive = false
        }

    /** A validated draft is committed as one document, on IO, before the UI reports success. */
    suspend fun saveEngineConfig(value: EngineConfig): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@Settings) {
            val saved = runCatching {
                prefs().edit().putString(KEY_ENGINE_CONFIG, app.sabbaq.core.engine.EngineConfigStore.encode(value)).commit()
            }.getOrDefault(false)
            if (saved) cachedConfig = null
            saved
        }.also { saved ->
            if (saved) withContext(Dispatchers.Main) {
                app.sabbaq.service.SabbaqA11yService.instance?.onConfigSaved()
                appContext?.let { app.sabbaq.service.KeeperService.resumeIfWanted(it) }
            }
        }
    }

    /**
     * After the account is deleted on the server nothing tied to it may stay on the
     * device. Display preferences (theme) are kept because they belong to the device.
     */
    fun clearAccountData(): Boolean {
        return synchronized(this) {
            val cleared = runCatching {
                prefs().edit()
                    .remove(KEY_ENGINE_CONFIG)
                    .remove(KEY_OLD_FILTERS)
                    .putBoolean(KEY_IS_ACTIVE, false)
                    .remove(KEY_A11Y_DISCLOSURE_VERSION)
                    .remove(KEY_KNOWN_SUBSCRIBER)
                    .remove(KEY_MONITORING_WANTED)
                    .remove(KEY_REFERRAL_PROMPT_DONE)
                    // C-06: the next account on this phone inherits no paid period and no admin flag.
                    .putLong(KEY_SUBSCRIPTION_ENDS_AT, 1L)
                    .remove("last_signed_in_admin")
                    .commit()
            }.getOrDefault(false)
            if (cleared) cachedConfig = null
            cleared
        }
    }

    /** A paid subscription was confirmed on this device; only hides the trial offer, grants nothing. */
    var knownSubscriber: Boolean
        get() = runCatching { prefs().getBoolean(KEY_KNOWN_SUBSCRIBER, false) }.getOrDefault(false)
        set(value) { runCatching { prefs().edit().putBoolean(KEY_KNOWN_SUBSCRIBER, value).apply() } }

    /** Spoken alerts belong to the device (like the theme), so they survive sign-out. */
    var voicePreferences: VoicePreferences
        get() = runCatching {
            val p = prefs()
            VoicePreferences(
                enabled = p.getBoolean("voice_enabled", true),
                status = p.getBoolean("voice_status", true),
                problems = p.getBoolean("voice_problems", true),
                connection = p.getBoolean("voice_connection", true),
                subscription = p.getBoolean("voice_subscription", true),
                offers = p.getBoolean("voice_offers", true),
                evenWhenSilent = p.getBoolean("voice_even_silent", false),
                volume = VoiceRules.clampVolume(p.getFloat("voice_volume", 1f)),
            )
        }.getOrDefault(VoicePreferences())
        set(value) {
            runCatching {
                prefs().edit()
                    .putBoolean("voice_enabled", value.enabled)
                    .putBoolean("voice_status", value.status)
                    .putBoolean("voice_problems", value.problems)
                    .putBoolean("voice_connection", value.connection)
                    .putBoolean("voice_subscription", value.subscription)
                    .putBoolean("voice_offers", value.offers)
                    .putBoolean("voice_even_silent", value.evenWhenSilent)
                    .putFloat("voice_volume", VoiceRules.clampVolume(value.volume))
                    .apply()
            }
        }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs().getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) { prefs().edit().putString("theme_mode", value.name).apply() }

    private fun prefs(): SharedPreferences = preferences
        ?: throw IllegalStateException("يجب استدعاء Settings.init قبل استخدام الإعدادات")

    private const val PREFERENCES_NAME = "sabbaq_prefs"
    private const val KEY_ENGINE_CONFIG = "engine_config"
    private const val KEY_OLD_FILTERS = "filters"
    private const val KEY_IS_ACTIVE = "is_active"
    private const val KEY_A11Y_DISCLOSURE_VERSION = "a11y_disclosure_version"
    private const val KEY_KNOWN_SUBSCRIBER = "known_subscriber"
    private const val KEY_REFERRAL_PROMPT_DONE = "referral_prompt_done"
    private const val KEY_MONITORING_WANTED = "monitoring_wanted"
    private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"
    private const val KEY_SUBSCRIPTION_ENDS_AT = "subscription_ends_at"
    private const val KEY_NEWEST_SEEN = "newest_seen_ms"
    private const val A11Y_DISCLOSURE_VERSION = 1
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

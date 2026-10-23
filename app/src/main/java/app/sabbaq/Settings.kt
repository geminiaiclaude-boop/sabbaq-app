package app.sabbaq

import android.content.Context
import android.content.SharedPreferences
import app.sabbaq.core.Filters
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
    private var cachedFilters: Filters? = null

    fun init(context: Context) {
        synchronized(this) {
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            cachedFilters = null
        }
    }

    /**
     * لا تكون صلاحية التشغيل قابلة للاستئناف بعد قتل العملية: جلسة الشبكة ذاكرية فقط،
     * لذا تبقى الخدمة معطلة إلى أن يمر المستخدم بالمصادقة والتحقق من الترخيص مجددًا.
     */
    fun clearProcessAuthorizationState() {
        isActive = false
    }

    var filters: Filters
        get() = synchronized(this) {
            cachedFilters ?: run {
                val stored = prefs().getString(KEY_FILTERS, null)
                val value = try {
                    stored?.let { Json.decodeFromString<Filters>(it) } ?: Filters()
                } catch (_: Exception) {
                    Filters()
                }
                cachedFilters = value
                value
            }
        }
        set(value) {
            synchronized(this) {
                prefs().edit().putString(KEY_FILTERS, Json.encodeToString(value)).apply()
                cachedFilters = null
            }
        }

    var isActive: Boolean
        get() = prefs().getBoolean(KEY_IS_ACTIVE, false)
        set(value) {
            prefs().edit().putBoolean(KEY_IS_ACTIVE, value).apply()
        }

    /**
     * The driver's own choice to keep monitoring on, kept across restarts. [isActive] is the
     * runtime gate the accessibility service reads; it is only re-opened after the license is
     * confirmed again, while this flag survives so monitoring resumes on its own.
     */
    var monitoringWanted: Boolean
        get() = runCatching { prefs().getBoolean(KEY_MONITORING_WANTED, false) }.getOrDefault(false)
        set(value) { runCatching { prefs().edit().putBoolean(KEY_MONITORING_WANTED, value).apply() } }

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
    suspend fun saveFilters(value: Filters): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@Settings) {
            val saved = runCatching {
                prefs().edit().putString(KEY_FILTERS, Json.encodeToString(value)).commit()
            }.getOrDefault(false)
            if (saved) cachedFilters = null
            saved
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
                    .remove(KEY_FILTERS)
                    .putBoolean(KEY_IS_ACTIVE, false)
                    .remove(KEY_A11Y_DISCLOSURE_VERSION)
                    .remove(KEY_KNOWN_SUBSCRIBER)
                    .remove(KEY_MONITORING_WANTED)
                    .commit()
            }.getOrDefault(false)
            if (cleared) cachedFilters = null
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
    private const val KEY_FILTERS = "filters"
    private const val KEY_IS_ACTIVE = "is_active"
    private const val KEY_A11Y_DISCLOSURE_VERSION = "a11y_disclosure_version"
    private const val KEY_KNOWN_SUBSCRIBER = "known_subscriber"
    private const val KEY_MONITORING_WANTED = "monitoring_wanted"
    private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"
    private const val A11Y_DISCLOSURE_VERSION = 1
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

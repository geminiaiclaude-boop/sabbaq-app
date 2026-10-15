package app.qannas

import android.content.Context
import android.content.SharedPreferences
import app.qannas.core.Filters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** إعدادات غير حساسة فقط؛ رموز الجلسات ومعرّفات الأجهزة لا تُخزَّن هنا. */
object Settings {
    @Volatile
    private var preferences: SharedPreferences? = null

    fun init(context: Context) {
        synchronized(this) {
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * لا تكون صلاحية التشغيل قابلة للاستئناف بعد قتل العملية: جلسة الشبكة ذاكرية فقط،
     * لذا تبقى الخدمة معطلة إلى أن يمر المستخدم بالمصادقة والتحقق من الترخيص مجدداً.
     */
    fun clearProcessAuthorizationState() {
        isActive = false
    }

    var filters: Filters
        get() {
            val stored = prefs().getString(KEY_FILTERS, null) ?: return Filters()
            return try {
                Json.decodeFromString(stored)
            } catch (_: Exception) {
                Filters()
            }
        }
        set(value) {
            prefs().edit().putString(KEY_FILTERS, Json.encodeToString(value)).apply()
        }

    var isActive: Boolean
        get() = prefs().getBoolean(KEY_IS_ACTIVE, false)
        set(value) {
            prefs().edit().putBoolean(KEY_IS_ACTIVE, value).apply()
        }

    /** A validated draft is committed as one document, on IO, before the UI reports success. */
    suspend fun saveFilters(value: Filters): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            prefs().edit().putString(KEY_FILTERS, Json.encodeToString(value)).commit()
        }.getOrDefault(false)
    }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs().getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) { prefs().edit().putString("theme_mode", value.name).apply() }

    private fun prefs(): SharedPreferences = preferences
        ?: throw IllegalStateException("يجب استدعاء Settings.init قبل استخدام الإعدادات")

    private const val PREFERENCES_NAME = "qannas_prefs"
    private const val KEY_FILTERS = "filters"
    private const val KEY_IS_ACTIVE = "is_active"
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

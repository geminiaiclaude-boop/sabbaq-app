package app.sabbaq

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The app's language. English is the default resource set and Arabic lives in values-ar, so
 * Android already opens Arabic on an Arabic device and English on any other language, with no
 * code. This only adds the user's explicit choice.
 *
 * On Android 13+ the framework stores the per-app language itself. Below that, the choice is
 * kept here and re-applied at process start ([restore]). AndroidX's own `autoStoreLocales` is
 * deliberately off: on its first run on Android 13+ it copies its (empty) storage over the
 * framework's per-app language and can silently wipe a language the user already chose.
 */
object LocaleManager {
    /** The languages offered in the in-app switcher, in display order. */
    enum class Language(val tag: String) { ARABIC("ar"), ENGLISH("en") }

    private const val PREFS = "sabbaq_locale"
    private const val KEY_LANGUAGE = "language"

    /** The language in force now, or null when following the device language (no explicit choice). */
    fun current(): Language? {
        val tag = runCatching {
            AppCompatDelegate.getApplicationLocales().takeUnless { it.isEmpty }?.get(0)?.language
        }.getOrNull()
        return Language.entries.firstOrNull { it.tag == tag }
    }

    /** True when Arabic is what the user sees right now, whether chosen or inherited from the device. */
    fun isArabic(): Boolean = when (current()) {
        Language.ARABIC -> true
        Language.ENGLISH -> false
        null -> java.util.Locale.getDefault().language == "ar"
    }

    /** Applies the user's choice; AndroidX recreates the activities. */
    fun set(context: Context, language: Language) {
        if (Build.VERSION.SDK_INT < 33) {
            prefs(context).edit().putString(KEY_LANGUAGE, language.tag).apply()
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    /** Re-applies a stored choice below Android 13, before any activity is created. */
    fun restore(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val tag = prefs(context).getString(KEY_LANGUAGE, null) ?: return
        if (Language.entries.none { it.tag == tag }) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

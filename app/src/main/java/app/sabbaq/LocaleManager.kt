package app.sabbaq

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The app's language. English is the default resource set and Arabic lives in values-ar, so
 * Android already opens Arabic on an Arabic device and English on any other language, with no
 * code. This only adds the user's explicit choice, which AndroidX persists automatically (the
 * AppLocalesMetadataHolderService in the manifest) so it survives restarts and never silently
 * turns an Arabic phone into English unless the user asked.
 */
object LocaleManager {
    /** The languages offered in the in-app switcher, in display order. */
    enum class Language(val tag: String) { ARABIC("ar"), ENGLISH("en") }

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

    /** Applies the user's choice; AndroidX recreates the activities and stores it. */
    fun set(language: Language) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}

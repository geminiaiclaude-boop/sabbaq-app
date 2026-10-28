package app.sabbaq.ui

import app.sabbaq.LocaleManager
import java.util.Locale

/**
 * Arabic month names with Western digits, the one numeral system used across the app
 * (fares, steppers, counters); "ar-SA" alone switches some devices to Arabic-Indic digits
 * and mixes "٠" with "0" on the same screen.
 */
internal val SabbaqLocale: Locale = Locale.forLanguageTag("ar-u-nu-latn")

/** The locale used to format dates and month names: Arabic (Latin digits) or English. */
internal fun sabbaqDateLocale(): Locale = if (LocaleManager.isArabic()) SabbaqLocale else Locale.ENGLISH

/**
 * Counted-noun agreement for the few counts the UI shows. Arabic changes the noun form with
 * the number ("شرط واحد" / "شرطان" / "5 شروط"); English keeps the plain singular/plural. Each
 * function returns the phrase in whichever language is in force, so call sites stay unchanged.
 */
internal object ArabicCount {
    private fun ar(): Boolean = LocaleManager.isArabic()

    /** e.g. 1 → "شرط واحد" / "1 condition", else "5 شروط" / "5 conditions". */
    fun conditions(n: Int): String = when {
        !ar() -> if (n == 1) "1 condition" else "$n conditions"
        n == 1 -> "شرط واحد"
        n == 2 -> "شرطان"
        n in 3..10 -> "$n شروط"
        else -> "$n شرطًا"
    }

    /** Accusative form used after a verb in Arabic; plain plural in English. */
    fun conditionsObject(n: Int): String = when {
        !ar() -> if (n == 1) "1 condition" else "$n conditions"
        n == 1 -> "شرطًا واحدًا"
        n == 2 -> "شرطين"
        n in 3..10 -> "$n شروط"
        else -> "$n شرطًا"
    }

    /** Remaining steps. */
    fun steps(n: Int): String = when {
        !ar() -> if (n == 1) "1 step" else "$n steps"
        n == 1 -> "خطوة واحدة"
        n == 2 -> "خطوتان"
        n in 3..10 -> "$n خطوات"
        else -> "$n خطوة"
    }

    /** Permissions/requirements. */
    fun permissions(n: Int): String = when {
        !ar() -> if (n == 1) "1 permission" else "$n permissions"
        n == 1 -> "إذن واحد"
        n == 2 -> "إذنان"
        n in 3..10 -> "$n أذونات"
        else -> "$n إذنًا"
    }

    /** Unread notifications. */
    fun unreadNotifications(n: Int): String = when {
        !ar() -> if (n == 1) "1 unread notification" else "$n unread notifications"
        n == 1 -> "إشعار واحد غير مقروء"
        n == 2 -> "إشعاران غير مقروءين"
        n in 3..10 -> "$n إشعارات غير مقروءة"
        else -> "$n إشعارًا غير مقروء"
    }

    /** Days. */
    fun days(n: Int): String = when {
        !ar() -> if (n == 1) "1 day" else "$n days"
        n == 1 -> "يوم واحد"
        n == 2 -> "يومان"
        n in 3..10 -> "$n أيام"
        else -> "$n يومًا"
    }

    fun days(n: Int?): String = days(n ?: 0)

    /** Seconds. */
    fun seconds(n: Int): String = when {
        !ar() -> if (n == 1) "1 second" else "$n seconds"
        n == 1 -> "ثانية واحدة"
        n == 2 -> "ثانيتين"
        n in 3..10 -> "$n ثوانٍ"
        else -> "$n ثانية"
    }

    /** Minutes. */
    fun minutes(n: Int): String = when {
        !ar() -> if (n == 1) "1 minute" else "$n minutes"
        n == 1 -> "دقيقة واحدة"
        n == 2 -> "دقيقتين"
        n in 3..10 -> "$n دقائق"
        else -> "$n دقيقة"
    }

    /** Hours. */
    fun hours(n: Int): String = when {
        !ar() -> if (n == 1) "1 hour" else "$n hours"
        n == 1 -> "ساعة واحدة"
        n == 2 -> "ساعتين"
        n in 3..10 -> "$n ساعات"
        else -> "$n ساعة"
    }
}

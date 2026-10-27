package app.sabbaq.ui

import java.util.Locale

/**
 * Arabic month names with Western digits, the one numeral system used across the app
 * (fares, steppers, counters); "ar-SA" alone switches some devices to Arabic-Indic digits
 * and mixes "٠" with "0" on the same screen.
 */
internal val SabbaqLocale: Locale = Locale.forLanguageTag("ar-u-nu-latn")

/**
 * Arabic counted-noun agreement for the few counts the UI shows. "1 شرط" or "2 شرط"
 * reads as machine output; Arabic changes the noun form with the number.
 */
internal object ArabicCount {
    /** e.g. 1 → "شرط واحد", 2 → "شرطان", 5 → "5 شروط", 12 → "12 شرطًا". */
    fun conditions(n: Int): String = when {
        n == 1 -> "شرط واحد"
        n == 2 -> "شرطان"
        n in 3..10 -> "$n شروط"
        else -> "$n شرطًا"
    }

    /** Accusative form used after a verb: "لا يطابق شرطًا واحدًا" / "شرطين" / "3 شروط". */
    fun conditionsObject(n: Int): String = when {
        n == 1 -> "شرطًا واحدًا"
        n == 2 -> "شرطين"
        n in 3..10 -> "$n شروط"
        else -> "$n شرطًا"
    }

    /** Remaining steps: 1 → "خطوة واحدة", 2 → "خطوتان", 3..10 → "3 خطوات". */
    fun steps(n: Int): String = when {
        n == 1 -> "خطوة واحدة"
        n == 2 -> "خطوتان"
        n in 3..10 -> "$n خطوات"
        else -> "$n خطوة"
    }

    /** Permissions/requirements: 1 → "إذن واحد", 2 → "إذنان", 3..10 → "3 أذونات". */
    fun permissions(n: Int): String = when {
        n == 1 -> "إذن واحد"
        n == 2 -> "إذنان"
        n in 3..10 -> "$n أذونات"
        else -> "$n إذنًا"
    }

    /** Days: 1 → "يوم واحد", 2 → "يومان", 3..10 → "3 أيام", 11+ → "30 يومًا". */
    fun days(n: Int): String = when {
        n == 1 -> "يوم واحد"
        n == 2 -> "يومان"
        n in 3..10 -> "$n أيام"
        else -> "$n يومًا"
    }

    fun days(n: Int?): String = days(n ?: 0)

    /** Seconds: 1 → "ثانية واحدة", 2 → "ثانيتين", 3..10 → "5 ثوانٍ", 11+ → "30 ثانية". */
    fun seconds(n: Int): String = when {
        n == 1 -> "ثانية واحدة"
        n == 2 -> "ثانيتين"
        n in 3..10 -> "$n ثوانٍ"
        else -> "$n ثانية"
    }

    /** Minutes: 1 → "دقيقة واحدة", 2 → "دقيقتين", 3..10 → "5 دقائق", 11+ → "15 دقيقة". */
    fun minutes(n: Int): String = when {
        n == 1 -> "دقيقة واحدة"
        n == 2 -> "دقيقتين"
        n in 3..10 -> "$n دقائق"
        else -> "$n دقيقة"
    }

    /** Hours: 1 → "ساعة واحدة", 2 → "ساعتين", 3..10 → "5 ساعات", 11+ → "12 ساعة". */
    fun hours(n: Int): String = when {
        n == 1 -> "ساعة واحدة"
        n == 2 -> "ساعتين"
        n in 3..10 -> "$n ساعات"
        else -> "$n ساعة"
    }
}

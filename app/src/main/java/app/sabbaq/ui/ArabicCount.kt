package app.sabbaq.ui

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
}

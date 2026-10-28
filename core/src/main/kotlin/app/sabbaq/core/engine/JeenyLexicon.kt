package app.sabbaq.core.engine

import java.util.regex.Pattern

/**
 * The Jeeny screen vocabulary, word for word as FareSniper 1.0.16 ships it
 * (docs/engine-parity/QANNAS_ENGINE_FACTS.md §12), including its own spellings («قتصل»,
 * «مستلام», «مواعد»). Keyword lists are matched as quoted substrings, case-insensitive with
 * Unicode case folding, exactly like FareSniper; the driver's extra keywords extend them.
 */
class JeenyLexicon(
    acceptExtra: String = "",
    timeExtra: String = "",
    currencyExtra: String = "",
    noiseExtra: String = "",
) {
    val accept: Pattern = anyOf(ACCEPT + split(acceptExtra))
    val currency: Pattern = anyOf(CURRENCY_SAR + CURRENCY_JOD + split(currencyExtra))
    val noise: Pattern = anyOf(NOISE + split(noiseExtra))
    val time: Pattern = Pattern.compile(
        "(?<!\\d)(\\d+)\\s*(?:" + (TIME_WORDS + split(timeExtra).map(Pattern::quote)).joinToString("|") + ")",
        FLAGS,
    )
    val hasTimeExtra: Boolean = split(timeExtra).isNotEmpty()

    companion object {
        const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

        val ACCEPT = listOf("accept", "موافق", "مواعد", "offer", "عرض", "confirm", "تأكيد", "take", "استلام", "قبول")
        val RULE_WORDS = listOf("rule", "قواعد", "شرط", "معدل القبول")
        val REJECT = listOf("reject", "رفض")
        val CURRENCY_SAR = listOf("sar", "﷼", "₡", "net", "earn", "⃁", "مستلام", "ريال", "ر.س", "صافي")
        val CURRENCY_JOD = listOf("jod", "دوج", "دينار", "مستلام")
        val NOISE = listOf(
            "economy", "إيكونومي", "standard", "ستاندرد", "lite", "لايت", "accept", "موافق", "reject", "رفض",
            "jod", "د.أ", "$", "mins", "min", "دقيقة", "دقائق", "يبعد", "يبعُد", "قبول", "عرض", "الطلب", "العرض",
            "km", "كم", "meter", "متر", "m ", "mi ", "cash", "نقدي", "card", "بطاقة", "wallet", "محفظة",
            "promo", "خصم", "discount", "coupon",
        )
        val ONLINE = listOf("online", "قتصل", "you're online", "أنت متصل")
        val UNIT_WORDS = listOf("km", "m", "م", "متر", "meter", "min", "دقيقة", "دقائق")
        val ACTIVE_RIDE = listOf(
            "arrived at pickup", "cancel ride", "end ride", "navigate", "picking", "pickup location", "in progress",
            "وصلت إلى نقطة الانطلاق", "وصلت الى نقطة الانطلاق", "وصلت إلى نقطة الإقلاع", "نقطة الانطلاق",
            "إنهاء المشوار", "انهاء المشوار", "إلغاء المشوار", "الغاء المشوار", "عرض الاتجاهات", "عرض الإتجاهات",
            "التوصيل", "في التوصيل", "جارٍ التوصيل",
        )
        val NETWORK_ERRORS = listOf(
            "check your network", "check your internet", "check your connection", "no internet",
            "internet connection", "network connection", "no connection", "you're offline", "you are offline",
            "connection lost", "reconnecting", "تحقق من الشبكة", "تحقق من اتصال", "تحقق من الاتصال", "تأكد من اتصال",
            "تاكد من اتصال", "الاتصال بالإنترنت", "الاتصال بالانترنت", "لا يوجد اتصال", "لا يوجد إنترنت",
            "لا يوجد انترنت", "انقطع الاتصال", "جاري إعادة الاتصال", "جاري اعادة الاتصال",
        )
        val CARD = listOf("card", "credit", "visa", "mada", "mastercard", "بطاقة", "ائتمان", "فيزا", "wallet", "محفظة")
        val CASH = listOf("cash", "نقد", "كاش", "money")
        val TIME_WORDS = listOf("mins", "min", "دقائق", "دقيقة", "دقيقه", "دقائ", "mn", "away", "بعيد", "قريب")
        /** Lower-case substrings that make a node worth a time read (Pk.provider). */
        val TIME_HINTS = listOf("min", "دق", "mn", "away", "بعيد", "قريب")

        val RULE: Pattern = anyOf(RULE_WORDS)
        val REJECT_PATTERN: Pattern = anyOf(REJECT)
        val ONLINE_PATTERN: Pattern = anyOf(ONLINE)
        val UNIT_PATTERN: Pattern = anyOf(UNIT_WORDS)
        val ACTIVE_RIDE_PATTERN: Pattern = anyOf(ACTIVE_RIDE)
        val NETWORK_PATTERN: Pattern = anyOf(NETWORK_ERRORS)
        val CARD_PATTERN: Pattern = anyOf(CARD)
        val CASH_PATTERN: Pattern = anyOf(CASH)

        private const val CURRENCY_MARKS = "\\$|JOD|SAR|د\\.أ|د\\.ا|د\\.|﷼|ر\\.س|⃁"
        /** Amount next to a currency mark; case-sensitive like FareSniper. */
        val PRICE: Pattern = Pattern.compile(
            "(?:$CURRENCY_MARKS)\\s*(\\d+(?:[\\.٫]\\d+)?)|(\\d+(?:[\\.٫]\\d+)?)\\s*(?:$CURRENCY_MARKS)",
        )
        val NON_NUMBER: Pattern = Pattern.compile("[^0-9.,٫]")
        val LEADING_DISTANCE: Pattern = Pattern.compile("^\\d+([.,٫]\\d+)?\\s*(km|m|mi|كم|متر|م\\s|كيلو)")
        val NUMERIC_ONLY: Pattern = Pattern.compile("^[\\d.,٫\\s★☆⭐%٪]+$")
        /** Currency words removed before counting an address's letters. */
        val CURRENCY_WORDS: Pattern = Pattern.compile("(?i)ريال|ر\\.س|دينار|sar|jod|sr")

        fun anyOf(words: List<String>): Pattern =
            Pattern.compile(words.filter { it.isNotEmpty() }.joinToString("|") { Pattern.quote(it) }, FLAGS)

        fun split(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** Arabic-Indic digits to Latin, the only digit folding FareSniper applies. */
        fun latinDigits(s: String): String {
            if (s.isEmpty()) return s
            val sb = StringBuilder(s.length)
            for (ch in s) sb.append(if (ch in '٠'..'٩') '0' + (ch - '٠') else ch)
            return sb.toString()
        }
    }
}

internal fun Pattern.found(input: String): Boolean = matcher(input).find()

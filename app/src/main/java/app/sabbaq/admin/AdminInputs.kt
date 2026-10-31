package app.sabbaq.admin

import app.sabbaq.LocaleManager

/**
 * The administration's input rules, pure so they are unit tested: Arabic and Persian digits are
 * accepted, money is parsed from text into halalas without floating point, and CSV fields are
 * neutralised against spreadsheet formula injection. Same rules as the server and the web panel.
 */
object AdminInputs {
    private val ar: Boolean get() = LocaleManager.isArabic()

    const val MIN_MINOR = 1L
    const val MAX_MINOR = 100_000_000L
    private const val MAX_CONTACT_URL_BYTES = 2048
    val WHATSAPP = Regex("^[1-9]\\d{7,14}$")

    fun normalizeDigits(value: String): String = buildString(value.length) {
        for (char in value) append(
            when (char) {
                in '٠'..'٩' -> '0' + (char - '٠')
                in '۰'..'۹' -> '0' + (char - '۰')
                else -> char
            }
        )
    }

    /** A whole number in [minimum, maximum] typed in any digit script; null when it is anything else. */
    fun boundedInt(raw: String, minimum: Int, maximum: Int): Int? {
        val normalized = normalizeDigits(raw.trim())
        if (normalized.isEmpty() || normalized.any { it !in '0'..'9' }) return null
        val canonical = normalized.trimStart('0').ifEmpty { "0" }
        if (canonical.length > maximum.toString().length) return null
        return canonical.toInt().takeIf { it in minimum..maximum }
    }

    /** "18.75" → 1875 halalas; at most two decimals, accepts «٫» and «,» as the separator. */
    fun sarToMinor(raw: String): Long? {
        val normalized = normalizeDigits(raw.trim()).replace('٫', '.').replace(',', '.')
        val match = Regex("^(\\d+)(?:\\.(\\d{1,2}))?$").matchEntire(normalized) ?: return null
        val whole = match.groupValues[1].trimStart('0').ifEmpty { "0" }
        if (whole.length > 7) return null
        val fraction = match.groupValues[2].padEnd(2, '0')
        val minor = whole.toLong() * 100 + fraction.toLong()
        return minor.takeIf { it in MIN_MINOR..MAX_MINOR }
    }

    fun minorToSar(minor: Long): String =
        if (minor !in MIN_MINOR..MAX_MINOR) "" else "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    sealed interface Whatsapp {
        data object Empty : Whatsapp
        data class Valid(val digits: String) : Whatsapp
        data object Invalid : Whatsapp
    }

    /** wa.me digits: 05XXXXXXXX becomes 9665XXXXXXXX and a 00 prefix is dropped. */
    fun whatsapp(raw: String): Whatsapp {
        var digits = normalizeDigits(raw).filterNot { it.isWhitespace() || it in "()+-" }
        if (digits.isEmpty()) return Whatsapp.Empty
        if (Regex("^05\\d{8}$").matches(digits)) digits = "966" + digits.substring(1)
        else if (Regex("^00[1-9].*").matches(digits)) digits = digits.substring(2)
        return if (WHATSAPP.matches(digits)) Whatsapp.Valid(digits) else Whatsapp.Invalid
    }

    fun isHttpsContact(value: String): Boolean =
        Regex("^https://\\S+$").matches(value) && value.toByteArray(Charsets.UTF_8).size <= MAX_CONTACT_URL_BYTES

    /** A quoted CSV field; a value a spreadsheet would run as a formula gets a leading quote. */
    fun csvField(value: Any?): String {
        val text = (value?.toString() ?: "").replace("\u0000", "")
        val safe = if (Regex("^[\\u0000-\\u0020\\uFEFF]*[=+\\-@].*", RegexOption.DOT_MATCHES_ALL).matches(text)) "'$text" else text
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    /** UTF-8 with a byte-order mark so spreadsheet apps read the Arabic header correctly. */
    fun codesCsv(rows: List<AdminExportCode>): String =
        "\uFEFF" + (listOf(if (ar) listOf<Any?>("الكود", "المدة (أيام)", "الدفعة") else listOf<Any?>("Code", "Duration (days)", "Batch")) + rows.map { listOf(it.code, it.days, it.batch) })
            .joinToString("\r\n") { row -> row.joinToString(",", transform = ::csvField) }

    fun androidRelease(api: Int?): String? {
        api ?: return null
        val releases = mapOf(26 to "8", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16")
        return releases[api] ?: "API $api"
    }
}

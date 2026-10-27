package app.sabbaq.core

import java.math.BigDecimal

/**
 * Normalisation shared by every comparison the engine makes. Ride apps mix
 * Arabic-Indic digits, bidi control marks and non-breaking spaces into the
 * same label, so raw string matching is never reliable.
 */
object Text {
    private val bidiMarks = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u061C\\uFEFF]")
    private val spaces = Regex("[\\s\\u00A0\\u202F]+")
    private val tatweel = Regex("\\u0640")
    // Harakat, tanween, shadda, sukun and superscript alef: Jeeny writes «يبعُد», profiles say «يبعد».
    private val harakat = Regex("[\\u064B-\\u065F\\u0670]")

    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٫' -> '.'
                    '٬' -> ','
                    '،' -> ','
                    else -> ch
                }
            )
        }
        val stripped = harakat.replace(tatweel.replace(bidiMarks.replace(sb.toString(), ""), ""), "")
        return spaces.replace(stripped, " ").trim().lowercase()
    }

    /** Number grammar accepted inside labels: `1,250.50`, `3.5`, `3,5`, `12`. */
    const val NUMBER = "\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?"

    fun parseNumber(token: String): BigDecimal? {
        val t = token.trim()
        if (t.isEmpty()) return null
        val canonical = when {
            t.contains('.') && t.contains(',') -> t.replace(",", "")
            t.contains(',') -> {
                val groups = t.split(',')
                val thousands = groups.size > 1 && groups.drop(1).all { it.length == 3 } && groups[0] != "0"
                if (thousands) t.replace(",", "") else t.replace(',', '.')
            }
            else -> t
        }
        return canonical.toBigDecimalOrNull()
    }

    /** Builds an alternation that matches any token as a standalone word. */
    fun tokenAlternation(tokens: Collection<String>): String =
        tokens.map { normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }

    /** True when [label] appears in [text] bounded by non-letters on both sides. */
    fun containsWord(text: String, label: String): Boolean {
        if (label.isEmpty()) return false
        var from = 0
        while (true) {
            val i = text.indexOf(label, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val afterIndex = i + label.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetter() && !after.isLetter()) return true
            from = i + 1
        }
    }
}

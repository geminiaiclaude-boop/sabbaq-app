package app.sabbaq.core

/**
 * An anonymous outline of a ride-app screen Sabbaq could not read, for support to fix the
 * reading profile over the air. It keeps the structure (depth, widget type, view id, clickable)
 * and only the words the profile itself knows (button labels, currency, units, role words);
 * every number keeps its shape as 9s and every other word becomes «ع» (Arabic) or «a» (Latin),
 * so names, addresses and fares never leave the phone.
 */
object ScreenSketch {
    const val MAX_CHARS = 900
    private const val MAX_NODES = 400

    fun render(root: ScreenNode, profile: RideProfile, maxChars: Int = MAX_CHARS): String {
        val vocabulary = vocabulary(profile)
        val lines = ArrayList<String>()
        var visited = 0
        fun visit(node: ScreenNode, depth: Int) {
            if (visited++ >= MAX_NODES) return
            val text = Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }
            val id = node.viewId?.substringAfter(":id/")?.take(24)
            if (text.isNotEmpty() || node.clickable || id != null) {
                lines += buildString {
                    append(depth).append(' ')
                    append(node.className?.substringAfterLast('.')?.take(12) ?: "View")
                    id?.let { append('#').append(it) }
                    if (node.clickable) append('*')
                    if (text.isNotEmpty()) append(" \"").append(mask(text, vocabulary)).append('"')
                }
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        val out = StringBuilder()
        for (line in lines) {
            if (out.length + line.length + 1 > maxChars) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    internal fun mask(text: String, vocabulary: Set<String>): String =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { word ->
            val bare = word.trim(',', '.', '،', ':', '·', '(', ')')
            when {
                bare.isEmpty() -> word
                bare in vocabulary -> word
                bare.any { it.isDigit() } -> bare.map { if (it.isDigit()) '9' else if (it == '.' || it == ',' || it == '٫' || it == ':') it else 'x' }.joinToString("")
                bare.any { it in '؀'..'ۿ' } -> "ع"
                bare.any { it.isLetter() } -> "a"
                else -> word
            }
        }.take(60)

    private fun vocabulary(profile: RideProfile): Set<String> = buildSet {
        (profile.acceptLabels + profile.confirmLabels + profile.weakConfirmLabels + profile.currencyTokens +
            profile.kmUnits + profile.meterUnits + profile.minuteUnits + profile.hourUnits + ROLE_WORDS)
            .flatMap { Text.normalize(it).split(Regex("\\s+")) }
            .filter { it.isNotEmpty() }
            .forEach { add(it) }
    }

    private val ROLE_WORDS = listOf("الوصول", "يبعد", "الرحلة", "المسافة", "مسافة", "مدة", "pickup", "trip", "away", "distance", "نقدي", "cash")
}

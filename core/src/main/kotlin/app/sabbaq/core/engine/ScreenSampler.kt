package app.sabbaq.core.engine

/**
 * A redacted structure of one ride-app screen, sent when an offer screen could not be used for a
 * reason other than the driver's conditions, so the reader can be taught the new layout.
 *
 * What leaves the phone: short class name, view-id entry, bounds, clickable/enabled, depth and a
 * redacted text. Text keeps numbers, words the reader already knows and short button labels; any
 * other word becomes a letter-shape token («حي النرجس» → «اا ااااا»), so addresses and names never
 * leave the device. Digit runs of seven or more (phone numbers, IDs), e-mail and link text are
 * dropped; the server rejects any sample that still carries them.
 */
object ScreenSampler {
    const val MAX_NODES = 150
    const val MAX_TEXT = 40
    private const val BUTTON_LABEL_MAX = 24
    private const val BUTTON_LABEL_WORDS = 3

    class Node(
        val className: String,
        val viewId: String,
        val bounds: IntArray,
        val clickable: Boolean,
        val enabled: Boolean,
        val text: String?,
        val depth: Int,
    )

    class Sample(val nodes: List<Node>, val signature: String)

    /** Words the reader knows: the built-in vocabulary plus [extra] (shared lexicon), lowercase. */
    fun vocabulary(extra: Collection<String> = emptyList()): Set<String> =
        (JeenyLexicon.ACCEPT + JeenyLexicon.REJECT + JeenyLexicon.CURRENCY_SAR + JeenyLexicon.CURRENCY_JOD +
            JeenyLexicon.NOISE + JeenyLexicon.ONLINE + JeenyLexicon.UNIT_WORDS + JeenyLexicon.ACTIVE_RIDE +
            JeenyLexicon.CARD + JeenyLexicon.CASH + JeenyLexicon.TIME_WORDS + JeenyLexicon.RULE_WORDS + extra)
            .flatMap { it.lowercase().split(WHITESPACE) }
            .filter { it.isNotBlank() }
            .toSet()

    fun capture(root: UiNode, known: Set<String>): Sample {
        val nodes = ArrayList<Node>()
        val queue = ArrayDeque<Pair<UiNode, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            val raw = node.text?.takeIf { it.isNotBlank() } ?: node.description
            val b = runCatching { node.bounds() }.getOrNull()
            nodes += Node(
                className = node.className.orEmpty().substringAfterLast('.').filter { it.isLetterOrDigit() && it.code < 128 || it == '.' }.take(40),
                viewId = node.viewId.orEmpty().substringAfter(":id/").lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(64),
                bounds = if (b == null) intArrayOf(0, 0, 0, 0) else intArrayOf(b.left, b.top, b.right, b.bottom),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                text = redact(raw, node.isClickable, known),
                depth = depth,
            )
            for (i in 0 until node.childCount) {
                if (nodes.size + queue.size >= MAX_NODES) break
                node.child(i)?.let { queue.addLast(it to depth + 1) }
            }
        }
        return Sample(nodes, signature(nodes))
    }

    /** The redacted text of one node, or null when nothing is left. */
    fun redact(raw: String?, clickable: Boolean, known: Set<String>): String? {
        val text = JeenyLexicon.latinDigits(raw.orEmpty()).replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null
        val words = text.split(' ')
        val label = clickable && words.size <= BUTTON_LABEL_WORDS && text.length <= BUTTON_LABEL_MAX && text.none { it.isDigit() }
        val out = words.mapNotNull { word ->
            when {
                '@' in word || word.contains("http", ignoreCase = true) || word.startsWith("www.", ignoreCase = true) -> null
                label || word.lowercase() in known -> word
                else -> shape(word)
            }
        }.joinToString(" ").replace(LONG_DIGITS, "#").trim()
        return out.take(MAX_TEXT).trim().ifEmpty { null }
    }

    /** Arabic letters → «ا», Latin letters → «a»; digits and currency/punctuation marks stay. */
    fun shape(word: String): String = buildString {
        for (c in word) when {
            c.isDigit() -> append(c)
            c in '؀'..'ۿ' && c.isLetter() -> append('ا')
            c.isLetter() -> append('a')
            c in KEPT_MARKS -> append(c)
        }
    }

    /** 16 hex chars over the structure only (depth, class, view id, clickable): same layout, same signature. */
    fun signature(nodes: List<Node>): String {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (n in nodes) {
            for (c in "${n.depth}|${n.className}|${n.viewId}|${if (n.clickable) 1 else 0};") {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    private val WHITESPACE = Regex("\\s+")
    private val LONG_DIGITS = Regex("\\d{7,}")
    private const val KEPT_MARKS = ".,:/%+-$€⃁﷼₡()"
}

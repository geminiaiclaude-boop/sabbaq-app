package app.sabbaq.core

/**
 * Bounded, process-independent hint set. Hints are never proof of an accept
 * control; callers must verify a live node and its matching offer context.
 */
class VerifiedIdHints(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxIdLength: Int = DEFAULT_MAX_ID_LENGTH,
) {
    init {
        require(maxEntries > 0)
        require(maxIdLength > 0)
    }

    private val ids = LinkedHashSet<String>()

    fun learn(id: String?): Boolean {
        val safe = sanitize(id) ?: return false
        if (safe in ids) return true
        if (ids.size >= maxEntries) return false
        ids += safe
        return true
    }

    fun replace(values: Iterable<String?>) {
        ids.clear()
        values.forEach { learn(it) }
    }

    fun snapshot(): List<String> = ids.toList()

    private fun sanitize(id: String?): String? {
        val value = id?.trim() ?: return null
        if (value.isEmpty() || value.length > maxIdLength) return null
        // Resource IDs only; reject labels, URLs, whitespace and control chars.
        if (!value.matches(Regex("^[A-Za-z0-9_.:/-]+$"))) return null
        return value
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 8
        const val DEFAULT_MAX_ID_LENGTH = 160
    }
}

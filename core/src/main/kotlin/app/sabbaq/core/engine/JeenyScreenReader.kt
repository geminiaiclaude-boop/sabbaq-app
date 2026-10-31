package app.sabbaq.core.engine

import java.util.concurrent.atomic.AtomicBoolean

/** The three resource ids learned from a successful read; later reads jump straight to them. */
data class LearnedOfferIds(val acceptId: String, val priceId: String, val timeId: String)

enum class Payment { UNKNOWN, CASH, CARD }

/** What one read of a ride-app screen produced (the reference engine `Ok`). */
data class ParsedRide(
    val price: Double? = null,
    val minutes: Int? = null,
    val destination: String? = null,
    /** The live node to tap: the very node that was read. */
    val acceptNode: UiNode? = null,
    /** Destination could not be told apart from the pickup (0 or 1 address, no label). */
    val addressAmbiguous: Boolean = false,
    val clickX: Int = 0,
    val clickY: Int = 0,
    val isActiveRide: Boolean = false,
    val addresses: List<String> = emptyList(),
    val pickup: String? = null,
    val payment: Payment = Payment.UNKNOWN,
    /** Passenger rating; the reference engine reads it on Bolt only. */
    val rating: Double? = null,
    /** "Wait & save" / economy offer; the reference engine reads it on Bolt only. */
    val waitAndSave: Boolean = false,
    val acceptViewId: String? = null,
    val priceViewId: String? = null,
    val timeViewId: String? = null,
    val fromFastIds: Boolean = false,
    val aborted: Boolean = false,
    val nodesVisited: Int = 0,
    /** Centres (x, y px) of the offer map's markers, read only when a zone needs the direction. */
    val markers: List<IntArray> = emptyList(),
    /** Which accept word made the accept control (index in JeenyLexicon.ACCEPT), -1 when none. */
    val acceptWord: Int = -1,
    /** Which ride app the screen came from: "jeeny", "bolt" or "petra" (telemetry and the decision log). */
    val provider: String = "jeeny",
) {
    /** The reference engine's screen-offer hash: same values at the same tap point = same frame. */
    val screenHash: Int get() = identityHash(price, minutes, destination) * 31 * 31 + clickX * 31 + clickY

    /** The reference engine's offer identity (price, minutes, destination). */
    val identity: Int get() = identityHash(price, minutes, destination)

    companion object {
        fun identityHash(a: Any?, b: Any?, c: Any?): Int =
            (((a?.hashCode() ?: 0) * 31) + (b?.hashCode() ?: 0)) * 31 + (c?.hashCode() ?: 0)
    }
}

/**
 * Reads a Jeeny screen exactly as the reference engine's `Pk.provider` / `Pk.dispatch` do
 * (reference engine notes). A screen is an offer when it shows an accept control and the
 * time to the customer; the price is required only while the price filter is on, and missing
 * addresses never stop a read.
 */
class JeenyScreenReader(private val config: EngineConfig, private val uptimeMs: () -> Long) {
    /**
     * Why the last [read] returned null, for the acceptance funnel: "not_offer_screen" (no offer
     * signal at all), "online_screen", or the missing piece of an offer that was half seen:
     * "no_accept", "no_minutes", "no_price". [lastMissDetail] says what was seen (p, m, a = 0/1,
     * n = nodes read, cap = node limit hit).
     */
    var lastMiss: String? = null
        private set
    var lastMissDetail: String? = null
        private set

    private val lex = config.lexicon()
    private val learnedIds: Set<String> = config.acceptViewIdsExtra.split(',').map { it.trim().lowercase() }
        .filter(app.sabbaq.core.SharedLexicon::validId).toSet()

    /** Learned-id shortcut (`Pk.dispatch`): three lookups instead of a tree walk. */
    fun readByLearnedIds(root: UiNode, ids: LearnedOfferIds): ParsedRide? {
        if (config.needsFullTree) return null
        var accept: UiNode? = null
        var cx = 0
        var cy = 0
        for (node in runCatching { root.findByViewId(ids.acceptId) }.getOrDefault(emptyList())) {
            val label = labelOf(node).trim()
            if (label.isEmpty() || !lex.accept.found(label) || !node.isEnabledSafe()) continue
            val b = node.bounds()
            if (b.right - b.left > 0 && b.bottom - b.top > 0) {
                accept = node; cx = b.centerX; cy = b.centerY
                break
            }
        }
        accept ?: return null
        val minutes = runCatching { root.findByViewId(ids.timeId) }.getOrDefault(emptyList()).firstNotNullOfOrNull { node ->
            val m = lex.time.matcher(JeenyLexicon.latinDigits(labelOf(node).trim()))
            if (m.find()) m.group(1)?.toIntOrNull() else null
        } ?: return null
        val price = runCatching { root.findByViewId(ids.priceId) }.getOrDefault(emptyList()).firstNotNullOfOrNull { node ->
            val m = JeenyLexicon.PRICE.matcher(JeenyLexicon.latinDigits(labelOf(node).trim()))
            if (m.find()) (m.group(1)?.takeIf { it.isNotBlank() } ?: m.group(2))?.replace('٫', '.')?.toDoubleOrNull() else null
        } ?: return null
        return ParsedRide(
            price = price, minutes = minutes, acceptNode = accept, addressAmbiguous = true,
            clickX = cx, clickY = cy, acceptViewId = ids.acceptId, priceViewId = ids.priceId,
            timeViewId = ids.timeId, fromFastIds = true,
        )
    }

    /**
     * Full breadth-first read (`Pk.provider`). [collectAddresses] is set by the late capture;
     * [hardDeadline] (uptime ms, 0 = none) ends the walk with what was found; [idleDeadline] and
     * [abort] end it as aborted only while nothing offer-like has been seen yet.
     */
    fun read(
        root: UiNode,
        screenHeight: Int,
        collectAddresses: Boolean = false,
        hardDeadline: Long = 0L,
        idleDeadline: Long = 0L,
        abort: AtomicBoolean? = null,
    ): ParsedRide? {
        val maxNodes = when {
            screenHeight <= 1600 -> 300
            screenHeight <= 2400 -> 400
            else -> 600
        }
        val priceFloor = (screenHeight * 0.3).toInt()
        val collect = collectAddresses || config.needsFullTree
        val addressWhenNoPrice = config.snipingNoDest && config.priceFilterOn
        val readPayment = config.paymentFilterMode != PaymentMode.ALL
        var cardSeen = false
        var cashSeen = false

        val queue = ArrayDeque<UiNode>()
        queue.addLast(root)
        var visited = 0
        var aborted = false

        var price: Double? = null
        var fallbackPrice: Double? = null
        var fallbackDecimals = -1
        var currencyLessPrice: Double? = null
        var currencyLessDecimals = -1
        var minutes: Int? = null
        var accept: UiNode? = null
        var acceptFound = false
        var acceptStrong = false
        var acceptWord = -1
        var reject: UiNode? = null
        var activeRide = false
        var online = false
        var currencySeen = false
        var clickX = 0
        var clickY = 0
        var acceptViewId: String? = null
        var learnedAccept: UiNode? = null
        var priceViewId: String? = null
        var timeViewId: String? = null
        val addresses = LinkedHashSet<String>()
        val wantMarkers = config.markersWanted
        val markers = LinkedHashSet<Long>()
        var pickupLabelled: String? = null
        var dropoffLabelled: String? = null
        var nextLabel = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (hardDeadline != 0L && (visited and 7) == 0 && uptimeMs() > hardDeadline) break
            if ((idleDeadline != 0L || abort != null) && minutes == null && price == null && fallbackPrice == null &&
                !acceptFound && reject == null && !activeRide
            ) {
                if (abort?.get() == true || (idleDeadline != 0L && uptimeMs() > idleDeadline)) {
                    aborted = true
                    break
                }
            }

            val rawText = node.text.orEmpty()
            val rawDescription = node.description.orEmpty()
            val norm = JeenyLexicon.latinDigits((if (rawText.isBlank()) rawDescription else rawText).trim())
            if (norm.isNotBlank()) {
                if (rawText.isBlank() && rawDescription.isNotBlank()) {
                    val d = rawDescription.lowercase()
                    when {
                        "destination" in d -> nextLabel = 2
                        "pick up" in d || "pickup" in d -> nextLabel = 1
                    }
                }
                val lower = norm.lowercase()
                if (readPayment && !cardSeen) {
                    if (JeenyLexicon.CARD_PATTERN.found(lower)) cardSeen = true
                    else if (!cashSeen && JeenyLexicon.CASH_PATTERN.found(lower)) cashSeen = true
                }
                val className = node.className.orEmpty()
                if ("TextureView" in className || "google map" in lower || "map marker" in lower) {
                    if (wantMarkers && "map marker" in lower) {
                        val b = node.bounds()
                        val w = b.right - b.left
                        val h = b.bottom - b.top
                        if (w in 1..400 && h in 1..600) markers += (b.centerX.toLong() shl 32) or (b.centerY.toLong() and 0xffffffffL)
                    }
                    enqueueChildren(node, queue, maxNodes)
                    continue
                }
                val disabled = !node.isEnabledSafe()
                if (!online && JeenyLexicon.ONLINE_PATTERN.found(norm)) online = true
                if (!activeRide && JeenyLexicon.ACTIVE_RIDE_PATTERN.found(norm)) activeRide = true

                var isTimeNode = false
                if (minutes == null && !disabled && (JeenyLexicon.TIME_HINTS.any { it in lower } || lex.hasTimeExtra)) {
                    val m = lex.time.matcher(norm)
                    if (m.find()) {
                        minutes = m.group(1)?.toIntOrNull()
                        if (minutes != null) {
                            timeViewId = node.viewIdSafe()
                            isTimeNode = true
                        }
                    }
                }

                if (!currencySeen && lex.currency.found(norm)) currencySeen = true

                if (price == null && !isTimeNode && !disabled) {
                    if (!currencySeen && !lex.currency.found(norm) && '$' !in norm) {
                        if (norm.length < 10 && norm.any { it in '0'..'9' }) {
                            val digits = JeenyLexicon.NON_NUMBER.matcher(norm).replaceAll("").replace(",", ".").replace("٫", ".")
                            val value = digits.toDoubleOrNull()
                            val decimals = digits.substringAfter('.', "").length
                            if (value != null && value in 0.5..500.0 &&
                                !JeenyLexicon.UNIT_PATTERN.found(norm) &&
                                !JeenyLexicon.LEADING_DISTANCE.found(lower) &&
                                !lex.time.found(norm) &&
                                (minutes == null || value != minutes!!.toDouble()) &&
                                node.bounds().top > priceFloor &&
                                (currencyLessPrice == null || decimals > currencyLessDecimals)
                            ) {
                                currencyLessDecimals = decimals
                                currencyLessPrice = value
                            }
                        }
                    } else {
                        val m = JeenyLexicon.PRICE.matcher(norm)
                        if (m.find() && node.bounds().top > priceFloor) {
                            val number = m.group(1)?.takeIf { it.isNotBlank() } ?: m.group(2)
                            price = number?.replace("٫", ".")?.toDoubleOrNull()
                            if (price != null) priceViewId = node.viewIdSafe()
                        }
                        if (price == null) {
                            val digits = JeenyLexicon.NON_NUMBER.matcher(norm).replaceAll("").replace(",", ".").replace("٫", ".")
                            val value = digits.toDoubleOrNull()
                            if (value != null && value > 0.0 && node.bounds().top > priceFloor) {
                                val hasCurrency = lex.currency.found(norm)
                                val unit = JeenyLexicon.UNIT_PATTERN.found(norm)
                                val looksLikeTime = lex.time.found(norm) || (minutes != null && value == minutes!!.toDouble())
                                if (hasCurrency && !unit && !looksLikeTime && value in 0.1..800.0) {
                                    price = value
                                } else if (!unit && !looksLikeTime && norm.length < 10 && value in 0.5..500.0) {
                                    val decimals = digits.substringAfter('.', "").length
                                    if (fallbackPrice == null || decimals > fallbackDecimals) {
                                        fallbackDecimals = decimals
                                        fallbackPrice = value
                                    }
                                }
                            }
                        }
                    }
                }

                // A trip phrase ("عرض الاتجاهات") is never an accept control, even though "عرض" is an accept word.
                val isAccept = lex.accept.found(norm) && !JeenyLexicon.RULE.found(norm) && !JeenyLexicon.ACTIVE_RIDE_PATTERN.found(norm)
                val isReject = JeenyLexicon.REJECT_PATTERN.found(norm)

                if ((collect || (addressWhenNoPrice && price == null && fallbackPrice == null)) &&
                    !disabled && !isAccept && !isReject && isAddressCandidate(rawText, norm, lower)
                ) {
                    val address = rawText.trim()
                    addresses += address
                    when (nextLabel) {
                        1 -> { if (pickupLabelled == null) pickupLabelled = address; nextLabel = 0 }
                        2 -> { if (dropoffLabelled == null) dropoffLabelled = address; nextLabel = 0 }
                    }
                }
                if (isReject && reject == null) reject = node
                // A button the fleet confirmed by its id, kept for when no accept word is on screen.
                if (learnedAccept == null && !isReject && !disabled && learnedIds.isNotEmpty() && node.isClickable &&
                    node.viewIdSafe()?.substringAfter(":id/")?.lowercase() in learnedIds
                ) learnedAccept = node

                if (!isReject && !disabled && isAccept) {
                    var target = node
                    if (!node.isClickable) {
                        var ancestor = node.parentSafe()
                        var hops = 0
                        while (ancestor != null && !ancestor.isClickable && hops < 3) {
                            ancestor = ancestor.parentSafe()
                            hops++
                        }
                        if (ancestor != null && ancestor.isClickable) target = ancestor
                    }
                    val own = node.bounds()
                    accept = target
                    clickX = own.centerX
                    clickY = own.centerY
                    acceptViewId = target.viewIdSafe()
                    acceptFound = true
                    acceptStrong = JeenyLexicon.STRONG_ACCEPT_PATTERN.found(norm)
                    acceptWord = JeenyLexicon.acceptWordIndex(lower)
                }
            }

            enqueueChildren(node, queue, maxNodes)

            val hasPrice = price != null || fallbackPrice != null
            if (hasPrice && minutes != null && acceptFound && (addresses.size >= 2 || !collect)) break
            if (activeRide && !acceptFound && hasPrice) break
            if (online) break
        }

        if (aborted) return ParsedRide(aborted = true, nodesVisited = visited)
        if (online) { lastMiss = "online_screen"; lastMissDetail = "n=$visited"; return null }

        val hasAnyPrice = price != null || fallbackPrice != null
        if (accept == null) learnedAccept?.let { node ->
            val b = node.bounds()
            accept = node; clickX = b.centerX; clickY = b.centerY
            acceptViewId = node.viewIdSafe(); acceptWord = LEARNED_ACCEPT_ID
        }
        if (accept == null && reject != null && hasAnyPrice && minutes != null && addresses.isNotEmpty()) {
            findBesideReject(root, reject)?.let { (node, x, y) ->
                accept = node; clickX = x; clickY = y
            }
        }
        // On a trip screen only an explicit accept word ("قبول" / "accept") makes an offer.
        if (activeRide && (accept == null || !acceptStrong)) return ParsedRide(isActiveRide = true, nodesVisited = visited)

        val finalPrice = price ?: fallbackPrice ?: if (currencySeen) currencyLessPrice else null
        val collectMode = collect || (addressWhenNoPrice && finalPrice == null)
        val destination: String?
        val ambiguous: Boolean
        when {
            dropoffLabelled != null -> { destination = dropoffLabelled; ambiguous = false }
            addresses.size >= 2 -> { destination = addresses.last(); ambiguous = false }
            else -> { destination = null; ambiguous = true }
        }
        val pickup = pickupLabelled ?: if (addresses.size >= 2) addresses.first().takeIf { it != destination } else null
        val priceOptional = !config.priceFilterOn ||
            (ambiguous && (collect || (collectMode && config.snipingNoDest)))

        val node = accept
        if (minutes != null && (finalPrice != null || priceOptional) && node != null) {
            return ParsedRide(
                price = finalPrice, minutes = minutes, destination = destination, acceptNode = node,
                addressAmbiguous = ambiguous, clickX = clickX, clickY = clickY, addresses = addresses.toList(),
                pickup = pickup, acceptViewId = acceptViewId,
                payment = when { cardSeen -> Payment.CARD; cashSeen -> Payment.CASH; else -> Payment.UNKNOWN }, priceViewId = priceViewId, timeViewId = timeViewId,
                nodesVisited = visited,
                markers = markers.map { intArrayOf((it shr 32).toInt(), it.toInt()) },
                acceptWord = acceptWord,
            )
        }
        val sawPrice = finalPrice != null || currencySeen
        lastMiss = when {
            !sawPrice && minutes == null && node == null -> "not_offer_screen"
            node == null -> "no_accept"
            minutes == null -> "no_minutes"
            else -> "no_price"
        }
        lastMissDetail = "p=${if (sawPrice) 1 else 0},m=${if (minutes != null) 1 else 0},a=${if (node != null) 1 else 0}," +
            "aw=$acceptWord,t=${if (activeRide) 1 else 0},n=$visited" + if (visited >= maxNodes) ",cap=1" else ""
        return null
    }

    /** Address rule (`Pk.callback`): readable text that is not a value, a label or a status line. */
    private fun isAddressCandidate(raw: String, norm: String, lower: String): Boolean {
        if (raw.isBlank() || raw.length <= 2) return false
        if (lex.noise.found(norm) || JeenyLexicon.ONLINE_PATTERN.found(norm)) return false
        if (JeenyLexicon.LEADING_DISTANCE.found(lower) || lex.time.found(norm)) return false
        if (norm.startsWith("يبعُد") || norm.startsWith("يبعد")) return false
        if (JeenyLexicon.NUMERIC_ONLY.matcher(raw).matches()) return false
        if (JeenyLexicon.NETWORK_PATTERN.found(norm)) return false
        return JeenyLexicon.CURRENCY_WORDS.matcher(raw).replaceAll("").count { it.isLetter() } >= 3
    }

    /** A clickable control on the reject button's row, to its right (accept drawn as an icon). */
    private fun findBesideReject(root: UiNode, reject: UiNode): Triple<UiNode, Int, Int>? {
        val r = reject.bounds()
        val rowY = r.centerY
        val height = r.bottom - r.top
        val width = r.right - r.left
        val queue = ArrayDeque<UiNode>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            val n = queue.removeFirst()
            visited++
            if (n.isClickable && n.isEnabledSafe()) {
                val b = n.bounds()
                val sameRow = kotlin.math.abs(b.centerY - rowY) < height
                val toTheRight = b.left >= r.right - (width * 0.5).toInt()
                if (sameRow && toTheRight && b.right - b.left > 50 && b.bottom - b.top > 30) return Triple(n, b.centerX, b.centerY)
            }
            for (i in 0 until n.childCount) n.childSafe(i)?.let(queue::addLast)
        }
        return null
    }

    private fun enqueueChildren(node: UiNode, queue: ArrayDeque<UiNode>, maxNodes: Int) {
        for (i in 0 until node.childCount) {
            val child = node.childSafe(i) ?: continue
            if (queue.size < maxNodes) queue.addLast(child)
        }
    }

    companion object {
        /** [ParsedRide.acceptWord] when the button was found by a fleet-learned id, not by a word. */
        const val LEARNED_ACCEPT_ID = -2
        /** Text, or the description when the node has no text at all (`Pk.dispatch`). */
        fun labelOf(node: UiNode): String = node.text ?: node.description.orEmpty()
    }
}

private fun UiNode.isEnabledSafe(): Boolean = runCatching { isEnabled }.getOrDefault(true)
private fun UiNode.viewIdSafe(): String? = runCatching { viewId }.getOrNull()?.takeIf { it.isNotBlank() }
private fun UiNode.parentSafe(): UiNode? = runCatching { parent() }.getOrNull()
private fun UiNode.childSafe(i: Int): UiNode? = runCatching { child(i) }.getOrNull()

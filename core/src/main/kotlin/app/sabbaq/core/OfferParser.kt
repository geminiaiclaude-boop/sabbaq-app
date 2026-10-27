package app.sabbaq.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads one screen of a ride app. A screen is an offer only when an accept
 * control is present; every value the filters need is read from the labels
 * around it and left null when it cannot be read with confidence.
 */
class OfferParser(private val profile: RideProfile, private val verifiedViewIdHints: Set<String> = emptySet()) {

    val profileId: String get() = profile.id

    /** Creates a parser with the latest bounded, verified runtime hints. */
    fun withVerifiedViewIdHints(hints: Set<String>): OfferParser =
        OfferParser(profile, hints)

    /**
     * Cheap Jeeny preflight. It is intentionally weaker than parse() and is
     * never an authorization to click: callers must still parse and verify the
     * live platform node. A learned id is accepted only when an unambiguous
     * accept label is present in the same current tree.
     */
    fun probeOfferSignal(root: ScreenNode): JeenyOfferSignal? {
        val assessment = JeenyFrameClassifier(profile, verifiedViewIdHints).assess(root)
        if (assessment.classification != JeenyFrameClass.OFFER) return null
        val accept = findAcceptControl(root) ?: return null
        val label = nodeLabel(accept)
        val evidence = when {
            accept.viewId in verifiedViewIdHints && !isAcceptLabel(label) ->
                JeenyOfferSignal.Evidence.HINTED_CONTROL_WITH_LABEL
            assessment.featureCount >= 4 -> JeenyOfferSignal.Evidence.LABELLED_CONTROL_WITH_ROUTE
            else -> JeenyOfferSignal.Evidence.LABELLED_CONTROL
        }
        return JeenyOfferSignal(accept.nodePath, evidence, assessment.score, assessment.featureCount)
    }

    private fun nodeLabel(node: ScreenNode): String =
        Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }

    private val acceptLabels = profile.acceptLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val confirmLabels = profile.confirmLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val weakConfirmLabels = profile.weakConfirmLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val acceptIds = profile.acceptViewIds.toSet()
    private val addressCues = profile.addressCues.map(Text::normalize).filter { it.isNotEmpty() }

    private val currencyRegex = tokenRegex(profile.currencyTokens + UNIVERSAL_CURRENCY_TOKENS)
    private val fareCues = profile.fareCues.map(Text::normalize).filter { it.isNotEmpty() }
    /** A lone amount, optionally with one sign drawn by an icon font (private use) or a currency symbol. */
    private val loneAmountRegex = Regex("^[\\p{Sc}\\p{So}\\p{Co}]?\\s*(${Text.NUMBER})\\s*[\\p{Sc}\\p{So}\\p{Co}]?$")
    private val numberRegex = Regex("(${Text.NUMBER})")
    private val kmRegex = unitRegex(profile.kmUnits)
    private val meterRegex = unitRegex(profile.meterUnits)
    private val minuteRegex = unitRegex(profile.minuteUnits)
    private val hourRegex = unitRegex(profile.hourUnits)

    private fun unitRegex(units: List<String>): Regex? {
        val alternation = Text.tokenAlternation(units)
        if (alternation.isEmpty()) return null
        return Regex("(${Text.NUMBER})\\s*($alternation)(?![\\p{L}])")
    }

    private fun tokenRegex(tokens: List<String>): Regex? {
        val alternation = Text.tokenAlternation(tokens)
        if (alternation.isEmpty()) return null
        return Regex("(?<![\\p{L}])($alternation)(?![\\p{L}])")
    }

    private class Item(val text: String, val node: ScreenNode)

    fun parse(root: ScreenNode): ParsedScreen {
        val items = ArrayList<Item>()
        var strongCue = false
        var weakCue = false
        root.walk { node ->
            val label = Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }
            if (label.isNotEmpty()) {
                items += Item(label, node)
                if (!strongCue && confirmLabels.any { Text.containsWord(label, it) }) strongCue = true
                if (!weakCue && weakConfirmLabels.any { Text.containsWord(label, it) }) weakCue = true
            }
        }
        val accept = findAcceptControl(root)
        val confirmed = strongCue || (weakCue && accept == null)
        val acceptNode = accept ?: return ParsedScreen(offer = null, acceptNode = null, confirmed = confirmed)
        // A confirmation state can retain an old accept control for a frame. Never
        // expose it as an actionable offer: the caller must not tap it again.
        return ParsedScreen(offer = if (confirmed) null else readOffer(items), acceptNode = acceptNode, confirmed = confirmed)
    }

    /**
     * Ride apps commonly expose the button label as a non-clickable Text node
     * inside a clickable button/container. Prefer a directly labelled control,
     * then fall back only to a clickable ancestor with an unambiguous accept
     * label in its subtree. This keeps the target safe for the live-node check.
     */
    private fun findAcceptControl(root: ScreenNode): ScreenNode? {
        var labelled: ScreenNode? = null
        var hintedLabelled: ScreenNode? = null
        var labelledContainer: ScreenNode? = null
        root.walk { node ->
            val label = Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }
            if (isAcceptControl(node, label)) {
                if (node.viewId in verifiedViewIdHints) hintedLabelled = node
                labelled = labelled ?: node
            } else if (node.clickable && containsAcceptLabel(node)) {
                labelledContainer = labelledContainer ?: node
            }
        }
        return hintedLabelled ?: labelled ?: labelledContainer
    }

    private fun containsAcceptLabel(node: ScreenNode): Boolean {
        var found = false
        node.walk { child ->
            val label = Text.normalize(child.text).ifEmpty { Text.normalize(child.description) }
            if (!child.clickable && label.isNotEmpty() && isAcceptLabel(label)) found = true
        }
        return found
    }

    private fun isAcceptLabel(label: String): Boolean =
        label.length <= 40 && acceptLabels.any { it == label || Text.containsWord(label, it) && label.length <= it.length + 12 }

    private fun isAcceptControl(node: ScreenNode, label: String): Boolean {
        if (!node.clickable) return false
        val id = node.viewId
        if (id != null && id in acceptIds) return true
        return isAcceptLabel(label)
    }

    private fun readOffer(items: List<Item>): Offer {
        val fares = ArrayList<Pair<BigDecimal, String>>()
        val distances = ArrayList<Pair<BigDecimal, DistanceRole?>>()
        val minutes = ArrayList<Pair<Int, DistanceRole?>>()
        val addresses = ArrayList<String>()

        items.forEachIndexed { index, item ->
            val text = item.text
            if (isLabel(text)) return@forEachIndexed
            val role = roleCue(text)

            val unitSpans = ArrayList<IntRange>()
            kmRegex?.findAll(text)?.forEach { m ->
                Text.parseNumber(m.groupValues[1])?.let { distances += it to role }
                unitSpans += m.range
            }
            meterRegex?.findAll(text)?.forEach { m ->
                if (unitSpans.none { it.overlaps(m.range) }) {
                    Text.parseNumber(m.groupValues[1])?.let { distances += it.divide(THOUSAND, 3, RoundingMode.HALF_UP) to role }
                    unitSpans += m.range
                }
            }
            readMinutes(text, unitSpans)?.let { values -> values.forEach { minutes += it to role } }

            val currency = currencyRegex?.find(text)
            if (currency != null) {
                val own = numberRegex.findAll(text)
                    .filter { m -> unitSpans.none { it.overlaps(m.range) } }
                    .mapNotNull { m -> Text.parseNumber(m.value) }
                    .toList()
                if (own.isNotEmpty()) {
                    own.forEach { fares += it to currency.value }
                } else {
                    neighbourNumber(items, index)?.let { fares += it to currency.value }
                }
            }

            if (currency == null && unitSpans.isEmpty() && isAddress(text)) addresses += text
        }

        // The currency may be an icon (no text at all): take the amount labelled as earnings.
        if (fares.isEmpty()) cuedAmount(items)?.let { fares += it to "" }
        val fare = when (profile.fareSelect) {
            FareSelect.FIRST -> fares.firstOrNull()
            FareSelect.MIN -> fares.minByOrNull { it.first }
            FareSelect.MAX -> fares.maxByOrNull { it.first }
        }
        val (pickupKm, tripKm) = assignWithCues(distances, profile.distanceOrder, profile.singleDistanceRole)
        val (pickupMin, tripMin) = assignWithCues(minutes, profile.minuteOrder, profile.singleMinuteRole)

        return Offer(
            profileId = profile.id,
            fare = fare?.first,
            currency = fare?.second,
            pickupKm = pickupKm,
            tripKm = tripKm,
            pickupMinutes = pickupMin,
            tripMinutes = tripMin,
            addresses = addresses,
            texts = items.map { it.text },
        )
    }

    /**
     * The amount next to an earnings label: in the label itself ("ستربح 5.84") or in the next one
     * or two labels when they are a lone amount. Distances and times are never taken as fares.
     */
    private fun cuedAmount(items: List<Item>): BigDecimal? {
        items.forEachIndexed { index, item ->
            if (fareCues.none { Text.containsWord(item.text, it) }) return@forEachIndexed
            loneAmountIn(item.text.substringAfterCue())?.let { return it }
            for (next in index + 1..minOf(index + 2, items.lastIndex)) {
                loneAmountIn(items[next].text)?.let { return it }
            }
        }
        return null
    }

    private fun String.substringAfterCue(): String {
        val cue = fareCues.filter { Text.containsWord(this, it) }.maxByOrNull { it.length } ?: return this
        return substringAfter(cue).trim()
    }

    private fun loneAmountIn(text: String): BigDecimal? =
        loneAmountRegex.matchEntire(text.trim())?.groupValues?.get(1)?.let(Text::parseNumber)?.takeIf { it.signum() > 0 }

    private fun readMinutes(text: String, unitSpans: MutableList<IntRange>): List<Int>? {
        val hours = hourRegex?.findAll(text)?.filter { m -> unitSpans.none { it.overlaps(m.range) } }?.toList().orEmpty()
        val mins = minuteRegex?.findAll(text)?.filter { m -> unitSpans.none { it.overlaps(m.range) } }?.toList().orEmpty()
        if (hours.isEmpty() && mins.isEmpty()) return null
        hours.forEach { unitSpans += it.range }
        mins.forEach { unitSpans += it.range }

        val matches = ArrayList<TimeMatch>(hours.size + mins.size)
        hours.forEach { matches += TimeMatch(it.range, it.groupValues[1], isHour = true) }
        mins.forEach { matches += TimeMatch(it.range, it.groupValues[1], isHour = false) }

        val values = ArrayList<Int>()
        var pendingHourMinutes: BigDecimal? = null
        for (match in matches.sortedBy { it.range.first }) {
            val value = Text.parseNumber(match.number) ?: return null
            if (value.signum() < 0) return null
            if (match.isHour) {
                pendingHourMinutes?.let {
                    if (!values.addDuration(it)) return null
                }
                pendingHourMinutes = value.multiply(SIXTY)
            } else {
                val total = pendingHourMinutes?.add(value) ?: value
                if (!values.addDuration(total)) return null
                pendingHourMinutes = null
            }
        }
        pendingHourMinutes?.let {
            if (!values.addDuration(it)) return null
        }
        return values.ifEmpty { null }
    }

    private fun MutableList<Int>.addDuration(value: BigDecimal): Boolean {
        val roundedUp = value.setScale(0, RoundingMode.CEILING)
        if (roundedUp.signum() < 0 || roundedUp > MAX_DURATION_MINUTES) return false
        add(roundedUp.toInt())
        return true
    }

    private fun neighbourNumber(items: List<Item>, index: Int): BigDecimal? {
        for (i in listOf(index - 1, index + 1)) {
            val candidate = items.getOrNull(i)?.text ?: continue
            if (numberRegex.matchEntire(candidate) != null) return Text.parseNumber(candidate)
        }
        return null
    }

    private fun isLabel(text: String): Boolean =
        acceptLabels.any { it == text } || confirmLabels.any { it == text } || weakConfirmLabels.any { it == text }

    private fun isAddress(text: String): Boolean {
        if (addressCues.any { Text.containsWord(text, it) }) return true
        // Any long label used to count as an address, so "طلب رحلة" or "نقدي" faked a visible
        // destination and skewed both the destination rule and the offer fingerprint.
        if (NON_ADDRESS_CUES.any { Text.containsWord(text, it) } || roleCue(text) != null) return false
        return text.count { it.isLetter() } >= 6 && acceptLabels.none { Text.containsWord(text, it) }
    }

    /**
     * The role a label states for its own values ("يبعد 4 دقائق" is pickup, "مسافة الرحلة 12 كم" is
     * trip). Null when the label does not say, in which case screen order decides as before.
     */
    private fun roleCue(text: String): DistanceRole? {
        val pickup = PICKUP_CUES.any { Text.containsWord(text, it) }
        val trip = TRIP_CUES.any { Text.containsWord(text, it) }
        return when {
            pickup && !trip -> DistanceRole.PICKUP
            trip && !pickup -> DistanceRole.TRIP
            else -> null
        }
    }

    /**
     * Values whose label names their role are placed first; unlabelled values only fill a role
     * still empty. Without cues this is exactly the positional [assign], so profiles that relied on
     * order keep their behaviour while a lone "يبعد 2 كم" is no longer read as the trip distance.
     */
    private fun <T> assignWithCues(values: List<Pair<T, DistanceRole?>>, order: List<DistanceRole>, single: DistanceRole): Pair<T?, T?> {
        if (values.none { it.second != null }) return assign(values.map { it.first }, order, single)
        var pickup = values.firstOrNull { it.second == DistanceRole.PICKUP }?.first
        var trip = values.firstOrNull { it.second == DistanceRole.TRIP }?.first
        val unlabelled = values.filter { it.second == null }.map { it.first }.iterator()
        if (pickup == null && unlabelled.hasNext()) pickup = unlabelled.next()
        if (trip == null && unlabelled.hasNext()) trip = unlabelled.next()
        return pickup to trip
    }

    private fun <T> assign(values: List<T>, order: List<DistanceRole>, single: DistanceRole): Pair<T?, T?> {
        if (values.isEmpty()) return null to null
        if (values.size == 1) {
            return if (single == DistanceRole.PICKUP) values[0] to null else null to values[0]
        }
        var pickup: T? = null
        var trip: T? = null
        order.forEachIndexed { i, role ->
            val v = values.getOrNull(i) ?: return@forEachIndexed
            if (role == DistanceRole.PICKUP) pickup = v else trip = v
        }
        return pickup to trip
    }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

    private data class TimeMatch(val range: IntRange, val number: String, val isHour: Boolean)

    private companion object {
        val PICKUP_CUES = listOf("يبعد", "بعيد", "للوصول", "منك", "عنك", "away", "pickup", "pick-up", "pick up", "to pickup", "from you")
        val TRIP_CUES = listOf("الرحلة", "رحلة", "المشوار", "مشوار", "الوجهة", "trip", "ride distance", "to destination", "dropoff", "drop-off")
        val NON_ADDRESS_CUES = listOf(
            "طلب", "عرض", "نقدي", "نقدا", "كاش", "بطاقة", "محفظة", "تقييم", "جديد",
            "request", "offer", "cash", "card", "wallet", "rating", "new",
        )
        val THOUSAND = BigDecimal(1000)
        val SIXTY = BigDecimal(60)
        val MAX_DURATION_MINUTES = BigDecimal(Int.MAX_VALUE)
    }
}

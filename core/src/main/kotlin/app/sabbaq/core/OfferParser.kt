package app.sabbaq.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads one screen of a ride app. A screen is an offer only when an accept
 * control is present; every value the filters need is read from the labels
 * around it and left null when it cannot be read with confidence.
 */
class OfferParser(private val profile: RideProfile) {

    val profileId: String get() = profile.id

    private val acceptLabels = profile.acceptLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val confirmLabels = profile.confirmLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val acceptIds = profile.acceptViewIds.toSet()
    private val addressCues = profile.addressCues.map(Text::normalize).filter { it.isNotEmpty() }

    private val currencyRegex = tokenRegex(profile.currencyTokens)
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
        var accept: ScreenNode? = null
        var confirmed = false
        root.walk { node ->
            val label = Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }
            if (accept == null && isAcceptControl(node, label)) accept = node
            if (label.isNotEmpty()) {
                items += Item(label, node)
                if (!confirmed && confirmLabels.any { Text.containsWord(label, it) }) confirmed = true
            }
        }
        val acceptNode = accept ?: return ParsedScreen(offer = null, acceptNode = null, confirmed = confirmed)
        // A confirmation state can retain an old accept control for a frame. Never
        // expose it as an actionable offer: the caller must not tap it again.
        return ParsedScreen(offer = if (confirmed) null else readOffer(items), acceptNode = acceptNode, confirmed = confirmed)
    }

    private fun isAcceptControl(node: ScreenNode, label: String): Boolean {
        if (!node.clickable) return false
        val id = node.viewId
        if (id != null && id in acceptIds) return true
        if (label.isEmpty() || label.length > 40) return false
        return acceptLabels.any { it == label || Text.containsWord(label, it) && label.length <= it.length + 12 }
    }

    private fun readOffer(items: List<Item>): Offer {
        val fares = ArrayList<Pair<BigDecimal, String>>()
        val distances = ArrayList<BigDecimal>()
        val minutes = ArrayList<Int>()
        val addresses = ArrayList<String>()

        items.forEachIndexed { index, item ->
            val text = item.text
            if (isLabel(text)) return@forEachIndexed

            val unitSpans = ArrayList<IntRange>()
            kmRegex?.findAll(text)?.forEach { m ->
                Text.parseNumber(m.groupValues[1])?.let { distances += it }
                unitSpans += m.range
            }
            meterRegex?.findAll(text)?.forEach { m ->
                if (unitSpans.none { it.overlaps(m.range) }) {
                    Text.parseNumber(m.groupValues[1])?.let { distances += it.divide(THOUSAND, 3, RoundingMode.HALF_UP) }
                    unitSpans += m.range
                }
            }
            readMinutes(text, unitSpans)?.let { minutes += it }

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

        val fare = when (profile.fareSelect) {
            FareSelect.FIRST -> fares.firstOrNull()
            FareSelect.MIN -> fares.minByOrNull { it.first }
            FareSelect.MAX -> fares.maxByOrNull { it.first }
        }
        val (pickupKm, tripKm) = assign(distances, profile.distanceOrder, profile.singleDistanceRole)
        val (pickupMin, tripMin) = assign(minutes, profile.minuteOrder, profile.singleMinuteRole)

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
        acceptLabels.any { it == text } || confirmLabels.any { it == text }

    private fun isAddress(text: String): Boolean {
        if (addressCues.any { Text.containsWord(text, it) }) return true
        return text.count { it.isLetter() } >= 6 && acceptLabels.none { Text.containsWord(text, it) }
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
        val THOUSAND = BigDecimal(1000)
        val SIXTY = BigDecimal(60)
        val MAX_DURATION_MINUTES = BigDecimal(Int.MAX_VALUE)
    }
}

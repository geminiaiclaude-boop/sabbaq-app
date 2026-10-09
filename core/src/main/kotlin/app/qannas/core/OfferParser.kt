package app.qannas.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads one screen of a ride app. A screen is an offer only when an accept
 * control is present; every value the filters need is read from the labels
 * around it and left null when it cannot be read with confidence.
 */
class OfferParser(private val profile: RideProfile) {

    private val acceptLabels = profile.acceptLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val confirmLabels = profile.confirmLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val acceptIds = profile.acceptViewIds.toSet()
    private val addressCues = profile.addressCues.map(Text::normalize).filter { it.isNotEmpty() }

    private val currencyRegex = Regex("(?<![\\p{L}])(${Text.tokenAlternation(profile.currencyTokens)})(?![\\p{L}])")
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
        return ParsedScreen(offer = readOffer(items), acceptNode = acceptNode, confirmed = confirmed)
    }

    private fun isAcceptControl(node: ScreenNode, label: String): Boolean {
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

            val currency = currencyRegex.find(text)
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
        val minuteValues = mins.mapNotNull { Text.parseNumber(it.groupValues[1])?.toInt() }.toMutableList()
        val hourValue = hours.firstOrNull()?.let { Text.parseNumber(it.groupValues[1]) } ?: return minuteValues
        val hourMinutes = hourValue.multiply(SIXTY).setScale(0, RoundingMode.HALF_UP).toInt()
        val first = minuteValues.removeFirstOrNull() ?: 0
        return listOf(hourMinutes + first) + minuteValues
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

    private companion object {
        val THOUSAND = BigDecimal(1000)
        val SIXTY = BigDecimal(60)
    }
}

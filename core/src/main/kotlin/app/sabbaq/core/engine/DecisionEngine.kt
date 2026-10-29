package app.sabbaq.core.engine

/** FareSniper `DecisionResult`. [reason] is null when the offer is accepted. */
data class Decision(
    val accept: Boolean,
    val matchedPrice: Boolean,
    val matchedTime: Boolean,
    val matchedDestination: Boolean,
    val reason: String?,
    val diagnostic: String? = null,
    val zoneEvidence: app.sabbaq.core.engine.zones.ZoneEvidence? = null,
)

/**
 * FareSniper's decision (`C0798b9.process`, QANNAS_ENGINE_FACTS.md §14) for the filters Sabbaq
 * offers: price range, time-to-customer range, destination include/exclude keywords and
 * "ignore the time when the price is high", combined with AND/OR. A bound of 0 means no limit,
 * no enabled check means accept, and an unreadable value rejects only through the driver's
 * reject-on-unknown switches.
 */
object DecisionEngine {

    /** [isBolt] enables the rating and wait-&-save filters, which FareSniper applies to Bolt only. */
    fun decide(ride: ParsedRide, config: EngineConfig, isBolt: Boolean = false, zones: app.sabbaq.core.engine.zones.ZoneEngine? = null): Decision {
        val price = ride.price
        val minutes = ride.minutes
        if (price == null && minutes == null) return reject("no_ride_data_detected")
        val ambiguous = ride.addressAmbiguous

        val ratingOn = isBolt && config.ratingFilterEnabled && config.minRating > 0.0
        val waitSaveOn = isBolt && config.waitSaveFilterMode == WaitSaveMode.EXCLUDE
        if (!config.includeOn && !config.excludeOn && !config.zoneStageOn && config.paymentFilterMode == PaymentMode.ALL && !ratingOn && !waitSaveOn) {
            if (ambiguous && !config.snipingNoDest && price == null) return reject("no_dest_sniping_disabled")
            val priceOk = priceMatches(price, ambiguous, config)
            val timeOk = timeMatches(price, minutes, config)
            val checks = buildList {
                if (config.priceFilterOn) add("price" to priceOk)
                if (config.timeFilterOn) add("time" to timeOk)
            }
            return combine(checks, config.logicMode, priceOk, timeOk, true, null)
        }

        val strictInclude = config.includeOn && config.destIncludeRejectOnUnknown
        if (ambiguous && !config.snipingNoDest && (price == null || strictInclude)) return reject("no_dest_sniping_disabled")

        paymentReason(ride.payment, config)?.let { return reject(it) }
        if (ratingOn) {
            val rating = ride.rating
            if (rating == null) { if (config.ratingRejectOnUnknown) return reject("rating_unknown_reject") }
            else if (rating < config.minRating) return reject("rating_below_min")
        }
        if (waitSaveOn && ride.waitAndSave) return reject("wait_save_filtered")

        var diagnostic: String? = null
        if (config.excludeOn) {
            val keywords = keywords(config.destExcludeKeywords)
            if (keywords.isNotEmpty()) {
                var candidates = ride.addresses.ifEmpty { listOfNotNull(ride.destination?.takeIf { it.isNotBlank() }) }
                if (ride.pickup != null && candidates.size >= 2) candidates = candidates.filter { it != ride.pickup }
                if (candidates.isNotEmpty()) {
                    val hit = candidates.any { address -> fold(address).let { a -> a.isNotBlank() && keywords.any { it in a } } }
                    if (hit) {
                        return Decision(false, priceMatches(price, ambiguous, config), timeMatches(price, minutes, config),
                            false, "dest_excluded")
                    }
                } else if (config.destExcludeRejectOnUnknown) {
                    return Decision(false, false, false, false, "exclude_no_dest_safe_reject", "exclude_skipped_no_dest")
                } else {
                    diagnostic = "exclude_skipped_no_dest"
                }
            }
        }

        val priceOk = priceMatches(price, ambiguous, config)
        val timeOk = timeMatches(price, minutes, config)
        val destinationOk = if (!config.includeOn || ambiguous) {
            true
        } else {
            val destination = ride.destination
            when {
                destination.isNullOrBlank() -> false
                keywords(config.destKeywords).isEmpty() -> true
                else -> fold(destination).let { d -> keywords(config.destKeywords).any { it in d } }
            }
        }
        val checks = buildList {
            if (config.priceFilterOn) add("price" to priceOk)
            if (config.timeFilterOn) add("time" to timeOk)
            if (config.destCheckEnabled) add("destination" to destinationOk)
        }
        val combined = combine(checks, config.logicMode, priceOk, timeOk, destinationOk, diagnostic)
        if (zones == null || !config.zoneStageOn) return combined
        val zone = runCatching { zones.evaluate(ride, config, combined.accept, diagnostic) }.getOrNull() ?: return combined
        zone.rejectReason?.let { return Decision(false, priceOk, timeOk, destinationOk, it, zone.diagnostic, zone.evidence) }
        return combined.copy(diagnostic = zone.diagnostic)
    }

    /** FareSniper's payment filter: the other method rejects; unknown rejects only when asked to. */
    private fun paymentReason(payment: Payment, config: EngineConfig): String? = when (config.paymentFilterMode) {
        PaymentMode.ALL -> null
        PaymentMode.CASH -> when (payment) {
            Payment.CASH -> null
            Payment.CARD -> "payment_not_cash"
            Payment.UNKNOWN -> if (config.paymentRejectOnUnknown) "payment_unknown_reject" else null
        }
        PaymentMode.CARD -> when (payment) {
            Payment.CARD -> null
            Payment.CASH -> "payment_not_card"
            Payment.UNKNOWN -> if (config.paymentRejectOnUnknown) "payment_unknown_reject" else null
        }
    }

    private fun combine(
        checks: List<Pair<String, Boolean>>,
        mode: LogicMode,
        priceOk: Boolean,
        timeOk: Boolean,
        destinationOk: Boolean,
        diagnostic: String?,
    ): Decision {
        val accept = when {
            checks.isEmpty() -> true
            mode == LogicMode.OR -> checks.any { it.second }
            else -> checks.all { it.second }
        }
        val reason = if (accept) null else checks.filter { !it.second }.map { it.first }
            .let { if (it.isEmpty()) "conservative_ocr_reject" else "failed_" + it.joinToString("_") }
        return Decision(accept, priceOk, timeOk, destinationOk, reason, diagnostic)
    }

    /** Missing price passes only when the destination was ambiguous (the reader allowed it). */
    private fun priceMatches(price: Double?, ambiguous: Boolean, config: EngineConfig): Boolean = when {
        !config.priceFilterOn -> true
        price != null -> inRange(price, config.minPrice, config.maxPrice)
        else -> ambiguous
    }

    private fun timeMatches(price: Double?, minutes: Int?, config: EngineConfig): Boolean {
        val bypass = config.bypassTimeEnabled && price != null && price >= config.bypassTimePrice
        if (bypass || !config.timeFilterOn) return true
        return inRange(minutes, config.minTime, config.maxTime)
    }

    /** FareSniper `b9.dispatch`: 0 = unbounded; a missing value fails only a bounded range. */
    fun inRange(value: Double?, min: Double, max: Double): Boolean {
        if (min <= 0.0 && max <= 0.0) return true
        value ?: return false
        return (min <= 0.0 || value >= min) && (max <= 0.0 || value <= max)
    }

    /** FareSniper `b9.handler`, the integer variant used for minutes. */
    fun inRange(value: Int?, min: Int, max: Int): Boolean {
        if (min <= 0 && max <= 0) return true
        value ?: return false
        return (min <= 0 || value >= min) && (max <= 0 || value <= max)
    }

    fun keywords(csv: String): List<String> = csv.split(',').map(::fold).filter { it.isNotBlank() }

    /**
     * FareSniper's destination text folding (`AbstractC2709uC.connect`): Latin digits, one alef,
     * ta marbuta as ha, one ya, Persian kaf as kaf, waw hamza as waw, diacritics, tatweel and
     * bare hamza removed, lower case, single spaces.
     */
    fun fold(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when {
                c in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ' || c == 'ٲ' || c == 'ٳ' -> sb.append('ا')
                c == 'ة' -> sb.append('ه')
                c == 'ى' || c == 'ئ' || c == 'ی' -> sb.append('ي')
                c == 'ک' -> sb.append('ك')
                c == 'ؤ' -> sb.append('و')
                c in 'ً'..'ٕ' || c == 'ٰ' || c == 'ـ' || c == 'ء' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString().lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun reject(reason: String) = Decision(false, false, false, false, reason)
}

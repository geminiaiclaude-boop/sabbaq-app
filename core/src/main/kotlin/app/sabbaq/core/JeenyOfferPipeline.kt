package app.sabbaq.core

/** Cheap, non-actionable evidence collected before a full Jeeny parse. */
data class JeenyOfferSignal(
    val acceptNodePath: List<Int>,
    val evidence: Evidence,
    val score: Int,
    val featureCount: Int,
) {
    enum class Evidence { LABELLED_CONTROL, LABELLED_CONTROL_WITH_ROUTE, HINTED_CONTROL_WITH_LABEL }
}

enum class JeenyFrameClass { NONE, INCOMPLETE, OFFER, CONFIRMED_OR_ACTIVE }

data class JeenyFrameAssessment(
    val classification: JeenyFrameClass,
    val score: Int,
    val featureCount: Int,
    val scannedNodes: Int,
    val acceptNodePath: List<Int>? = null,
    /** Machine-only feature summary for telemetry, for example `a1,f0,d1,t1,p1,o0,c0,n42,cap0`. */
    val flags: String = "",
)

/**
 * Conservative, pure Jeeny frame classifier. It is deliberately separate from
 * parsing and clicking: a hint or a single label can never authorize an action.
 */
class JeenyFrameClassifier(
    private val profile: RideProfile,
    private val verifiedViewIdHints: Set<String> = emptySet(),
    private val maxNodes: Int = MAX_NODES,
    private val maxTextChars: Int = MAX_TEXT_CHARS,
) {
    init { require(maxNodes in 1..MAX_NODES); require(maxTextChars in 64..MAX_TEXT_CHARS) }

    fun assess(root: ScreenNode): JeenyFrameAssessment {
        val labels = ArrayList<Pair<ScreenNode, String>>()
        var scanned = 0
        var chars = 0
        root.walk { node ->
            if (scanned >= maxNodes || chars >= maxTextChars) return@walk
            scanned++
            val label = Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }
            if (label.isNotEmpty()) {
                val bounded = label.take(maxTextChars - chars)
                chars += bounded.length
                labels += node to bounded
            }
        }
        val all = labels.map { it.second }
        val accept = labels.firstOrNull { (node, label) ->
            node.clickable && isAcceptLabel(label)
        } ?: labels.firstOrNull { (node, _) ->
            node.clickable && containsAcceptLabel(node, labels)
        }
        val strongCue = all.any { label -> strongConfirmCues.any { Text.containsWord(label, it) } }
        // Same rule as OfferParser: cues that also appear on offer cards count only once the
        // accept control is gone, otherwise a real offer is classified as an active ride.
        val weakCue = all.any { label -> weakConfirmCues.any { Text.containsWord(label, it) } }
        val confirmed = strongCue || (weakCue && accept == null)
        val hasFare = all.any { hasCurrencyAndNumber(it) } || hasCuedAmount(all)
        val hasDistance = all.any { kmRegex.containsMatchIn(it) || meterRegex.containsMatchIn(it) }
        val hasTime = all.any { minuteRegex.containsMatchIn(it) || hourRegex.containsMatchIn(it) }
        val hasPickup = all.any { label -> pickupCues.any { Text.containsWord(label, it) } }
        val hasAddress = all.any { label -> profile.addressCues.any { Text.containsWord(label, Text.normalize(it)) } }
        val hasOfferLabel = all.any { label -> offerLabels.any { Text.containsWord(label, it) } }
        val features = listOf(hasFare, hasDistance, hasTime, hasPickup || hasAddress, hasOfferLabel).count { it }
        val score = (if (accept != null) 4 else 0) +
            (if (hasFare) 3 else 0) + (if (hasDistance) 1 else 0) +
            (if (hasTime) 1 else 0) + (if (hasPickup || hasAddress) 2 else 0) +
            (if (hasOfferLabel) 1 else 0) - (if (confirmed) 8 else 0)
        val classification = when {
            confirmed -> JeenyFrameClass.CONFIRMED_OR_ACTIVE
            accept == null -> JeenyFrameClass.NONE
            score >= OFFER_THRESHOLD && features >= MIN_INDEPENDENT_FEATURES -> JeenyFrameClass.OFFER
            score >= INCOMPLETE_THRESHOLD -> JeenyFrameClass.INCOMPLETE
            else -> JeenyFrameClass.NONE
        }
        val capped = scanned >= maxNodes || chars >= maxTextChars
        val flags = listOf(
            "a" to (accept != null), "f" to hasFare, "d" to hasDistance, "t" to hasTime,
            "p" to (hasPickup || hasAddress), "o" to hasOfferLabel, "c" to confirmed,
        ).joinToString(",") { (key, present) -> key + if (present) "1" else "0" } +
            ",n$scanned,cap${if (capped) 1 else 0}"
        return JeenyFrameAssessment(classification, score.coerceIn(MIN_SCORE, MAX_SCORE), features, scanned, accept?.first?.nodePath, flags)
    }

    private fun hasCurrencyAndNumber(label: String): Boolean =
        currencyRegex.any { label.contains(it) && (it.length == 1 || Text.containsWord(label, it)) } && numberRegex.containsMatchIn(label)

    /** An earnings label followed by a lone amount: the fare when the currency is an icon. */
    private fun hasCuedAmount(all: List<String>): Boolean = all.indices.any { index ->
        fareCues.any { Text.containsWord(all[index], it) } &&
            (index..minOf(index + 2, all.lastIndex)).any { loneAmount.containsMatchIn(all[it]) }
    }

    private fun isAcceptLabel(label: String): Boolean =
        label.length <= 48 && acceptLabels.any { it == label || Text.containsWord(label, it) && label.length <= it.length + 16 }

    private fun containsAcceptLabel(node: ScreenNode, labels: List<Pair<ScreenNode, String>>): Boolean =
        node.children.any { child -> labels.any { it.first.id == child.id && isAcceptLabel(it.second) } } ||
            node.children.any { child -> containsAcceptLabel(child, labels) }

    private val acceptLabels = profile.acceptLabels.map(Text::normalize).filter { it.isNotEmpty() }
    private val strongConfirmCues = (profile.confirmLabels + listOf("in progress", "جارٍ التوصيل", "في التوصيل"))
        .map(Text::normalize).filter { it.isNotEmpty() }.distinct()
    private val weakConfirmCues = (profile.weakConfirmLabels + "active")
        .map(Text::normalize).filter { it.isNotEmpty() }.distinct()
    private val currencyRegex = (profile.currencyTokens + UNIVERSAL_CURRENCY_TOKENS).map(Text::normalize).filter { it.isNotEmpty() }
    private val fareCues = profile.fareCues.map(Text::normalize).filter { it.isNotEmpty() }
    private val loneAmount = Regex("(?:^|\\s)[\\p{Sc}\\p{So}\\p{Co}]?\\s*(?:${Text.NUMBER})\\s*[\\p{Sc}\\p{So}\\p{Co}]?$")
    private val numberRegex = Regex(Text.NUMBER)
    private val kmRegex = Regex("(${Text.NUMBER})\\s*(?:${Text.tokenAlternation(profile.kmUnits)})(?![\\p{L}])")
    private val meterRegex = Regex("(${Text.NUMBER})\\s*(?:${Text.tokenAlternation(profile.meterUnits)})(?![\\p{L}])")
    private val minuteRegex = Regex("(${Text.NUMBER})\\s*(?:${Text.tokenAlternation(profile.minuteUnits)})(?![\\p{L}])")
    private val hourRegex = Regex("(${Text.NUMBER})\\s*(?:${Text.tokenAlternation(profile.hourUnits)})(?![\\p{L}])")
    private val pickupCues = listOf("pickup", "pick up", "from", "passenger", "away", "الإقلاع", "الانطلاق", "الاقلال", "الراكب", "يبعد", "منك", "للوصول")
    private val offerLabels = listOf("offer", "ride request", "request", "طلب", "عرض", "رحلة", "مشوار")

    companion object {
        // Must cover the whole bounded accessibility tree (3000 nodes): an offer card rendered
        // below a map or list would otherwise be cut off and silently classified as NONE.
        const val MAX_NODES = 3000
        const val MAX_TEXT_CHARS = 32768
        const val OFFER_THRESHOLD = 8
        const val INCOMPLETE_THRESHOLD = 4
        const val MIN_INDEPENDENT_FEATURES = 3
        const val MIN_SCORE = -16
        const val MAX_SCORE = 16
    }
}

/**
 * Small state machine for one Jeeny window. It deliberately does not perform
 * clicks; ClickGate and AcceptTracker remain the only action/confirmation gates.
 */
class JeenyOfferPipeline(private val maxLateCaptures: Int = 2) {
    init { require(maxLateCaptures in 0..2) }

    private var generation = 0L
    private var packageName: String? = null
    private var windowToken: String? = null
    private var lastFingerprint: String? = null
    private var lateCaptures = 0
    private var actionPending = false

    fun begin(packageName: String, windowToken: String?): Long {
        if (this.packageName != packageName || this.windowToken != windowToken) reset()
        this.packageName = packageName
        this.windowToken = windowToken
        return generation
    }

    fun isCurrent(token: Long): Boolean = token == generation
    fun allowLateCapture(): Boolean = lateCaptures < maxLateCaptures && !actionPending
    fun hasActionPending(): Boolean = actionPending
    fun markLateCapture(): Boolean = if (allowLateCapture()) { lateCaptures++; true } else false

    /**
     * A new accessibility event starts a new render burst with its own late-read budget. The
     * budget used to last for the whole window, so a long-lived Jeeny window stopped re-reading
     * progressively rendered offer cards after the first two late reads of the session.
     */
    fun beginBurst() { lateCaptures = 0 }
    fun markActionPending() { actionPending = true }
    fun markConfirmationResolved() { actionPending = false }
    fun shouldProcess(fingerprint: String): Boolean = !actionPending && fingerprint != lastFingerprint
    fun remember(fingerprint: String) { lastFingerprint = fingerprint }

    fun reset() {
        generation++
        packageName = null
        windowToken = null
        lastFingerprint = null
        lateCaptures = 0
        actionPending = false
    }
}

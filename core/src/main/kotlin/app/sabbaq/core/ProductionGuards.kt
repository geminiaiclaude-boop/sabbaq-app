package app.sabbaq.core

import java.security.MessageDigest

/** Platform strategy boundary; profiles remain the source of labels and parsing rules. */
enum class NodeResolutionStrategy { LABEL_THEN_ID_THEN_ANCESTOR }
enum class ConfirmationStrategy { PROFILE_LABELS_AND_ACTIVE_CUES }

data class AdapterSignalConfig(
    val maxNodes: Int = JeenyFrameClassifier.MAX_NODES,
    val maxTextChars: Int = JeenyFrameClassifier.MAX_TEXT_CHARS,
    val minimumIndependentFeatures: Int = JeenyFrameClassifier.MIN_INDEPENDENT_FEATURES,
)

interface RideAppAdapter {
    val profile: RideProfile
    val packageNames: Set<String>
    val requiresPreflight: Boolean get() = false
    val acceptLabelFamilies: List<String> get() = profile.acceptLabels
    val confirmLabelFamilies: List<String> get() = profile.confirmLabels
    val signalConfig: AdapterSignalConfig get() = AdapterSignalConfig()
    val nodeResolutionStrategy: NodeResolutionStrategy get() = NodeResolutionStrategy.LABEL_THEN_ID_THEN_ANCESTOR
    val confirmationStrategy: ConfirmationStrategy get() = ConfirmationStrategy.PROFILE_LABELS_AND_ACTIVE_CUES
    fun parser(verifiedViewIdHints: Set<String> = emptySet()): OfferParser = OfferParser(profile, verifiedViewIdHints)
    fun classify(root: ScreenNode, verifiedViewIdHints: Set<String> = emptySet()): JeenyFrameAssessment? =
        if (requiresPreflight) JeenyFrameClassifier(profile, verifiedViewIdHints, signalConfig.maxNodes, signalConfig.maxTextChars).assess(root) else null
}

class JeenyRideAppAdapter(override val profile: RideProfile) : RideAppAdapter {
    override val packageNames: Set<String> = profile.packages.toSet()
    override val requiresPreflight: Boolean = true
}

object RideAppAdapters {
    private val factories: Map<String, (RideProfile) -> RideAppAdapter> = mapOf(
        "jeeny" to ::JeenyRideAppAdapter,
        "bolt" to ::BoltRideAppAdapter,
        "petra" to ::PetraRideAppAdapter,
    )

    fun forProfile(profile: RideProfile): RideAppAdapter =
        factories[profile.id]?.invoke(profile)
            ?: error("Unsupported bundled ride profile: ${profile.id}")

    fun supportedProfileIds(): Set<String> = factories.keys

    fun byPackage(profiles: Iterable<RideProfile>): Map<String, RideAppAdapter> = buildMap {
        profiles.filter { it.enabled }.forEach { profile ->
            val adapter = forProfile(profile)
            adapter.packageNames.filter { it.isNotBlank() }.forEach { put(it, adapter) }
        }
    }
}

/** Bounded, deterministic structural fingerprint. It never returns screen text. */
object ScreenChangeFingerprint {
    // Covers the whole bounded tree: with a smaller window a new offer card rendered after
    // the first nodes left the fingerprint unchanged and the frame was dropped as stale.
    const val MAX_NODES = 3000
    const val MAX_TEXT_CHARS = 32768

    fun compute(packageName: String, windowToken: String?, root: ScreenNode): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(packageName.toByteArray())
        digest.update(0)
        digest.update((windowToken ?: "").toByteArray())
        var nodes = 0
        var chars = 0
        root.walk { node ->
            if (nodes >= MAX_NODES || chars >= MAX_TEXT_CHARS) return@walk
            nodes++
            val label = Text.normalize(node.text).ifEmpty { Text.normalize(node.description) }
            val bounded = label.take(MAX_TEXT_CHARS - chars)
            chars += bounded.length
            val signal = listOf(
                node.nodePath.joinToString("."), node.className.orEmpty(), node.viewId.orEmpty(),
                node.clickable.toString(), node.bounds.isEmpty.toString(), bounded.length.toString(),
                bounded.lowercase().hashCode().toString(),
            ).joinToString("|")
            digest.update(signal.toByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

enum class DiagnosticStage {
    PACKAGE_SEEN, EVENT_RECEIVED, SIGNAL_FOUND, SIGNAL_MISS, FRAME_INCOMPLETE,
    FARE_MISSING, REJECTED, ACCEPT_NODE_FOUND, CLICK_PATH, CONFIRMATION_CONFIRMED,
    CONFIRMATION_TIMEOUT, STALE, WATCHDOG, GATE_BLOCKED, ROOT_UNAVAILABLE, TREE_REJECTED,
    OFFER_NOT_PARSED, OFFER_DETECTED,
}

data class DiagnosticEntry(val stage: DiagnosticStage, val atElapsedMs: Long, val reason: String? = null)

/** In-memory ring buffer: metadata only, never offer text, addresses, screenshots or payloads. */
class BoundedDiagnostics(private val capacity: Int = 64) {
    init { require(capacity in 1..256) }
    private val entries = ArrayDeque<DiagnosticEntry>(capacity)
    @Synchronized fun record(stage: DiagnosticStage, nowMs: Long, reason: String? = null) {
        if (entries.size == capacity) entries.removeFirst()
        val safeReason = reason?.takeIf { it.length <= 48 && it.matches(Regex("[A-Za-z0-9_.:-]+")) }
        entries.addLast(DiagnosticEntry(stage, nowMs, safeReason ?: reason?.let { "redacted" }))
    }
    @Synchronized fun snapshot(): List<DiagnosticEntry> = entries.toList()
}

data class LivenessSnapshot(
    val lastAccessibilityEventMs: Long?, val lastTargetEventMs: Long?, val lastOfferDetectionMs: Long?,
    val lastDecisionMs: Long?, val lastClickMs: Long?, val stale: Boolean, val pausedReason: String?,
)

/** Explicit service liveness state; callers advance it from their existing lifecycle/events. */
class LivenessState(private val staleAfterMs: Long = 15_000L) {
    init { require(staleAfterMs > 0) }
    private var accessibility: Long? = null
    private var target: Long? = null
    private var offer: Long? = null
    private var decision: Long? = null
    private var click: Long? = null
    private var paused: String? = null
    @Synchronized fun accessibilityEvent(now: Long) { accessibility = now }
    @Synchronized fun targetEvent(now: Long) { target = now }
    @Synchronized fun offerDetected(now: Long) { offer = now }
    @Synchronized fun decision(now: Long) { decision = now }
    @Synchronized fun click(now: Long) { click = now }
    @Synchronized fun pause(reason: String?) { paused = reason?.take(48) }
    @Synchronized fun snapshot(now: Long): LivenessSnapshot {
        val last = listOfNotNull(accessibility, target, offer, decision, click).maxOrNull()
        return LivenessSnapshot(accessibility, target, offer, decision, click,
            paused != null || (last != null && now - last > staleAfterMs), paused)
    }
}

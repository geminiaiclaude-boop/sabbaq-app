package app.sabbaq.core

/** Cheap, non-actionable evidence collected before a full Jeeny parse. */
data class JeenyOfferSignal(
    val acceptNodePath: List<Int>,
    val evidence: Evidence,
) {
    enum class Evidence { LABELLED_CONTROL, HINTED_CONTROL_WITH_LABEL }
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

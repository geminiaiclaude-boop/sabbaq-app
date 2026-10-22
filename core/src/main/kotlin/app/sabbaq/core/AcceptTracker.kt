package app.sabbaq.core

/** Tracks one dispatched accept gesture until confirmation or timeout. */
class AcceptTracker(val confirmWindowMs: Long = DEFAULT_CONFIRM_WINDOW_MS) {
    init {
        require(confirmWindowMs > 0) { "confirmWindowMs must be positive" }
    }

    sealed interface Outcome {
        data class Confirmed(val fingerprint: String) : Outcome
        data class NotConfirmed(val fingerprint: String) : Outcome
    }

    private data class Pending(
        val fingerprint: String,
        val profileId: String,
        val tappedAtMs: Long,
    )

    private var pending: Pending? = null

    /** A newer dispatched gesture replaces the previous pending gesture. */
    fun tapped(fingerprint: String, profileId: String, nowMs: Long) {
        pending = Pending(fingerprint, profileId, nowMs)
    }

    /**
     * A confirmation only counts for the profile that received the tap and before
     * the deadline. A backwards-moving clock invalidates the pending observation.
     */
    fun observe(confirmedScreenForProfile: String?, nowMs: Long): Outcome? {
        val current = pending ?: return null
        if (nowMs < current.tappedAtMs) {
            pending = null
            return null
        }

        val elapsed = nowMs - current.tappedAtMs
        if (confirmedScreenForProfile == current.profileId && elapsed < confirmWindowMs) {
            pending = null
            return Outcome.Confirmed(current.fingerprint)
        }
        if (elapsed >= confirmWindowMs) {
            pending = null
            return Outcome.NotConfirmed(current.fingerprint)
        }
        return null
    }

    private companion object {
        const val DEFAULT_CONFIRM_WINDOW_MS = 5_000L
    }
}

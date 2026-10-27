package app.sabbaq.service

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest anonymous outline of a Jeeny screen that looked like an offer but could not be read.
 * Memory only and at most one: it leaves the phone only when the driver sends it to support.
 */
object ScreenSketchStore {
    data class Sketch(val text: String, val reason: String, val atEpochMs: Long)

    private const val MIN_INTERVAL_MS = 30_000L
    private val _latest = MutableStateFlow<Sketch?>(null)
    val latest: StateFlow<Sketch?> = _latest.asStateFlow()
    @Volatile private var lastKeptAt = 0L

    /** Cheap gate checked before building a sketch, so a burst of frames renders at most one. */
    fun wantsSketch(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        lastKeptAt == 0L || nowMs - lastKeptAt >= MIN_INTERVAL_MS

    fun keep(text: String, reason: String) {
        if (text.isBlank()) return
        lastKeptAt = SystemClock.elapsedRealtime()
        _latest.value = Sketch(text, reason, System.currentTimeMillis())
    }

    fun clear() {
        _latest.value = null
    }
}

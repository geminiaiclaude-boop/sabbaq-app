package app.sabbaq.service

import android.os.SystemClock
import app.sabbaq.core.LiveAcceptProbe
import app.sabbaq.core.LiveAcceptProbe.Method
import app.sabbaq.core.LiveAcceptProbe.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state of one live acceptance test between the test screen and the accessibility
 * service. Only while a test is armed does the service watch Sabbaq's own window; arming lasts
 * [LiveAcceptProbe.TIMEOUT_MS] at most, so a forgotten test never leaves it watching.
 */
object LiveAcceptTest {
    data class State(
        val armedAtMs: Long? = null,
        val reached: Map<Step, Long> = emptyMap(),
        val method: Method? = null,
        val dispatchRefused: Boolean = false,
        val finished: Boolean = false,
    ) {
        val succeeded: Boolean get() = Step.CONFIRMED in reached
        fun elapsed(step: Step): Long? = armedAtMs?.let { start -> reached[step]?.minus(start) }
    }

    private val lock = Any()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by the connected service so a new test re-applies its window filter immediately. */
    @Volatile var onArmChanged: (() -> Unit)? = null

    fun arm() {
        synchronized(lock) { _state.value = State(armedAtMs = SystemClock.elapsedRealtime()) }
        onArmChanged?.invoke()
    }

    fun finish() {
        val changed = synchronized(lock) {
            if (_state.value.armedAtMs == null || _state.value.finished) false
            else { _state.value = _state.value.copy(finished = true); true }
        }
        if (changed) onArmChanged?.invoke()
    }

    fun isArmed(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val current = _state.value
        val start = current.armedAtMs ?: return false
        return !current.finished && nowMs - start in 0..LiveAcceptProbe.TIMEOUT_MS
    }

    fun hasDispatched(): Boolean = Step.DISPATCHED in _state.value.reached

    fun reach(step: Step, nowMs: Long = SystemClock.elapsedRealtime(), method: Method? = null) {
        synchronized(lock) {
            val current = _state.value
            if (current.armedAtMs == null || current.finished || step in current.reached) return
            _state.value = current.copy(reached = current.reached + (step to nowMs), method = method ?: current.method)
        }
        if (step == Step.CONFIRMED) finish()
    }

    fun refuseDispatch() = synchronized(lock) {
        _state.value = _state.value.copy(dispatchRefused = true)
    }
}

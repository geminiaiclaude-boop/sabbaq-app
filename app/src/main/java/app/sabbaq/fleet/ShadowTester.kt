package app.sabbaq.fleet

import android.os.SystemClock
import app.sabbaq.Settings
import app.sabbaq.core.TelemetryStage
import app.sabbaq.core.engine.RideApp
import app.sabbaq.core.engine.RideApps
import app.sabbaq.core.engine.ShadowCheck
import app.sabbaq.core.engine.UiNode
import app.sabbaq.telemetry.TelemetryRepository
import java.util.concurrent.Executors

/**
 * Runs ShadowCheck on a Jeeny screen that failed to read: on a low-priority thread, at most once
 * every 20 seconds per miss reason, read-only (the reader never taps). A hit is reported so the
 * server can adopt the word once enough drivers confirm it.
 */
object ShadowTester {
    private const val GAP_MS = 20_000L
    private val last = HashMap<String, Long>()

    fun test(reason: String, screen: UiNode?, screenHeight: Int) {
        screen ?: return
        val shadow = FleetRepository.shadow()
        if (shadow.version <= 0 || reason !in setOf("no_minutes", "no_price", "no_accept")) return
        val now = SystemClock.elapsedRealtime()
        synchronized(last) {
            val prev = last[reason]
            if (prev != null && now - prev < GAP_MS) return
            last[reason] = now
        }
        val base = FleetRepository.effectiveConfig(Settings.engineConfig)
        runCatching {
            // The sampler's thread: AccessibilityNodeInfo must never be walked by two threads at once.
            ScreenSamples.walker.execute {
                val hit = runCatching {
                    ShadowCheck.find(base, shadow, reason) { cfg ->
                        RideApps.read(RideApp.JEENY, screen, screenHeight, cfg, null, { SystemClock.uptimeMillis() })
                    }
                }.getOrNull() ?: return@execute
                TelemetryRepository.trace(TelemetryStage.ACCEPT_LEARNED, hit.reason, detail = hit.detail, provider = "jeeny")
            }
        }
    }
}

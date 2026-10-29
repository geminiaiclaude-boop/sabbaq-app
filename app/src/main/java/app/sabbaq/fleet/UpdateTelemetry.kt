package app.sabbaq.fleet

import android.content.Context
import app.sabbaq.BuildConfig
import app.sabbaq.core.AppRelease
import app.sabbaq.core.TelemetryStage
import app.sabbaq.core.UpdateState
import app.sabbaq.telemetry.TelemetryRepository

/** The update lifecycle in telemetry: offered, downloaded, refused checks, installed (with the previous version). */
object UpdateTelemetry {
    private const val PREFS = "sabbaq_fleet"
    private const val KEY_LAST_CODE = "last_run_code"

    fun offered(release: AppRelease) {
        val required = release.state(BuildConfig.VERSION_CODE.toLong()) == UpdateState.REQUIRED
        state(if (required) "required" else "offered", release.latestCode)
    }

    fun state(reason: String, code: Long) {
        TelemetryRepository.trace(TelemetryStage.UPDATE_STATE, reason.take(48),
            detail = if (code > 0) "to=$code" else null, provider = "sabbaq")
    }

    /** Called once per process: a changed version code since the last run is an installed update. */
    fun recordRun(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getLong(KEY_LAST_CODE, 0L)
        val current = BuildConfig.VERSION_CODE.toLong()
        if (previous == current) return
        prefs.edit().putLong(KEY_LAST_CODE, current).apply()
        if (previous > 0L) {
            TelemetryRepository.trace(TelemetryStage.UPDATE_STATE, if (current > previous) "installed" else "downgraded",
                detail = "from=$previous", provider = "sabbaq")
        }
    }
}

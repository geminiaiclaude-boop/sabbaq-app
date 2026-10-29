package app.sabbaq.fleet

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import app.sabbaq.Settings
import app.sabbaq.core.ProcessExit
import app.sabbaq.core.StandbyBucket
import app.sabbaq.core.TelemetryStage
import app.sabbaq.receiver.WatchdogReceiver
import app.sabbaq.telemetry.TelemetryRepository

/**
 * Keeps monitoring alive against the ways Android ends it:
 * - a watchdog alarm every 15 minutes restarts the service when the system or the phone maker's
 *   cleaner killed it (the foreground service's sticky restart is skipped on many phones);
 * - swiping Sabbaq out of recent apps re-arms a restart a few seconds later;
 * - Android 11+ app hibernation ("pause app activity if unused") and the rare/restricted standby
 *   buckets are detected and shown to the driver with the screen that turns them off;
 * - every process death Android recorded (ApplicationExitInfo) is reported with its reason, so the
 *   fleet report shows which phones and versions are being killed and why.
 */
object KeepAlive {
    private const val WATCHDOG_MS = 15 * 60_000L
    private const val REQUEST_WATCHDOG = 4101
    private const val REQUEST_SOON = 4102
    private const val PREFS = "sabbaq_fleet"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    private const val KEY_RETRIES = "restart_retries"
    private const val MAX_RETRIES = 5
    private const val FIRST_RETRY_MS = 2_000L

    /**
     * Arms the next watchdog check while the driver wants monitoring; cancels it otherwise. A
     * one-shot alarm allowed while idle, re-armed by every check: repeating alarms do not fire in Doze.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pending(app, REQUEST_WATCHDOG)
        if (!Settings.monitoringWanted) { alarms.cancel(pending); return }
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + WATCHDOG_MS, pending) }
    }

    /**
     * The reference engine's restart after an unrequested stop (task swiped away, service
     * destroyed by the system): 2, 4, 8, 16 and 32 seconds, then the 15-minute watchdog takes
     * over. An exact alarm when Android allows one, otherwise the nearest allowed while idle.
     */
    fun restartWithBackoff(context: Context) {
        if (!Settings.monitoringWanted) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val retries = prefs.getInt(KEY_RETRIES, 0)
        if (retries >= MAX_RETRIES) return
        prefs.edit().putInt(KEY_RETRIES, retries + 1).apply()
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = SystemClock.elapsedRealtime() + FIRST_RETRY_MS * (1L shl retries)
        val pending = pending(app, REQUEST_SOON)
        val exact = Build.VERSION.SDK_INT < 31 || runCatching { alarms.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }.recoverCatching { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending) }
    }

    /** A successful start clears the backoff, like the reference's crash_retry_count reset. */
    fun startedOk(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_RETRIES, 0).apply()
    }

    private fun pending(context: Context, request: Int): PendingIntent =
        PendingIntent.getBroadcast(context, request, Intent(context, WatchdogReceiver::class.java).setAction(WatchdogReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Android 11+: true when the system may hibernate Sabbaq for being "unused". */
    fun hibernationOn(context: Context): Boolean = Build.VERSION.SDK_INT >= 30 &&
        runCatching { !context.packageManager.isAutoRevokeWhitelisted }.getOrDefault(false)

    /** The screen where the driver turns off "pause app activity if unused". */
    fun hibernationIntent(context: Context): Intent =
        Intent(if (Build.VERSION.SDK_INT >= 30) Intent.ACTION_AUTO_REVOKE_PERMISSIONS else android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** App standby bucket (API 28+), or 10 (active) when unknown. */
    fun standbyBucket(context: Context): Int = if (Build.VERSION.SDK_INT >= 28) runCatching {
        (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
    }.getOrDefault(10) else 10

    /** Device and keep-alive state for the fleet report (lowercase, telemetry detail contract). */
    fun deviceInfo(context: Context): String {
        val maker = Build.MANUFACTURER.orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(12).ifEmpty { "unknown" }
        val bat = runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
        val ram = runCatching {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            ((info.totalMem + 512L * 1024 * 1024) / (1024L * 1024 * 1024)).coerceIn(0, 64)
        }.getOrDefault(0L)
        return "mf=$maker,bkt=${StandbyBucket.letter(standbyBucket(context))},hib=${if (hibernationOn(context)) 1 else 0}," +
            "bat=${if (bat) 1 else 0},ram=$ram,wd=${if (Settings.monitoringWanted) 1 else 0}"
    }

    /**
     * Reports each process death Android recorded since the last report (API 30+): reason and the
     * process importance at the time. Read once per process start.
     */
    fun reportProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits = runCatching {
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(app.packageName, 0, 16)
        }.getOrNull().orEmpty().filter { it.timestamp > since }
        if (exits.isEmpty()) return
        prefs.edit().putLong(KEY_LAST_EXIT, exits.maxOf { it.timestamp }).apply()
        // The first run only anchors the timestamp, so an install never replays old history.
        if (since == 0L) return
        for (exit in exits.sortedBy { it.timestamp }) {
            val reason = ProcessExit.reason(exit.reason)
            val proc = if (exit.processName.orEmpty().contains(':')) "svc" else "main"
            TelemetryRepository.trace(TelemetryStage.PROCESS_EXIT, reason,
                detail = "imp=${exit.importance},p=$proc,sys=${if (ProcessExit.isSystemKill(reason)) 1 else 0}", provider = "sabbaq")
        }
    }
}

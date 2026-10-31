package app.sabbaq.fleet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sabbaq.BuildConfig
import app.sabbaq.LocaleManager
import app.sabbaq.R
import app.sabbaq.core.AppRelease
import app.sabbaq.core.UpdateState
import app.sabbaq.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * «تحديث جديد متاح: الإصدار …» as a notification, once per published version: tapping it (or its
 * «تحديث الآن») opens Sabbaq, which downloads, checks and installs the update ([ROUTE_UPDATE]).
 * A required update is posted again on every check until the driver updates.
 */
object UpdateNotifier {
    const val ROUTE_UPDATE = "update"
    private const val CHANNEL_ID = "sabbaq_updates"
    private const val NOTIFICATION_ID = 1005
    private const val PREFS = "sabbaq_update_notice"
    private const val KEY_NOTIFIED = "notified_code"

    /** What to post for [release] on a build at [installedCode], given the version last announced; null = nothing. */
    fun decide(release: AppRelease, installedCode: Long, notifiedCode: Long): UpdateState? = when (val s = release.state(installedCode)) {
        UpdateState.NONE -> null
        UpdateState.REQUIRED -> s
        UpdateState.AVAILABLE -> s.takeIf { release.latestCode > notifiedCode }
    }

    fun maybeNotify(context: Context, release: AppRelease) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = decide(release, BuildConfig.VERSION_CODE.toLong(), prefs.getLong(KEY_NOTIFIED, 0L)) ?: return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val ar = LocaleManager.isArabic()
        createChannel(app, ar)
        val required = state == UpdateState.REQUIRED
        val notes = (if (ar) release.notesAr else release.notesEn).trim()
        val title = app.getString(if (required) R.string.upd_notif_required_t else R.string.upd_notif_available_t, release.latestName)
        val text = notes.ifBlank { app.getString(R.string.upd_notif_body) }
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(FloatingBubble.EXTRA_ROUTE, ROUTE_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sabbaq)
            .setColor(0xFF4E2A84.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(if (required) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, app.getString(R.string.upd_now), open)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification) }
            .onSuccess { prefs.edit().putLong(KEY_NOTIFIED, release.latestCode).apply() }
    }

    /** Clears the notice once the update is installed or being installed. */
    fun dismiss(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun createChannel(context: Context, ar: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            if (ar) "تحديثات سبّاق" else "Sabbaq updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = if (ar) "تنبيه عند صدور نسخة جديدة من سبّاق." else "Tells you when a new Sabbaq version is out." })
    }
}

/**
 * Checks for a new version every six hours on any network, whether monitoring runs or not (the
 * monitoring service also checks while it runs). Survives restarts; Android picks the moment.
 */
class UpdateCheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        work = scope.launch {
            runCatching { FleetRepository.refreshIfStale(force = true) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 51_005
        private const val PERIOD_MS = 6 * 60 * 60_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }
        }
    }
}

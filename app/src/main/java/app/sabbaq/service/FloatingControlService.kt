package app.sabbaq.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.sabbaq.LocaleManager
import app.sabbaq.Settings
import app.sabbaq.fleet.FloatingBubble
import app.sabbaq.ui.MainActivity

/**
 * Keeps the process alive while the floating control is shown and monitoring is off, as the
 * reference does by keeping its foreground service up while its overlay is visible. While
 * [KeeperService] runs it already holds the process, so this service steps aside; when monitoring
 * stops with the control still shown, it takes over. Started only after the control's window is
 * on screen, which is what lets Android accept the start from the background.
 */
class FloatingControlService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            // Visible first: a service started in the foreground must post its notification in time.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: RuntimeException) {
            Log.w(TAG, "foreground start refused", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!Settings.floatingBubble || KeeperService.isRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        isRunning = true
        // After Android kills and recreates it, App.onCreate attaches the control again.
        FloatingBubble.refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ar = LocaleManager.isArabic()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            if (ar) "زر سبّاق العائم" else "Sabbaq floating control",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = if (ar) "إشعار دائم ما دام الزر العائم ظاهرًا." else "A persistent notification while the floating control is shown." })
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(if (LocaleManager.isArabic()) "زر سبّاق العائم ظاهر" else "Sabbaq floating control is on")
        .setContentText(if (LocaleManager.isArabic()) "المتابعة متوقفة؛ شغّلها من الزر العائم." else "Monitoring is off; start it from the floating control.")
        .setSmallIcon(app.sabbaq.R.drawable.ic_stat_sabbaq)
        .setColor(0xFF4E2A84.toInt())
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        .build()

    companion object {
        private const val TAG = "FloatingControlService"
        private const val CHANNEL_ID = "sabbaq_floating_control"
        private const val NOTIFICATION_ID = 1004

        @Volatile var isRunning: Boolean = false
            private set

        /** Runs the service exactly while [needed]; called by FloatingBubble on every refresh. */
        fun sync(context: Context, needed: Boolean) {
            if (needed == isRunning) return
            val intent = Intent(context, FloatingControlService::class.java)
            runCatching {
                if (needed) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                } else {
                    context.stopService(intent)
                }
            }.onFailure { Log.w(TAG, "sync($needed) refused", it) }
        }
    }
}

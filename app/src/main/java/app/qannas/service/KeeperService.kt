package app.qannas.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.qannas.Settings
import app.qannas.network.SupabaseClient
import app.qannas.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Keeps monitoring alive while the driver wants it: a visible foreground service that
 * renews the session and license lease, resumes on its own after the process is killed
 * (START_STICKY) or the phone restarts (BootReceiver), and re-checks the moment the screen
 * turns on so an offer shown right after unlocking is not missed. It never decides on an
 * offer itself; the accessibility service reads its state from [Settings.isActive].
 */
class KeeperService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var licenseMonitor: Job? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The driver stopped it: do not resume after a restart either.
            Settings.monitoringWanted = false
            Settings.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.monitoringWanted || !Settings.a11yDisclosureAccepted || !ServicesSafetyPolicy.canRunForegroundService(
                isExplicitlyEnabled = true,
                sdkInt = Build.VERSION.SDK_INT,
                notificationPermissionGranted = hasNotificationPermission(),
            )
        ) {
            Log.w(TAG, "تم رفض تشغيل خدمة أمامية غير مفعلة أو غير مرئية")
            Settings.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createChannel()
        return try {
            // Visible first: Android requires the notification within seconds of the start.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(resuming = !SupabaseClient.hasValidLicense()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
            registerScreenReceiver()
            if (licenseMonitor?.isActive != true) {
                licenseMonitor = serviceScope.launch { monitor() }
            }
            // Android recreates a sticky service after killing it; monitor() restores the session.
            START_STICKY
        } catch (e: SecurityException) {
            Log.e(TAG, "رفض النظام تشغيل الخدمة الأمامية")
            Settings.isActive = false
            stopSelf(startId)
            START_NOT_STICKY
        } catch (e: IllegalStateException) {
            Log.e(TAG, "لم تعد حالة التطبيق تسمح بخدمة أمامية")
            Settings.isActive = false
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private suspend fun monitor() {
        // After a restart the license lease is empty: resume the saved session first.
        if (!SupabaseClient.hasValidLicense()) {
            val restored = runCatching { SupabaseClient.restoreSession().getOrNull() == true }.getOrDefault(false)
            if (!restored) {
                notifySignInNeeded()
                shutDown()
                return
            }
        }
        Settings.isActive = true
        updateNotification()
        while (currentCoroutineContext().isActive) {
            delay(60_000)
            if (!checkStillAllowed()) break
        }
    }

    /** One license check; false when monitoring must stop. Network hiccups ride on the lease's grace. */
    private suspend fun checkStillAllowed(): Boolean {
        runCatching { SupabaseClient.revalidateLicense() }
        val allowed = Settings.monitoringWanted && Settings.a11yDisclosureAccepted &&
            hasNotificationPermission() && SupabaseClient.hasValidLicense()
        if (!allowed) shutDown()
        return allowed
    }

    private fun shutDown() {
        Settings.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // The device may have slept through the periodic check: refresh at once.
                serviceScope.launch { checkStillAllowed() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "حالة قناص الطلبات",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "إشعار دائم يوضح أن مراقبة العروض مفعلة." })
            manager.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL_ID,
                "تنبيهات قناص",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "تنبيه عندما تحتاج المتابعة إلى تسجيل الدخول لتستأنف." })
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(resuming = false))
    }

    private fun notifySignInNeeded() {
        createChannel()
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("توقفت متابعة الطلبات")
            .setContentText("افتح قناص وسجّل الدخول لاستئناف المتابعة.")
            .setSmallIcon(app.qannas.R.drawable.ic_stat_qannas)
            .setColor(0xFF006B58.toInt())
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(SIGN_IN_NOTIFICATION_ID, notification) }
    }

    private fun openAppIntent() = android.app.PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(resuming: Boolean) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(if (resuming) "قناص يستأنف المتابعة…" else "قناص الطلبات مفعّل")
        .setContentText(if (resuming) "نتحقق من اشتراكك ونعيد المتابعة تلقائيًا." else "يراقب العروض وفق الإعدادات التي اخترتها.")
        .setSmallIcon(app.qannas.R.drawable.ic_stat_qannas)
        .setColor(0xFF006B58.toInt())
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(
            android.R.drawable.ic_media_pause,
            "إيقاف",
            android.app.PendingIntent.getService(
                this, 1, Intent(this, KeeperService::class.java).setAction(ACTION_STOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setContentIntent(openAppIntent())
        .build()

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        serviceScope.cancel()
        // Not monitoringWanted: if Android destroyed us, the sticky restart resumes monitoring.
        Settings.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeeperService"
        private const val CHANNEL_ID = "qannas_monitoring"
        private const val ALERT_CHANNEL_ID = "qannas_alerts"
        private const val NOTIFICATION_ID = 1001
        private const val SIGN_IN_NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "app.qannas.STOP_MONITORING"

        /** Starts monitoring if the driver left it on; safe to call from any context. */
        fun resumeIfWanted(context: Context) {
            if (!Settings.monitoringWanted || !SupabaseClient.hasSavedSession()) return
            val intent = Intent(context, KeeperService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: IllegalStateException) {
                // Android may refuse a background start (no battery exemption): the next
                // boot, app open or sticky restart will try again.
                Log.w(TAG, "رفض النظام استئناف الخدمة الآن")
            } catch (e: SecurityException) {
                Log.w(TAG, "تعذر استئناف الخدمة لسبب أمني")
            }
        }
    }
}

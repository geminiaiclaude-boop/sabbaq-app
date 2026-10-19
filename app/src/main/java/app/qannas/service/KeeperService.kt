package app.qannas.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

class KeeperService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var licenseMonitor: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.a11yDisclosureAccepted || !SupabaseClient.hasValidLicense() || !ServicesSafetyPolicy.canRunForegroundService(
                isExplicitlyEnabled = Settings.isActive,
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
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
            if (licenseMonitor?.isActive != true) {
                licenseMonitor = serviceScope.launch {
                    while (isActive) {
                        delay(60_000)
                        SupabaseClient.revalidateLicense()
                        if (!Settings.a11yDisclosureAccepted || !Settings.isActive || !hasNotificationPermission() || !SupabaseClient.hasValidLicense()) {
                            Settings.isActive = false
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            break
                        }
                    }
                }
            }
            START_NOT_STICKY
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "حالة قناص الطلبات",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار دائم يوضح أن مراقبة العروض مفعلة."
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("قناص الطلبات مفعّل")
        .setContentText("يراقب العروض وفق الإعدادات التي اخترتها.")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
        .setContentIntent(
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        serviceScope.cancel()
        Settings.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "KeeperService"
        const val CHANNEL_ID = "qannas_monitoring"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "app.qannas.STOP_MONITORING"
    }
}

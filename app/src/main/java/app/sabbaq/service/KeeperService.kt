package app.sabbaq.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Service
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.sabbaq.Settings
import app.sabbaq.core.TelemetryStage
import app.sabbaq.network.SupabaseClient
import app.sabbaq.telemetry.TelemetryRepository
import app.sabbaq.voice.VoiceAnnouncer
import app.sabbaq.voice.VoiceKind
import app.sabbaq.voice.VoiceLines
import app.sabbaq.voice.VoiceRules
import java.time.Instant
import app.sabbaq.ui.MainActivity
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var offlineAlert: Job? = null
    private var offlineAnnounced = false
    private var monitoringLive = false
    private var pausedForOutage = false
    private var accessibilityWasOn: Boolean? = null
    private var subscriptionWarnedFor: Instant? = null
    private var subscriptionWarnedLevel = 0

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
        val resuming = !SupabaseClient.hasValidLicense()
        if (resuming && !restoreWithRetry()) {
            stoppedOnItsOwn()
            shutDown()
            return
        }
        Settings.isActive = true
        monitoringLive = true
        updateNotification()
        VoiceAnnouncer.announce(VoiceKind.Status, if (resuming) VoiceLines.RESUMED else VoiceLines.STARTED)
        registerNetworkCallback()
        checkAccessibility()
        warnIfSubscriptionEnding()
        while (currentCoroutineContext().isActive) {
            delay(60_000)
            if (!checkStillAllowed()) break
        }
    }

    /**
     * After a reboot the service usually starts before Wi-Fi or mobile data is up. A network
     * failure leaves the saved session intact, so keep retrying with backoff; only a definitive
     * answer (refresh token refused, no saved session, license not valid) stops monitoring.
     */
    private suspend fun restoreWithRetry(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + RESTORE_RETRY_WINDOW_MILLIS
        var backoff = RESTORE_FIRST_RETRY_MILLIS
        while (currentCoroutineContext().isActive) {
            val result = try {
                SupabaseClient.restoreSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (result.getOrNull() == true) return true
            if (result.isSuccess) {
                TelemetryRepository.trace(TelemetryStage.PAUSED, "keeper_restore_refused")
                return false
            }
            if (!Settings.monitoringWanted || SystemClock.elapsedRealtime() + backoff > deadline) {
                TelemetryRepository.trace(TelemetryStage.PAUSED, "keeper_restore_timeout")
                return false
            }
            TelemetryRepository.trace(TelemetryStage.PAUSED, "keeper_restore_retry")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RESTORE_MAX_RETRY_MILLIS)
        }
        return false
    }

    /** One license check; false when monitoring must stop. Network hiccups ride on the lease's grace. */
    private suspend fun checkStillAllowed(): Boolean {
        val revalidation = try {
            SupabaseClient.revalidateLicense()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val driverAllows = Settings.monitoringWanted && Settings.a11yDisclosureAccepted && hasNotificationPermission()
        if (!driverAllows) {
            TelemetryRepository.trace(TelemetryStage.PAUSED, when {
                !Settings.monitoringWanted -> "keeper_stopped_by_driver"
                !Settings.a11yDisclosureAccepted -> "keeper_disclosure_withdrawn"
                else -> "keeper_notifications_denied"
            })
            // A stop the driver chose (stop button, consent withdrawn) needs no alert.
            if (monitoringLive && Settings.monitoringWanted && Settings.a11yDisclosureAccepted) stoppedOnItsOwn()
            shutDown()
            return false
        }
        if (SupabaseClient.hasValidLicense()) {
            resumeAfterOutage()
            checkAccessibility()
            warnIfSubscriptionEnding()
            return true
        }
        // The server answered and the license is not valid: a definitive stop.
        if (revalidation.isSuccess) return stopForLicense()
        // The 15-minute lease ran out without a server answer (no coverage, session renewal
        // failed). Pause instead of stopping: the old behaviour shut monitoring down for good
        // and a driver who drove through a dead zone lost every offer until reopening the app.
        val restored = try {
            SupabaseClient.restoreSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        return when {
            restored.getOrNull() == true -> { resumeAfterOutage(); true }
            restored.isSuccess -> stopForLicense()
            else -> { pauseForOutage(); true }
        }
    }

    private fun stopForLicense(): Boolean {
        TelemetryRepository.trace(TelemetryStage.PAUSED, "keeper_license_lost")
        if (monitoringLive) stoppedOnItsOwn()
        shutDown()
        return false
    }

    /** Keeps the foreground service alive with the engine gated until the license is confirmed. */
    private fun pauseForOutage() {
        if (pausedForOutage) return
        pausedForOutage = true
        Settings.isActive = false
        TelemetryRepository.trace(TelemetryStage.PAUSED, "keeper_waiting_network")
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(resuming = true))
    }

    private fun resumeAfterOutage() {
        if (!pausedForOutage) return
        pausedForOutage = false
        Settings.isActive = true
        TelemetryRepository.trace(TelemetryStage.SERVICE_CONNECTED, "keeper_resumed")
        updateNotification()
        VoiceAnnouncer.announce(VoiceKind.Status, VoiceLines.RESUMED)
    }

    /** Monitoring stopped without the driver: say why, and leave a notification that opens the app. */
    private fun stoppedOnItsOwn() {
        val cause = VoiceRules.stopCause(
            notificationsAllowed = hasNotificationPermission(),
            hasSavedSession = SupabaseClient.hasSavedSession(),
            serverState = SupabaseClient.subscriptionSnapshot()?.state,
        )
        monitoringLive = false
        VoiceAnnouncer.announce(VoiceKind.Problem, VoiceRules.line(cause))
        notifyAlert(STOP_NOTIFICATION_ID, "توقفت متابعة الطلبات", VoiceRules.line(cause).removePrefix("توقف سبّاق. "))
    }

    /** The engine cannot accept anything while its permission is off: warn once per change. */
    private fun checkAccessibility() {
        val on = isAccessibilityEnabled()
        val before = accessibilityWasOn
        accessibilityWasOn = on
        if (!on && before != false) {
            VoiceAnnouncer.announce(VoiceKind.Problem, VoiceLines.ACCESSIBILITY_OFF)
            notifyAlert(A11Y_NOTIFICATION_ID, "إذن متابعة الطلبات مغلق", "لن يقبل سبّاق أي طلب حتى تفعّله. اضغط لفتح التطبيق.")
        } else if (on && before == false) {
            VoiceAnnouncer.announce(VoiceKind.Problem, VoiceLines.ACCESSIBILITY_BACK)
            runCatching { getSystemService(NotificationManager::class.java)?.cancel(A11Y_NOTIFICATION_ID) }
        }
    }

    /** One reminder a day before the end and one in the last hour, per subscription period. */
    private fun warnIfSubscriptionEnding() {
        val validUntil = SupabaseClient.subscriptionSnapshot()?.validUntil ?: return
        val millisLeft = validUntil.toEpochMilli() - System.currentTimeMillis()
        val level = VoiceRules.subscriptionWarningLevel(millisLeft)
        if (validUntil != subscriptionWarnedFor) {
            subscriptionWarnedFor = validUntil
            subscriptionWarnedLevel = 0
        }
        if (level > subscriptionWarnedLevel) {
            subscriptionWarnedLevel = level
            VoiceAnnouncer.announce(VoiceKind.Subscription, VoiceLines.subscriptionEnding(VoiceRules.hoursLeft(millisLeft)))
        }
    }

    /** Internet loss is announced only if it lasts, and its return only after a loss was announced. */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                serviceScope.launch { onConnectivity(online) }
            }

            override fun onLost(network: Network) {
                serviceScope.launch { onConnectivity(false) }
            }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) networkCallback = callback
    }

    private fun onConnectivity(online: Boolean) {
        if (online) {
            offlineAlert?.cancel()
            offlineAlert = null
            if (offlineAnnounced) {
                offlineAnnounced = false
                VoiceAnnouncer.announce(VoiceKind.Connection, VoiceLines.CONNECTION_BACK)
            }
        } else if (offlineAlert?.isActive != true && !offlineAnnounced) {
            offlineAlert = serviceScope.launch {
                delay(OFFLINE_GRACE_MILLIS)
                if (monitoringLive) {
                    offlineAnnounced = true
                    VoiceAnnouncer.announce(VoiceKind.Connection, VoiceLines.CONNECTION_LOST)
                }
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedId = ComponentName(this, SabbaqA11yService::class.java).flattenToString()
        val manager = getSystemService(AccessibilityManager::class.java) ?: return true
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == expectedId }
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
                // Not while the saved session is still being restored: that is not a lost license.
                serviceScope.launch { if (monitoringLive) checkStillAllowed() }
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
                "حالة سبّاق الطلبات",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "إشعار دائم يوضح أن مراقبة العروض مفعلة." })
            manager.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL_ID,
                "تنبيهات سبّاق",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "تنبيه عندما تتوقف المتابعة أو يُغلق إذن تحتاجه." })
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(resuming = false))
    }

    private fun notifyAlert(id: Int, title: String, text: String) {
        createChannel()
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(app.sabbaq.R.drawable.ic_stat_sabbaq)
            .setColor(0xFF006B58.toInt())
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(id, notification) }
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
        .setContentTitle(if (resuming) "سبّاق يستأنف المتابعة…" else "سبّاق الطلبات مفعّل")
        .setContentText(if (resuming) "نتحقق من اشتراكك ونعيد المتابعة تلقائيًا." else "يراقب العروض وفق الإعدادات التي اخترتها.")
        .setSmallIcon(app.sabbaq.R.drawable.ic_stat_sabbaq)
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
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        // The driver turned it off (here, from the app or by signing out): confirm it aloud.
        if (monitoringLive && !Settings.monitoringWanted) VoiceAnnouncer.announce(VoiceKind.Status, VoiceLines.STOPPED)
        monitoringLive = false
        serviceScope.cancel()
        // Not monitoringWanted: if Android destroyed us, the sticky restart resumes monitoring.
        Settings.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeeperService"
        private const val CHANNEL_ID = "sabbaq_monitoring"
        private const val ALERT_CHANNEL_ID = "sabbaq_alerts"
        private const val NOTIFICATION_ID = 1001
        private const val STOP_NOTIFICATION_ID = 1002
        private const val A11Y_NOTIFICATION_ID = 1003
        private const val OFFLINE_GRACE_MILLIS = 20_000L
        private const val RESTORE_FIRST_RETRY_MILLIS = 5_000L
        private const val RESTORE_MAX_RETRY_MILLIS = 60_000L
        private const val RESTORE_RETRY_WINDOW_MILLIS = 20 * 60_000L
        private const val ACTION_STOP = "app.sabbaq.STOP_MONITORING"

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

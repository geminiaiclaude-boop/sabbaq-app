package app.sabbaq.service.zones

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.sabbaq.LocaleManager
import app.sabbaq.Settings
import app.sabbaq.core.engine.zones.ExcludeZoneWatch
import app.sabbaq.service.SabbaqA11yService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Warns the driver when an accepted ride turns out to end inside an exclude zone (the reference's
 * `Mb`, see [ExcludeZoneWatch]): a high-priority notification with an alarm-like sound and
 * vibration, and a 15-second warning card over the ride app, so the ride can be cancelled there.
 */
object ExcludeZoneAlerts {
    private const val CHANNEL_ID = "sabbaq_exclude_zone_alerts"
    private const val NOTIFICATION_ID = 7411

    private val watch = ExcludeZoneWatch { System.currentTimeMillis() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var appContext: Context? = null

    /**
     * A tap on this offer was confirmed (or the offer vanished after the tap): keep locating its
     * destination and warn if it lies inside an exclude zone.
     */
    fun onAccepted(context: Context, destination: String?) {
        val dest = destination?.trim().orEmpty()
        if (dest.isEmpty()) return
        val app = context.applicationContext
        appContext = app
        watch.recordAccepted(dest)
        if (!watch.applies(Settings.engineConfig)) return
        scope.launch {
            var attempt = 0
            while (true) {
                val point = cached(dest)
                    ?: if (attempt < ExcludeZoneWatch.NETWORK_ATTEMPTS) runCatching { GeocodeWorker.resolveNow(app, dest) }.getOrNull() else null
                when (val step = watch.attempt(Settings.engineConfig, dest, attempt, point)) {
                    ExcludeZoneWatch.Step.Warn -> { warn(app, dest); return@launch }
                    ExcludeZoneWatch.Step.Done -> return@launch
                    is ExcludeZoneWatch.Step.Retry -> { delay(step.delayMs); attempt++ }
                }
            }
        }
    }

    /** The background geocoder placed [address]: warn if it was accepted lately and lies in an exclude zone. */
    fun onPlaced(address: String, lat: Double, lng: Double) {
        val app = appContext ?: return
        if (watch.onPlaced(Settings.engineConfig, address, lat, lng)) warn(app, address)
    }

    private fun cached(dest: String): DoubleArray? {
        val me = ZoneStore.driverLocation() ?: return null
        return ZoneStore.poiIndex()?.resolve(dest, me[0], me[1], Settings.engineConfig.destExcludeProximityMaxKm)?.point
    }

    private fun warn(context: Context, destination: String) {
        val ar = LocaleManager.isArabic()
        val title = if (ar) "⚠️ رحلة داخل منطقة مستثناة" else "⚠️ Ride inside an excluded zone"
        val text = if (ar) "الرحلة المقبولة إلى «$destination» تقع داخل منطقة استثناء — يمكنك إلغاؤها من تطبيق الرحلات."
            else "The accepted ride to \"$destination\" is inside an exclude zone — you can cancel it in the ride app."
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
                    if (ar) "تنبيهات المناطق المستثناة" else "Exclude zone alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = if (ar) "تنبيه حين يتبيّن أن رحلة مقبولة تنتهي داخل منطقة تتجنبها." else "Alerts when an accepted ride turns out to be inside an exclude zone"
                    setShowBadge(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                    enableLights(true)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                })
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(app.sabbaq.R.drawable.ic_stat_sabbaq)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .apply { open?.let { setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) } }
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
        SabbaqA11yService.instance?.showExcludeZoneWarning(title, text)
    }
}

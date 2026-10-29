package app.sabbaq.telemetry

import app.sabbaq.core.StandbyBucket
import app.sabbaq.fleet.FleetRepository
import app.sabbaq.fleet.KeepAlive
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import app.sabbaq.Settings
import app.sabbaq.core.DeviceHealth
import app.sabbaq.network.SupabaseClient
import app.sabbaq.service.SabbaqA11yService

/**
 * Reads the device-side preconditions for accepting offers: permissions, system restrictions,
 * account state and whether the ride app is installed. Booleans only; nothing identifying.
 */
internal object DeviceHealthProbe {
    fun read(
        context: Context,
        serviceConnected: Boolean,
        targetInstalled: Boolean,
        targetInOtherProfile: Boolean = false,
        targetUnknownPackage: Boolean = false,
    ): DeviceHealth {
        val app = context.applicationContext
        return DeviceHealth(
            accessibilityEnabled = runCatching { accessibilityEnabled(app) }.getOrDefault(false),
            serviceConnected = serviceConnected,
            monitoringActive = Settings.isActive,
            disclosureAccepted = Settings.a11yDisclosureAccepted,
            signedIn = runCatching { SupabaseClient.hasSavedSession() }.getOrDefault(false),
            licenseValid = runCatching { SupabaseClient.hasValidLicense() }.getOrDefault(false),
            targetInstalled = targetInstalled,
            targetInOtherProfile = targetInOtherProfile,
            targetUnknownPackage = targetUnknownPackage,
            batteryUnrestricted = runCatching {
                (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName)
            }.getOrDefault(false),
            backgroundRestricted = runCatching {
                Build.VERSION.SDK_INT >= 28 && (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
            }.getOrDefault(false),
            notificationsEnabled = runCatching {
                (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
            }.getOrDefault(false),
            powerSaveMode = runCatching {
                (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
            }.getOrDefault(false),
            networkAvailable = runCatching { networkAvailable(app) }.getOrDefault(false),
            hibernationOn = KeepAlive.hibernationOn(app),
            standbyRestricted = StandbyBucket.atRisk(KeepAlive.standbyBucket(app)),
            updateRequired = FleetRepository.updateRequired(),
        )
    }

    private fun accessibilityEnabled(context: Context): Boolean {
        val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val component = ComponentName(context, SabbaqA11yService::class.java)
        val names = setOf(component.flattenToString(), component.flattenToShortString())
        return enabled.split(':').any { it.trim() in names }
    }

    private fun networkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

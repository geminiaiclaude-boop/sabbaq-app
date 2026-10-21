package app.sabbaq.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import app.sabbaq.BuildConfig

/**
 * What Android and phone makers need before an app may keep running in the background:
 * an exemption from battery optimization (otherwise Doze and app standby pause it) and, on
 * some brands, an extra "auto-start" switch without which the app is not restarted after a
 * reboot. Each check returns live state; each action opens the exact system screen.
 */
internal object PowerSetup {
    fun isBatteryUnrestricted(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /**
     * The direct build asks Android directly (one tap: «سماح»). Google Play limits that request
     * to certain app types, so the Play build opens the list where the driver picks Sabbaq.
     */
    @SuppressLint("BatteryLife")
    fun batteryIntent(context: Context): Intent =
        if (BuildConfig.DIRECT_DISTRIBUTION) {
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }

    /** Brands known to block background restarts unless their own auto-start switch is on. */
    fun needsAutostart(manufacturer: String = Build.MANUFACTURER): Boolean =
        manufacturer.lowercase() in AUTOSTART_BRANDS

    /** The brand's auto-start screen, or the app's own settings page when none is found. */
    fun autostartIntent(context: Context): Intent {
        val pm = context.packageManager
        return AUTOSTART_SCREENS
            .map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .firstOrNull { runCatching { it.resolveActivity(pm) != null }.getOrDefault(false) }
            ?: appDetailsIntent(context)
    }

    fun appDetailsIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private val AUTOSTART_BRANDS = setOf(
        "xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus",
        "vivo", "iqoo", "asus", "meizu", "letv", "tecno", "infinix", "itel",
    )

    private val AUTOSTART_SCREENS = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
    )
}

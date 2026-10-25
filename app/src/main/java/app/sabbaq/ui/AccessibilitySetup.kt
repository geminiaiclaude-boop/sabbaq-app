package app.sabbaq.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * System-level routing for the accessibility setup flow.
 *
 * Android 13+ applies Restricted Settings to sensitive services, including
 * accessibility services, when an app was installed outside Google Play.
 */
object AccessibilitySetup {
    private const val GOOGLE_PLAY_INSTALLER = "com.android.vending"

    fun requiresRestrictedSettingsGuidance(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGooglePlayInstallation(context)

    fun isGooglePlayInstallation(context: Context): Boolean {
        val packageManager = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { packageManager.getInstallerPackageName(context.packageName) }.getOrNull()
        }
        return isGooglePlayInstaller(installer)
    }

    internal fun isGooglePlayInstaller(installerPackageName: String?): Boolean =
        installerPackageName == GOOGLE_PLAY_INSTALLER

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

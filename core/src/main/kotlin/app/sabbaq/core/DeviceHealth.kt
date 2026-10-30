package app.sabbaq.core

/**
 * Device-side preconditions for accepting offers, reported in every heartbeat. [primaryIssue]
 * names the first blocking problem in the order a driver has to fix them, so support reads one
 * reason instead of decoding twelve flags.
 */
data class DeviceHealth(
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val monitoringActive: Boolean,
    val disclosureAccepted: Boolean,
    val signedIn: Boolean,
    val licenseValid: Boolean,
    val targetInstalled: Boolean,
    val batteryUnrestricted: Boolean,
    val backgroundRestricted: Boolean,
    val notificationsEnabled: Boolean,
    val powerSaveMode: Boolean,
    val networkAvailable: Boolean,
    /** A supported ride app was found only in another profile (dual app, work profile, secure folder). */
    val targetInOtherProfile: Boolean = false,
    /** A ride app was found under a package the profiles do not list yet. */
    val targetUnknownPackage: Boolean = false,
    /** Android may hibernate the app when unused (Android 11+ "pause app activity"), ending the service. */
    val hibernationOn: Boolean = false,
    /** Rare or restricted standby bucket: Android defers the app's work for hours. */
    val standbyRestricted: Boolean = false,
    /** Below the administration's minimum version: offers are not accepted until updated. */
    val updateRequired: Boolean = false,
) {
    fun primaryIssue(): String = when {
        !accessibilityEnabled -> "a11y_disabled"
        !serviceConnected -> "a11y_not_connected"
        !targetInstalled && targetInOtherProfile -> "target_other_profile"
        !targetInstalled && targetUnknownPackage -> "target_unknown_package"
        !targetInstalled -> "target_not_installed"
        !signedIn -> "signed_out"
        // Without network the 15-minute license lease cannot be renewed, so an invalid license
        // while offline is a connectivity problem, not a subscription one.
        !networkAvailable -> "offline"
        !licenseValid -> "license_invalid"
        updateRequired -> "update_required"
        !disclosureAccepted -> "disclosure_required"
        !monitoringActive -> "monitoring_off"
        backgroundRestricted -> "background_restricted"
        !batteryUnrestricted -> "battery_optimized"
        hibernationOn -> "hibernation_on"
        standbyRestricted -> "standby_restricted"
        powerSaveMode -> "power_save"
        !notificationsEnabled -> "notifications_off"
        else -> "healthy"
    }

    /** Compact flags within the telemetry detail contract (lowercase, `key=0|1`, at most 96 chars). */
    fun flags(): String = listOf(
        "a11y" to accessibilityEnabled, "svc" to serviceConnected, "act" to monitoringActive,
        "dis" to disclosureAccepted, "ses" to signedIn, "lic" to licenseValid, "tgt" to targetInstalled,
        "bat" to batteryUnrestricted, "bg" to backgroundRestricted, "ntf" to notificationsEnabled,
        "pwr" to powerSaveMode, "net" to networkAvailable, "oth" to targetInOtherProfile, "unk" to targetUnknownPackage,
    ).joinToString(",") { (key, on) -> "$key=${if (on) 1 else 0}" }
}

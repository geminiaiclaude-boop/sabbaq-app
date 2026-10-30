package app.sabbaq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sabbaq.Settings
import app.sabbaq.fleet.KeepAlive
import app.sabbaq.service.KeeperService
import app.sabbaq.ui.MainActivity

/**
 * After the phone restarts or the app is updated, monitoring resumes on its own when the
 * driver left it on. The service restores the saved session and re-checks the license
 * before the accessibility service is allowed to act again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED && action !in QUICK_BOOT) return
        val started = KeeperService.resumeIfWanted(context)
        KeepAlive.schedule(context)
        // The reference engine opens the app when Android refuses the service at boot, so the
        // driver sees at once that monitoring needs a tap.
        if (!started && action != Intent.ACTION_MY_PACKAGE_REPLACED && Settings.monitoringWanted) runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private companion object {
        /** Fast-boot broadcasts some phone makers send instead of BOOT_COMPLETED. */
        val QUICK_BOOT = setOf("android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")
    }
}

package app.sabbaq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sabbaq.service.KeeperService

/**
 * After the phone restarts or the app is updated, monitoring resumes on its own when the
 * driver left it on. The service restores the saved session and re-checks the license
 * before the accessibility service is allowed to act again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        KeeperService.resumeIfWanted(context)
    }
}

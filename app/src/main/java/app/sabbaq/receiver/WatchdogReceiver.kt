package app.sabbaq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sabbaq.fleet.KeepAlive
import app.sabbaq.service.KeeperService

/**
 * The keep-alive watchdog (KeepAlive): when the driver left monitoring on and the service is not
 * running, start it again; then re-arm the alarm. Starting it twice is harmless.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (!KeeperService.isRunning) KeeperService.resumeIfWanted(context)
        KeepAlive.schedule(context)
    }

    companion object {
        const val ACTION = "app.sabbaq.action.WATCHDOG"
    }
}

package app.sabbaq

import android.app.Application
import app.sabbaq.network.SupabaseClient
import app.sabbaq.notifications.NotificationCenter
import app.sabbaq.support.SupportRepository
import app.sabbaq.service.KeeperService
import app.sabbaq.service.ProfileRepository
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.telemetry.TelemetryRepository
import app.sabbaq.voice.VoiceAnnouncer

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        LocaleManager.restore(this)
        Settings.init(this)
        app.sabbaq.service.zones.ZoneStore.init(this)
        runCatching { OfferDecisionLogStore.init(this) }
        runCatching { app.sabbaq.stats.OfferStatsStore.init(this) }
        runCatching { NotificationCenter.init(this) }
        runCatching { SupportRepository.init(this) }
        runCatching { ProfileRepository.init(this) }
        runCatching { TelemetryRepository.init(this) }
        runCatching { app.sabbaq.fleet.FleetRepository.init(this) }
        runCatching { app.sabbaq.fleet.ZonePolicyRepository.init(this) }
        runCatching { app.sabbaq.fleet.ScreenSamples.init(this) }
        runCatching { app.sabbaq.fleet.UpdateTelemetry.recordRun(this) }
        runCatching { app.sabbaq.fleet.KeepAlive.reportProcessExits(this) }
        SupabaseClient.init(this)
        VoiceAnnouncer.init(this)
        runCatching { app.sabbaq.fleet.FloatingBubble.attach(this) }
        // A new process starts with no session in memory and the runtime gate closed. The
        // saved session (encrypted) lets monitoring resume once the license is confirmed again.
        SupabaseClient.clearSession(forget = false)
        Settings.clearProcessAuthorizationState()
        KeeperService.resumeIfWanted(this)
        runCatching { app.sabbaq.fleet.KeepAlive.schedule(this) }
    }
}

package app.sabbaq

import android.app.Application
import app.sabbaq.network.SupabaseClient
import app.sabbaq.service.KeeperService
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.telemetry.TelemetryRepository
import app.sabbaq.voice.VoiceAnnouncer

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Settings.init(this)
        runCatching { OfferDecisionLogStore.init(this) }
        runCatching { TelemetryRepository.init(this) }
        SupabaseClient.init(this)
        VoiceAnnouncer.init(this)
        // A new process starts with no session in memory and the runtime gate closed. The
        // saved session (encrypted) lets monitoring resume once the license is confirmed again.
        SupabaseClient.clearSession(forget = false)
        Settings.clearProcessAuthorizationState()
        KeeperService.resumeIfWanted(this)
    }
}

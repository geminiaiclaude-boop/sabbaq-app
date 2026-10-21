package app.qannas

import android.app.Application
import app.qannas.network.SupabaseClient
import app.qannas.service.KeeperService
import app.qannas.voice.VoiceAnnouncer

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Settings.init(this)
        SupabaseClient.init(this)
        VoiceAnnouncer.init(this)
        // A new process starts with no session in memory and the runtime gate closed. The
        // saved session (encrypted) lets monitoring resume once the license is confirmed again.
        SupabaseClient.clearSession(forget = false)
        Settings.clearProcessAuthorizationState()
        KeeperService.resumeIfWanted(this)
    }
}

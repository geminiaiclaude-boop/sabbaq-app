package app.qannas

import android.app.Application
import app.qannas.network.SupabaseClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Settings.init(this)
        // لا توجد جلسة دائمة؛ التشغيل لا يعود إلا بعد مصادقة وربط ترخيص جديدين.
        SupabaseClient.clearSession()
        Settings.clearProcessAuthorizationState()
    }
}

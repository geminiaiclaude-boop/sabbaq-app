package app.qannas.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.qannas.Settings
import app.qannas.service.KeeperService
import app.qannas.service.ServicesSafetyPolicy

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!ServicesSafetyPolicy.canRunForegroundService(
                isExplicitlyEnabled = Settings.isActive,
                sdkInt = Build.VERSION.SDK_INT,
                notificationPermissionGranted = hasNotificationPermission(context),
            )
        ) {
            Log.i(TAG, "لم تتم استعادة الخدمة بعد الإقلاع لغياب التفعيل أو إذن الإشعارات")
            return
        }

        val serviceIntent = Intent(context, KeeperService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "رفض النظام استعادة الخدمة الأمامية بعد الإقلاع")
        } catch (e: SecurityException) {
            Log.w(TAG, "تعذر استعادة الخدمة الأمامية لسبب أمني")
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BootReceiver"
    }
}

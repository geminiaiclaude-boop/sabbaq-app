package app.qannas.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.qannas.Settings
import app.qannas.network.SupabaseClient
import app.qannas.service.KeeperService
import app.qannas.ui.theme.QannasTheme

enum class AppState {
    LOGIN, DRIVER_PANEL, ADMIN_PANEL
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var notificationPermissionGranted by remember {
                mutableStateOf(hasNotificationPermission())
            }
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                notificationPermissionGranted = granted
            }
            QannasTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        var appState by remember { mutableStateOf(AppState.LOGIN) }

                        Column(Modifier.fillMaxSize()) {
                            if (appState != AppState.LOGIN) {
                                TextButton(onClick = {
                                    Settings.isActive = false
                                    stopService(Intent(this@MainActivity, KeeperService::class.java))
                                    SupabaseClient.clearSession()
                                    appState = AppState.LOGIN
                                }) { Text("تسجيل الخروج") }
                            }
                        when (appState) {
                            AppState.LOGIN -> {
                                LoginScreen(onLoginSuccess = { isAdmin ->
                                    appState = if (isAdmin) AppState.ADMIN_PANEL else AppState.DRIVER_PANEL
                                })
                            }
                            AppState.ADMIN_PANEL -> {
                                AdminScreen()
                            }
                            AppState.DRIVER_PANEL -> {
                                SettingsScreen(
                                    notificationPermissionGranted = notificationPermissionGranted,
                                    onRequestNotificationPermission = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    onOpenA11y = {
                                        runCatching {
                                            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    },
                                    onToggleKeeper = { shouldStart ->
                                        runCatching {
                                            val intent = Intent(this@MainActivity, KeeperService::class.java)
                                            if (shouldStart) {
                                                check(SupabaseClient.hasValidLicense()) { "يلزم اشتراك صالح قبل التشغيل" }
                                                // تتحقق KeeperService من هذه الحالة قبل عرض الإشعار الأمامي.
                                                Settings.isActive = true
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    startForegroundService(intent)
                                                } else {
                                                    startService(intent)
                                                }
                                            } else {
                                                Settings.isActive = false
                                                stopService(intent)
                                            }
                                            Unit
                                        }.onFailure {
                                            if (shouldStart) Settings.isActive = false
                                        }
                                    }
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

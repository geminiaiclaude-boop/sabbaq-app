package app.qannas.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.qannas.service.KeeperService
import app.qannas.ui.theme.QannasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QannasTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        var isLoggedIn by remember { mutableStateOf(false) }

                        if (!isLoggedIn) {
                            LoginScreen(onLoginSuccess = { isLoggedIn = true })
                        } else {
                            SettingsScreen(
                                onOpenA11y = {
                                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onToggleKeeper = { start ->
                                    val intent = Intent(this, KeeperService::class.java)
                                    if (start) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            startForegroundService(intent)
                                        } else {
                                            startService(intent)
                                        }
                                    } else {
                                        stopService(intent)
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

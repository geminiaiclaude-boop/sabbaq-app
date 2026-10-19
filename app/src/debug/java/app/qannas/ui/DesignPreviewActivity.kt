package app.qannas.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.qannas.ThemeMode
import app.qannas.ui.theme.QannasTheme

/** Debug-source-set only. No auth grant, credentials, service start, or license bypass. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "login"
        val dark = intent.getBooleanExtra("dark", false)
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(210, 20, 44, 55))
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        val destination = when (screen) {
            "filters" -> DriverDestination.Filters
            "simulator" -> DriverDestination.Simulator
            "account" -> DriverDestination.Account
            else -> DriverDestination.Dashboard
        }
        setContent {
            QannasTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    QannasShell(showHeader = screen !in setOf("login", "welcome", "welcome-example", "welcome-ready", "disclosure"), themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                        onThemeChange = {}, onLogout = { finish() }) {
                        if (screen == "login") LoginScreen(onLoginSuccess = { finish() })
                        else if (screen == "welcome") OnboardingScreen(onContinue = { finish() })
                        else if (screen == "welcome-example") OnboardingScreen(onContinue = { finish() }, initialPage = 1)
                        else if (screen == "welcome-ready") OnboardingScreen(onContinue = { finish() }, initialPage = 2)
                        else if (screen == "disclosure") AccessibilityDisclosureDialog(onAccept = { finish() }, onDismiss = { finish() })
                        else SettingsScreen(
                            notificationPermissionGranted = false,
                            onRequestNotificationPermission = {},
                            onOpenA11y = { Result.failure(IllegalStateException("المعاينة لا تمنح الأذونات")) },
                            onToggleKeeper = { Result.failure(IllegalStateException("المعاينة لا تشغّل الخدمات")) },
                            initialDestination = destination
                        )
                    }
                }
            }
        }
    }
}

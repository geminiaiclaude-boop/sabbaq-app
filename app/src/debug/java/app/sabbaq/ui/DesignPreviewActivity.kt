package app.sabbaq.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.sabbaq.ThemeMode
import app.sabbaq.stats.DecisionLogOutcome
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.ui.theme.SabbaqTheme

/** Debug-source-set only. No auth grant, credentials, service start, or license bypass. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "login"
        val dark = intent.getBooleanExtra("dark", false)
        if (screen == "decision-log") {
            check(OfferDecisionLogStore.clear(this)) { "Could not reset debug decision-log fixture" }
            OfferDecisionLogStore.recordOutcome("ci-preview", DecisionLogOutcome.ACCEPTED_CONFIRMED)
        }
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(210, 20, 44, 55))
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (screen == "cinematic-opening") {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        val destination = when (screen) {
            "decision-log" -> DriverDestination.Dashboard
            "filters" -> DriverDestination.Filters
            "simulator" -> DriverDestination.Simulator
            "account" -> DriverDestination.Account
            "setup" -> DriverDestination.Setup
            else -> DriverDestination.Dashboard
        }
        setContent {
            SabbaqTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    if (screen == "cinematic-opening") {
                        SabbaqVideoOpening(onFinished = {})
                    } else SabbaqShell(showHeader = screen !in setOf("login", "login-trial", "welcome", "welcome-example", "welcome-ready", "disclosure", "intro"), themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                        onThemeChange = {}, onLogout = { finish() }) {
                        if (screen == "intro") SabbaqLaunchIntro(onFinished = {}, staticFrame = true)
                        else if (screen == "login") LoginScreen(onLoginSuccess = { finish() })
                        else if (screen == "login-trial") LoginScreen(onLoginSuccess = { finish() }, initialIntent = AccessIntent.Trial)
                        else if (screen == "welcome") OnboardingScreen(onContinue = { finish() })
                        else if (screen == "welcome-example") OnboardingScreen(onContinue = { finish() }, initialPage = 1)
                        else if (screen == "welcome-ready") OnboardingScreen(onContinue = { finish() }, initialPage = 2)
                        else if (screen == "disclosure") AccessibilityDisclosureDialog(onAccept = { finish() }, onDismiss = { finish() })
                        else SettingsScreen(
                            notificationPermissionGranted = false,
                            onRequestNotificationPermission = {},
                            onOpenA11y = { Result.failure(IllegalStateException("المعاينة لا تمنح الأذونات")) },
                            onToggleKeeper = { Result.failure(IllegalStateException("المعاينة لا تشغّل الخدمات")) },
                            initialDestination = destination,
                            autoSetupGuide = false,
                        )
                    }
                }
            }
        }
    }
}

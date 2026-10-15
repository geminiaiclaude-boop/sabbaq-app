package app.qannas.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.qannas.Settings
import app.qannas.ThemeMode
import app.qannas.network.SupabaseClient
import app.qannas.service.KeeperService
import app.qannas.ui.theme.QannasTheme

enum class AppState { LOGIN, DRIVER_PANEL, ADMIN_PANEL }

/** Keeps navigation across rotation only. No credentials or authorization are persisted. */
class SessionNavigationViewModel : ViewModel() {
    var route by mutableStateOf(AppState.LOGIN)
        private set
    fun authenticated(isAdmin: Boolean) { route = if (isAdmin) AppState.ADMIN_PANEL else AppState.DRIVER_PANEL }
    fun signedOut() { route = AppState.LOGIN }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val navigation = ViewModelProvider(this)[SessionNavigationViewModel::class.java]
        setContent {
            var mode by remember { mutableStateOf(Settings.themeMode) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            var notificationsGranted by remember { mutableStateOf(hasNotificationPermission()) }
            val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                notificationsGranted = it
            }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(210, 20, 44, 55))
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            QannasTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    QannasShell(
                        showHeader = navigation.route != AppState.LOGIN,
                        themeMode = mode,
                        onThemeChange = { mode = it; Settings.themeMode = it },
                        onLogout = {
                            Settings.isActive = false
                            stopService(Intent(this@MainActivity, KeeperService::class.java))
                            SupabaseClient.clearSession()
                            navigation.signedOut()
                        }
                    ) {
                        when (navigation.route) {
                            AppState.LOGIN -> LoginScreen(onLoginSuccess = navigation::authenticated)
                            AppState.ADMIN_PANEL -> AdminScreen()
                            AppState.DRIVER_PANEL -> SettingsScreen(
                                notificationPermissionGranted = notificationsGranted,
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                        permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                onOpenA11y = { runCatching {
                                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)); Unit
                                } },
                                onToggleKeeper = { start -> runCatching {
                                    val intent = Intent(this@MainActivity, KeeperService::class.java)
                                    if (start) {
                                        check(SupabaseClient.hasValidLicense()) { "يلزم اشتراك صالح قبل التشغيل" }
                                        Settings.isActive = true
                                        startForegroundService(intent)
                                    } else {
                                        Settings.isActive = false
                                        stopService(intent)
                                    }
                                    Unit
                                }.onFailure { if (start) Settings.isActive = false } }
                            )
                        }
                    }
                }
            }
        }
    }
    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QannasShell(
    showHeader: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onLogout: () -> Unit,
    content: @Composable () -> Unit
) {
    var showThemeMenu by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (showHeader) TopAppBar(
                title = { QannasBrand(compact = true) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    Box {
                        IconButton(onClick = { showThemeMenu = true }) {
                            Icon(when (themeMode) { ThemeMode.DARK -> Icons.Outlined.DarkMode
                                ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                ThemeMode.SYSTEM -> Icons.Outlined.SettingsBrightness }, "تغيير مظهر التطبيق")
                        }
                        DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                            ThemeMode.entries.forEach { item ->
                                DropdownMenuItem(text = { Text(when (item) {
                                    ThemeMode.SYSTEM -> "حسب النظام"; ThemeMode.LIGHT -> "فاتح"; ThemeMode.DARK -> "داكن"
                                }) }, onClick = { showThemeMenu = false; onThemeChange(item) },
                                    trailingIcon = { RadioButton(selected = item == themeMode, onClick = null) })
                            }
                        }
                    }
                    IconButton(onClick = { confirmLogout = true }) { Icon(Icons.AutoMirrored.Outlined.Logout, "تسجيل الخروج") }
                }
            )
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) { content() }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("تسجيل الخروج؟") },
        text = { Text("سيتوقف القناص وتُمسح جلسة الدخول. أي تعديلات غير محفوظة على المرشحات لن تُطبّق.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("إيقاف القناص والخروج") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("البقاء") } }
    )
}

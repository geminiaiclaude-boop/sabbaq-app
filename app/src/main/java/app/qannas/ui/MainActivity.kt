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
import app.qannas.BuildConfig
import app.qannas.Settings
import app.qannas.ThemeMode
import app.qannas.network.SupabaseClient
import app.qannas.service.KeeperService
import app.qannas.ui.theme.QannasDepthContent
import app.qannas.ui.theme.QannasTheme

enum class AppState { WELCOME, LOGIN, DRIVER_PANEL, ADMIN_PANEL }

/** Keeps navigation across rotation only. No credentials or authorization are persisted. */
class SessionNavigationViewModel : ViewModel() {
    var route by mutableStateOf(AppState.LOGIN)
        private set
    private var initialized = false
    fun initialize(showWelcome: Boolean) {
        if (!initialized) {
            route = if (showWelcome) AppState.WELCOME else AppState.LOGIN
            initialized = true
        }
    }
    fun welcomeFinished() { route = AppState.LOGIN }
    fun authenticated(isAdmin: Boolean) {
        route = if (isAdmin && BuildConfig.DIRECT_DISTRIBUTION) AppState.ADMIN_PANEL else AppState.DRIVER_PANEL
    }
    fun signedOut() { route = AppState.LOGIN }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val navigation = ViewModelProvider(this)[SessionNavigationViewModel::class.java]
        navigation.initialize(!OnboardingPreferences.isComplete(this))
        setContent {
            var mode by remember { mutableStateOf(Settings.themeMode) }
            var showA11yDisclosure by remember { mutableStateOf(false) }
            var permissionMessage by remember { mutableStateOf<String?>(null) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            var notificationsGranted by remember { mutableStateOf(hasNotificationPermission()) }
            var unsavedFilters by remember { mutableStateOf(false) }
            val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                notificationsGranted = it
            }
            val endSession = {
                Settings.isActive = false
                Settings.a11yDisclosureAccepted = false
                stopService(Intent(this@MainActivity, KeeperService::class.java))
                SupabaseClient.clearSession()
                unsavedFilters = false
                navigation.signedOut()
            }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(210, 20, 44, 55))
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            QannasTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    QannasShell(
                        showHeader = navigation.route == AppState.DRIVER_PANEL || navigation.route == AppState.ADMIN_PANEL,
                        themeMode = mode,
                        onThemeChange = { mode = it; Settings.themeMode = it },
                        hasUnsavedChanges = unsavedFilters,
                        onLogout = endSession,
                    ) {
                        QannasDepthContent(target = navigation.route, order = { it.ordinal }, label = "app-route") { route ->
                        when (route) {
                            AppState.WELCOME -> OnboardingScreen(onContinue = {
                                if (!OnboardingPreferences.markComplete(this@MainActivity)) {
                                    permissionMessage = "قد تظهر هذه المقدمة مرة أخرى عند فتح التطبيق. يمكنك المتابعة الآن."
                                }
                                navigation.welcomeFinished()
                            })
                            AppState.LOGIN -> LoginScreen(onLoginSuccess = navigation::authenticated)
                            AppState.ADMIN_PANEL -> {
                                if (BuildConfig.DIRECT_DISTRIBUTION) AdminScreen()
                                else Text("لوحة الإدارة غير متاحة في هذه النسخة")
                            }
                            AppState.DRIVER_PANEL -> SettingsScreen(
                                notificationPermissionGranted = notificationsGranted,
                                onRequestNotificationPermission = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                        permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                onOpenA11y = { runCatching {
                                    showA11yDisclosure = true
                                    Unit
                                } },
                                onToggleKeeper = { start -> runCatching {
                                    val intent = Intent(this@MainActivity, KeeperService::class.java)
                                    if (start) {
                                        if (!Settings.a11yDisclosureAccepted) {
                                            showA11yDisclosure = true
                                            error("راجع طريقة عمل القبول التلقائي ووافق عليها قبل التشغيل")
                                        }
                                        check(SupabaseClient.hasValidLicense()) { "يلزم اشتراك صالح قبل التشغيل" }
                                        Settings.isActive = true
                                        startForegroundService(intent)
                                    } else {
                                        Settings.isActive = false
                                        stopService(intent)
                                    }
                                    Unit
                                }.onFailure { if (start) Settings.isActive = false } },
                                onUnsavedFiltersChanged = { unsavedFilters = it },
                                onAccountDeleted = {
                                    val wiped = Settings.clearAccountData()
                                    endSession()
                                    permissionMessage = if (wiped) "حُذف حسابك وبياناته المرتبطة. شكرًا لاستخدامك قناص."
                                        else "حُذف حسابك من الخادم، لكن تعذّر مسح بعض الإعدادات المحلية. احذف بيانات التطبيق من إعدادات الجهاز لإكمال المسح."
                                },
                            )
                        }
                        }
                    }
                    if (showA11yDisclosure) AccessibilityDisclosureDialog(
                        onAccept = {
                            showA11yDisclosure = false
                            Settings.a11yDisclosureAccepted = true
                            runCatching { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                .onFailure { permissionMessage = "تعذر فتح إعدادات إمكانية الوصول. حاول مجددًا من لوحة القيادة." }
                        },
                        onDismiss = {
                            showA11yDisclosure = false
                            Settings.a11yDisclosureAccepted = false
                            stopService(Intent(this@MainActivity, KeeperService::class.java))
                        }
                    )
                    permissionMessage?.let { message -> AlertDialog(
                        onDismissRequest = { permissionMessage = null },
                        title = { Text("تنبيه") }, text = { Text(message) },
                        confirmButton = { TextButton(onClick = { permissionMessage = null }) { Text("حسنًا") } }
                    ) }
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
    hasUnsavedChanges: Boolean = false,
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
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            OfflineBanner(online = rememberIsOnline())
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("تسجيل الخروج؟") },
        text = { Text(
            if (hasUnsavedChanges) "لديك تعديلات على شروطك لم تُحفظ وستُفقد عند الخروج. سيتوقف القبول التلقائي أيضًا."
            else "سيتوقف القبول التلقائي بقناص. شروطك المحفوظة تبقى على هذا الجهاز."
        ) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) {
            Text(if (hasUnsavedChanges) "تجاهل التعديلات والخروج" else "إيقاف قناص والخروج")
        } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("البقاء") } }
    )
}

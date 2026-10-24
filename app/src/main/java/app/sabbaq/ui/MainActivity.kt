package app.sabbaq.ui

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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.sabbaq.BuildConfig
import app.sabbaq.Settings
import app.sabbaq.ThemeMode
import app.sabbaq.network.LicenseState
import app.sabbaq.network.SupabaseClient
import app.sabbaq.service.KeeperService
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.stats.OfferStatsStore
import app.sabbaq.ui.theme.SabbaqDepthContent
import app.sabbaq.ui.theme.SabbaqTheme
import kotlinx.coroutines.CancellationException

enum class AppState { WELCOME, RESTORING_SESSION, SESSION_RESTORE_ERROR, LOGIN, DRIVER_PANEL, ADMIN_PANEL }

/** Keeps navigation across rotation only. No credentials or authorization are persisted. */
class SessionNavigationViewModel : ViewModel() {
    var route by mutableStateOf(AppState.LOGIN)
        private set
    private var initialized = false
    fun initialize(showWelcome: Boolean, hasSavedSession: Boolean = false) {
        if (!initialized) {
            route = when {
                showWelcome -> AppState.WELCOME
                hasSavedSession -> AppState.RESTORING_SESSION
                else -> AppState.LOGIN
            }
            initialized = true
        }
    }
    fun welcomeFinished(hasSavedSession: Boolean = false) {
        route = if (hasSavedSession) AppState.RESTORING_SESSION else AppState.LOGIN
    }
    fun sessionRestoreFinished(restored: Boolean) {
        if (route == AppState.RESTORING_SESSION) route = if (restored) AppState.DRIVER_PANEL else AppState.LOGIN
    }
    fun sessionRestoreUnavailable() {
        if (route == AppState.RESTORING_SESSION) route = AppState.SESSION_RESTORE_ERROR
    }
    fun retrySessionRestore() {
        if (route == AppState.SESSION_RESTORE_ERROR) route = AppState.RESTORING_SESSION
    }
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
        navigation.initialize(
            showWelcome = !OnboardingPreferences.isComplete(this),
            hasSavedSession = SupabaseClient.hasSavedSession(),
        )
        setContent {
            var mode by remember { mutableStateOf(Settings.themeMode) }
            var showA11yDisclosure by remember { mutableStateOf(false) }
            var permissionMessage by remember { mutableStateOf<String?>(null) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            var notificationsGranted by remember { mutableStateOf(hasNotificationPermission()) }
            var filterSavePending by remember { mutableStateOf(false) }
            var hasUnpersistedFilterChanges by remember { mutableStateOf(false) }
            // The cinematic clip precedes the existing logo intro once per install/update.
            var showVideoOpening by rememberSaveable {
                mutableStateOf(savedInstanceState == null && LaunchVideoPreferences.shouldShow(this@MainActivity))
            }
            // The existing logo intro plays once per cold start, not again on rotation.
            var showIntro by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            DisposableEffect(showVideoOpening) {
                val bars = WindowInsetsControllerCompat(window, window.decorView)
                if (showVideoOpening) {
                    bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    bars.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    bars.show(WindowInsetsCompat.Type.systemBars())
                }
                onDispose { bars.show(WindowInsetsCompat.Type.systemBars()) }
            }
            val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                notificationsGranted = it
            }
            val endSession = {
                // Signing out ends automatic resume too; clearSession() also wipes the saved session.
                Settings.monitoringWanted = false
                Settings.isActive = false
                Settings.a11yDisclosureAccepted = false
                stopService(Intent(this@MainActivity, KeeperService::class.java))
                SupabaseClient.clearSession()
                filterSavePending = false
                hasUnpersistedFilterChanges = false
                navigation.signedOut()
            }
            LaunchedEffect(navigation.route) {
                if (navigation.route != AppState.RESTORING_SESSION) return@LaunchedEffect
                if (!SupabaseClient.hasSavedSession()) {
                    navigation.sessionRestoreFinished(restored = false)
                    return@LaunchedEffect
                }
                try {
                    val result = SupabaseClient.restoreSession()
                    when {
                        result.getOrNull() == true && SupabaseClient.hasValidLicense() -> {
                            if (SupabaseClient.subscriptionSnapshot()?.state == LicenseState.ACTIVE) {
                                Settings.knownSubscriber = true
                            }
                            KeeperService.resumeIfWanted(this@MainActivity)
                            navigation.sessionRestoreFinished(restored = true)
                        }
                        result.isFailure && SupabaseClient.hasSavedSession() ->
                            navigation.sessionRestoreUnavailable()
                        else -> navigation.sessionRestoreFinished(restored = false)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (SupabaseClient.hasSavedSession()) navigation.sessionRestoreUnavailable()
                    else navigation.sessionRestoreFinished(restored = false)
                }
            }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(210, 20, 44, 55))
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            SabbaqTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SabbaqShell(
                        showHeader = navigation.route == AppState.DRIVER_PANEL || navigation.route == AppState.ADMIN_PANEL,
                        themeMode = mode,
                        onThemeChange = { mode = it; Settings.themeMode = it },
                        isSavingFilters = filterSavePending,
                        hasUnpersistedFilterChanges = hasUnpersistedFilterChanges,
                        onLogout = endSession,
                    ) {
                        SabbaqDepthContent(target = navigation.route, order = { it.ordinal }, label = "app-route") { route ->
                        when (route) {
                            AppState.WELCOME -> OnboardingScreen(onContinue = {
                                if (!OnboardingPreferences.markComplete(this@MainActivity)) {
                                    permissionMessage = "قد تظهر هذه المقدمة مرة أخرى عند فتح التطبيق. يمكنك المتابعة الآن."
                                }
                                navigation.welcomeFinished(SupabaseClient.hasSavedSession())
                            })
                            AppState.RESTORING_SESSION -> SessionRestoringScreen()
                            AppState.SESSION_RESTORE_ERROR -> SessionRestoreErrorScreen(
                                onRetry = navigation::retrySessionRestore,
                                onUseAnotherAccount = endSession,
                            )
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
                                        Settings.monitoringWanted = true
                                        Settings.isActive = true
                                        startForegroundService(intent)
                                    } else {
                                        Settings.monitoringWanted = false
                                        Settings.isActive = false
                                        stopService(intent)
                                    }
                                    Unit
                                }.onFailure { if (start) { Settings.isActive = false; Settings.monitoringWanted = false } } },
                                onFilterSaveStateChanged = { saving, unpersisted ->
                                    filterSavePending = saving
                                    hasUnpersistedFilterChanges = unpersisted
                                },
                                onAccountDeleted = {
                                    val wiped = Settings.clearAccountData()
                                    val statsWiped = OfferStatsStore.clear(this@MainActivity)
                                    val historyWiped = OfferDecisionLogStore.clear(this@MainActivity)
                                    endSession()
                                    permissionMessage = if (wiped && statsWiped && historyWiped) "حُذف حسابك وبياناته المرتبطة. شكرًا لاستخدامك سبّاق."
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
                    // Do not compose the logo intro underneath the cinematic clip: its
                    // 2.3s animation would finish while hidden instead of playing after it.
                    if (shouldRenderLogoIntro(showIntro, showVideoOpening)) {
                        SabbaqLaunchIntro(onFinished = { showIntro = false })
                    }
                    // Access just granted (code or trial): celebrate over everything, once.
                    val celebration by Celebrations.current.collectAsState()
                    celebration?.let { ActivationCelebration(it, onDone = Celebrations::dismiss) }
                }
                if (showVideoOpening) {
                    SabbaqVideoOpening(onFinished = {
                        LaunchVideoPreferences.markSeen(this@MainActivity)
                        showVideoOpening = false
                    })
                }
            }
        }
    }
    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SessionRestoringScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("سبّاق", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("جارٍ استعادة جلسة الدخول المحفوظة…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SessionRestoreErrorScreen(onRetry: () -> Unit, onUseAnotherAccount: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("تعذر استعادة جلسة الدخول", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "لم تُمسح الجلسة المحفوظة. تعذر التحقق منها الآن؛ تحقق من الاتصال ثم أعد المحاولة، أو اختر تسجيل الدخول بحساب آخر.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("إعادة المحاولة") }
        TextButton(onClick = onUseAnotherAccount) { Text("تسجيل الدخول بحساب آخر") }
    }
}

internal fun shouldRenderLogoIntro(showIntro: Boolean, showVideoOpening: Boolean): Boolean =
    showIntro && !showVideoOpening

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SabbaqShell(
    showHeader: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onLogout: () -> Unit,
    isSavingFilters: Boolean = false,
    hasUnpersistedFilterChanges: Boolean = false,
    content: @Composable () -> Unit
) {
    var showThemeMenu by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (showHeader) TopAppBar(
                title = { SabbaqBrand(compact = true) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
        Box(Modifier.fillMaxSize()) {
            SabbaqAmbientBackground(Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                OfflineBanner(online = rememberIsOnline())
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("تسجيل الخروج؟") },
        text = { Text(when {
            isSavingFilters -> "جارٍ حفظ آخر تعديل تلقائيًا. انتظر حتى يكتمل الحفظ قبل تسجيل الخروج."
            hasUnpersistedFilterChanges -> "هناك تعديل على الشروط لم يُحفظ؛ سيبقى آخر إعداد محفوظ على هذا الجهاز إذا خرجت الآن."
            else -> "سيتوقف القبول التلقائي بسبّاق. شروطك المحفوظة تبقى على هذا الجهاز."
        }) },
        confirmButton = { TextButton(
            enabled = !isSavingFilters,
            onClick = { confirmLogout = false; onLogout() },
        ) {
            Text(when {
                isSavingFilters -> "جارٍ الحفظ…"
                hasUnpersistedFilterChanges -> "الخروج دون حفظ التعديل"
                else -> "إيقاف سبّاق والخروج"
            })
        } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("البقاء") } }
    )
}

package app.sabbaq.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import app.sabbaq.ui.theme.LocalSabbaqBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class AppState { WELCOME, LOGIN, DRIVER_PANEL, ADMIN_PANEL }
enum class SessionRestoreStatus { NONE, RESTORING, RETRYABLE_ERROR }

/** Keeps presentation/navigation state only; a known-session route never grants authorization. */
class SessionNavigationViewModel : ViewModel() {
    var route by mutableStateOf(AppState.LOGIN)
        private set
    var sessionRestoreStatus by mutableStateOf(SessionRestoreStatus.NONE)
        private set
    private var initialized = false
    private var restoreJob: Job? = null
    fun initialize(showWelcome: Boolean, hasSavedSession: Boolean = false) {
        if (!initialized) {
            route = when { showWelcome -> AppState.WELCOME; hasSavedSession -> AppState.DRIVER_PANEL; else -> AppState.LOGIN }
            sessionRestoreStatus = if (!showWelcome && hasSavedSession) SessionRestoreStatus.RESTORING
                else SessionRestoreStatus.NONE
            initialized = true
        }
    }
    fun welcomeFinished(hasSavedSession: Boolean = false) {
        route = if (hasSavedSession) AppState.DRIVER_PANEL else AppState.LOGIN
        sessionRestoreStatus = if (hasSavedSession) SessionRestoreStatus.RESTORING else SessionRestoreStatus.NONE
    }
    fun sessionRestoreFinished(restored: Boolean) {
        if (sessionRestoreStatus != SessionRestoreStatus.RESTORING) return
        sessionRestoreStatus = SessionRestoreStatus.NONE
        if (!restored) route = AppState.LOGIN
    }
    fun sessionRestoreUnavailable() {
        if (sessionRestoreStatus == SessionRestoreStatus.RESTORING) {
            sessionRestoreStatus = SessionRestoreStatus.RETRYABLE_ERROR
        }
    }
    fun retrySessionRestore() {
        if (sessionRestoreStatus == SessionRestoreStatus.RETRYABLE_ERROR) {
            sessionRestoreStatus = SessionRestoreStatus.RESTORING
        }
    }
    fun startSessionRestore(context: Context) {
        if (sessionRestoreStatus != SessionRestoreStatus.RESTORING || restoreJob?.isActive == true) return
        val appContext = context.applicationContext
        restoreJob = viewModelScope.launch {
            try {
                if (!SupabaseClient.hasSavedSession()) {
                    sessionRestoreFinished(restored = false)
                    return@launch
                }
                val result = SupabaseClient.restoreSession()
                when {
                    result.getOrNull() == true && SupabaseClient.hasValidLicense() -> {
                        if (SupabaseClient.subscriptionSnapshot()?.state == LicenseState.ACTIVE) {
                            Settings.knownSubscriber = true
                        }
                        KeeperService.resumeIfWanted(appContext)
                        sessionRestoreFinished(restored = true)
                    }
                    result.isFailure && SupabaseClient.hasSavedSession() -> sessionRestoreUnavailable()
                    else -> sessionRestoreFinished(restored = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (SupabaseClient.hasSavedSession()) sessionRestoreUnavailable()
                else sessionRestoreFinished(restored = false)
            } finally {
                restoreJob = null
                if (sessionRestoreStatus == SessionRestoreStatus.RESTORING) startSessionRestore(appContext)
            }
        }
    }
    fun authenticated(isAdmin: Boolean) {
        sessionRestoreStatus = SessionRestoreStatus.NONE
        route = if (isAdmin && BuildConfig.DIRECT_DISTRIBUTION) AppState.ADMIN_PANEL else AppState.DRIVER_PANEL
    }
    fun signedOut() { sessionRestoreStatus = SessionRestoreStatus.NONE; route = AppState.LOGIN }
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
            var showRestrictedA11yGuidance by remember { mutableStateOf(false) }
            var permissionMessage by remember { mutableStateOf<String?>(null) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (mode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            var notificationsGranted by remember { mutableStateOf(hasNotificationPermission()) }
            var filterSavePending by remember { mutableStateOf(false) }
            var hasUnpersistedFilterChanges by remember { mutableStateOf(false) }
            var driverDestinationRequest by rememberSaveable { mutableStateOf<DriverDestination?>(null) }
            var activeDriverDestination by rememberSaveable { mutableStateOf(DriverDestination.Dashboard) }
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
            val restrictedSettingsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                // Android does not expose a public API that reports whether the
                // Restricted Settings menu action was accepted. Open the target
                // screen after the user returns; the system then reflects the
                // real state and shows no extra block when it was accepted.
                runCatching { startActivity(AccessibilitySetup.accessibilitySettingsIntent()) }
                    .onFailure { permissionMessage = "تعذر فتح إعدادات إمكانية الوصول. حاول مجددًا من لوحة القيادة." }
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
                driverDestinationRequest = null
                activeDriverDestination = DriverDestination.Dashboard
                navigation.signedOut()
            }
            LaunchedEffect(navigation.sessionRestoreStatus) {
                if (navigation.sessionRestoreStatus == SessionRestoreStatus.RESTORING)
                    navigation.startSessionRestore(this@MainActivity)
            }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(210, 20, 44, 55))
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            SabbaqTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SabbaqShell(
                        showHeader = navigation.route != AppState.WELCOME && !showVideoOpening && !showIntro,
                        themeMode = mode,
                        onThemeChange = { mode = it; Settings.themeMode = it },
                        isSavingFilters = filterSavePending,
                        hasUnpersistedFilterChanges = hasUnpersistedFilterChanges,
                        onLogout = endSession,
                        allowLogout = navigation.sessionRestoreStatus != SessionRestoreStatus.RESTORING,
                        isAuthenticated = navigation.route == AppState.DRIVER_PANEL || navigation.route == AppState.ADMIN_PANEL,
                        isAdmin = navigation.route == AppState.ADMIN_PANEL,
                        activeDriverDestination = activeDriverDestination,
                        onOpenDriverDestination = { driverDestinationRequest = it },
                        onOpenLogin = { if (navigation.route == AppState.WELCOME) navigation.welcomeFinished(false) },
                    ) {
                        SabbaqDepthContent(target = navigation.route, order = { it.ordinal }, label = "app-route") { route ->
                        when (route) {
                            AppState.WELCOME -> OnboardingScreen(onContinue = {
                                if (!OnboardingPreferences.markComplete(this@MainActivity)) {
                                    permissionMessage = "قد تظهر هذه المقدمة مرة أخرى عند فتح التطبيق. يمكنك المتابعة الآن."
                                }
                                navigation.welcomeFinished(SupabaseClient.hasSavedSession())
                            })
                            AppState.LOGIN -> LoginScreen(onLoginSuccess = navigation::authenticated)
                            AppState.ADMIN_PANEL -> {
                                if (BuildConfig.DIRECT_DISTRIBUTION) AdminScreen()
                                else Text("لوحة الإدارة غير متاحة في هذه النسخة")
                            }
                            AppState.DRIVER_PANEL -> SettingsScreen(
                                notificationPermissionGranted = notificationsGranted,
                                autoSetupGuide = navigation.sessionRestoreStatus == SessionRestoreStatus.NONE,
                                isRestoringSession = navigation.sessionRestoreStatus == SessionRestoreStatus.RESTORING,
                                sessionRestoreError = navigation.sessionRestoreStatus == SessionRestoreStatus.RETRYABLE_ERROR,
                                onRetrySessionRestore = navigation::retrySessionRestore,
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
                                navigationRequest = driverDestinationRequest,
                                onDestinationChanged = { activeDriverDestination = it },
                                onNavigationRequestHandled = { driverDestinationRequest = null },
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
                            if (AccessibilitySetup.requiresRestrictedSettingsGuidance(this@MainActivity)) {
                                showRestrictedA11yGuidance = true
                            } else {
                                runCatching { startActivity(AccessibilitySetup.accessibilitySettingsIntent()) }
                                    .onFailure { permissionMessage = "تعذر فتح إعدادات إمكانية الوصول. حاول مجددًا من لوحة القيادة." }
                            }
                        },
                        onDismiss = {
                            showA11yDisclosure = false
                            Settings.a11yDisclosureAccepted = false
                            stopService(Intent(this@MainActivity, KeeperService::class.java))
                        }
                    )
                    if (showRestrictedA11yGuidance) RestrictedAccessibilityDialog(
                        onOpenAppDetails = {
                            showRestrictedA11yGuidance = false
                            runCatching {
                                restrictedSettingsLauncher.launch(AccessibilitySetup.appDetailsIntent(this@MainActivity))
                            }.onFailure {
                                permissionMessage = "تعذر فتح معلومات التطبيق. افتح إعدادات الجهاز ثم التطبيقات ثم سبّاق."
                            }
                        },
                        onDismiss = { showRestrictedA11yGuidance = false },
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

internal fun shouldRenderLogoIntro(showIntro: Boolean, showVideoOpening: Boolean): Boolean =
    showIntro && !showVideoOpening

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SabbaqShell(
    showHeader: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onLogout: () -> Unit,
    allowLogout: Boolean = true,
    isSavingFilters: Boolean = false,
    hasUnpersistedFilterChanges: Boolean = false,
    isAuthenticated: Boolean = false,
    isAdmin: Boolean = false,
    activeDriverDestination: DriverDestination? = null,
    onOpenDriverDestination: (DriverDestination) -> Unit = {},
    onOpenLogin: () -> Unit = {},
    content: @Composable () -> Unit
) {
    var showThemeMenu by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val requestLogout = { confirmLogout = true }
    val closeDrawer = { scope.launch { drawerState.close() } }

    BackHandler(enabled = drawerState.isOpen) {
        closeDrawer()
    }

    // The drawer is an explicit navigation surface. Keep it closed whenever the
    // shell enters or leaves a header-bearing screen; it must never appear as a
    // side effect of the first render or another button navigation.
    LaunchedEffect(showHeader) {
        drawerState.close()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Keep the standard swipe-to-close interaction available. Opening is still
        // controlled by the hamburger button because the drawer starts closed.
        gesturesEnabled = true,
        drawerContent = {
            if (showHeader) SabbaqDrawerSheet(
                isAuthenticated = isAuthenticated,
                isAdmin = isAdmin,
                activeDestination = activeDriverDestination,
                onOpenDriverDestination = { destination ->
                    closeDrawer()
                    onOpenDriverDestination(destination)
                },
                onOpenLogin = {
                    closeDrawer()
                    onOpenLogin()
                },
                onOpenPrivacy = {
                    closeDrawer()
                    PublicLinks.open(context, PublicLinks.PRIVACY_POLICY)
                },
                onOpenDeletion = {
                    closeDrawer()
                    PublicLinks.open(context, PublicLinks.ACCOUNT_DELETION)
                },
                onRequestLogout = {
                    closeDrawer()
                    requestLogout()
                },
                onClose = { closeDrawer() },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (showHeader) SabbaqTopBar(
                    themeMode = themeMode,
                    isAuthenticated = isAuthenticated,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onThemeMenu = { showThemeMenu = true },
                    themeMenuExpanded = showThemeMenu,
                    onDismissThemeMenu = { showThemeMenu = false },
                    onThemeChange = { showThemeMenu = false; onThemeChange(it) },
                    onRequestLogout = requestLogout,
                    allowLogout = allowLogout,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SabbaqTopBar(
    themeMode: ThemeMode,
    isAuthenticated: Boolean,
    onOpenDrawer: () -> Unit,
    onThemeMenu: () -> Unit,
    themeMenuExpanded: Boolean,
    onDismissThemeMenu: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onRequestLogout: () -> Unit,
    allowLogout: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Outlined.Menu, contentDescription = "فتح القائمة الجانبية")
                }
            },
            title = { SabbaqBrand(compact = true) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                Box {
                    IconButton(onClick = onThemeMenu, modifier = Modifier.size(52.dp)) {
                        Icon(
                            when (themeMode) {
                                ThemeMode.DARK -> Icons.Outlined.DarkMode
                                ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                ThemeMode.SYSTEM -> Icons.Outlined.SettingsBrightness
                            },
                            contentDescription = "تغيير مظهر التطبيق",
                        )
                    }
                    DropdownMenu(expanded = themeMenuExpanded, onDismissRequest = onDismissThemeMenu) {
                        ThemeMode.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(when (item) {
                                    ThemeMode.SYSTEM -> "حسب النظام"
                                    ThemeMode.LIGHT -> "فاتح"
                                    ThemeMode.DARK -> "داكن"
                                }) },
                                onClick = { onThemeChange(item) },
                                trailingIcon = { RadioButton(selected = item == themeMode, onClick = null) },
                            )
                        }
                    }
                }
                if (isAuthenticated) {
                    IconButton(onClick = onRequestLogout, enabled = allowLogout, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "تسجيل الخروج")
                    }
                }
            },
        )
    }
}

private data class SabbaqDrawerItem(
    val destination: DriverDestination,
    val label: String,
    val icon: ImageVector,
)

private val sabbaqDrawerItems = listOf(
    SabbaqDrawerItem(DriverDestination.Dashboard, "حالة العمل", Icons.Outlined.Home),
    SabbaqDrawerItem(DriverDestination.Filters, "طلبات تناسبك", Icons.Outlined.FilterAlt),
    SabbaqDrawerItem(DriverDestination.Simulator, "تجربة الطلب", Icons.Outlined.Visibility),
    SabbaqDrawerItem(DriverDestination.Account, "حسابي", Icons.Outlined.AccountCircle),
    SabbaqDrawerItem(DriverDestination.History, "سجل القرارات", Icons.Outlined.Info),
    SabbaqDrawerItem(DriverDestination.Setup, "دليل الإعداد", Icons.Outlined.Tune),
)

@Composable
private fun SabbaqDrawerSheet(
    isAuthenticated: Boolean,
    isAdmin: Boolean,
    activeDestination: DriverDestination?,
    onOpenDriverDestination: (DriverDestination) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenDeletion: () -> Unit,
    onRequestLogout: () -> Unit,
    onClose: () -> Unit,
) {
    val brand = LocalSabbaqBrand.current
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 320.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 8.dp,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = brand.heroStart,
            contentColor = brand.onHero,
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SabbaqBrand(onHero = true)
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "إغلاق القائمة")
                    }
                }
                Text(
                    if (isAuthenticated) "مسجل الدخول — إدارة عملك بثقة" else "استكشف سبّاق وسجّل الدخول للبدء",
                    style = MaterialTheme.typography.bodySmall,
                    color = brand.onHeroMuted,
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isAuthenticated && !isAdmin) {
                sabbaqDrawerItems.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = item.destination == activeDestination,
                        onClick = { onOpenDriverDestination(item.destination) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        modifier = Modifier.heightIn(min = 52.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            if (isAdmin) {
                NavigationDrawerItem(
                    label = { Text("لوحة الإدارة") },
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    modifier = Modifier.heightIn(min = 52.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            if (!isAuthenticated) {
                NavigationDrawerItem(
                    label = { Text("تسجيل الدخول") },
                    selected = false,
                    onClick = onOpenLogin,
                    icon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                    modifier = Modifier.heightIn(min = 52.dp),
                )
            }
            NavigationDrawerItem(
                label = { Text("سياسة الخصوصية") },
                selected = false,
                onClick = onOpenPrivacy,
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.heightIn(min = 52.dp),
            )
            NavigationDrawerItem(
                label = { Text("طلب حذف الحساب") },
                selected = false,
                onClick = onOpenDeletion,
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.heightIn(min = 52.dp),
            )
            if (isAuthenticated) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("تسجيل الخروج") },
                    selected = false,
                    onClick = onRequestLogout,
                    icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    modifier = Modifier.heightIn(min = 52.dp),
                )
            }
        }
    }
}

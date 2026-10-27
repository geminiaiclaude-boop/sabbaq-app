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
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
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
import app.sabbaq.notifications.NotificationAction
import app.sabbaq.notifications.NotificationCenter
import app.sabbaq.notifications.NotificationInbox
import app.sabbaq.service.KeeperService
import app.sabbaq.stats.OfferDecisionLogStore
import app.sabbaq.stats.OfferStatsStore
import app.sabbaq.telemetry.TelemetryRepository
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
                NotificationCenter.clear()
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
            val showHeader = navigation.route != AppState.WELCOME && !showVideoOpening && !showIntro
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(210, 31, 22, 51))
                // The brand header is deep purple in both themes, so its status bar icons are light.
                val statusStyle = if (showHeader) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else style
                enableEdgeToEdge(statusBarStyle = statusStyle, navigationBarStyle = style)
            }
            SabbaqTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SabbaqShell(
                        showHeader = showHeader,
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
                                themeMode = mode,
                                onThemeChange = { mode = it; Settings.themeMode = it },
                                onAccountDeleted = {
                                    val wiped = Settings.clearAccountData()
                                    val statsWiped = OfferStatsStore.clear(this@MainActivity)
                                    val historyWiped = OfferDecisionLogStore.clear(this@MainActivity)
                                    TelemetryRepository.clearForAccountDeletion()
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
    var showNotifications by remember { mutableStateOf(false) }
    val isDriver = isAuthenticated && !isAdmin
    val inbox by NotificationCenter.state.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val requestLogout = { confirmLogout = true }
    val closeDrawer = { scope.launch { drawerState.close() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Server messages arrive while the app is open: sync on entering the driver panel and on
    // every return to the foreground.
    DisposableEffect(isDriver, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (isDriver && event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { NotificationCenter.refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isDriver) { if (!isDriver) showNotifications = false }

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
                logoutEnabled = allowLogout,
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
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onThemeMenu = { showThemeMenu = true },
                    themeMenuExpanded = showThemeMenu,
                    onDismissThemeMenu = { showThemeMenu = false },
                    onThemeChange = { showThemeMenu = false; onThemeChange(it) },
                    showNotificationBell = isDriver,
                    unreadNotifications = if (NotificationCenter.belongsToCurrentAccount(inbox)) NotificationInbox.unread(inbox) else 0,
                    onOpenNotifications = { showNotifications = true },
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
    if (showNotifications) NotificationsSheet(
        onDismiss = { showNotifications = false },
        onAction = { action ->
            showNotifications = false
            when (action) {
                NotificationAction.OPEN_ACCOUNT -> onOpenDriverDestination(DriverDestination.Account)
                NotificationAction.OPEN_SETUP -> onOpenDriverDestination(DriverDestination.Setup)
                NotificationAction.OPEN_FILTERS -> onOpenDriverDestination(DriverDestination.Filters)
                NotificationAction.UPDATE_APP ->
                    PublicLinks.open(context, "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SabbaqTopBar(
    themeMode: ThemeMode,
    onOpenDrawer: () -> Unit,
    onThemeMenu: () -> Unit,
    themeMenuExpanded: Boolean,
    onDismissThemeMenu: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    showNotificationBell: Boolean,
    unreadNotifications: Int,
    onOpenNotifications: () -> Unit,
) {
    val brand = LocalSabbaqBrand.current
    // Deep purple brand header in both themes, finished by the signature yellow rule.
    Surface(color = brand.heroStart, shadowElevation = 6.dp) {
        Column(Modifier.background(Brush.horizontalGradient(listOf(brand.heroEnd, brand.heroStart)))) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Outlined.Menu, contentDescription = "فتح القائمة الجانبية")
                    }
                },
                title = { SabbaqBrand(compact = true, onHero = true) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = brand.onHero,
                    titleContentColor = brand.onHero,
                    actionIconContentColor = brand.onHero,
                ),
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
                    if (showNotificationBell) NotificationBell(unread = unreadNotifications, onClick = onOpenNotifications)
                },
            )
            Box(Modifier.fillMaxWidth().height(3.dp).background(brand.signature))
        }
    }
}

private data class SabbaqDrawerItem(
    val destination: DriverDestination,
    val label: String,
    val icon: ImageVector,
)

/** Drawer sections in the order a driver uses them: work, tools, account. */
private val sabbaqDrawerSections = listOf(
    "العمل" to listOf(
        SabbaqDrawerItem(DriverDestination.Dashboard, "حالة العمل", Icons.Outlined.Home),
        SabbaqDrawerItem(DriverDestination.Filters, "طلبات تناسبك", Icons.Outlined.FilterAlt),
        SabbaqDrawerItem(DriverDestination.History, "سجل القرارات", Icons.Outlined.History),
    ),
    "الأدوات" to listOf(
        SabbaqDrawerItem(DriverDestination.Simulator, "تجربة الطلب", Icons.Outlined.Visibility),
        SabbaqDrawerItem(DriverDestination.Setup, "دليل الإعداد", Icons.Outlined.Checklist),
        SabbaqDrawerItem(DriverDestination.Settings, "الإعدادات", Icons.Outlined.SettingsIcon),
    ),
    "الحساب" to listOf(
        SabbaqDrawerItem(DriverDestination.Account, "حسابي", Icons.Outlined.AccountCircle),
    ),
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
    logoutEnabled: Boolean,
    onClose: () -> Unit,
) {
    val brand = LocalSabbaqBrand.current
    val snapshot = remember(isAuthenticated) { if (isAuthenticated) SupabaseClient.subscriptionSnapshot() else null }
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 320.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 0.dp,
        drawerShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
    ) {
        // Header: brand on the hero gradient, close, and who is signed in at a glance.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(brand.heroStart, brand.heroEnd)))
                .padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SabbaqBrand(onHero = true)
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "إغلاق القائمة", tint = brand.onHero)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        isAdmin -> DrawerPill("إدارة", brand.onHero.copy(alpha = 0.18f), brand.onHero)
                        snapshot?.state == LicenseState.ACTIVE -> DrawerPill("PRO", brand.signature, brand.onSignature)
                        snapshot?.state == LicenseState.TRIAL -> DrawerPill("تجربة", brand.onHero.copy(alpha = 0.18f), brand.onHero)
                    }
                    Text(
                        when {
                            !isAuthenticated -> "استكشف سبّاق وسجّل الدخول للبدء"
                            isAdmin -> "لوحة الإدارة"
                            snapshot?.state == LicenseState.ACTIVE -> "مشترك — تابع عملك بثقة"
                            snapshot?.state == LicenseState.TRIAL -> "فترتك التجريبية فعّالة"
                            else -> "مسجل الدخول"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = brand.onHeroMuted,
                    )
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (isAuthenticated && !isAdmin) {
                sabbaqDrawerSections.forEach { (title, items) ->
                    DrawerSectionTitle(title)
                    items.forEach { item ->
                        DrawerEntry(
                            label = item.label,
                            icon = item.icon,
                            selected = item.destination == activeDestination,
                            onClick = { onOpenDriverDestination(item.destination) },
                        )
                    }
                }
            }
            if (isAdmin) {
                DrawerSectionTitle("الإدارة")
                DrawerEntry("لوحة الإدارة", Icons.Outlined.Tune, selected = true, onClick = {})
            }
            if (!isAuthenticated) {
                DrawerEntry("تسجيل الدخول", Icons.Outlined.AccountCircle, selected = false, onClick = onOpenLogin)
            }
            DrawerSectionTitle("الخصوصية")
            DrawerEntry("سياسة الخصوصية", Icons.Outlined.PrivacyTip, selected = false, onClick = onOpenPrivacy)
            DrawerEntry("طلب حذف الحساب", Icons.Outlined.DeleteOutline, selected = false, onClick = onOpenDeletion)
        }
        // Footer: sign-out set apart, then the version.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isAuthenticated) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DrawerEntry("تسجيل الخروج", Icons.AutoMirrored.Outlined.Logout, selected = false, destructive = true,
                    enabled = logoutEnabled, onClick = onRequestLogout)
            }
            Text(
                "سبّاق ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun DrawerSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerPill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/**
 * One drawer row: a tinted icon badge and the label. The current page gets a filled pill and an
 * accent bar at its leading edge; sign-out uses the error colours so it is never tapped by habit.
 */
@Composable
private fun DrawerEntry(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        destructive -> colors.error
        selected -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.7f) else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) colors.primary else Color.Transparent),
        )
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = if (selected || destructive) 0.16f else 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (destructive) colors.error else if (selected) colors.onPrimaryContainer else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

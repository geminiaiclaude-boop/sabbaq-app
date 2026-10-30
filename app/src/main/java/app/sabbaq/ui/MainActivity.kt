package app.sabbaq.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.CardGiftcard
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
import androidx.compose.material.icons.outlined.SupportAgent
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.sabbaq.BuildConfig
import app.sabbaq.Settings
import app.sabbaq.R
import app.sabbaq.LocaleManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sabbaq.ThemeMode
import app.sabbaq.network.LicenseState
import app.sabbaq.network.SupabaseClient
import app.sabbaq.notifications.NotificationAction
import app.sabbaq.notifications.NotificationCenter
import app.sabbaq.notifications.NotificationInbox
import app.sabbaq.support.SupportRepository
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
        Settings.lastSignedInAdmin = isAdmin && BuildConfig.DIRECT_DISTRIBUTION
        app.sabbaq.fleet.FloatingBubble.refresh()
        sessionRestoreStatus = SessionRestoreStatus.NONE
        route = if (isAdmin && BuildConfig.DIRECT_DISTRIBUTION) AppState.ADMIN_PANEL else AppState.DRIVER_PANEL
    }
    fun signedOut() { sessionRestoreStatus = SessionRestoreStatus.NONE; route = AppState.LOGIN; app.sabbaq.fleet.FloatingBubble.refresh() }
}

class MainActivity : AppCompatActivity() {
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
            var logoutRequests by remember { mutableIntStateOf(0) }
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
                    .onFailure { permissionMessage = (if (LocaleManager.isArabic()) "تعذر فتح إعدادات إمكانية الوصول. حاول مجددًا من لوحة القيادة." else "Could not open the accessibility settings. Try again from the dashboard.") }
            }
            val endSession = {
                // Signing out ends automatic resume too; clearSession() also wipes the saved session.
                Settings.monitoringWanted = false
                Settings.isActive = false
                Settings.a11yDisclosureAccepted = false
                stopService(Intent(this@MainActivity, KeeperService::class.java))
                SupabaseClient.clearSession()
                NotificationCenter.clear()
                SupportRepository.clear()
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
                    logoutRequests = logoutRequests,
                ) {
                    SabbaqDepthContent(target = navigation.route, order = { it.ordinal }, label = "app-route") { route ->
                    when (route) {
                        AppState.WELCOME -> OnboardingScreen(onContinue = {
                            if (!OnboardingPreferences.markComplete(this@MainActivity)) {
                                permissionMessage = (if (LocaleManager.isArabic()) "قد تظهر هذه المقدمة مرة أخرى عند فتح التطبيق. يمكنك المتابعة الآن." else "This intro may appear again when you open the app. You can continue now.")
                            }
                            navigation.welcomeFinished(SupabaseClient.hasSavedSession())
                        })
                        AppState.LOGIN -> LoginScreen(onLoginSuccess = navigation::authenticated)
                        AppState.ADMIN_PANEL -> {
                            if (BuildConfig.DIRECT_DISTRIBUTION) app.sabbaq.ui.admin.AdminPanel()
                            else Text(stringResource(R.string.ma_admin_unavailable))
                        }
                        AppState.DRIVER_PANEL -> {
                        InviteCodePrompt()
                        SettingsScreen(
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
                            onRequestLogout = { logoutRequests++ },
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
                                permissionMessage = if (wiped && statsWiped && historyWiped) (if (LocaleManager.isArabic()) "حُذف حسابك وبياناته المرتبطة. شكرًا لاستخدامك سبّاق." else "Your account and its related data were deleted. Thank you for using Sabbaq.")
                                    else (if (LocaleManager.isArabic()) "حُذف حسابك من الخادم، لكن تعذّر مسح بعض الإعدادات المحلية. احذف بيانات التطبيق من إعدادات الجهاز لإكمال المسح." else "Your account was deleted from the server, but some local settings could not be cleared. Delete the app data from the device settings to complete the wipe.")
                            },
                        )
                        }
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
                                .onFailure { permissionMessage = (if (LocaleManager.isArabic()) "تعذر فتح إعدادات إمكانية الوصول. حاول مجددًا من لوحة القيادة." else "Could not open the accessibility settings. Try again from the dashboard.") }
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
                            permissionMessage = (if (LocaleManager.isArabic()) "تعذر فتح معلومات التطبيق. افتح إعدادات الجهاز ثم التطبيقات ثم سبّاق." else "Could not open the app info. Open the device settings, then Apps, then Sabbaq.")
                        }
                    },
                    onDismiss = { showRestrictedA11yGuidance = false },
                )
                permissionMessage?.let { message -> AlertDialog(
                    onDismissRequest = { permissionMessage = null },
                    title = { Text(stringResource(R.string.ma_notice)) }, text = { Text(message) },
                    confirmButton = { TextButton(onClick = { permissionMessage = null }) { Text(stringResource(R.string.ma_ok)) } }
                ) }
                // Do not compose the logo intro underneath the cinematic clip: its
                // 2.3s animation would finish while hidden instead of playing after it.
                if (shouldRenderLogoIntro(showIntro, showVideoOpening)) {
                    SabbaqLaunchIntro(onFinished = { showIntro = false })
                }
                // Access just granted (code or trial): celebrate over everything, once.
                val celebration by Celebrations.current.collectAsState()
                celebration?.let { ActivationCelebration(it, onDone = Celebrations::dismiss) }
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
    logoutRequests: Int = 0,
    content: @Composable () -> Unit
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    val isDriver = isAuthenticated && !isAdmin
    val inbox by NotificationCenter.state.collectAsState()
    val support by SupportRepository.state.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val requestLogout = { confirmLogout = true }
    // Pages ask for sign-out through this counter so every path shares the one confirmation.
    LaunchedEffect(logoutRequests) { if (logoutRequests > 0 && allowLogout) confirmLogout = true }
    val closeDrawer = { scope.launch { drawerState.close() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Server messages arrive while the app is open: sync on entering the driver panel and on
    // every return to the foreground.
    DisposableEffect(isDriver, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (isDriver && event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch {
                NotificationCenter.refresh()
                SupportRepository.refreshUnread()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isDriver) { if (!isDriver) showNotifications = false }

    // Lowest priority (registered first): on a root page one back press only warns; a second
    // within two seconds leaves the app. Pages and the drawer register later and win.
    var lastRootBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = showHeader) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRootBackAt < 2_000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastRootBackAt = now
            android.widget.Toast.makeText(context, (if (LocaleManager.isArabic()) "اضغط رجوع مرة أخرى للخروج من سبّاق" else "Press back again to exit Sabbaq"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // The drawer is an explicit navigation surface. Keep it closed whenever the
    // shell enters or leaves a header-bearing screen; it must never appear as a
    // side effect of the first render or another button navigation.
    LaunchedEffect(showHeader) {
        drawerState.close()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swipe only where the hamburger exists; on the welcome tour the drawer stays shut.
        gesturesEnabled = showHeader || drawerState.isOpen,
        // The sheet is always composed: when it appeared only with the header its width jumped
        // from 0 to full on the first page after the tour, so the drawer's "closed" anchor was
        // wrong for a frame and the menu flashed open and shut on taps like «ابدأ التجربة».
        drawerContent = {
            SabbaqDrawerSheet(
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
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onClose = { closeDrawer() },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (showHeader) SabbaqTopBar(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    showDriverActions = isDriver,
                    unreadSupport = support.unread,
                    onOpenSupport = { onOpenDriverDestination(DriverDestination.Support) },
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
                    // After the pages, so an open drawer closes before any page goes back.
                    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
                }
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.ma_logout_q)) },
        text = { Text(when {
            isSavingFilters -> stringResource(R.string.ma_logout_saving)
            hasUnpersistedFilterChanges -> stringResource(R.string.ma_logout_unpersisted)
            else -> stringResource(R.string.ma_logout_default)
        }) },
        confirmButton = { TextButton(
            enabled = !isSavingFilters,
            onClick = { confirmLogout = false; onLogout() },
        ) {
            Text(when {
                isSavingFilters -> stringResource(R.string.ma_logout_saving_btn)
                hasUnpersistedFilterChanges -> stringResource(R.string.ma_logout_leave_unsaved)
                else -> stringResource(R.string.ma_logout_stop_leave)
            })
        } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.ma_stay)) } }
    )
    if (showNotifications) NotificationsSheet(
        onDismiss = { showNotifications = false },
        onAction = { action ->
            showNotifications = false
            when (action) {
                NotificationAction.OPEN_ACCOUNT -> onOpenDriverDestination(DriverDestination.Account)
                NotificationAction.OPEN_SETUP -> onOpenDriverDestination(DriverDestination.Setup)
                NotificationAction.OPEN_FILTERS -> onOpenDriverDestination(DriverDestination.Filters)
                NotificationAction.OPEN_SUPPORT -> onOpenDriverDestination(DriverDestination.Support)
                NotificationAction.UPDATE_APP ->
                    if (BuildConfig.DIRECT_DISTRIBUTION) onOpenDriverDestination(DriverDestination.Dashboard)
                    else PublicLinks.open(context, "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SabbaqTopBar(
    onOpenDrawer: () -> Unit,
    showDriverActions: Boolean,
    unreadSupport: Int,
    onOpenSupport: () -> Unit,
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
                        Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.ma_open_drawer_cd))
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
                    if (showDriverActions) {
                        SupportButton(unread = unreadSupport, onClick = onOpenSupport)
                        NotificationBell(unread = unreadNotifications, onClick = onOpenNotifications)
                    }
                },
            )
            Box(Modifier.fillMaxWidth().height(3.dp).background(brand.signature))
        }
    }
}

private data class SabbaqDrawerItem(
    val destination: DriverDestination,
    val labelRes: Int,
    val icon: ImageVector,
)

/** Drawer sections in the order a driver uses them: work, tools, account. */
private val sabbaqDrawerSections = listOf(
    R.string.ma_sec_work to listOf(
        SabbaqDrawerItem(DriverDestination.Dashboard, R.string.nav_dashboard, Icons.Outlined.Home),
        SabbaqDrawerItem(DriverDestination.Filters, R.string.nav_filters, Icons.Outlined.FilterAlt),
        SabbaqDrawerItem(DriverDestination.History, R.string.nav_history, Icons.Outlined.History),
    ),
    R.string.ma_sec_tools to listOf(
        SabbaqDrawerItem(DriverDestination.Simulator, R.string.nav_simulator, Icons.Outlined.Visibility),
        SabbaqDrawerItem(DriverDestination.Setup, R.string.ss_setup_guide, Icons.Outlined.Checklist),
        SabbaqDrawerItem(DriverDestination.Settings, R.string.nav_settings, Icons.Outlined.SettingsIcon),
    ),
    R.string.ma_sec_account to listOf(
        SabbaqDrawerItem(DriverDestination.Account, R.string.nav_account, Icons.Outlined.AccountCircle),
        SabbaqDrawerItem(DriverDestination.Referrals, R.string.ref_nav, Icons.Outlined.CardGiftcard),
        SabbaqDrawerItem(DriverDestination.Support, R.string.sub_contact_support, Icons.Outlined.SupportAgent),
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
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
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
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ma_close_menu_cd), tint = brand.onHero)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        isAdmin -> DrawerPill(stringResource(R.string.ma_pill_admin), brand.onHero.copy(alpha = 0.18f), brand.onHero)
                        snapshot?.state == LicenseState.ACTIVE -> DrawerPill("PRO", brand.signature, brand.onSignature)
                        snapshot?.state == LicenseState.TRIAL -> DrawerPill(stringResource(R.string.ma_pill_trial), brand.onHero.copy(alpha = 0.18f), brand.onHero)
                    }
                    Text(
                        when {
                            !isAuthenticated -> stringResource(R.string.ma_drawer_explore)
                            isAdmin -> stringResource(R.string.ma_drawer_admin)
                            snapshot?.state == LicenseState.ACTIVE -> stringResource(R.string.ma_drawer_pro)
                            snapshot?.state == LicenseState.TRIAL -> stringResource(R.string.ma_drawer_trial)
                            else -> stringResource(R.string.ma_drawer_signed_in)
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
                    DrawerSectionTitle(stringResource(title))
                    items.forEach { item ->
                        DrawerEntry(
                            label = stringResource(item.labelRes),
                            icon = item.icon,
                            selected = item.destination == activeDestination,
                            onClick = { onOpenDriverDestination(item.destination) },
                        )
                    }
                }
            }
            if (isAdmin) {
                DrawerSectionTitle(stringResource(R.string.ma_sec_admin))
                DrawerEntry(stringResource(R.string.ma_drawer_admin), Icons.Outlined.Tune, selected = true, onClick = {})
            }
            if (!isAuthenticated) {
                DrawerEntry(stringResource(R.string.ma_sign_in), Icons.Outlined.AccountCircle, selected = false, onClick = onOpenLogin)
            }
            DrawerSectionTitle(stringResource(R.string.language_title))
            LanguageSwitch(Modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag("drawer-language"), fill = true)
            DrawerSectionTitle(stringResource(R.string.ma_sec_appearance))
            DrawerThemeChooser(themeMode, onThemeChange)
            DrawerSectionTitle(stringResource(R.string.ma_sec_privacy))
            DrawerEntry(stringResource(R.string.sub_privacy_policy), Icons.Outlined.PrivacyTip, selected = false, onClick = onOpenPrivacy)
            DrawerEntry(stringResource(R.string.ma_request_deletion), Icons.Outlined.DeleteOutline, selected = false, onClick = onOpenDeletion)
        }
        // Footer: sign-out set apart, then the version.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isAuthenticated) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DrawerEntry(stringResource(R.string.sub_sign_out), Icons.AutoMirrored.Outlined.Logout, selected = false, destructive = true,
                    enabled = logoutEnabled, onClick = onRequestLogout)
            }
            Text(
                stringResource(R.string.ma_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** System / light / dark, one tap each; the choice is applied and saved immediately. */
@Composable
private fun DrawerThemeChooser(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            Triple(ThemeMode.SYSTEM, stringResource(R.string.ma_theme_auto), Icons.Outlined.SettingsBrightness),
            Triple(ThemeMode.LIGHT, stringResource(R.string.ma_theme_light), Icons.Outlined.LightMode),
            Triple(ThemeMode.DARK, stringResource(R.string.ma_theme_dark), Icons.Outlined.DarkMode),
        ).forEach { (mode, label, icon) ->
            val themeCd = stringResource(R.string.ma_theme_cd, label)
            FilterChip(
                selected = themeMode == mode,
                onClick = { onThemeChange(mode) },
                label = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f).semantics { contentDescription = themeCd },
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

package app.sabbaq.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.ui.theme.LocalReducedMotion
import app.sabbaq.ui.theme.SabbaqDepthContent
import app.sabbaq.ui.theme.SabbaqMotion
import app.sabbaq.ui.theme.flipWhenSelected
import app.sabbaq.ui.theme.sabbaqFlipIn
import app.sabbaq.ui.theme.tilt3D
import androidx.compose.runtime.key
import app.sabbaq.ui.theme.sabbaqEntrance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sabbaq.BuildConfig
import app.sabbaq.R
import app.sabbaq.core.engine.Decision
import app.sabbaq.core.engine.DecisionEngine
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.ParsedRide
import app.sabbaq.LocaleManager
import androidx.compose.ui.res.stringResource
import app.sabbaq.Settings
import app.sabbaq.ThemeMode
import app.sabbaq.network.LicenseState
import app.sabbaq.network.SubscriptionSnapshot
import app.sabbaq.network.SupabaseClient
import app.sabbaq.service.SabbaqA11yService
import app.sabbaq.core.RuntimeStatusSnapshot
import java.math.BigDecimal
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver

/** وجهات لوحة السائق؛ يمكن استدعاء الشاشة مباشرة إلى أي منها في معاينات/اختبارات الواجهة. */
enum class DriverDestination { Dashboard, Filters, Simulator, Account, History, Setup, Settings, Support, Referrals, Zones }

private data class DriverStatus(val message: String, val isError: Boolean = false, val success: Boolean = false) {
    val tone: SabbaqTone get() = when {
        isError -> SabbaqTone.Error
        success -> SabbaqTone.Success
        else -> SabbaqTone.Info
    }
}
private data class PreviewResult(val decision: Decision?, val error: String? = null)
private data class DriverNavItem(
    val destination: DriverDestination,
    val labelRes: Int,
    val icon: ImageVector,
)

private val driverDestinations = listOf(
    DriverNavItem(DriverDestination.Dashboard, R.string.nav_dashboard, Icons.Outlined.Tune),
    DriverNavItem(DriverDestination.Filters, R.string.nav_filters, Icons.Outlined.FilterAlt),
    DriverNavItem(DriverDestination.Settings, R.string.nav_settings, Icons.Outlined.SettingsIcon),
    DriverNavItem(DriverDestination.Account, R.string.nav_account, Icons.Outlined.AccountCircle),
    DriverNavItem(DriverDestination.Simulator, R.string.nav_simulator, Icons.Outlined.Visibility),
    DriverNavItem(DriverDestination.History, R.string.nav_history, Icons.Outlined.Info),
)

// Four tabs on phones; «تجربة الطلب» and «النشاط والتشخيص» stay one tap away in the drawer and
// in «الإعدادات ← المزيد».
private val bottomDriverDestinations = driverDestinations.filter {
    it.destination in setOf(DriverDestination.Dashboard, DriverDestination.Filters, DriverDestination.Settings, DriverDestination.Account)
}

/**
 * لوحة السائق الإنتاجية. الحواف الآمنة وapp bar والخروج يملكها SabbaqShell في MainActivity؛
 * لذلك لا تضيف هذه الشاشة systemBarsPadding أو أي حالة مصادقة دائمة.
 */
@Composable
fun SettingsScreen(
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenA11y: () -> Result<Unit>,
    onToggleKeeper: (Boolean) -> Result<Unit>,
    initialDestination: DriverDestination = DriverDestination.Dashboard,
    onFilterSaveStateChanged: (isSaving: Boolean, hasUnpersistedChanges: Boolean) -> Unit = { _, _ -> },
    onAccountDeleted: () -> Unit = {},
    onRequestLogout: () -> Unit = {},
    /** Opens the setup guide first for a driver who has not finished or dismissed it. */
    autoSetupGuide: Boolean = true,
    isRestoringSession: Boolean = false,
    sessionRestoreError: Boolean = false,
    onRetrySessionRestore: () -> Unit = {},
    navigationRequest: DriverDestination? = null,
    onDestinationChanged: (DriverDestination) -> Unit = {},
    onNavigationRequestHandled: () -> Unit = {},
    themeMode: ThemeMode = Settings.themeMode,
    onThemeChange: (ThemeMode) -> Unit = { Settings.themeMode = it },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable {
        // First open after sign-in always starts with the guide; afterwards it is one tap away
        // from the dashboard.
        val needsSetup = autoSetupGuide && initialDestination == DriverDestination.Dashboard &&
            !SetupGuidePreferences.isDone(context)
        mutableStateOf(if (needsSetup) DriverDestination.Setup else initialDestination)
    }
    // Pages visited before the current one, so the system back button retraces them.
    val backStack = rememberSaveable(saver = listSaver<MutableList<DriverDestination>, String>(
        save = { stack -> stack.map { it.name } },
        restore = { names -> names.mapNotNull { name -> DriverDestination.entries.firstOrNull { it.name == name } }.toMutableStateList() },
    )) { mutableStateListOf() }
    var savedFilters by remember { mutableStateOf(Settings.engineConfig) }
    val draftSaver = remember {
        Saver<FilterDraft, String>(
            save = { Json.encodeToString(it) },
            restore = { runCatching { Json.decodeFromString<FilterDraft>(it) }.getOrNull() }
        )
    }
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(FilterDraft.from(savedFilters)) }
    val draftValidation = remember(draft, savedFilters) { draft.validate(savedFilters) }
    val filtersDirty = remember(draft, savedFilters) { draft.isDirtyComparedTo(savedFilters) }
    var filtersMessage by remember { mutableStateOf<DriverStatus?>(null) }
    var runtimeMessage by remember { mutableStateOf<DriverStatus?>(null) }
    var accountMessage by remember { mutableStateOf<DriverStatus?>(null) }
    var isSavingFilters by remember { mutableStateOf(false) }
    var isActive by remember { mutableStateOf(Settings.isActive) }
    var accessibilityEnabled by remember(context) { mutableStateOf(context.isSabbaqAccessibilityEnabled()) }
    var notificationsEnabled by remember(context) { mutableStateOf(context.hasNotificationPermission()) }
    var batteryUnrestricted by remember(context) { mutableStateOf(PowerSetup.isBatteryUnrestricted(context)) }
    var hibernationOff by remember(context) { mutableStateOf(!app.sabbaq.fleet.KeepAlive.hibernationOn(context)) }
    var autostartDone by remember { mutableStateOf(!PowerSetup.needsAutostart() || Settings.autostartConfirmed) }
    var licenseInMemory by remember { mutableStateOf(SupabaseClient.hasValidLicense()) }
    var subscriptionSnapshot by remember { mutableStateOf(SupabaseClient.subscriptionSnapshot()) }
    var checkingActivation by remember { mutableStateOf(false) }
    var showActivationConsent by remember { mutableStateOf(false) }
    var showAccessibilityRequired by remember { mutableStateOf(false) }
    var showNotificationRequired by remember { mutableStateOf(false) }
    var showMissingPermissions by remember { mutableStateOf(false) }
    var showResetFilters by remember { mutableStateOf(false) }
    var returnToSetupAfterAction by rememberSaveable { mutableStateOf(false) }
    val filterSaveMutex = remember { Mutex() }
    var filterRevision by remember { mutableStateOf(0L) }

    LaunchedEffect(navigationRequest) {
        navigationRequest?.let {
            // Drawer, bell and support entries record history like in-page navigation.
            if (it != destination) {
                if (it == DriverDestination.Dashboard) backStack.clear()
                else { backStack.remove(it); backStack.add(destination) }
            }
            destination = it
            onDestinationChanged(it)
            onNavigationRequestHandled()
        }
    }
    LaunchedEffect(destination) {
        onDestinationChanged(destination)
    }

    val latestToggleKeeper by rememberUpdatedState(onToggleKeeper)
    val haptics = LocalHapticFeedback.current
    val latestFilterSaveStateChanged by rememberUpdatedState(onFilterSaveStateChanged)
    LaunchedEffect(filtersDirty, isSavingFilters) {
        latestFilterSaveStateChanged(isSavingFilters, filtersDirty && !isSavingFilters)
    }

    fun stopKeeper(message: String? = null) {
        val stopResult = latestToggleKeeper(false)
        // الإيقاف المحلي دائم حتى إن لم تؤكد الخدمة ذلك؛ لا نترك واجهة توحي بتشغيل غير مؤكد.
        Settings.isActive = false
        isActive = false
        runtimeMessage = when {
            stopResult.isFailure -> DriverStatus(
                context.getString(R.string.ss_stop_unconfirmed),
                isError = true,
            )
            message != null -> DriverStatus(message)
            else -> DriverStatus(context.getString(R.string.ss_stop_default))
        }
    }

    fun refreshRuntimePrerequisites() {
        val a11y = context.isSabbaqAccessibilityEnabled()
        val notifications = context.hasNotificationPermission()
        val licensed = SupabaseClient.hasValidLicense() // لقطة ذاكرية فقط؛ لا شبكة في النبض الدوري.
        accessibilityEnabled = a11y
        notificationsEnabled = notifications
        batteryUnrestricted = PowerSetup.isBatteryUnrestricted(context)
        hibernationOff = !app.sabbaq.fleet.KeepAlive.hibernationOn(context)
        autostartDone = !PowerSetup.needsAutostart() || Settings.autostartConfirmed
        licenseInMemory = licensed
        subscriptionSnapshot = SupabaseClient.subscriptionSnapshot()
        val persistedActive = Settings.isActive
        if (persistedActive != isActive) isActive = persistedActive
        // KeeperService owns the license and accessibility lifecycle: it pauses while the lease
        // cannot be renewed and resumes when accessibility comes back. Stopping here (which
        // clears monitoringWanted) turned every coverage gap or accessibility blip seen while
        // the dashboard was open into a permanent stop. Only a revoked notification permission,
        // which the keeper also treats as fatal, ends monitoring from the screen.
        if (persistedActive && !notifications) {
            stopKeeper(context.getString(R.string.ss_stop_notifications_off))
        } else if (persistedActive && !a11y) {
            runtimeMessage = DriverStatus(context.getString(R.string.ss_a11y_off_running), isError = true)
        } else if (persistedActive && !licensed) {
            runtimeMessage = DriverStatus(context.getString(R.string.ss_license_checking_running), isError = true)
        }
    }

    /** لا طلبات شبكة كل خمس ثوانٍ: نرصد فقط شروط النظام والذاكرة أثناء RESUMED. */
    LaunchedEffect(lifecycleOwner, context, notificationPermissionGranted, isActive) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                refreshRuntimePrerequisites()
                delay(2_000)
            }
        }
    }
    DisposableEffect(lifecycleOwner, context, notificationPermissionGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshRuntimePrerequisites()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isRestoringSession, sessionRestoreError) {
        refreshRuntimePrerequisites()
    }

    /**
     * [stopIfActive] is false for the quick controls on «حالة العمل»: they are bounded steppers
     * and switches whose value is validated before the debounced save, and the service reads
     * the saved filters on the next offer, so a mid-shift adjustment applies without a restart.
     */
    fun updateDraft(next: FilterDraft, @Suppress("UNUSED_PARAMETER") stopIfActive: Boolean = false) {
        if (next === draft) return
        draft = next
        filtersMessage = null
        val revision = filterRevision + 1
        filterRevision = revision
        val validation = next.validate(savedFilters)
        val filters = validation.config
        if (filters == null) {
            isSavingFilters = false
            filtersMessage = DriverStatus(context.getString(R.string.ss_review_marked_values), isError = true)
            return
        }
        isSavingFilters = true
        filtersMessage = DriverStatus(context.getString(R.string.ss_saving_auto))
        scope.launch {
            delay(180)
            filterSaveMutex.withLock {
                if (revision != filterRevision) return@withLock
                try {
                    val saved = Settings.saveEngineConfig(filters)
                    if (revision != filterRevision) {
                        // A later invalid edit has no save job of its own. Restore the last
                        // durable filters so an older in-flight commit cannot replace them.
                        if (saved && draft.validate(savedFilters).config == null && !Settings.saveEngineConfig(savedFilters)) {
                            filtersMessage = DriverStatus(context.getString(R.string.ss_restore_failed_after_invalid), isError = true)
                        }
                        return@withLock
                    }
                    if (saved) {
                        savedFilters = filters
                        draft = FilterDraft.from(filters)
                        filtersMessage = DriverStatus(context.getString(R.string.ss_saved_auto), success = true)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (returnToSetupAfterAction && destination == DriverDestination.Filters) {
                            returnToSetupAfterAction = false
                            destination = DriverDestination.Setup
                        }
                    } else {
                        filtersMessage = DriverStatus(context.getString(R.string.ss_save_failed_retry), isError = true)
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (revision == filterRevision) {
                        filtersMessage = DriverStatus(context.getString(R.string.ss_save_failed_retry), isError = true)
                    }
                } finally {
                    if (revision == filterRevision) isSavingFilters = false
                }
            }
        }
    }

    fun retrySaveDraft() {
        updateDraft(draft.copy())
    }

    fun navigateTo(next: DriverDestination) {
        if (next == destination) return
        // «حالة العمل» is home: reaching it resets the history, so back from home leaves the app.
        if (next == DriverDestination.Dashboard) backStack.clear()
        else {
            backStack.remove(next)
            backStack.add(destination)
            while (backStack.size > MAX_BACK_STACK) backStack.removeAt(0)
        }
        destination = next
    }

    fun navigateFromSetup(next: DriverDestination) {
        returnToSetupAfterAction = true
        navigateTo(next)
    }

    fun requestActivation() {
        if (checkingActivation || isActive) return
        if (isRestoringSession || sessionRestoreError) {
            runtimeMessage = DriverStatus(
                if (isRestoringSession) context.getString(R.string.ss_restoring_session_wait)
                else context.getString(R.string.ss_session_update_failed),
                isError = sessionRestoreError,
            )
            return
        }
        if (!draftValidation.isValid) {
            runtimeMessage = DriverStatus(context.getString(R.string.ss_fix_conditions_before_start), isError = true)
            return
        }
        if (filtersDirty || isSavingFilters) {
            runtimeMessage = DriverStatus(context.getString(R.string.ss_autosave_wait), isError = true)
            return
        }
        if (!FilterDraft.canStartWith(savedFilters)) {
            navigateTo(DriverDestination.Filters)
            filtersMessage = DriverStatus(context.getString(R.string.ss_set_one_condition), isError = true)
            return
        }
        refreshRuntimePrerequisites()
        // Everything that blocks the start is listed together, not one prompt at a time.
        if (!accessibilityEnabled || !notificationsEnabled) {
            showMissingPermissions = true
            return
        }
        runtimeMessage = null
        // A paid period already saved on this phone starts at once: the server is asked in the
        // background, and only its explicit refusal stops monitoring (the licence loop handles it).
        // Waiting on «جارٍ التحقق من اشتراكك» at every start was dead time (owner, 2026-10-31).
        if (Settings.subscriptionEndsAtMs > System.currentTimeMillis()) {
            showActivationConsent = true
            scope.launch {
                runCatching { SupabaseClient.revalidateLicense() }
                licenseInMemory = SupabaseClient.hasValidLicense()
            }
            return
        }
        checkingActivation = true
        scope.launch {
            try {
                // هذا هو موضع الفحص الشبكي الصريح الوحيد في مسار بدء التشغيل.
                // A failed network check never blocks a subscriber whose end date is already saved.
                val verified = SupabaseClient.revalidateLicense().getOrNull() == true && SupabaseClient.hasValidLicense() ||
                    Settings.subscriptionEndsAtMs > System.currentTimeMillis()
                licenseInMemory = SupabaseClient.hasValidLicense()
                if (verified) {
                    showActivationConsent = true
                } else {
                    runtimeMessage = DriverStatus(
                        context.getString(R.string.ss_verify_failed_renew),
                        isError = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runtimeMessage = DriverStatus(context.getString(R.string.ss_verify_failed_network), isError = true)
            } finally {
                checkingActivation = false
            }
        }
    }

    fun confirmActivation() {
        showActivationConsent = false
        refreshRuntimePrerequisites()
        if (filtersDirty || isSavingFilters || !FilterDraft.canStartWith(savedFilters) || !accessibilityEnabled || !notificationsEnabled ||
            (!SupabaseClient.hasValidLicense() && Settings.subscriptionEndsAtMs <= System.currentTimeMillis())) {
            Settings.isActive = false
            isActive = false
            runtimeMessage = DriverStatus(context.getString(R.string.ss_requirements_changed), isError = true)
            return
        }
        val result = latestToggleKeeper(true)
        if (result.isFailure) {
            Settings.isActive = false
            isActive = false
            runtimeMessage = DriverStatus(context.getString(R.string.ss_start_failed_perms), isError = true)
        } else {
            isActive = true
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            runtimeMessage = DriverStatus(context.getString(R.string.ss_monitoring_started), success = true)
        }
    }

    // Every page except home goes back one step (the page it was opened from), never out of the app.
    fun goBack() {
        returnToSetupAfterAction = false
        val previous = backStack.removeLastOrNull()
        destination = previous ?: DriverDestination.Dashboard
        if (destination == DriverDestination.Dashboard) backStack.clear()
    }
    BackHandler(enabled = destination != DriverDestination.Dashboard) { goBack() }

    if (showAccessibilityRequired) {
        AlertDialog(
            onDismissRequest = { showAccessibilityRequired = false },
            title = { Text(stringResource(R.string.ss_dlg_a11y_title)) },
            text = { Text(stringResource(R.string.ss_dlg_a11y_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityRequired = false
                    if (onOpenA11y().isFailure) {
                        runtimeMessage = DriverStatus(context.getString(R.string.ss_a11y_open_failed), true)
                    }
                }) { Text(stringResource(R.string.ss_open_device_settings)) }
            },
            dismissButton = { TextButton(onClick = { showAccessibilityRequired = false }) { Text(stringResource(R.string.ss_cancel)) } },
        )
    }

    if (showMissingPermissions) {
        AlertDialog(
            onDismissRequest = { showMissingPermissions = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text(stringResource(R.string.ss_dlg_perms_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ss_dlg_perms_text),
                        style = MaterialTheme.typography.bodyMedium)
                    if (!accessibilityEnabled) PermissionAction(Icons.Outlined.AccessibilityNew, stringResource(R.string.ss_perm_a11y_title),
                        stringResource(R.string.ss_perm_a11y_why), stringResource(R.string.ss_enable)) {
                        showMissingPermissions = false
                        if (onOpenA11y().isFailure) {
                            runtimeMessage = DriverStatus(context.getString(R.string.ss_a11y_open_failed), true)
                        }
                    }
                    if (!notificationsEnabled) PermissionAction(Icons.Outlined.Notifications, stringResource(R.string.ss_perm_notif_title),
                        stringResource(R.string.ss_perm_notif_why), stringResource(R.string.ss_allow)) {
                        showMissingPermissions = false
                        onRequestNotificationPermission()
                    }
                    if (!batteryUnrestricted) PermissionAction(Icons.Outlined.BatteryChargingFull, stringResource(R.string.ss_perm_batt_title),
                        stringResource(R.string.ss_perm_batt_why), stringResource(R.string.ss_allow)) {
                        runCatching { context.startActivity(PowerSetup.batteryIntent(context)) }
                            .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMissingPermissions = false }) { Text(stringResource(R.string.ss_done)) } },
        )
    }

    if (showNotificationRequired) {
        AlertDialog(
            onDismissRequest = { showNotificationRequired = false },
            title = { Text(stringResource(R.string.ss_dlg_notif_title)) },
            text = { Text(stringResource(R.string.ss_dlg_notif_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationRequired = false
                    onRequestNotificationPermission()
                }) { Text(stringResource(R.string.ss_allow_notifications)) }
            },
            dismissButton = { TextButton(onClick = { showNotificationRequired = false }) { Text(stringResource(R.string.ss_cancel)) } },
        )
    }

    if (showActivationConsent) {
        AlertDialog(
            onDismissRequest = { showActivationConsent = false },
            title = { Text(stringResource(R.string.ss_dlg_start_title)) },
            text = {
                Text(
                    stringResource(R.string.ss_dlg_start_text)
                )
            },
            confirmButton = { TextButton(onClick = ::confirmActivation) { Text(stringResource(R.string.ss_dlg_start_confirm)) } },
            dismissButton = { TextButton(onClick = { showActivationConsent = false }) { Text(stringResource(R.string.ss_cancel)) } },
        )
    }

    if (showResetFilters) {
        AlertDialog(
            onDismissRequest = { showResetFilters = false },
            title = { Text(stringResource(R.string.ss_dlg_reset_title)) },
            text = { Text(stringResource(R.string.ss_dlg_reset_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetFilters = false
                    updateDraft(FilterDraft.from(EngineConfig()))
                }) { Text(stringResource(R.string.ss_reset)) }
            },
            dismissButton = { TextButton(onClick = { showResetFilters = false }) { Text(stringResource(R.string.ss_cancel)) } },
        )
    }

    val missingCount = listOf(
        accessibilityEnabled, notificationsEnabled, licenseInMemory, batteryUnrestricted, autostartDone,
    ).count { !it }
    DriverNavigationShell(
        destination = destination,
        onDestinationSelected = ::navigateTo,
        dashboardBadge = missingCount,
    ) {
        SabbaqDepthContent(target = destination, order = { it.ordinal }, label = "driver-destination") { page ->
        when (page) {
            DriverDestination.Dashboard -> DashboardPage(
                isActive = isActive,
                isCheckingActivation = checkingActivation,
                isRestoringSession = isRestoringSession,
                sessionRestoreError = sessionRestoreError,
                onRetrySessionRestore = onRetrySessionRestore,
                accessibilityEnabled = accessibilityEnabled,
                notificationsEnabled = notificationsEnabled,
                licenseInMemory = licenseInMemory,
                subscriptionSnapshot = subscriptionSnapshot,
                draft = draft,
                draftErrors = draftValidation.errors,
                filtersMessage = filtersMessage,
                onQuickDraftChange = { updateDraft(it, stopIfActive = false) },
                activeFilterCount = FilterDraft.from(savedFilters).activeCount(),
                message = runtimeMessage,
                onToggle = { enabled -> if (enabled) requestActivation() else stopKeeper() },
                onOpenA11y = {
                    if (onOpenA11y().isFailure) {
                        runtimeMessage = DriverStatus(context.getString(R.string.ss_a11y_open_failed2), true)
                    }
                },
                onRequestNotifications = onRequestNotificationPermission,
                onOpenFilters = { navigateTo(DriverDestination.Filters) },
                onOpenZones = { navigateTo(DriverDestination.Zones) },
                onOpenAccount = { navigateTo(DriverDestination.Account) },
                onOpenSetupGuide = { navigateTo(DriverDestination.Setup) },
                batteryUnrestricted = batteryUnrestricted,
                hibernationOff = hibernationOff,
                onOpenHibernation = {
                    runCatching { context.startActivity(app.sabbaq.fleet.KeepAlive.hibernationIntent(context)) }
                        .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                },
                autostartNeeded = PowerSetup.needsAutostart(),
                autostartDone = autostartDone,
                onOpenBattery = {
                    runCatching { context.startActivity(PowerSetup.batteryIntent(context)) }
                        .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                },
                onOpenAutostart = {
                    runCatching { context.startActivity(PowerSetup.autostartIntent(context)) }
                    // The setting cannot be read back; opening it is taken as done, and the row explains what to switch on.
                    Settings.autostartConfirmed = true
                    autostartDone = true
                },
            )

            DriverDestination.Setup -> DriverPage {
                val leaveGuide = {
                    SetupGuidePreferences.markDone(context)
                    navigateTo(DriverDestination.Dashboard)
                }
                SetupGuidePage(
                    steps = listOf(
                        SetupStep(
                            icon = Icons.Outlined.VerifiedUser,
                            title = stringResource(R.string.ss_setup_sub_title),
                            why = stringResource(R.string.ss_setup_sub_why),
                            doneText = stringResource(R.string.ss_setup_sub_done),
                            actionLabel = stringResource(R.string.ss_setup_open_account),
                            done = licenseInMemory,
                            onAction = { navigateFromSetup(DriverDestination.Account) },
                        ),
                        SetupStep(
                            icon = Icons.Outlined.AccessibilityNew,
                            title = stringResource(R.string.ss_setup_a11y_title),
                            why = stringResource(R.string.ss_setup_a11y_why),
                            doneText = stringResource(R.string.ss_setup_a11y_done),
                            actionLabel = stringResource(R.string.ss_setup_a11y_action),
                            done = accessibilityEnabled,
                            onAction = {
                                if (onOpenA11y().isFailure) {
                                    runtimeMessage = DriverStatus(context.getString(R.string.ss_a11y_open_failed2), true)
                                }
                            },
                        ),
                        SetupStep(
                            icon = Icons.Outlined.Notifications,
                            title = stringResource(R.string.ss_setup_notif_title),
                            why = stringResource(R.string.ss_setup_notif_why),
                            doneText = stringResource(R.string.ss_setup_notif_done),
                            actionLabel = stringResource(R.string.ss_allow_notifications),
                            done = notificationsEnabled,
                            onAction = onRequestNotificationPermission,
                        ),
                        SetupStep(
                            icon = Icons.Outlined.BatteryChargingFull,
                            title = stringResource(R.string.ss_setup_batt_title),
                            why = stringResource(R.string.ss_setup_batt_why),
                            doneText = stringResource(R.string.ss_setup_batt_done),
                            actionLabel = stringResource(R.string.ss_allow),
                            done = batteryUnrestricted,
                            onAction = {
                                runCatching { context.startActivity(PowerSetup.batteryIntent(context)) }
                                    .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                            },
                        ),
                    ) + listOfNotNull(
                        if (PowerSetup.needsAutostart()) SetupStep(
                            icon = Icons.Outlined.RestartAlt,
                            title = stringResource(R.string.ss_setup_autostart_title),
                            why = stringResource(R.string.ss_setup_autostart_why),
                            doneText = stringResource(R.string.ss_setup_autostart_done),
                            actionLabel = stringResource(R.string.ss_setup_open_setting),
                            done = autostartDone,
                            onAction = {
                                runCatching { context.startActivity(PowerSetup.autostartIntent(context)) }
                                Settings.autostartConfirmed = true
                                autostartDone = true
                            },
                        ) else null
                    ),
                    onTryExample = { SetupGuidePreferences.markDone(context); navigateTo(DriverDestination.Simulator) },
                    onFinish = leaveGuide,
                    onLater = leaveGuide,
                )
            }

            DriverDestination.Filters -> FiltersPage(
                draft = draft,
                validationErrors = draftValidation.errors,
                dirty = filtersDirty,
                isSaving = isSavingFilters,
                message = filtersMessage,
                onDraftChange = ::updateDraft,
                onSave = ::retrySaveDraft,
                onRevert = { updateDraft(FilterDraft.from(savedFilters)); filtersMessage = null },
                onReset = { showResetFilters = true },
            )

            DriverDestination.Simulator -> SimulatorPage(savedFilters = savedFilters)

            DriverDestination.Zones -> DriverPage {
                SabbaqAnimatedMessage(filtersMessage?.message, filtersMessage?.tone ?: SabbaqTone.Info)
                ZonesPage(draft, draftValidation.errors, ::updateDraft, onBack = ::goBack)
            }

            DriverDestination.Settings -> DriverPage {
                val openBattery = {
                    runCatching { context.startActivity(PowerSetup.batteryIntent(context)) }
                        .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                    Unit
                }
                val openA11y = {
                    if (onOpenA11y().isFailure) {
                        runtimeMessage = DriverStatus(context.getString(R.string.ss_a11y_open_failed2), true)
                    }
                }
                SettingsHubPage(
                    permissions = listOf(
                        ReadinessItem(Icons.Outlined.AccessibilityNew, stringResource(R.string.ss_perm_a11y_title), accessibilityEnabled,
                            stringResource(R.string.ss_ready_a11y_ready), stringResource(R.string.ss_ready_a11y_why), stringResource(R.string.ss_setup_a11y_action), openA11y),
                        ReadinessItem(Icons.Outlined.Notifications, stringResource(R.string.ss_ready_notif_title), notificationsEnabled,
                            stringResource(R.string.ss_ready_notif_ready), stringResource(R.string.ss_ready_notif_why), stringResource(R.string.ss_allow), onRequestNotificationPermission),
                        ReadinessItem(Icons.Outlined.VerifiedUser, stringResource(R.string.ss_ready_sub_title), licenseInMemory,
                            stringResource(R.string.ss_ready_sub_ready), stringResource(R.string.ss_ready_sub_why), stringResource(R.string.ss_setup_open_account)) { navigateTo(DriverDestination.Account) },
                    ),
                    power = buildList {
                        add(ReadinessItem(Icons.Outlined.BatteryChargingFull, stringResource(R.string.ss_perm_batt_title), batteryUnrestricted,
                            stringResource(R.string.ss_setup_batt_done), stringResource(R.string.ss_ready_batt_why), stringResource(R.string.ss_allow), openBattery))
                        add(ReadinessItem(Icons.Outlined.BatteryAlert, stringResource(R.string.keep_bubble_t),
                            app.sabbaq.fleet.FloatingBubble.permitted(context), stringResource(R.string.keep_bubble_done), stringResource(R.string.keep_bubble_b),
                            stringResource(R.string.ss_allow)) {
                            app.sabbaq.fleet.FloatingBubble.openPermission(context)
                        })
                        if (android.os.Build.VERSION.SDK_INT >= 30) add(ReadinessItem(Icons.Outlined.BatteryAlert, stringResource(R.string.keep_hib_t),
                            !app.sabbaq.fleet.KeepAlive.hibernationOn(context), stringResource(R.string.keep_hib_done), stringResource(R.string.keep_hib_b),
                            stringResource(R.string.ss_setup_open_setting)) {
                            runCatching { context.startActivity(app.sabbaq.fleet.KeepAlive.hibernationIntent(context)) }
                                .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                        })
                        if (PowerSetup.needsAutostart()) add(ReadinessItem(Icons.Outlined.RestartAlt, stringResource(R.string.ss_ready_autostart_title), autostartDone,
                            stringResource(R.string.ss_setup_autostart_done), stringResource(R.string.ss_ready_autostart_why), stringResource(R.string.ss_setup_open_setting)) {
                            runCatching { context.startActivity(PowerSetup.autostartIntent(context)) }
                            Settings.autostartConfirmed = true
                            autostartDone = true
                        })
                    },
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                    onOpenSimulator = { navigateTo(DriverDestination.Simulator) },
                    onOpenHistory = { navigateTo(DriverDestination.History) },
                    onOpenSetupGuide = { navigateTo(DriverDestination.Setup) },
                )
            }

            DriverDestination.History -> DriverPage {
                OfferDecisionLogScreen(
                    onBack = ::goBack,
                    onDiagnosisAction = { action ->
                        when (action) {
                            DiagnosisAction.ACCESSIBILITY -> if (onOpenA11y().isFailure) {
                                runtimeMessage = DriverStatus(context.getString(R.string.ss_a11y_open_failed2), true)
                            }
                            DiagnosisAction.ACCOUNT -> navigateTo(DriverDestination.Account)
                            DiagnosisAction.CONDITIONS -> navigateTo(DriverDestination.Filters)
                            DiagnosisAction.NONE -> Unit
                        }
                    },
                )
            }

            DriverDestination.Support -> SupportPage()

            DriverDestination.Referrals -> DriverPage { ReferralsPage() }

            DriverDestination.Account -> AccountPage(
                licenseInMemory = licenseInMemory,
                message = accountMessage,
                onLicenseChanged = {
                    licenseInMemory = it
                    if (returnToSetupAfterAction && it) {
                        returnToSetupAfterAction = false
                        navigateTo(DriverDestination.Setup)
                    }
                },
                onMessage = { accountMessage = it },
                onAccountDeleted = {
                    if (isActive) stopKeeper()
                    onAccountDeleted()
                },
                onOpenSupport = { navigateTo(DriverDestination.Support) },
                onSignOut = onRequestLogout,
            )
        }
        }
    }
}

@Composable
private fun DriverNavigationShell(
    destination: DriverDestination,
    onDestinationSelected: (DriverDestination) -> Unit,
    dashboardBadge: Int = 0,
    content: @Composable () -> Unit,
) {
    // A red count on «حالة العمل» keeps missing permissions visible from every page.
    val navIcon: @Composable (DriverNavItem) -> Unit = { item ->
        val selected = destination == item.destination
        if (item.destination == DriverDestination.Dashboard && dashboardBadge > 0) {
            BadgedBox(badge = { Badge { Text("$dashboardBadge") } }) {
                Icon(item.icon, contentDescription = stringResource(R.string.nav_missing_badge, stringResource(item.labelRes), dashboardBadge), modifier = Modifier.flipWhenSelected(selected))
            }
        } else {
            Icon(item.icon, contentDescription = stringResource(item.labelRes), modifier = Modifier.flipWhenSelected(selected))
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp
        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.fillMaxHeight(), windowInsets = WindowInsets(0, 0, 0, 0)) {
                    driverDestinations.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item.destination,
                            onClick = { onDestinationSelected(item.destination) },
                            icon = { navIcon(item) },
                            // The rail item is a fixed 80dp; two centred lines keep labels like
                            // «طلبات تناسبك» whole instead of clipping at the screen edge.
                            label = {
                                Text(
                                    stringResource(item.labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                )
                            },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) { content() }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    val colors = MaterialTheme.colorScheme
                    Column {
                        HorizontalDivider(color = colors.outlineVariant)
                        NavigationBar(
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            containerColor = colors.surface,
                            tonalElevation = 0.dp,
                        ) {
                            bottomDriverDestinations.forEach { item ->
                                val selected = destination == item.destination
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { onDestinationSelected(item.destination) },
                                    icon = { navIcon(item) },
                                    label = {
                                        Text(stringResource(item.labelRes), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = colors.onSecondaryContainer,
                                        selectedTextColor = colors.primary,
                                        indicatorColor = colors.secondaryContainer,
                                        unselectedIconColor = colors.onSurfaceVariant,
                                        unselectedTextColor = colors.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.TopCenter,
                ) { content() }
            }
        }
    }
}

@Composable
private fun DriverPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) { content() }
}

@Composable
private fun ActiveSubscriptionStatusCard(
    snapshot: SubscriptionSnapshot?,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = LocalSabbaqBrand.current
    val colors = MaterialTheme.colorScheme
    val state = snapshot?.state
    val isPro = state == LicenseState.ACTIVE
    val isTrial = state == LicenseState.TRIAL
    val until = when (state) {
        LicenseState.ACTIVE -> snapshot?.proUntil
        LicenseState.TRIAL -> snapshot?.trialUntil
        else -> null
    }
    val now = java.time.Instant.now()
    val remaining = until?.let { java.time.Duration.between(now, it).coerceAtLeast(java.time.Duration.ZERO) }
    val remainingDays = remaining?.toDays()
    val endDate = until?.let {
        java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", sabbaqDateLocale())
            .withZone(java.time.ZoneId.systemDefault()).format(it)
    }
    val title = when (state) {
        LicenseState.ACTIVE -> stringResource(R.string.ss_sub_pro)
        LicenseState.TRIAL -> stringResource(R.string.ss_sub_trial)
        LicenseState.PENDING -> stringResource(R.string.ss_sub_pending)
        LicenseState.EXPIRED -> stringResource(R.string.ss_sub_expired)
        LicenseState.DEVICE_MISMATCH -> stringResource(R.string.ss_sub_mismatch)
        LicenseState.BANNED, LicenseState.REJECTED -> stringResource(R.string.ss_sub_unavailable)
        LicenseState.UNREGISTERED, null -> stringResource(R.string.ss_sub_unknown)
    }
    val detail = when {
        (isPro || isTrial) && remainingDays == 0L -> stringResource(R.string.ss_sub_ends_today)
        (isPro || isTrial) && remainingDays != null -> stringResource(R.string.ss_sub_remaining, ArabicCount.days(remainingDays.toInt()), endDate ?: "")
        isPro || isTrial -> stringResource(R.string.ss_sub_available)
        state == LicenseState.PENDING -> stringResource(R.string.ss_sub_pending_detail)
        state == null -> stringResource(R.string.ss_sub_update_from_account)
        else -> stringResource(R.string.ss_sub_open_account_detail)
    }
    val endingSoon = (isPro || isTrial) && remainingDays != null && remainingDays <= 2
    // Trial length is known, so its progress is shown; a paid period's start is not, so Pro shows the date only.
    val trialProgress = if (isTrial && remaining != null) snapshot?.trialDays?.takeIf { it > 0 }?.let { days ->
        (remaining.toMinutes().toFloat() / (days * 24 * 60f)).coerceIn(0f, 1f)
    } else null

    val background = when {
        isPro -> Brush.linearGradient(listOf(brand.heroStart, brand.heroEnd))
        isTrial -> Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer))
        else -> Brush.linearGradient(listOf(colors.errorContainer, colors.errorContainer))
    }
    val content = when {
        isPro -> brand.onHero
        isTrial -> colors.onPrimaryContainer
        else -> colors.onErrorContainer
    }
    val cardStateDescription = stringResource(R.string.ss_sub_state_desc, title, detail)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = stringResource(R.string.ss_open_account_label), role = Role.Button, onClick = onOpenAccount)
            .semantics(mergeDescendants = true) { stateDescription = cardStateDescription },
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        contentColor = content,
        shadowElevation = if (isPro) 8.dp else 2.dp,
    ) {
        Box(Modifier.background(background)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MembershipBadge(isPro = isPro, isTrial = isTrial)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                endingSoon && isPro -> brand.heroAccent
                                endingSoon -> colors.error
                                else -> content.copy(alpha = 0.85f)
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = content.copy(alpha = 0.8f))
                }
                trialProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = colors.primary,
                        trackColor = colors.primary.copy(alpha = 0.18f),
                    )
                }
            }
        }
    }
}

/** «PRO» in gold for members, «تجربة» for trial users, a warning mark otherwise. */
@Composable
private fun MembershipBadge(isPro: Boolean, isTrial: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (label, fill, ink) = when {
        isPro -> Triple("PRO", Brush.linearGradient(listOf(Color(0xFFF7D774), Color(0xFFD9A526))), Color(0xFF3A2A00))
        isTrial -> Triple(stringResource(R.string.ss_badge_trial), Brush.linearGradient(listOf(colors.primary, colors.primary)), colors.onPrimary)
        else -> Triple("!", Brush.linearGradient(listOf(colors.error, colors.error)), colors.onError)
    }
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .widthIn(min = 52.dp)
            .shadow(if (isPro) 6.dp else 0.dp, RoundedCornerShape(12.dp), spotColor = Color(0xFFD9A526))
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DashboardPage(
    isActive: Boolean,
    isCheckingActivation: Boolean,
    isRestoringSession: Boolean,
    sessionRestoreError: Boolean,
    onRetrySessionRestore: () -> Unit,
    accessibilityEnabled: Boolean,
    notificationsEnabled: Boolean,
    licenseInMemory: Boolean,
    subscriptionSnapshot: SubscriptionSnapshot?,
    activeFilterCount: Int,
    draft: FilterDraft,
    draftErrors: Map<String, String>,
    filtersMessage: DriverStatus?,
    onQuickDraftChange: (FilterDraft) -> Unit,
    message: DriverStatus?,
    onToggle: (Boolean) -> Unit,
    onOpenA11y: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenZones: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    batteryUnrestricted: Boolean = true,
    hibernationOff: Boolean = true,
    onOpenHibernation: () -> Unit = {},
    autostartNeeded: Boolean = false,
    autostartDone: Boolean = true,
    onOpenBattery: () -> Unit = {},
    onOpenAutostart: () -> Unit = {},
) = DriverPage {
    val readiness = listOf(
        ReadinessItem(Icons.Outlined.AccessibilityNew, stringResource(R.string.ss_perm_a11y_title), accessibilityEnabled,
            stringResource(R.string.ss_ready_a11y_ready), stringResource(R.string.ss_ready_a11y_why), stringResource(R.string.ss_setup_a11y_action), onOpenA11y),
        ReadinessItem(Icons.Outlined.Notifications, stringResource(R.string.ss_ready_notif_title), notificationsEnabled,
            stringResource(R.string.ss_ready_notif_ready), stringResource(R.string.ss_ready_notif_why), stringResource(R.string.ss_allow), onRequestNotifications),
        ReadinessItem(Icons.Outlined.VerifiedUser, stringResource(R.string.ss_ready_sub_title), licenseInMemory,
            stringResource(R.string.ss_ready_sub_ready), stringResource(R.string.ss_ready_sub_why), stringResource(R.string.ss_setup_open_account), onOpenAccount),
    )
    // What keeps it running when the screen locks, the phone sleeps or restarts.
    val power = buildList {
        add(ReadinessItem(Icons.Outlined.BatteryChargingFull, stringResource(R.string.ss_perm_batt_title), batteryUnrestricted,
            stringResource(R.string.ss_setup_batt_done), stringResource(R.string.ss_ready_batt_why), stringResource(R.string.ss_allow), onOpenBattery))
        // Android 11+ pauses apps it considers unused; a row here, not a separate warning card.
        if (android.os.Build.VERSION.SDK_INT >= 30) add(ReadinessItem(Icons.Outlined.BatteryAlert, stringResource(R.string.keep_hib_t), hibernationOff,
            stringResource(R.string.keep_hib_done), stringResource(R.string.keep_hib_b), stringResource(R.string.ss_setup_open_setting), onOpenHibernation))
        if (autostartNeeded) add(ReadinessItem(Icons.Outlined.RestartAlt, stringResource(R.string.ss_ready_autostart_title), autostartDone,
            stringResource(R.string.ss_setup_autostart_done), stringResource(R.string.ss_ready_autostart_why), stringResource(R.string.ss_setup_open_setting), onOpenAutostart))
    }
    val readyCount = readiness.count { it.ready }
    val allCount = readiness.size + power.size
    val allReady = readyCount + power.count { it.ready }
    if (isRestoringSession || sessionRestoreError) {
        SessionRestoreNotice(isRestoring = isRestoringSession, onRetry = onRetrySessionRestore)
    }
    ActiveSubscriptionStatusCard(
        snapshot = subscriptionSnapshot,
        onOpenAccount = onOpenAccount,
        modifier = Modifier.sabbaqEntrance(0),
    )
    UpdateBanner()
    OperationHero(
        isActive = isActive,
        isChecking = isCheckingActivation || isRestoringSession,
        readyCount = allReady,
        totalCount = allCount,
        onToggle = onToggle,
        canStart = !isRestoringSession && !sessionRestoreError,
        busyText = if (isRestoringSession) stringResource(R.string.ss_busy_restoring) else stringResource(R.string.ss_busy_verifying),
        modifier = Modifier.sabbaqEntrance(1),
    )
    SabbaqAnimatedMessage(message?.message, message?.tone ?: SabbaqTone.Info)
    // Always on the dashboard, whether monitoring runs or not: the control works in both states.
    FloatingControlButton(Modifier.sabbaqEntrance(2))
    ActiveConditionsCockpit(
        draft = draft,
        errors = draftErrors,
        activeCount = activeFilterCount,
        isActive = isActive,
        saveMessage = filtersMessage,
        onDraftChange = onQuickDraftChange,
        onOpenFilters = onOpenFilters,
        onOpenZones = onOpenZones,
        modifier = Modifier.sabbaqEntrance(2),
    )
    // «تشخيص الرصد» lives only in «النشاط والتشخيص», next to the statistics (owner, 2026-10-30).
    val missing = (readiness + power).filterNot { it.ready }
    if (missing.isNotEmpty()) MissingPermissionsCard(missing, Modifier.sabbaqEntrance(1))

    SabbaqCard(
        title = if (readyCount == readiness.size) stringResource(R.string.ss_card_ready_title) else stringResource(R.string.ss_card_before_start),
        subtitle = if (readyCount == readiness.size) stringResource(R.string.ss_card_ready_sub)
            else stringResource(R.string.ss_card_steps_left, ArabicCount.steps(readiness.size - readyCount)),
        icon = Icons.Outlined.VerifiedUser,
        modifier = Modifier.sabbaqEntrance(1),
    ) {
        val readyCountState = stringResource(R.string.ss_ready_count_state, readyCount, readiness.size)
        val progress by animateFloatAsState(
            targetValue = readyCount.toFloat() / readiness.size,
            animationSpec = tween(SabbaqMotion.LONG, easing = SabbaqMotion.Emphasized),
            label = "readiness-progress",
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = readyCountState },
        )
        readiness.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReadinessRow(item)
        }
        SabbaqButton(
            if (readyCount < readiness.size) stringResource(R.string.ss_complete_setup_steps) else stringResource(R.string.ss_setup_guide),
            onClick = onOpenSetupGuide,
            style = if (readyCount < readiness.size) SabbaqButtonStyle.Secondary else SabbaqButtonStyle.Text,
            icon = Icons.Outlined.Tune,
        )
    }

    val powerReady = power.all { it.ready }
    SabbaqCard(
        title = if (powerReady) stringResource(R.string.ss_card_full_power_on) else stringResource(R.string.ss_card_full_power_off),
        subtitle = if (powerReady) stringResource(R.string.ss_card_full_power_on_sub)
            else stringResource(R.string.ss_card_full_power_off_sub),
        icon = Icons.Outlined.Bolt,
        modifier = Modifier.sabbaqEntrance(2),
    ) {
        power.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReadinessRow(item)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReadinessRow(ReadinessItem(Icons.Outlined.Autorenew, stringResource(R.string.ss_resume_after_restart), true,
            stringResource(R.string.ss_resume_after_restart_ready), "", "", {}))
    }

    // Voice alerts live in «الإعدادات ← الصوت والتنبيهات»; statistics live in «النشاط والتشخيص».
    SupportedPlatformsCard(Modifier.sabbaqEntrance(5))

    SabbaqCard(title = stringResource(R.string.ss_card_during_work), icon = Icons.Outlined.Info, modifier = Modifier.sabbaqEntrance(6)) {
        Text(
            stringResource(R.string.ss_during_work_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun RuntimeStatusCard(status: RuntimeStatusSnapshot, modifier: Modifier = Modifier) {
    // Relative times ("قبل 12 ثانية") must keep moving even when no event updates the snapshot.
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val state = when {
        status.pausedReason != null -> RuntimeLabels.pausedReason(status.pausedReason)
        status.stale -> stringResource(R.string.ss_runtime_stale)
        status.active -> stringResource(R.string.ss_runtime_active)
        else -> stringResource(R.string.ss_runtime_stopped)
    }
    SabbaqCard(title = stringResource(R.string.ss_runtime_title), subtitle = state, icon = Icons.Outlined.Autorenew, modifier = modifier) {
        Text(stringResource(R.string.ss_runtime_last_event, RuntimeLabels.relativeTime(status.lastEventMs, now)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.ss_runtime_last_offer_decision, RuntimeLabels.relativeTime(status.lastOfferMs, now), RuntimeLabels.relativeTime(status.lastDecisionMs, now)), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.ss_runtime_last_click, RuntimeLabels.relativeTime(status.lastClickMs, now)), style = MaterialTheme.typography.bodySmall)
        status.latestReport?.let { report ->
            Text(stringResource(R.string.ss_runtime_clarity, report.score), style = MaterialTheme.typography.bodySmall)
            if (report.reasons.isNotEmpty()) {
                Text(stringResource(R.string.ss_runtime_reject_reason, report.reasons.take(2).joinToString(" · ") { reasonLabel(it).removeSuffix(".") }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (status.diagnostics.isNotEmpty()) {
            Text(stringResource(R.string.ss_runtime_stages, status.diagnostics.takeLast(4).joinToString(" ← ") { RuntimeLabels.stage(it.stage) }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionRestoreNotice(isRestoring: Boolean, onRetry: () -> Unit) {
    val container = if (isRestoring) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isRestoring) stringResource(R.string.ss_restore_welcome) else stringResource(R.string.ss_restore_session_failed),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (isRestoring) stringResource(R.string.ss_restore_checking)
                    else stringResource(R.string.ss_restore_local),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!isRestoring) TextButton(onClick = onRetry) { Text(stringResource(R.string.ss_retry)) }
            }
            if (isRestoring) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * «شروط القيادة»: the ride app, the conditions drivers change most and, below «قنص الطلبات بدون
 * وجهة», the places and zones, editable in place (the same components as «طلبات تناسبك», so both
 * edit one draft). Everything else stays on the full page behind the button.
 */
@Composable
private fun ActiveConditionsCockpit(
    draft: FilterDraft,
    errors: Map<String, String>,
    activeCount: Int,
    isActive: Boolean,
    saveMessage: DriverStatus?,
    onDraftChange: (FilterDraft) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenZones: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SabbaqCard(
        title = stringResource(R.string.ss_conditions_title),
        subtitle = when {
            activeCount == 0 -> stringResource(R.string.ss_conditions_none)
            isActive -> stringResource(R.string.ss_conditions_active_live, ArabicCount.conditions(activeCount))
            else -> stringResource(R.string.ss_conditions_active_saved, ArabicCount.conditions(activeCount))
        },
        icon = Icons.Outlined.Tune,
        modifier = modifier,
    ) {
        QuickConditionsPanel(draft, errors, onDraftChange)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ZoneConditions(draft, errors, onDraftChange, onOpenZones, framed = false)
        SabbaqAnimatedMessage(saveMessage?.message, saveMessage?.tone ?: SabbaqTone.Info)
        SabbaqButton(
            stringResource(R.string.ss_conditions_more),
            onClick = onOpenFilters,
            style = SabbaqButtonStyle.Secondary,
            icon = Icons.Outlined.FilterAlt,
        )
    }
}

internal data class ReadinessItem(
    val icon: ImageVector,
    val title: String,
    val ready: Boolean,
    val readyText: String,
    val missingText: String,
    val actionLabel: String,
    val onAction: () -> Unit,
)

/**
 * The single control of the dashboard. While monitoring is live a slow radar pulse
 * radiates from the status mark; it stops the moment monitoring stops, so the motion
 * itself is the state signal (paired with text for screen readers).
 */
@Composable
private fun OperationHero(
    isActive: Boolean,
    isChecking: Boolean,
    readyCount: Int,
    totalCount: Int,
    onToggle: (Boolean) -> Unit,
    canStart: Boolean = true,
    busyText: String = stringResource(R.string.ss_busy_verifying),
    modifier: Modifier = Modifier,
) {
    val brand = LocalSabbaqBrand.current
    val reduced = LocalReducedMotion.current
    val readinessState = stringResource(R.string.ss_readiness_state, readyCount, totalCount)
    val readiness = if (totalCount > 0) readyCount.toFloat() / totalCount else 0f
    val ring by animateFloatAsState(
        targetValue = if (isActive) 1f else readiness,
        animationSpec = tween(SabbaqMotion.LONG, easing = SabbaqMotion.Emphasized),
        label = "dial-ring",
    )
    // One 12 s clock drives the radar face: three times faster while monitoring runs, slow and
    // dim while stopped, frozen under reduced motion.
    val clock = if (reduced) null else rememberInfiniteTransition(label = "dial-clock").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "dial-clock-phase",
    )
    val sweepAngle = ((clock?.value ?: 0.15f) * (if (isActive) 3f else 1f) * 360f) % 360f

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val dial = minOf(maxWidth, 340.dp)
        Box(
            modifier = Modifier
                .size(dial)
                .tilt3D(maxDegrees = 6f, idle = true)
                .shadow(18.dp, CircleShape, ambientColor = brand.heroEnd, spotColor = brand.heroEnd)
                .clip(CircleShape)
                .border(2.dp, brand.signature.copy(alpha = 0.6f), CircleShape)
                .background(Brush.radialGradient(listOf(brand.heroEnd, brand.heroStart))),
            contentAlignment = Alignment.Center,
        ) {
            DialRadarFace(sweepAngle = sweepAngle, active = isActive, modifier = Modifier.matchParentSize())
            Canvas(Modifier.matchParentSize()) {
                val radius = size.minDimension / 2f
                val bezel = 10.dp.toPx()
                // Minute ticks: 60 marks, longer every five like a watch bezel.
                for (i in 0 until 60) {
                    val major = i % 5 == 0
                    val angle = Math.toRadians(i * 6.0 - 90.0)
                    val outer = radius - bezel
                    val inner = outer - if (major) 9.dp.toPx() else 4.dp.toPx()
                    drawLine(
                        color = brand.onHero.copy(alpha = if (major) 0.55f else 0.22f),
                        start = center + androidx.compose.ui.geometry.Offset((kotlin.math.cos(angle) * inner).toFloat(), (kotlin.math.sin(angle) * inner).toFloat()),
                        end = center + androidx.compose.ui.geometry.Offset((kotlin.math.cos(angle) * outer).toFloat(), (kotlin.math.sin(angle) * outer).toFloat()),
                        strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                    )
                }
                // Readiness ring (stopped) / full live ring (running).
                val ringWidth = 6.dp.toPx()
                val inset = bezel / 2f + ringWidth / 2f - 2.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(brand.onHero.copy(alpha = 0.14f), -90f, 360f, false, topLeft, arcSize, style = Stroke(ringWidth))
                drawArc(brand.heroAccent, -90f, 360f * ring, false, topLeft, arcSize,
                    style = Stroke(ringWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                // The bezel tip of the radar sweep: the dial's second hand.
                if (isActive) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, brand.onHero.copy(alpha = 0.9f)), center),
                        startAngle = sweepAngle - 90f - 36f, sweepAngle = 36f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(ringWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .widthIn(max = dial * 0.72f)
                    .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isActive) LiveStatusMark(isActive = true)
                else SabbaqProgressCoin(done = readyCount, total = totalCount,
                    modifier = Modifier.semantics { stateDescription = readinessState })
                AnimatedContent(
                    targetState = isActive,
                    transitionSpec = { (fadeIn(tween(SabbaqMotion.MEDIUM)) togetherWith fadeOut(tween(SabbaqMotion.SHORT))) },
                    label = "hero-status",
                ) { active ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (active) stringResource(R.string.ss_hero_active) else stringResource(R.string.ss_hero_stopped),
                            style = MaterialTheme.typography.titleMedium,
                            color = brand.onHero,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            if (active) stringResource(R.string.ss_hero_active_sub)
                            else if (readyCount < totalCount) stringResource(R.string.ss_hero_ready_sub, readyCount, totalCount)
                            else stringResource(R.string.ss_hero_allready_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = brand.onHeroMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
                val toggleContentDescription = if (isActive) stringResource(R.string.ss_stop_cd) else stringResource(R.string.ss_start_cd)
                SabbaqButton(
                    text = if (isActive) stringResource(R.string.ss_stop) else stringResource(R.string.ss_start),
                    onClick = { onToggle(!isActive) },
                    enabled = isActive || canStart,
                    busy = isChecking,
                    busyText = busyText,
                    icon = if (isActive) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    fillWidth = false,
                    modifier = Modifier.semantics {
                        contentDescription = toggleContentDescription
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) brand.onHero.copy(alpha = 0.14f) else brand.signature,
                        contentColor = if (isActive) brand.onHero else brand.onSignature,
                        disabledContainerColor = brand.onHero.copy(alpha = 0.24f),
                        disabledContentColor = brand.onHero,
                    ),
                )
            }
        }
    }
}

/**
 * The dial's own 3D scene: a radar seen from above. Depth rings shade toward the rim, a lit
 * cone sweeps with the second hand, and order blips flare as it passes (the matched one keeps a
 * halo). A soft dark core behind the centre keeps the text readable over the motion.
 */
@Composable
private fun DialRadarFace(sweepAngle: Float, active: Boolean, modifier: Modifier = Modifier) {
    val brand = LocalSabbaqBrand.current
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val accent = brand.heroAccent
        val light = brand.onHero
        val intensity = if (active) 1f else 0.45f

        // Depth: the face is lit at the centre and falls into shadow toward the bezel.
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)), center, radius), radius, center)
        for (ring in 1..4) {
            val k = ring / 4.6f
            drawCircle(accent.copy(alpha = 0.10f * intensity * (1.2f - k)), radius * k, center, style = Stroke(1.dp.toPx()))
        }
        drawLine(accent.copy(alpha = 0.08f * intensity), center.copy(x = center.x - radius * 0.86f), center.copy(x = center.x + radius * 0.86f), strokeWidth = 1.dp.toPx())
        drawLine(accent.copy(alpha = 0.08f * intensity), center.copy(y = center.y - radius * 0.86f), center.copy(y = center.y + radius * 0.86f), strokeWidth = 1.dp.toPx())

        // The sweep cone trailing behind the hand, in canvas angles (0° = 3 o'clock).
        val handAngle = sweepAngle - 90f
        drawArc(
            brush = Brush.sweepGradient(
                0f to Color.Transparent,
                0.85f to Color.Transparent,
                1f to accent.copy(alpha = 0.38f * intensity),
                center = center,
            ),
            startAngle = handAngle - 60f, sweepAngle = 60f, useCenter = true,
            topLeft = center - androidx.compose.ui.geometry.Offset(radius * 0.86f, radius * 0.86f),
            size = androidx.compose.ui.geometry.Size(radius * 1.72f, radius * 1.72f),
        )
        rotate(handAngle + 90f, center) {
            drawLine(
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.9f * intensity), Color.Transparent), startY = center.y - radius * 0.86f, endY = center.y),
                start = center, end = center.copy(y = center.y - radius * 0.86f), strokeWidth = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        // Order blips: brightest right after the sweep passes them, fading over the next turn.
        listOf(Triple(38f, 0.62f, false), Triple(152f, 0.74f, true), Triple(250f, 0.52f, false), Triple(318f, 0.80f, false)).forEach { (angle, dist, matched) ->
            val since = ((sweepAngle - angle) % 360f + 360f) % 360f / 360f
            val glow = (1f - since).let { it * it } * intensity
            val rad = Math.toRadians(angle - 90.0)
            val spot = center + androidx.compose.ui.geometry.Offset(
                (kotlin.math.cos(rad) * radius * dist).toFloat(), (kotlin.math.sin(rad) * radius * dist).toFloat(),
            )
            val color = if (matched) accent else light
            drawCircle(color.copy(alpha = 0.18f + 0.6f * glow), radius = (3.dp.toPx() + 3.dp.toPx() * glow), center = spot)
            if (matched && active) drawCircle(accent.copy(alpha = 0.22f * (0.4f + glow)), radius = 11.dp.toPx(), center = spot, style = Stroke(1.5.dp.toPx()))
        }

        // Readability core behind the centred status, button and text.
        drawCircle(Brush.radialGradient(listOf(brand.heroStart.copy(alpha = 0.78f), Color.Transparent), center, radius * 0.62f), radius * 0.62f, center)
    }
}

@Composable
private fun LiveStatusMark(isActive: Boolean) {
    val brand = LocalSabbaqBrand.current
    val reduced = LocalReducedMotion.current
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (isActive && !reduced) {
            val pulse = rememberInfiniteTransition(label = "live-pulse")
            val phase by pulse.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
                label = "live-pulse-phase",
            )
            Canvas(Modifier.matchParentSize()) {
                listOf(phase, (phase + 0.5f) % 1f).forEach { p ->
                    drawCircle(
                        color = brand.heroAccent.copy(alpha = (1f - p) * 0.45f),
                        radius = size.minDimension / 2f * (0.45f + 0.55f * p),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
        Surface(
            shape = CircleShape,
            color = if (isActive) brand.heroAccent else brand.onHero.copy(alpha = 0.16f),
            contentColor = if (isActive) brand.heroStart else brand.onHero,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isActive) Icons.Outlined.CheckCircle else Icons.Outlined.Stop,
                    contentDescription = if (isActive) stringResource(R.string.ss_hero_active) else stringResource(R.string.ss_hero_stopped),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** One readiness requirement. The whole row is the action, so no extra button rows are needed. */
@Composable
internal fun ReadinessRow(item: ReadinessItem) {
    val tint by animateColorAsState(
        if (item.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        tween(SabbaqMotion.MEDIUM),
        label = "readiness-tint",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = item.actionLabel, role = Role.Button, onClick = item.onAction)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(item.icon, contentDescription = null, tint = tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            Text(if (item.ready) item.readyText else item.missingText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.ready) {
            SabbaqStatusPill(stringResource(R.string.ss_ready_pill), ready = true)
        } else if (item.actionLabel.isNotEmpty()) {
            Button(onClick = item.onAction, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                Text(item.actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The missing permissions, alone and loud: warning colours, why each one matters, and a
 * button that opens the exact system screen. Disappears once everything is granted.
 */
@Composable
private fun MissingPermissionsCard(missing: List<ReadinessItem>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                Text(
                    stringResource(R.string.ss_missing_title, ArabicCount.permissions(missing.size)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Text(stringResource(R.string.ss_missing_text),
                style = MaterialTheme.typography.bodyMedium)
            missing.forEach { item ->
                PermissionAction(item.icon, item.title, item.missingText, item.actionLabel, item.onAction)
            }
        }
    }
}

/** One permission: what it is, why, and the button that grants it. */
@Composable
private fun PermissionAction(icon: ImageVector, title: String, why: String, action: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SabbaqIconBadge(icon, destructive = true)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 40.dp)) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun FiltersPage(
    draft: FilterDraft,
    validationErrors: Map<String, String>,
    dirty: Boolean,
    isSaving: Boolean,
    message: DriverStatus?,
    onDraftChange: (FilterDraft) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    onReset: () -> Unit,
) = DriverPage {
    SabbaqSectionTitle(
        title = stringResource(R.string.ss_filters_title),
        subtitle = if (isSaving) stringResource(R.string.ss_filters_saving) else stringResource(R.string.ss_filters_subtitle),
    )
    SabbaqAnimatedMessage(message?.message, message?.tone ?: SabbaqTone.Info)
    SabbaqAnimatedMessage(
        if (validationErrors.isNotEmpty()) stringResource(R.string.ss_filters_fix_marked) else null,
        SabbaqTone.Error,
    )
    AdvancedFiltersEditor(
        draft = draft,
        errors = validationErrors,
        onDraftChange = onDraftChange,
    )
    SabbaqCard(title = stringResource(R.string.ss_instant_save_title), subtitle = stringResource(R.string.ss_instant_save_sub), icon = Icons.Outlined.Autorenew) {
        if (isSaving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.ss_saving_last), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dirty && !isSaving && message?.isError == true) {
            SabbaqButton(
                text = stringResource(R.string.ss_retry_save),
                onClick = onSave,
                enabled = validationErrors.isEmpty(),
                icon = Icons.Outlined.Refresh,
            )
        }
        AnimatedVisibility(dirty && !isSaving && validationErrors.isEmpty()) {
            SabbaqButton(text = stringResource(R.string.ss_restore_saved_conditions), onClick = onRevert, style = SabbaqButtonStyle.Secondary)
        }
        SabbaqButton(
            text = stringResource(R.string.ss_restore_defaults),
            onClick = onReset,
            enabled = !isSaving,
            style = SabbaqButtonStyle.Text,
            icon = Icons.Outlined.Refresh,
        )
    }
}

/** Tries a made-up offer against the saved conditions with the same decision the service uses. */
@Composable
private fun SimulatorPage(savedFilters: EngineConfig) {
    var fare by rememberSaveable { mutableStateOf("") }
    var hasDestination by rememberSaveable { mutableStateOf(true) }
    var pickupMinutes by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<PreviewResult?>(null) }
    val hasInput = listOf(fare, pickupMinutes, destination).any { it.isNotBlank() } || !hasDestination
    val invalidNumberMsg = stringResource(R.string.ss_sim_invalid_number)

    fun evaluate() {
        val fareValue = parsePreviewDecimal(fare)
        val minutesValue = parsePreviewMinutes(pickupMinutes)
        if ((fare.isNotBlank() && fareValue == null) || (pickupMinutes.isNotBlank() && minutesValue == null)) {
            result = PreviewResult(null, invalidNumberMsg)
            return
        }
        val dest = destination.trim().takeIf { hasDestination && it.isNotEmpty() }
        val ride = ParsedRide(
            price = fareValue?.toDouble(), minutes = minutesValue, destination = dest,
            addressAmbiguous = dest == null, addresses = listOfNotNull(dest),
        )
        result = PreviewResult(DecisionEngine.decide(ride, savedFilters,
            zones = app.sabbaq.core.engine.zones.ZoneEngine(app.sabbaq.service.zones.ZoneStore)))
    }

    fun clearAll() {
        fare = ""; hasDestination = true; pickupMinutes = ""; destination = ""
        result = null
    }

    DriverPage {
        SabbaqSectionTitle(stringResource(R.string.ss_sim_title), stringResource(R.string.ss_sim_subtitle))
        SabbaqMessage(stringResource(R.string.ss_sim_note))
        SabbaqCard(title = stringResource(R.string.ss_sim_card_title), subtitle = stringResource(R.string.ss_sim_card_sub), icon = Icons.Outlined.Visibility) {
            PreviewField(fare, stringResource(R.string.ss_sim_fare), KeyboardType.Decimal) { fare = it; result = null }
            PreviewField(pickupMinutes, stringResource(R.string.ss_sim_pickup_min), KeyboardType.Number, unit = stringResource(R.string.ss_unit_min)) { pickupMinutes = it; result = null }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ss_sim_has_destination), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.ss_sim_has_destination_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = hasDestination, onCheckedChange = { hasDestination = it; result = null })
            }
            AnimatedVisibility(hasDestination) {
                PreviewField(destination, stringResource(R.string.ss_sim_destination), KeyboardType.Text) { destination = it; result = null }
            }
            SabbaqButton(text = stringResource(R.string.ss_sim_check), onClick = ::evaluate, enabled = hasInput, icon = Icons.Outlined.Visibility)
            AnimatedVisibility(hasInput) {
                SabbaqButton(text = stringResource(R.string.ss_sim_clear), onClick = ::clearAll, style = SabbaqButtonStyle.Text, icon = Icons.Outlined.Refresh)
            }
        }
        AnimatedContent(
            targetState = result,
            transitionSpec = { fadeIn(tween(SabbaqMotion.MEDIUM)) togetherWith fadeOut(tween(SabbaqMotion.SHORT)) },
            label = "preview-verdict",
        ) { preview ->
            // Each new result turns over into view like a card, then leans with the finger.
            if (preview != null) key(preview) { Box(Modifier.sabbaqFlipIn().tilt3D(maxDegrees = 6f, idle = false)) { PreviewVerdictCard(preview) } }
        }
    }
}

@Composable
private fun PreviewField(
    value: String,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth(),
    unit: String? = null,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(2048)) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = unit?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = modifier,
    )
}

@Composable
private fun PreviewVerdictCard(result: PreviewResult) {
    val decision = result.decision
    if (result.error != null || decision == null) {
        SabbaqMessage(result.error.orEmpty(), tone = SabbaqTone.Error)
        return
    }
    SabbaqCard(
        title = if (decision.accept) stringResource(R.string.ss_verdict_match) else stringResource(R.string.ss_verdict_nomatch),
        subtitle = if (decision.accept) stringResource(R.string.ss_verdict_match_sub) else stringResource(R.string.ss_verdict_nomatch_sub),
        icon = if (decision.accept) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
    ) {
        SabbaqMessage(
            if (decision.accept) stringResource(R.string.ss_verdict_match_msg) else reasonLabel(decision.reason.orEmpty()),
            tone = if (decision.accept) SabbaqTone.Success else SabbaqTone.Error,
        )
    }
}

@Composable
private fun AccountPage(
    licenseInMemory: Boolean,
    message: DriverStatus?,
    onLicenseChanged: (Boolean) -> Unit,
    onMessage: (DriverStatus?) -> Unit,
    onAccountDeleted: () -> Unit,
    onOpenSupport: () -> Unit,
    onSignOut: () -> Unit,
) {
    SubscriptionScreen(
        licenseInMemory = licenseInMemory,
        externalMessage = message?.message,
        externalMessageIsError = message?.isError == true,
        onLicenseChanged = onLicenseChanged,
        onMessage = { nextMessage, isError ->
            onMessage(nextMessage?.let { DriverStatus(it, isError) })
        },
        onAccountDeleted = onAccountDeleted,
        onOpenSupport = onOpenSupport,
        onSignOut = onSignOut,
    )
}

internal fun parsePreviewDecimal(raw: String): BigDecimal? {
    if (raw.isBlank() || raw.length > 32) return null
    val normalized = app.sabbaq.core.Text.normalize(raw)
    if (!Regex("(?:${app.sabbaq.core.Text.NUMBER})").matches(normalized)) return null
    val parsed = app.sabbaq.core.Text.parseNumber(normalized) ?: return null
    return parsed.takeIf { it.signum() >= 0 }
}

internal fun parsePreviewMinutes(raw: String): Int? {
    if (raw.isBlank() || raw.length > 10) return null
    val normalized = app.sabbaq.core.Text.normalize(raw)
    if (!Regex("\\d+").matches(normalized)) return null
    return normalized.toIntOrNull()?.takeIf { it >= 0 }
}


private fun Context.isSabbaqAccessibilityEnabled(): Boolean {
    val expectedId = ComponentName(this, SabbaqA11yService::class.java).flattenToString()
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == expectedId }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private const val MAX_BACK_STACK = 12

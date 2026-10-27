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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Autorenew
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sabbaq.BuildConfig
import app.sabbaq.Settings
import app.sabbaq.ThemeMode
import app.sabbaq.core.Filters
import app.sabbaq.core.Offer
import app.sabbaq.core.OfferEvaluator
import app.sabbaq.core.ReasonCode
import app.sabbaq.core.Verdict
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

/** وجهات لوحة السائق؛ يمكن استدعاء الشاشة مباشرة إلى أي منها في معاينات/اختبارات الواجهة. */
enum class DriverDestination { Dashboard, Filters, Simulator, Account, History, Setup, Settings }

private data class DriverStatus(val message: String, val isError: Boolean = false, val success: Boolean = false) {
    val tone: SabbaqTone get() = when {
        isError -> SabbaqTone.Error
        success -> SabbaqTone.Success
        else -> SabbaqTone.Info
    }
}
private data class PreviewResult(val verdict: Verdict, val error: String? = null)
private data class DriverNavItem(
    val destination: DriverDestination,
    val label: String,
    val icon: ImageVector,
)

private val driverDestinations = listOf(
    DriverNavItem(DriverDestination.Dashboard, "حالة العمل", Icons.Outlined.Tune),
    DriverNavItem(DriverDestination.Filters, "طلبات تناسبك", Icons.Outlined.FilterAlt),
    DriverNavItem(DriverDestination.Settings, "الإعدادات", Icons.Outlined.SettingsIcon),
    DriverNavItem(DriverDestination.Account, "حسابي", Icons.Outlined.AccountCircle),
    DriverNavItem(DriverDestination.Simulator, "تجربة الطلب", Icons.Outlined.Visibility),
    DriverNavItem(DriverDestination.History, "سجل القرارات", Icons.Outlined.Info),
)

// Four tabs on phones; «تجربة الطلب» and «سجل القرارات» stay one tap away in the drawer and
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
    var savedFilters by remember { mutableStateOf(Settings.filters) }
    val draftSaver = remember {
        Saver<FilterDraft, String>(
            save = { Json.encodeToString(it) },
            restore = { runCatching { Json.decodeFromString<FilterDraft>(it) }.getOrNull() }
        )
    }
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(FilterDraft.from(savedFilters)) }
    val draftValidation = remember(draft) { draft.validate() }
    val filtersDirty = remember(draft, savedFilters) { draft.isDirtyComparedTo(savedFilters) }
    var filtersMessage by remember { mutableStateOf<DriverStatus?>(null) }
    var runtimeMessage by remember { mutableStateOf<DriverStatus?>(null) }
    var accountMessage by remember { mutableStateOf<DriverStatus?>(null) }
    var isSavingFilters by remember { mutableStateOf(false) }
    var isActive by remember { mutableStateOf(Settings.isActive) }
    var accessibilityEnabled by remember(context) { mutableStateOf(context.isSabbaqAccessibilityEnabled()) }
    var notificationsEnabled by remember(context) { mutableStateOf(context.hasNotificationPermission()) }
    var batteryUnrestricted by remember(context) { mutableStateOf(PowerSetup.isBatteryUnrestricted(context)) }
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
                "تم إيقاف متابعة الطلبات، لكن لم نتمكن من تأكيد الإيقاف بالكامل. أعد فتح التطبيق إذا استمرت المتابعة.",
                isError = true,
            )
            message != null -> DriverStatus(message)
            else -> DriverStatus("تم إيقاف متابعة الطلبات. عدّل شروطك أو ابدأ مجددًا عندما تكون جاهزًا.")
        }
    }

    fun refreshRuntimePrerequisites() {
        val a11y = context.isSabbaqAccessibilityEnabled()
        val notifications = context.hasNotificationPermission()
        val licensed = SupabaseClient.hasValidLicense() // لقطة ذاكرية فقط؛ لا شبكة في النبض الدوري.
        accessibilityEnabled = a11y
        notificationsEnabled = notifications
        batteryUnrestricted = PowerSetup.isBatteryUnrestricted(context)
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
            stopKeeper("توقفت متابعة الطلبات لأن إذن الإشعارات لم يعد مفعّلًا.")
        } else if (persistedActive && !a11y) {
            runtimeMessage = DriverStatus("إذن إمكانية الوصول مغلق؛ لن يُقبل أي طلب حتى تعيد تفعيله، وتُستأنف المتابعة تلقائيًا بعدها.", isError = true)
        } else if (persistedActive && !licensed) {
            runtimeMessage = DriverStatus("نتحقق من اشتراكك؛ تُستأنف المتابعة تلقائيًا عند عودة الاتصال.", isError = true)
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
    fun updateDraft(next: FilterDraft, stopIfActive: Boolean = true) {
        if (next === draft) return
        if (isActive && stopIfActive) {
            stopKeeper("أوقفنا متابعة الطلبات قبل تعديل شروطك؛ سيُحفظ التغيير تلقائيًا.")
        }
        draft = next
        filtersMessage = null
        val revision = filterRevision + 1
        filterRevision = revision
        val validation = next.validate()
        val filters = validation.filters
        if (filters == null) {
            isSavingFilters = false
            filtersMessage = DriverStatus("راجع القيم المعلّمة؛ لا تُحفظ الفلاتر غير الصالحة.", isError = true)
            return
        }
        isSavingFilters = true
        filtersMessage = DriverStatus("جارٍ حفظ التعديلات تلقائيًا.")
        scope.launch {
            delay(180)
            filterSaveMutex.withLock {
                if (revision != filterRevision) return@withLock
                try {
                    val saved = Settings.saveFilters(filters)
                    if (revision != filterRevision) {
                        // A later invalid edit has no save job of its own. Restore the last
                        // durable filters so an older in-flight commit cannot replace them.
                        if (saved && draft.validate().filters == null && !Settings.saveFilters(savedFilters)) {
                            filtersMessage = DriverStatus("تعذر استرجاع الشروط المحفوظة بعد تعديل غير صالح.", isError = true)
                        }
                        return@withLock
                    }
                    if (saved) {
                        savedFilters = filters
                        draft = FilterDraft.from(filters)
                        filtersMessage = DriverStatus("حُفظت التعديلات تلقائيًا.", success = true)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (returnToSetupAfterAction && destination == DriverDestination.Filters) {
                            returnToSetupAfterAction = false
                            destination = DriverDestination.Setup
                        }
                    } else {
                        filtersMessage = DriverStatus("تعذر حفظ التعديلات على الجهاز؛ ما زالت الشروط السابقة مستخدمة. أعد المحاولة.", isError = true)
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (revision == filterRevision) {
                        filtersMessage = DriverStatus("تعذر حفظ التعديلات على الجهاز؛ ما زالت الشروط السابقة مستخدمة. أعد المحاولة.", isError = true)
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
        destination = next
    }

    fun navigateFromSetup(next: DriverDestination) {
        returnToSetupAfterAction = true
        navigateTo(next)
    }

    fun requestActivation() {
        if (checkingActivation || isSavingFilters || isActive) return
        if (isRestoringSession || sessionRestoreError) {
            runtimeMessage = DriverStatus(
                if (isRestoringSession) "جارٍ استعادة جلستك المحفوظة. ستتمكن من بدء المتابعة بعد اكتمال التحقق."
                else "تعذر تحديث الجلسة؛ أعد المحاولة من إشعار الاستعادة قبل بدء المتابعة.",
                isError = sessionRestoreError,
            )
            return
        }
        if (filtersDirty) {
            runtimeMessage = DriverStatus("تُحفظ التعديلات تلقائيًا؛ انتظر اكتمال الحفظ أو صحح القيم المعلّمة.", isError = true)
            return
        }
        if (!draftValidation.isValid || UiFormValidation.filtersError(savedFilters) != null) {
            runtimeMessage = DriverStatus("صحح شروط الطلبات واحفظها قبل بدء العمل.", isError = true)
            return
        }
        if (!savedFilters.anyActive()) {
            runtimeMessage = DriverStatus("أضف شرطًا واحدًا محفوظًا على الأقل لاختيار الطلبات قبل بدء العمل.", isError = true)
            return
        }
        refreshRuntimePrerequisites()
        // Everything that blocks the start is listed together, not one prompt at a time.
        if (!accessibilityEnabled || !notificationsEnabled) {
            showMissingPermissions = true
            return
        }
        checkingActivation = true
        runtimeMessage = null
        scope.launch {
            try {
                // هذا هو موضع الفحص الشبكي الصريح الوحيد في مسار بدء التشغيل.
                val verified = SupabaseClient.revalidateLicense().getOrNull() == true && SupabaseClient.hasValidLicense()
                licenseInMemory = SupabaseClient.hasValidLicense()
                if (verified) {
                    showActivationConsent = true
                } else {
                    runtimeMessage = DriverStatus(
                        "لم نتمكن من التحقق من اشتراكك. جدّده أو سجّل الدخول مجددًا ثم حاول مرة أخرى.",
                        isError = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runtimeMessage = DriverStatus("لم نتمكن من التحقق من اشتراكك. تحقق من اتصال الإنترنت ثم حاول مرة أخرى.", isError = true)
            } finally {
                checkingActivation = false
            }
        }
    }

    fun confirmActivation() {
        showActivationConsent = false
        refreshRuntimePrerequisites()
        if (filtersDirty || isSavingFilters || !savedFilters.anyActive() ||
            !SupabaseClient.hasValidLicense() || !accessibilityEnabled || !notificationsEnabled) {
            Settings.isActive = false
            isActive = false
            runtimeMessage = DriverStatus("تغيّرت متطلبات بدء العمل؛ راجعها ثم حاول مرة أخرى.", isError = true)
            return
        }
        val result = latestToggleKeeper(true)
        if (result.isFailure) {
            Settings.isActive = false
            isActive = false
            runtimeMessage = DriverStatus("لم نتمكن من بدء متابعة الطلبات. راجع أذونات الجهاز ثم حاول مرة أخرى.", isError = true)
        } else {
            isActive = true
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            runtimeMessage = DriverStatus("متابعة الطلبات تعمل وفق شروطك المحفوظة وأوقات عملك. يمكنك إيقافها في أي وقت.", success = true)
        }
    }

    BackHandler(enabled = destination == DriverDestination.Filters || destination == DriverDestination.History ||
        destination == DriverDestination.Setup || destination == DriverDestination.Account ||
        destination == DriverDestination.Settings || destination == DriverDestination.Simulator) {
        if (returnToSetupAfterAction) {
            returnToSetupAfterAction = false
            navigateTo(DriverDestination.Setup)
        } else {
            navigateTo(DriverDestination.Dashboard)
        }
    }

    if (showAccessibilityRequired) {
        AlertDialog(
            onDismissRequest = { showAccessibilityRequired = false },
            title = { Text("فعّل إذن متابعة الطلبات") },
            text = { Text("يحتاج سبّاق إلى إذن إمكانية الوصول لمتابعة تفاصيل الطلبات الظاهرة والنقر على الطلب المطابق لشروطك. فعّله من إعدادات الجهاز، ويمكنك إيقاف المتابعة في أي وقت.") },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityRequired = false
                    if (onOpenA11y().isFailure) {
                        runtimeMessage = DriverStatus("لم نتمكن من فتح إعدادات إمكانية الوصول. افتحها يدويًا من إعدادات الجهاز.", true)
                    }
                }) { Text("فتح إعدادات الجهاز") }
            },
            dismissButton = { TextButton(onClick = { showAccessibilityRequired = false }) { Text("إلغاء") } },
        )
    }

    if (showMissingPermissions) {
        AlertDialog(
            onDismissRequest = { showMissingPermissions = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("قبل البدء: أذونات مطلوبة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("يحتاج سبّاق إلى هذه الأذونات ليعمل. امنحها من هنا، ثم اضغط «ابدأ» مجددًا.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (!accessibilityEnabled) PermissionAction(Icons.Outlined.AccessibilityNew, "إذن متابعة الطلبات",
                        "ليقرأ الطلب الظاهر ويقارنه بشروطك", "تفعيل") {
                        showMissingPermissions = false
                        if (onOpenA11y().isFailure) {
                            runtimeMessage = DriverStatus("لم نتمكن من فتح إعدادات إمكانية الوصول. افتحها يدويًا من إعدادات الجهاز.", true)
                        }
                    }
                    if (!notificationsEnabled) PermissionAction(Icons.Outlined.Notifications, "التنبيهات",
                        "ليظهر لك أن المتابعة تعمل", "السماح") {
                        showMissingPermissions = false
                        onRequestNotificationPermission()
                    }
                    if (!batteryUnrestricted) PermissionAction(Icons.Outlined.BatteryChargingFull, "العمل دون قيود البطارية",
                        "حتى لا يوقفه أندرويد عند قفل الشاشة (موصى به)", "السماح") {
                        runCatching { context.startActivity(PowerSetup.batteryIntent(context)) }
                            .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMissingPermissions = false }) { Text("تم") } },
        )
    }

    if (showNotificationRequired) {
        AlertDialog(
            onDismissRequest = { showNotificationRequired = false },
            title = { Text("يلزم إذن الإشعارات") },
            text = { Text("يحتاج سبّاق إلى الإشعارات ليُظهر لك بوضوح أن متابعة الطلبات تعمل.") },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationRequired = false
                    onRequestNotificationPermission()
                }) { Text("السماح بالإشعارات") }
            },
            dismissButton = { TextButton(onClick = { showNotificationRequired = false }) { Text("إلغاء") } },
        )
    }

    if (showActivationConsent) {
        AlertDialog(
            onDismissRequest = { showActivationConsent = false },
            title = { Text("بدء متابعة الطلبات") },
            text = {
                Text(
                    "عند البدء، يقرأ سبّاق تفاصيل الطلبات الظاهرة وفق شروطك المحفوظة، ويمكنه النقر تلقائيًا على طلب يطابقها. " +
                        "يمكنك إيقاف المتابعة في أي وقت."
                )
            },
            confirmButton = { TextButton(onClick = ::confirmActivation) { Text("موافق، ابدأ المتابعة") } },
            dismissButton = { TextButton(onClick = { showActivationConsent = false }) { Text("إلغاء") } },
        )
    }

    if (showResetFilters) {
        AlertDialog(
            onDismissRequest = { showResetFilters = false },
            title = { Text("استعادة الشروط الافتراضية؟") },
            text = { Text("سيُعاد ضبط جميع الشروط المحفوظة فورًا، وستتوقف المتابعة أولًا إذا كانت تعمل. لا يمكن التراجع عن هذا إلا بإدخال الشروط من جديد.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetFilters = false
                    updateDraft(FilterDraft.from(Filters()))
                }) { Text("إعادة الضبط") }
            },
            dismissButton = { TextButton(onClick = { showResetFilters = false }) { Text("إلغاء") } },
        )
    }

    val missingCount = listOf(
        savedFilters.anyActive(), accessibilityEnabled, notificationsEnabled, licenseInMemory, batteryUnrestricted, autostartDone,
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
                activeFilterCount = activeFilterCount(savedFilters),
                message = runtimeMessage,
                onToggle = { enabled -> if (enabled) requestActivation() else stopKeeper() },
                onOpenA11y = {
                    if (onOpenA11y().isFailure) {
                        runtimeMessage = DriverStatus("تعذر فتح إعدادات إمكانية الوصول. افتحها يدويًا من إعدادات الجهاز.", true)
                    }
                },
                onRequestNotifications = onRequestNotificationPermission,
                onOpenFilters = { navigateTo(DriverDestination.Filters) },
                onOpenAccount = { navigateTo(DriverDestination.Account) },
                onOpenSetupGuide = { navigateTo(DriverDestination.Setup) },
                batteryUnrestricted = batteryUnrestricted,
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
                            title = "تأكد من اشتراكك",
                            why = "ابدأ تجربتك أو فعّل اشتراكك من صفحة حسابي.",
                            doneText = "اشتراكك جاهز للعمل",
                            actionLabel = "فتح حسابي",
                            done = licenseInMemory,
                            onAction = { navigateFromSetup(DriverDestination.Account) },
                        ),
                        SetupStep(
                            icon = Icons.Outlined.AccessibilityNew,
                            title = "اسمح لسبّاق بمتابعة الطلبات",
                            why = "يقرأ سبّاق شاشة الطلب في التطبيقات المدعومة فقط ليقارنها بشروطك، ويعالجها على جهازك. سنشرح لك التفاصيل قبل فتح الإعدادات.",
                            doneText = "الإذن مفعّل",
                            actionLabel = "تفعيل الإذن",
                            done = accessibilityEnabled,
                            onAction = {
                                if (onOpenA11y().isFailure) {
                                    runtimeMessage = DriverStatus("تعذر فتح إعدادات إمكانية الوصول. افتحها يدويًا من إعدادات الجهاز.", true)
                                }
                            },
                        ),
                        SetupStep(
                            icon = Icons.Outlined.Notifications,
                            title = "فعّل تنبيهات حالة العمل",
                            why = "تنبيه ثابت يُظهر لك أن المتابعة تعمل، فتعرف دائمًا متى يعمل سبّاق ومتى يتوقف.",
                            doneText = "التنبيهات مفعّلة",
                            actionLabel = "السماح بالتنبيهات",
                            done = notificationsEnabled,
                            onAction = onRequestNotificationPermission,
                        ),
                        SetupStep(
                            icon = Icons.Outlined.BatteryChargingFull,
                            title = "اسمح له بالعمل دون قيود البطارية",
                            why = "بدون هذا قد يوقف أندرويد سبّاق عند قفل الشاشة أو بعد فترة. يستهلك سبّاق القليل من البطارية لأنه ينتظر فقط ظهور الطلبات.",
                            doneText = "لن يوقفه توفير الطاقة",
                            actionLabel = "السماح",
                            done = batteryUnrestricted,
                            onAction = {
                                runCatching { context.startActivity(PowerSetup.batteryIntent(context)) }
                                    .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                            },
                        ),
                    ) + listOfNotNull(
                        if (PowerSetup.needsAutostart()) SetupStep(
                            icon = Icons.Outlined.RestartAlt,
                            title = "فعّل التشغيل التلقائي في جوالك",
                            why = "جوالك يمنع التطبيقات من العودة بعد إعادة التشغيل ما لم تفعّل لها «التشغيل التلقائي». سنفتح لك الإعداد مباشرة.",
                            doneText = "مفعّل لسبّاق",
                            actionLabel = "فتح الإعداد",
                            done = autostartDone,
                            onAction = {
                                runCatching { context.startActivity(PowerSetup.autostartIntent(context)) }
                                Settings.autostartConfirmed = true
                                autostartDone = true
                            },
                        ) else null
                    ) + listOf(
                        SetupStep(
                            icon = Icons.Outlined.FilterAlt,
                            title = "اختر الطلبات التي تناسبك",
                            why = "الآن بعد تجهيز التطبيق، اختر قالبًا أو اضبط الأجرة والمسافة والوقت؛ كل تغيير صالح يُحفظ تلقائيًا فورًا.",
                            doneText = "محفوظ لديك: ${ArabicCount.conditions(activeFilterCount(savedFilters))}",
                            actionLabel = "ضبط الشروط",
                            done = savedFilters.anyActive(),
                            onAction = { navigateFromSetup(DriverDestination.Filters) },
                            extra = {
                                FilterPresetChips(draft = draft, enabled = true, onDraftChange = ::updateDraft)
                                SabbaqAnimatedMessage(filtersMessage?.message, filtersMessage?.tone ?: SabbaqTone.Info)
                            },
                        ),
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

            DriverDestination.Settings -> DriverPage {
                val openBattery = {
                    runCatching { context.startActivity(PowerSetup.batteryIntent(context)) }
                        .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
                    Unit
                }
                val openA11y = {
                    if (onOpenA11y().isFailure) {
                        runtimeMessage = DriverStatus("تعذر فتح إعدادات إمكانية الوصول. افتحها يدويًا من إعدادات الجهاز.", true)
                    }
                }
                SettingsHubPage(
                    permissions = listOf(
                        ReadinessItem(Icons.Outlined.AccessibilityNew, "إذن متابعة الطلبات", accessibilityEnabled,
                            "مفعّل", "ليقرأ الطلب ويقبل المطابق؛ بدونه لا يعمل", "تفعيل الإذن", openA11y),
                        ReadinessItem(Icons.Outlined.Notifications, "تنبيهات حالة العمل", notificationsEnabled,
                            "مفعّلة", "ليظهر لك دائمًا أن المتابعة تعمل", "السماح", onRequestNotificationPermission),
                        ReadinessItem(Icons.Outlined.VerifiedUser, "حالة الاشتراك", licenseInMemory,
                            "جاهز للعمل", "ابدأ تجربتك أو فعّل اشتراكك", "فتح حسابي") { navigateTo(DriverDestination.Account) },
                    ),
                    power = buildList {
                        add(ReadinessItem(Icons.Outlined.BatteryChargingFull, "العمل دون قيود البطارية", batteryUnrestricted,
                            "لن يوقفه توفير الطاقة", "قد يوقفه أندرويد عند قفل الشاشة", "السماح", openBattery))
                        if (PowerSetup.needsAutostart()) add(ReadinessItem(Icons.Outlined.RestartAlt, "التشغيل التلقائي في جوالك", autostartDone,
                            "مفعّل لسبّاق", "ليعود بعد إعادة تشغيل الجوال؛ فعّل «التشغيل التلقائي»", "فتح الإعداد") {
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
                OfferDecisionLogScreen(onBack = { navigateTo(DriverDestination.Dashboard) })
            }

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
                Icon(item.icon, contentDescription = "${item.label}، ينقصه $dashboardBadge", modifier = Modifier.flipWhenSelected(selected))
            }
        } else {
            Icon(item.icon, contentDescription = item.label, modifier = Modifier.flipWhenSelected(selected))
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
                                    item.label,
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
                    NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                        bottomDriverDestinations.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item.destination,
                                onClick = { onDestinationSelected(item.destination) },
                                icon = { navIcon(item) },
                                label = { Text(item.label) },
                            )
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
        java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale("ar"))
            .withZone(java.time.ZoneId.systemDefault()).format(it)
    }
    val title = when (state) {
        LicenseState.ACTIVE -> "مشترك في سبّاق Pro"
        LicenseState.TRIAL -> "فترة تجريبية مجانية"
        LicenseState.PENDING -> "الاشتراك بانتظار التفعيل"
        LicenseState.EXPIRED -> "انتهى اشتراكك"
        LicenseState.DEVICE_MISMATCH -> "الاشتراك مرتبط بجهاز آخر"
        LicenseState.BANNED, LicenseState.REJECTED -> "الاشتراك غير متاح"
        LicenseState.UNREGISTERED, null -> "حالة الاشتراك غير مؤكدة"
    }
    val detail = when {
        (isPro || isTrial) && remainingDays == 0L -> "ينتهي خلال أقل من يوم — جدّد الآن"
        (isPro || isTrial) && remainingDays != null -> "متبقٍ ${ArabicCount.days(remainingDays.toInt())} · حتى $endDate"
        isPro || isTrial -> "متابعة الطلبات متاحة"
        state == LicenseState.PENDING -> "سنفعّل الوصول بعد اكتمال التحقق"
        state == null -> "حدّث حالة حسابك من «حسابي»"
        else -> "افتح «حسابي» للتجديد أو التفاصيل"
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = "فتح حسابي", role = Role.Button, onClick = onOpenAccount)
            .semantics(mergeDescendants = true) { stateDescription = "$title، $detail" },
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
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                endingSoon && isPro -> brand.heroAccent
                                endingSoon -> colors.error
                                else -> content.copy(alpha = 0.85f)
                            },
                            maxLines = 1,
                        )
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null, tint = content.copy(alpha = 0.8f))
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
        isTrial -> Triple("تجربة", Brush.linearGradient(listOf(colors.primary, colors.primary)), colors.onPrimary)
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
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
    onOpenAccount: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    batteryUnrestricted: Boolean = true,
    autostartNeeded: Boolean = false,
    autostartDone: Boolean = true,
    onOpenBattery: () -> Unit = {},
    onOpenAutostart: () -> Unit = {},
) = DriverPage {
    val readiness = listOf(
        ReadinessItem(Icons.Outlined.FilterAlt, "شروط الطلبات", activeFilterCount > 0,
            "محفوظ لديك: ${ArabicCount.conditions(activeFilterCount)}", "حتى يعرف أي الطلبات تناسبك", "تعديل الشروط", onOpenFilters),
        ReadinessItem(Icons.Outlined.AccessibilityNew, "إذن متابعة الطلبات", accessibilityEnabled,
            "مفعّل", "ليقرأ الطلب ويقبل المطابق؛ بدونه لا يعمل", "تفعيل الإذن", onOpenA11y),
        ReadinessItem(Icons.Outlined.Notifications, "تنبيهات حالة العمل", notificationsEnabled,
            "مفعّلة", "ليظهر لك دائمًا أن المتابعة تعمل", "السماح", onRequestNotifications),
        ReadinessItem(Icons.Outlined.VerifiedUser, "حالة الاشتراك", licenseInMemory,
            "جاهز للعمل", "ابدأ تجربتك أو فعّل اشتراكك", "فتح حسابي", onOpenAccount),
    )
    // What keeps it running when the screen locks, the phone sleeps or restarts.
    val power = buildList {
        add(ReadinessItem(Icons.Outlined.BatteryChargingFull, "العمل دون قيود البطارية", batteryUnrestricted,
            "لن يوقفه توفير الطاقة", "قد يوقفه أندرويد عند قفل الشاشة", "السماح", onOpenBattery))
        if (autostartNeeded) add(ReadinessItem(Icons.Outlined.RestartAlt, "التشغيل التلقائي في جوالك", autostartDone,
            "مفعّل لسبّاق", "ليعود بعد إعادة تشغيل الجوال؛ فعّل «التشغيل التلقائي»", "فتح الإعداد", onOpenAutostart))
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
    OperationHero(
        isActive = isActive,
        isChecking = isCheckingActivation || isRestoringSession,
        readyCount = allReady,
        totalCount = allCount,
        onToggle = onToggle,
        canStart = !isRestoringSession && !sessionRestoreError,
        busyText = if (isRestoringSession) "جارٍ استعادة الجلسة" else "جارٍ التحقق من الاشتراك",
        modifier = Modifier.sabbaqEntrance(1),
    )
    ActiveConditionsCockpit(
        draft = draft,
        errors = draftErrors,
        activeCount = activeFilterCount,
        isActive = isActive,
        saveMessage = filtersMessage,
        onDraftChange = onQuickDraftChange,
        onOpenFilters = onOpenFilters,
        modifier = Modifier.sabbaqEntrance(2),
    )
    // «حالة التشغيل الفعلية» lives in «سجل القرارات» next to the statistics.
    TelemetryDiagnosticsCard(Modifier.sabbaqEntrance(1))
    val missing = (readiness + power).filterNot { it.ready }
    if (missing.isNotEmpty()) MissingPermissionsCard(missing, Modifier.sabbaqEntrance(1))
    SabbaqAnimatedMessage(message?.message, message?.tone ?: SabbaqTone.Info)

    SabbaqCard(
        title = if (readyCount == readiness.size) "كل شيء جاهز" else "قبل بدء العمل",
        subtitle = if (readyCount == readiness.size) "يمكنك بدء المتابعة في أي وقت."
            else "تبقّت ${ArabicCount.steps(readiness.size - readyCount)} قبل البدء.",
        icon = Icons.Outlined.VerifiedUser,
        modifier = Modifier.sabbaqEntrance(1),
    ) {
        val progress by animateFloatAsState(
            targetValue = readyCount.toFloat() / readiness.size,
            animationSpec = tween(SabbaqMotion.LONG, easing = SabbaqMotion.Emphasized),
            label = "readiness-progress",
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$readyCount من ${readiness.size} جاهزة" },
        )
        readiness.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReadinessRow(item)
        }
        SabbaqButton(
            if (readyCount < readiness.size) "أكمل الإعداد خطوة بخطوة" else "دليل الإعداد",
            onClick = onOpenSetupGuide,
            style = if (readyCount < readiness.size) SabbaqButtonStyle.Secondary else SabbaqButtonStyle.Text,
            icon = Icons.Outlined.Tune,
        )
    }

    val powerReady = power.all { it.ready }
    SabbaqCard(
        title = if (powerReady) "يعمل بكامل طاقته" else "ليعمل بكامل طاقته",
        subtitle = if (powerReady) "يستمر مع قفل الشاشة، ويعود تلقائيًا بعد إعادة تشغيل الجوال."
            else "حتى لا يوقفه أندرويد أثناء قفل الشاشة أو بعد إعادة التشغيل.",
        icon = Icons.Outlined.Bolt,
        modifier = Modifier.sabbaqEntrance(2),
    ) {
        power.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReadinessRow(item)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReadinessRow(ReadinessItem(Icons.Outlined.Autorenew, "الاستئناف بعد إعادة التشغيل", true,
            "تلقائي إذا تركت المتابعة تعمل", "", "", {}))
    }

    // Voice alerts live in «الإعدادات ← الصوت والتنبيهات»; statistics live in «سجل القرارات».
    SupportedPlatformsCard(Modifier.sabbaqEntrance(5))

    SabbaqCard(title = "أثناء وقت العمل", icon = Icons.Outlined.Info, modifier = Modifier.sabbaqEntrance(6)) {
        Text(
            "يتابع سبّاق الطلبات الظاهرة وفق سعر الطلب والعائد لكل كم ومسافة الوصول وأوقات العمل التي حددتها. " +
                "عند ظهور طلب مطابق، يمكنه النقر عليه تلقائيًا. يمكنك إيقاف المتابعة في أي وقت.",
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
        status.stale -> "لا نشاط حديث؛ افتح تطبيق النقل وتأكد أنه متصل"
        status.active -> "تعمل وتراقب الطلبات"
        else -> "متوقفة"
    }
    SabbaqCard(title = "حالة التشغيل الفعلية", subtitle = state, icon = Icons.Outlined.Autorenew, modifier = modifier) {
        Text("آخر نشاط من تطبيق النقل: ${RuntimeLabels.relativeTime(status.lastEventMs, now)}", style = MaterialTheme.typography.bodySmall)
        Text("آخر طلب: ${RuntimeLabels.relativeTime(status.lastOfferMs, now)} · آخر قرار: ${RuntimeLabels.relativeTime(status.lastDecisionMs, now)}", style = MaterialTheme.typography.bodySmall)
        Text("آخر ضغط على قبول: ${RuntimeLabels.relativeTime(status.lastClickMs, now)}", style = MaterialTheme.typography.bodySmall)
        status.latestReport?.let { report ->
            Text("وضوح آخر طلب: ${report.score}/100", style = MaterialTheme.typography.bodySmall)
            if (report.reasons.isNotEmpty()) {
                Text("سبب عدم القبول: ${report.reasons.take(2).joinToString(" · ") { reasonArabic(it).removeSuffix(".") }}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (status.diagnostics.isNotEmpty()) {
            Text("آخر المراحل: ${status.diagnostics.takeLast(4).joinToString(" ← ") { RuntimeLabels.stage(it.stage) }}",
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
                    if (isRestoring) "أهلًا بعودتك" else "الحساب محفوظ لكن تعذر تحديث الجلسة",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (isRestoring) "جارٍ التحقق من الجلسة والترخيص. ستبقى متابعة الطلبات متوقفة حتى يكتمل التحقق."
                    else "لم تُمسح الجلسة. يمكنك استخدام إعداداتك المحلية؛ أعد المحاولة قبل تشغيل متابعة الطلبات.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!isRestoring) TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }
            if (isRestoring) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * «شروط القيادة»: the four conditions drivers change most, editable in place with their own
 * switches (the same components as «طلبات تناسبك», so both edit one draft). Everything else
 * stays on the full page behind the button.
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
    modifier: Modifier = Modifier,
) {
    SabbaqCard(
        title = "شروط القيادة",
        subtitle = when {
            activeCount == 0 -> "فعّل شرطًا واحدًا على الأقل لاختيار الطلبات."
            isActive -> "${ArabicCount.conditions(activeCount)} فعّالة · أي تعديل هنا يُطبَّق فورًا دون إيقاف المتابعة."
            else -> "${ArabicCount.conditions(activeCount)} فعّالة · تُحفظ التعديلات تلقائيًا."
        },
        icon = Icons.Outlined.Tune,
        modifier = modifier,
    ) {
        QuickConditionsPanel(draft, errors, onDraftChange)
        SabbaqAnimatedMessage(saveMessage?.message, saveMessage?.tone ?: SabbaqTone.Info)
        SabbaqButton(
            "باقي الشروط في «طلبات تناسبك»",
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
    busyText: String = "جارٍ التحقق من الاشتراك",
    modifier: Modifier = Modifier,
) {
    val brand = LocalSabbaqBrand.current
    val reduced = LocalReducedMotion.current
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
                    modifier = Modifier.semantics { stateDescription = "جاهزية $readyCount من $totalCount" })
                AnimatedContent(
                    targetState = isActive,
                    transitionSpec = { (fadeIn(tween(SabbaqMotion.MEDIUM)) togetherWith fadeOut(tween(SabbaqMotion.SHORT))) },
                    label = "hero-status",
                ) { active ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (active) "متابعة الطلبات تعمل" else "متابعة الطلبات متوقفة",
                            style = MaterialTheme.typography.titleMedium,
                            color = brand.onHero,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            if (active) "تُطبّق شروطك المحفوظة وأوقات عملك."
                            else if (readyCount < totalCount) "جاهزية $readyCount من $totalCount؛ أكمل البقية أدناه."
                            else "كل شيء جاهز. ابدأ عندما تكون مستعدًا.",
                            style = MaterialTheme.typography.bodySmall,
                            color = brand.onHeroMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
                SabbaqButton(
                    text = if (isActive) "إيقاف" else "ابدأ",
                    onClick = { onToggle(!isActive) },
                    enabled = isActive || canStart,
                    busy = isChecking,
                    busyText = busyText,
                    icon = if (isActive) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    fillWidth = false,
                    modifier = Modifier.semantics {
                        contentDescription = if (isActive) "إيقاف متابعة الطلبات" else "ابدأ متابعة الطلبات"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActive) brand.onHero.copy(alpha = 0.14f) else brand.onHero,
                        contentColor = if (isActive) brand.onHero else brand.heroStart,
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
                    contentDescription = if (isActive) "متابعة الطلبات تعمل" else "متابعة الطلبات متوقفة",
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
            SabbaqStatusPill("جاهز", ready = true)
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
                    "ينقص سبّاق ${ArabicCount.permissions(missing.size)} ليعمل بكامل طاقته",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Text("بدونها قد لا يقبل الطلبات، أو يتوقف عند قفل الشاشة أو بعد إعادة التشغيل. اضغط على كل زر وامنح الإذن.",
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
        title = "طلبات تناسبك",
        subtitle = if (isSaving) "جارٍ تطبيق آخر تعديل وحفظه على هذا الجهاز…" else "حدّد ما يلائم وقتك ومسافة وصولك والعائد الذي تريده؛ تُحفظ التغييرات تلقائيًا.",
    )
    SabbaqAnimatedMessage(message?.message, message?.tone ?: SabbaqTone.Info)
    SabbaqAnimatedMessage(
        if (validationErrors.isNotEmpty()) "راجع الحقول المعلَّمة؛ لن نستبدل شروطك المحفوظة حتى تصلحها." else null,
        SabbaqTone.Error,
    )
    FilterPresetsCard(draft = draft, enabled = !isSaving, onDraftChange = onDraftChange)
    AdvancedFiltersEditor(
        draft = draft,
        errors = validationErrors,
        onDraftChange = onDraftChange,
    )
    SabbaqCard(title = "الحفظ الفوري", subtitle = "يُطبّق كل شرط صالح ويحفظ محليًا دون خطوة إضافية.", icon = Icons.Outlined.Autorenew) {
        if (isSaving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("جارٍ حفظ آخر تعديل…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dirty && !isSaving && message?.isError == true) {
            SabbaqButton(
                text = "إعادة محاولة الحفظ",
                onClick = onSave,
                enabled = validationErrors.isEmpty(),
                icon = Icons.Outlined.Refresh,
            )
        }
        AnimatedVisibility(dirty && !isSaving && validationErrors.isEmpty()) {
            SabbaqButton(text = "استرجاع الشروط المحفوظة", onClick = onRevert, style = SabbaqButtonStyle.Secondary)
        }
        SabbaqButton(
            text = "استعادة الإعدادات الافتراضية",
            onClick = onReset,
            enabled = !isSaving,
            style = SabbaqButtonStyle.Text,
            icon = Icons.Outlined.Refresh,
        )
    }
}

/** One-tap starting points for distance/time conditions; the selected preset is auto-saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPresetsCard(draft: FilterDraft, enabled: Boolean, onDraftChange: (FilterDraft) -> Unit) {
    val selected = FilterPreset.entries.firstOrNull { it.matches(draft) }
    SabbaqCard(
        title = "ابدأ من قالب",
        subtitle = selected?.description ?: "يضبط حدود الوصول والرحلة فقط، ثم يحفظها تلقائيًا.",
        icon = Icons.Outlined.Tune,
    ) {
        FilterPresetChips(draft, enabled, onDraftChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPresetChips(draft: FilterDraft, enabled: Boolean, onDraftChange: (FilterDraft) -> Unit) {
    val selected = FilterPreset.entries.firstOrNull { it.matches(draft) }
    run {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPreset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == selected,
                    enabled = enabled,
                    onClick = { onDraftChange(preset.applyTo(draft)) },
                    label = { Text(preset.label) },
                    leadingIcon = if (preset == selected) {
                        { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SimulatorPage(savedFilters: Filters) {
    var fare by rememberSaveable { mutableStateOf("") }
    var hasDestination by rememberSaveable { mutableStateOf(true) }
    var pickupKm by rememberSaveable { mutableStateOf("") }
    var tripKm by rememberSaveable { mutableStateOf("") }
    var pickupMinutes by rememberSaveable { mutableStateOf("") }
    var tripMinutes by rememberSaveable { mutableStateOf("") }
    var offerText by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<PreviewResult?>(null) }
    val hasInput = listOf(fare, pickupKm, tripKm, pickupMinutes, tripMinutes, offerText).any { it.isNotBlank() } || !hasDestination

    fun evaluate() {
        val fareValue = parsePreviewDecimal(fare)
        val pickupKmValue = parsePreviewDecimal(pickupKm)
        val tripKmValue = parsePreviewDecimal(tripKm)
        val pickupMinutesValue = parsePreviewMinutes(pickupMinutes)
        val tripMinutesValue = parsePreviewMinutes(tripMinutes)
        val invalid = listOf(
            fare to fareValue,
            pickupKm to pickupKmValue,
            tripKm to tripKmValue,
        ).any { (raw, parsed) -> raw.isNotBlank() && parsed == null } ||
            listOf(pickupMinutes to pickupMinutesValue, tripMinutes to tripMinutesValue)
                .any { (raw, parsed) -> raw.isNotBlank() && parsed == null }
        if (invalid) {
            result = PreviewResult(
                verdict = OfferEvaluator.evaluate(emptyPreviewOffer(), savedFilters, currentMinuteOfDay()),
                error = "أدخل رقمًا غير سالب، أو اترك الحقل فارغًا إذا لم تتوفر هذه المعلومة.",
            )
            return
        }
        val offer = Offer(
            profileId = "local-preview",
            fare = fareValue,
            currency = null,
            pickupKm = pickupKmValue,
            tripKm = tripKmValue,
            pickupMinutes = pickupMinutesValue,
            tripMinutes = tripMinutesValue,
            addresses = if (hasDestination) listOf("عنوان الانطلاق", "الوجهة") else emptyList(),
            texts = offerText.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
        )
        result = PreviewResult(OfferEvaluator.evaluate(offer, savedFilters, currentMinuteOfDay()))
    }

    fun clearAll() {
        fare = ""; hasDestination = true; pickupKm = ""; tripKm = ""; pickupMinutes = ""; tripMinutes = ""; offerText = ""
        result = null
    }

    DriverPage {
        SabbaqSectionTitle("جرّب شروطك", "أدخل تفاصيل طلب مشابه لتعرف إن كان يطابق شروطك المحفوظة، من دون التأثير في عملك.")
        SabbaqMessage("هذه تجربة فقط: لن تغيّر شروطك ولن تنقر على أي طلب.")
        SabbaqCard(title = "تفاصيل الطلب للتجربة", subtitle = "أدخل ما تعرفه من تفاصيل الطلب، واترك أي خانة فارغة إذا لم تظهر لك.", icon = Icons.Outlined.Visibility) {
            PreviewField(fare, "سعر الطلب", KeyboardType.Decimal) { fare = it; result = null }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("الطلب يعرض وجهة", style = MaterialTheme.typography.bodyLarge)
                    Text("أطفئه لمحاكاة طلب بلا وجهة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = hasDestination, onCheckedChange = { hasDestination = it; result = null })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewField(pickupKm, "مسافة الوصول (كم)", KeyboardType.Decimal, Modifier.weight(1f)) { pickupKm = it; result = null }
                PreviewField(pickupMinutes, "وقت الوصول (دقائق)", KeyboardType.Number, Modifier.weight(1f)) { pickupMinutes = it; result = null }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewField(tripKm, "مسافة الرحلة (كم)", KeyboardType.Decimal, Modifier.weight(1f)) { tripKm = it; result = null }
                PreviewField(tripMinutes, "مدة الرحلة (دقائق)", KeyboardType.Number, Modifier.weight(1f)) { tripMinutes = it; result = null }
            }
            PreviewField(offerText, "كلمات في وصف الطلب (اختياري)", KeyboardType.Text, singleLine = false) { offerText = it; result = null }
            SabbaqButton(text = "اعرف هل يناسبك", onClick = ::evaluate, enabled = hasInput, icon = Icons.Outlined.Visibility)
            AnimatedVisibility(hasInput) {
                SabbaqButton(text = "مسح التجربة", onClick = ::clearAll, style = SabbaqButtonStyle.Text, icon = Icons.Outlined.Refresh)
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
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(2048)) },
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = modifier,
    )
}

@Composable
private fun PreviewVerdictCard(result: PreviewResult) {
    if (result.error != null) {
        SabbaqMessage(result.error, tone = SabbaqTone.Error)
        return
    }
    val verdict = result.verdict
    SabbaqCard(
        title = if (verdict.accept) "نتيجة التجربة: يناسبك" else "نتيجة التجربة: لا يناسبك",
        subtitle = if (verdict.accept) "بحسب شروطك المحفوظة وتفاصيل التجربة." else "لن يطابق هذا الطلب شروطك للأسباب التالية.",
        icon = if (verdict.accept) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
    ) {
        SabbaqMessage(
            if (verdict.accept) "يطابق شروطك المحفوظة." else "لا يطابق ${ArabicCount.conditionsObject(verdict.reasons.size)} من شروطك.",
            tone = if (verdict.accept) SabbaqTone.Success else SabbaqTone.Error,
        )
        verdict.reasons.forEach { reason ->
            Text("• ${reasonArabic(reason.code)}", style = MaterialTheme.typography.bodyMedium)
        }
        verdict.farePerKm?.let { perKm ->
            Text("العائد المحسوب لكل كم: ${perKm.stripTrailingZeros().toPlainString()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountPage(
    licenseInMemory: Boolean,
    message: DriverStatus?,
    onLicenseChanged: (Boolean) -> Unit,
    onMessage: (DriverStatus?) -> Unit,
    onAccountDeleted: () -> Unit,
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
    )
}

private fun activeFilterCount(filters: Filters): Int = buildList {
    if (filters.isFareRangeEnabled && (!filters.minFare.isNullOrBlank() || !filters.maxFare.isNullOrBlank())) add(Unit)
    if (filters.isFarePerKmEnabled && !filters.minFarePerKm.isNullOrBlank()) add(Unit)
    if (filters.isPickupDistanceEnabled && !filters.maxPickupKm.isNullOrBlank()) add(Unit)
    if (filters.isPickupTimeEnabled && ((filters.minPickupMinutes?.let { it > 0 } == true) || filters.maxPickupMinutes != null)) add(Unit)
    if (filters.isTripDistanceEnabled && (!filters.minTripKm.isNullOrBlank() || !filters.maxTripKm.isNullOrBlank())) add(Unit)
    if (filters.isTripTimeEnabled && ((filters.minTripMinutes?.let { it > 0 } == true) || filters.maxTripMinutes != null)) add(Unit)
    if (filters.excludeKeywords.any { it.isNotBlank() }) add(Unit)
    if (filters.scheduleEnabled) add(Unit)
    if (filters.destinationZones.any { it.enabled }) add(Unit)
    if (filters.smartDestination != null) add(Unit)
    if (filters.destinationCorridor != null) add(Unit)
}.size

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

private fun emptyPreviewOffer(): Offer = Offer(
    profileId = "local-preview",
    fare = null,
    currency = null,
    pickupKm = null,
    tripKm = null,
    pickupMinutes = null,
    tripMinutes = null,
    addresses = listOf("عنوان الانطلاق", "الوجهة"),
    texts = emptyList(),
)

private fun currentMinuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

internal fun reasonArabic(code: ReasonCode): String = when (code) {
    ReasonCode.NO_FILTERS -> "لم تحدد شرطًا لاختيار الطلبات."
    ReasonCode.OUTSIDE_SCHEDULE -> "الوقت خارج أوقات العمل التي حددتها."
    ReasonCode.INVALID_FILTER -> "أحد شروط الطلبات غير صالح."
    ReasonCode.FARE_MISSING -> "سعر الطلب غير ظاهر."
    ReasonCode.FARE_TOO_LOW -> "سعر الطلب أقل من الحد الذي حددته."
    ReasonCode.FARE_TOO_HIGH -> "سعر الطلب أعلى من الحد الذي حددته."
    ReasonCode.PER_KM_MISSING -> "لا يمكن حساب العائد لكل كم من التفاصيل المتاحة."
    ReasonCode.PER_KM_TOO_LOW -> "العائد لكل كم أقل من الحد الذي حددته."
    ReasonCode.PICKUP_KM_MISSING -> "مسافة الوصول غير ظاهرة."
    ReasonCode.PICKUP_TOO_FAR -> "مسافة الوصول أبعد مما حددته."
    ReasonCode.PICKUP_MIN_MISSING -> "وقت الوصول غير ظاهر."
    ReasonCode.PICKUP_TOO_LONG -> "وقت الوصول أطول مما حددته."
    ReasonCode.PICKUP_TOO_SHORT -> "وقت الوصول أقصر مما حددته."
    ReasonCode.DESTINATION_MISSING -> "وجهة الطلب غير ظاهرة، وخيار الطلبات بلا وجهة متوقف."
    ReasonCode.TRIP_KM_MISSING -> "مسافة الرحلة غير ظاهرة."
    ReasonCode.TRIP_TOO_SHORT -> "مسافة الرحلة أقصر مما حددته."
    ReasonCode.TRIP_TOO_LONG -> "مسافة الرحلة أطول مما حددته."
    ReasonCode.TRIP_MIN_MISSING -> "مدة الرحلة غير ظاهرة."
    ReasonCode.TRIP_MIN_TOO_LONG -> "مدة الرحلة أطول مما حددته."
    ReasonCode.TRIP_MIN_TOO_SHORT -> "مدة الرحلة أقصر مما حددته."
    ReasonCode.EXCLUDED_KEYWORD -> "وصف الطلب يحتوي كلمة لا تناسبك."
    ReasonCode.DESTINATION_GEO_UNKNOWN -> "تعذر تحديد إحداثيات الوجهة من الربط المحلي؛ لم يخمّن سبّاق موقعًا."
    ReasonCode.DESTINATION_ZONE_EXCLUDED -> "الوجهة تقع داخل منطقة استبعاد محددة."
    ReasonCode.DESTINATION_ZONE_NOT_MATCHED -> "الوجهة خارج جميع مناطق الإدراج المحددة."
    ReasonCode.SMART_DESTINATION_MISMATCH -> "الوجهة أبعد من النطاق المحدد للوجهة المفضلة."
    ReasonCode.DESTINATION_CORRIDOR_MISMATCH -> "الوجهة خارج ممر الرحلة المحدد."
}

private fun Context.isSabbaqAccessibilityEnabled(): Boolean {
    val expectedId = ComponentName(this, SabbaqA11yService::class.java).flattenToString()
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == expectedId }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

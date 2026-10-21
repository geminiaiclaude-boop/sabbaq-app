package app.qannas.ui

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.qannas.ui.theme.LocalQannasBrand
import app.qannas.ui.theme.LocalReducedMotion
import app.qannas.ui.theme.QannasDepthContent
import app.qannas.ui.theme.QannasMotion
import app.qannas.ui.theme.flipWhenSelected
import app.qannas.ui.theme.qannasFlipIn
import app.qannas.ui.theme.tilt3D
import androidx.compose.runtime.key
import app.qannas.ui.theme.qannasEntrance
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
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
import app.qannas.BuildConfig
import app.qannas.Settings
import app.qannas.core.Filters
import app.qannas.core.Offer
import app.qannas.core.OfferEvaluator
import app.qannas.core.ReasonCode
import app.qannas.core.Verdict
import app.qannas.network.SupabaseClient
import app.qannas.service.SniperA11yService
import java.math.BigDecimal
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** وجهات لوحة السائق؛ يمكن استدعاء الشاشة مباشرة إلى أي منها في معاينات/اختبارات الواجهة. */
enum class DriverDestination { Dashboard, Filters, Simulator, Account, Setup }

private data class DriverStatus(val message: String, val isError: Boolean = false, val success: Boolean = false) {
    val tone: QannasTone get() = when {
        isError -> QannasTone.Error
        success -> QannasTone.Success
        else -> QannasTone.Info
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
    DriverNavItem(DriverDestination.Simulator, "تجربة الطلب", Icons.Outlined.Visibility),
    DriverNavItem(DriverDestination.Account, "حسابي", Icons.Outlined.AccountCircle),
)

/**
 * لوحة السائق الإنتاجية. الحواف الآمنة وapp bar والخروج يملكها QannasShell في MainActivity؛
 * لذلك لا تضيف هذه الشاشة systemBarsPadding أو أي حالة مصادقة دائمة.
 */
@Composable
fun SettingsScreen(
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenA11y: () -> Result<Unit>,
    onToggleKeeper: (Boolean) -> Result<Unit>,
    initialDestination: DriverDestination = DriverDestination.Dashboard,
    onUnsavedFiltersChanged: (Boolean) -> Unit = {},
    onAccountDeleted: () -> Unit = {},
    /** Opens the setup guide first for a driver who has not finished or dismissed it. */
    autoSetupGuide: Boolean = true,
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
    var accessibilityEnabled by remember(context) { mutableStateOf(context.isQannasAccessibilityEnabled()) }
    var notificationsEnabled by remember(context) { mutableStateOf(context.hasNotificationPermission()) }
    var licenseInMemory by remember { mutableStateOf(SupabaseClient.hasValidLicense()) }
    var checkingActivation by remember { mutableStateOf(false) }
    var showActivationConsent by remember { mutableStateOf(false) }
    var showAccessibilityRequired by remember { mutableStateOf(false) }
    var showNotificationRequired by remember { mutableStateOf(false) }
    var showResetFilters by remember { mutableStateOf(false) }
    var showUnsavedChanges by remember { mutableStateOf(false) }
    var pendingDestination by remember { mutableStateOf<DriverDestination?>(null) }

    val latestToggleKeeper by rememberUpdatedState(onToggleKeeper)
    val haptics = LocalHapticFeedback.current
    val latestUnsavedChanged by rememberUpdatedState(onUnsavedFiltersChanged)
    LaunchedEffect(filtersDirty) { latestUnsavedChanged(filtersDirty) }

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
        val a11y = context.isQannasAccessibilityEnabled()
        val notifications = context.hasNotificationPermission()
        val licensed = SupabaseClient.hasValidLicense() // لقطة ذاكرية فقط؛ لا شبكة في النبض الدوري.
        accessibilityEnabled = a11y
        notificationsEnabled = notifications
        licenseInMemory = licensed
        val persistedActive = Settings.isActive
        if (persistedActive != isActive) isActive = persistedActive
        if (persistedActive && (!a11y || !notifications || !licensed)) {
            stopKeeper(
                when {
                    !a11y -> "توقفت متابعة الطلبات لأن إذن إمكانية الوصول لم يعد مفعّلًا."
                    !notifications -> "توقفت متابعة الطلبات لأن إذن الإشعارات لم يعد مفعّلًا."
                    else -> "توقفت متابعة الطلبات لأن حالة اشتراكك تحتاج إلى تحقق."
                }
            )
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

    fun updateDraft(next: FilterDraft) {
        if (isSavingFilters) return
        if (next == draft) return
        if (isActive) {
            stopKeeper("أوقفنا متابعة الطلبات قبل تعديل شروطك. احفظ الشروط ثم ابدأ العمل من جديد.")
        }
        draft = next
        filtersMessage = null
    }

    fun saveDraft(onSaved: () -> Unit = {}) {
        if (isSavingFilters) return
        val filters = draftValidation.filters
        if (filters == null) {
            filtersMessage = DriverStatus("صحح الحقول المعلَّمة قبل حفظ شروط الطلبات.", isError = true)
            return
        }
        isSavingFilters = true
        filtersMessage = null
        val submittedDraft = draft
        scope.launch {
            try {
                val saved = Settings.saveFilters(filters)
                if (saved) {
                    savedFilters = filters
                    if (draft === submittedDraft) draft = FilterDraft.from(filters)
                    filtersMessage = DriverStatus("حُفظت شروطك. ستُستخدم عند بدء متابعة الطلبات.", success = true)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSaved()
                } else {
                    filtersMessage = DriverStatus("لم نستطع حفظ شروطك على جهازك؛ ما زالت الشروط السابقة هي المستخدمة.", isError = true)
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                isSavingFilters = false
            }
        }
    }

    fun navigateTo(next: DriverDestination) {
        if (isSavingFilters) return
        if (next == destination) return
        if (destination == DriverDestination.Filters && filtersDirty) {
            pendingDestination = next
            showUnsavedChanges = true
        } else {
            destination = next
        }
    }

    fun requestActivation() {
        if (checkingActivation || isSavingFilters || isActive) return
        if (filtersDirty) {
            runtimeMessage = DriverStatus("احفظ تغييرات الشروط أو تراجع عنها قبل بدء العمل.", isError = true)
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
        if (!accessibilityEnabled) {
            showAccessibilityRequired = true
            return
        }
        if (!notificationsEnabled) {
            showNotificationRequired = true
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

    BackHandler(enabled = destination == DriverDestination.Filters || destination == DriverDestination.Setup) {
        navigateTo(DriverDestination.Dashboard)
    }

    if (showAccessibilityRequired) {
        AlertDialog(
            onDismissRequest = { showAccessibilityRequired = false },
            title = { Text("فعّل إذن متابعة الطلبات") },
            text = { Text("يحتاج قناص إلى إذن إمكانية الوصول لمتابعة تفاصيل الطلبات الظاهرة والنقر على الطلب المطابق لشروطك. فعّله من إعدادات الجهاز، ويمكنك إيقاف المتابعة في أي وقت.") },
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

    if (showNotificationRequired) {
        AlertDialog(
            onDismissRequest = { showNotificationRequired = false },
            title = { Text("يلزم إذن الإشعارات") },
            text = { Text("يحتاج قناص إلى الإشعارات ليُظهر لك بوضوح أن متابعة الطلبات تعمل.") },
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
                    "عند البدء، يقرأ قناص تفاصيل الطلبات الظاهرة وفق شروطك المحفوظة، ويمكنه النقر تلقائيًا على طلب يطابقها. " +
                        "يمكنك إيقاف المتابعة في أي وقت."
                )
            },
            confirmButton = { TextButton(onClick = ::confirmActivation) { Text("موافق، ابدأ المتابعة") } },
            dismissButton = { TextButton(onClick = { showActivationConsent = false }) { Text("إلغاء") } },
        )
    }

    if (showUnsavedChanges) {
        AlertDialog(
            onDismissRequest = { if (!isSavingFilters) { showUnsavedChanges = false; pendingDestination = null } },
            title = { Text("تغييرات شروط الطلبات غير محفوظة") },
            text = { Text("احفظها لتستخدمها في عملك، أو تجاهلها للرجوع إلى شروطك السابقة.") },
            confirmButton = {
                TextButton(
                    enabled = !isSavingFilters,
                    onClick = {
                        saveDraft {
                            showUnsavedChanges = false
                            destination = pendingDestination ?: DriverDestination.Dashboard
                            pendingDestination = null
                        }
                    },
                ) { Text("حفظ") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        draft = FilterDraft.from(savedFilters)
                        showUnsavedChanges = false
                        destination = pendingDestination ?: DriverDestination.Dashboard
                        pendingDestination = null
                    }, enabled = !isSavingFilters) { Text("تجاهل") }
                    TextButton(onClick = { showUnsavedChanges = false; pendingDestination = null }, enabled = !isSavingFilters) { Text("إلغاء") }
                }
            },
        )
    }

    if (showResetFilters) {
        AlertDialog(
            onDismissRequest = { showResetFilters = false },
            title = { Text("مسح التعديلات؟") },
            text = { Text("سيتم مسح القيم التي أدخلتها الآن. تبقى شروطك المحفوظة كما هي إلى أن تحفظ تعديلات جديدة.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetFilters = false
                    updateDraft(FilterDraft.from(Filters()))
                }) { Text("إعادة الضبط") }
            },
            dismissButton = { TextButton(onClick = { showResetFilters = false }) { Text("إلغاء") } },
        )
    }

    DriverNavigationShell(
        destination = destination,
        onDestinationSelected = ::navigateTo,
    ) {
        QannasDepthContent(target = destination, order = { it.ordinal }, label = "driver-destination") { page ->
        when (page) {
            DriverDestination.Dashboard -> DashboardPage(
                isActive = isActive,
                isCheckingActivation = checkingActivation,
                accessibilityEnabled = accessibilityEnabled,
                notificationsEnabled = notificationsEnabled,
                licenseInMemory = licenseInMemory,
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
            )

            DriverDestination.Setup -> DriverPage {
                val leaveGuide = {
                    SetupGuidePreferences.markDone(context)
                    navigateTo(DriverDestination.Dashboard)
                }
                SetupGuidePage(
                    steps = listOf(
                        SetupStep(
                            icon = Icons.Outlined.FilterAlt,
                            title = "اختر الطلبات التي تناسبك",
                            why = "ابدأ بقالب جاهز ثم احفظه. يمكنك ضبط الأجرة والمسافة والأوقات بدقة لاحقًا.",
                            doneText = "محفوظ لديك: ${ArabicCount.conditions(activeFilterCount(savedFilters))}",
                            actionLabel = if (filtersDirty) "حفظ الشروط" else "ضبط متقدم للشروط",
                            done = savedFilters.anyActive(),
                            onAction = { if (filtersDirty) saveDraft() else navigateTo(DriverDestination.Filters) },
                            extra = {
                                FilterPresetChips(draft = draft, enabled = !isSavingFilters, onDraftChange = ::updateDraft)
                                QannasAnimatedMessage(filtersMessage?.message, filtersMessage?.tone ?: QannasTone.Info)
                            },
                        ),
                        SetupStep(
                            icon = Icons.Outlined.AccessibilityNew,
                            title = "اسمح لقناص بمتابعة الطلبات",
                            why = "يقرأ قناص شاشة الطلب في التطبيقات المدعومة فقط ليقارنها بشروطك، ويعالجها على جهازك. سنشرح لك التفاصيل قبل فتح الإعدادات.",
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
                            why = "تنبيه ثابت يُظهر لك أن المتابعة تعمل، فتعرف دائمًا متى يعمل قناص ومتى يتوقف.",
                            doneText = "التنبيهات مفعّلة",
                            actionLabel = "السماح بالتنبيهات",
                            done = notificationsEnabled,
                            onAction = onRequestNotificationPermission,
                        ),
                        SetupStep(
                            icon = Icons.Outlined.VerifiedUser,
                            title = "تأكد من اشتراكك",
                            why = "ابدأ تجربتك أو فعّل اشتراكك من صفحة حسابي.",
                            doneText = "اشتراكك جاهز للعمل",
                            actionLabel = "فتح حسابي",
                            done = licenseInMemory,
                            onAction = { navigateTo(DriverDestination.Account) },
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
                onSave = ::saveDraft,
                onRevert = { draft = FilterDraft.from(savedFilters); filtersMessage = null },
                onReset = { showResetFilters = true },
            )

            DriverDestination.Simulator -> SimulatorPage(savedFilters = savedFilters)

            DriverDestination.Account -> AccountPage(
                licenseInMemory = licenseInMemory,
                message = accountMessage,
                onLicenseChanged = { licenseInMemory = it },
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
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 700.dp
        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.fillMaxHeight(), windowInsets = WindowInsets(0, 0, 0, 0)) {
                    driverDestinations.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item.destination,
                            onClick = { onDestinationSelected(item.destination) },
                            icon = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.flipWhenSelected(destination == item.destination)) },
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
                        driverDestinations.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item.destination,
                                onClick = { onDestinationSelected(item.destination) },
                                icon = { Icon(item.icon, contentDescription = item.label, modifier = Modifier.flipWhenSelected(destination == item.destination)) },
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
private fun DashboardPage(
    isActive: Boolean,
    isCheckingActivation: Boolean,
    accessibilityEnabled: Boolean,
    notificationsEnabled: Boolean,
    licenseInMemory: Boolean,
    activeFilterCount: Int,
    message: DriverStatus?,
    onToggle: (Boolean) -> Unit,
    onOpenA11y: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSetupGuide: () -> Unit,
) = DriverPage {
    val readiness = listOf(
        ReadinessItem(Icons.Outlined.FilterAlt, "شروط الطلبات", activeFilterCount > 0,
            "محفوظ لديك: ${ArabicCount.conditions(activeFilterCount)}", "اختر ما يناسبك أولًا", "تعديل الشروط", onOpenFilters),
        ReadinessItem(Icons.Outlined.AccessibilityNew, "إذن متابعة الطلبات", accessibilityEnabled,
            "مفعّل", "يحتاج إلى تفعيل", "تفعيل الإذن", onOpenA11y),
        ReadinessItem(Icons.Outlined.Notifications, "تنبيهات حالة العمل", notificationsEnabled,
            "مفعّلة", "تحتاج إلى سماح", "السماح", onRequestNotifications),
        ReadinessItem(Icons.Outlined.VerifiedUser, "حالة الاشتراك", licenseInMemory,
            "جاهز للعمل", "تحتاج إلى تحقق", "فتح حسابي", onOpenAccount),
    )
    val readyCount = readiness.count { it.ready }
    OperationHero(
        isActive = isActive,
        isChecking = isCheckingActivation,
        readyCount = readyCount,
        totalCount = readiness.size,
        onToggle = onToggle,
        modifier = Modifier.qannasEntrance(0),
    )
    QannasAnimatedMessage(message?.message, message?.tone ?: QannasTone.Info)

    QannasCard(
        title = if (readyCount == readiness.size) "كل شيء جاهز" else "قبل بدء العمل",
        subtitle = if (readyCount == readiness.size) "يمكنك بدء المتابعة في أي وقت."
            else "تبقّت ${ArabicCount.steps(readiness.size - readyCount)} قبل البدء.",
        icon = Icons.Outlined.VerifiedUser,
        modifier = Modifier.qannasEntrance(1),
    ) {
        val progress by animateFloatAsState(
            targetValue = readyCount.toFloat() / readiness.size,
            animationSpec = tween(QannasMotion.LONG, easing = QannasMotion.Emphasized),
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
        QannasButton(
            if (readyCount < readiness.size) "أكمل الإعداد خطوة بخطوة" else "دليل الإعداد",
            onClick = onOpenSetupGuide,
            style = if (readyCount < readiness.size) QannasButtonStyle.Secondary else QannasButtonStyle.Text,
            icon = Icons.Outlined.Tune,
        )
    }

    QannasCard(title = "أثناء وقت العمل", icon = Icons.Outlined.Info, modifier = Modifier.qannasEntrance(2)) {
        Text(
            "يتابع قناص الطلبات الظاهرة وفق سعر الطلب والعائد لكل كم ومسافة الوصول وأوقات العمل التي حددتها. " +
                "عند ظهور طلب مطابق، يمكنه النقر عليه تلقائيًا. يمكنك إيقاف المتابعة في أي وقت.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class ReadinessItem(
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
    modifier: Modifier = Modifier,
) {
    val brand = LocalQannasBrand.current
    QannasHeroSurface(modifier, sceneActive = isActive) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LiveStatusMark(isActive = isActive)
            Column(modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                AnimatedContent(
                    targetState = isActive,
                    transitionSpec = { (fadeIn(tween(QannasMotion.MEDIUM)) togetherWith fadeOut(tween(QannasMotion.SHORT))) },
                    label = "hero-status",
                ) { active ->
                    Column {
                        Text(if (active) "متابعة الطلبات تعمل" else "متابعة الطلبات متوقفة", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (active) "تُطبّق شروطك المحفوظة وأوقات عملك."
                            else if (readyCount < totalCount) "اكتمل $readyCount من $totalCount متطلبات. أكمل البقية أدناه."
                            else "كل المتطلبات جاهزة. ابدأ عندما تكون مستعدًا.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = brand.onHeroMuted,
                        )
                    }
                }
            }
        }
        QannasButton(
            text = if (isActive) "إيقاف متابعة الطلبات" else "ابدأ متابعة الطلبات",
            onClick = { onToggle(!isActive) },
            busy = isChecking,
            busyText = "جارٍ التحقق من الاشتراك",
            icon = if (isActive) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) brand.onHero.copy(alpha = 0.14f) else brand.onHero,
                contentColor = if (isActive) brand.onHero else brand.heroStart,
                disabledContainerColor = brand.onHero.copy(alpha = 0.24f),
                disabledContentColor = brand.onHero,
            ),
        )
    }
}

@Composable
private fun LiveStatusMark(isActive: Boolean) {
    val brand = LocalQannasBrand.current
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
private fun ReadinessRow(item: ReadinessItem) {
    val tint by animateColorAsState(
        if (item.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        tween(QannasMotion.MEDIUM),
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
            QannasStatusPill("جاهز", ready = true)
        } else {
            Text(item.actionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
    QannasSectionTitle(
        title = "طلبات تناسبك",
        subtitle = if (dirty) "لديك تعديلات لم تحفظ بعد." else "حدّد ما يلائم وقتك ومسافة وصولك والعائد الذي تريده.",
    )
    QannasAnimatedMessage(message?.message, message?.tone ?: QannasTone.Info)
    QannasAnimatedMessage(
        if (validationErrors.isNotEmpty()) "راجع الحقول المعلَّمة؛ لن نستبدل شروطك المحفوظة حتى تصلحها." else null,
        QannasTone.Error,
    )
    FilterPresetsCard(draft = draft, enabled = !isSaving, onDraftChange = onDraftChange)
    AdvancedFiltersEditor(
        draft = draft,
        errors = validationErrors,
        onDraftChange = onDraftChange,
    )
    QannasCard(title = "احفظ شروطك", subtitle = "لا تصبح التعديلات جاهزة للعمل إلا بعد الحفظ.", icon = Icons.Outlined.Save) {
        QannasButton(
            text = "حفظ شروط الطلبات",
            onClick = onSave,
            enabled = dirty && validationErrors.isEmpty(),
            busy = isSaving,
            busyText = "جارٍ حفظ الشروط",
            icon = Icons.Outlined.Save,
        )
        AnimatedVisibility(dirty && !isSaving) {
            QannasButton(text = "التراجع عن التعديلات", onClick = onRevert, style = QannasButtonStyle.Secondary)
        }
        QannasButton(
            text = "مسح التعديلات",
            onClick = onReset,
            enabled = !isSaving,
            style = QannasButtonStyle.Text,
            icon = Icons.Outlined.Refresh,
        )
    }
}

/** One-tap starting points for distance/time conditions; the result is still an unsaved draft. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPresetsCard(draft: FilterDraft, enabled: Boolean, onDraftChange: (FilterDraft) -> Unit) {
    val selected = FilterPreset.entries.firstOrNull { it.matches(draft) }
    QannasCard(
        title = "ابدأ من قالب",
        subtitle = selected?.description ?: "يضبط المسافة والوقت فقط. راجع القيم ثم احفظ.",
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
    var pickupKm by rememberSaveable { mutableStateOf("") }
    var tripKm by rememberSaveable { mutableStateOf("") }
    var pickupMinutes by rememberSaveable { mutableStateOf("") }
    var tripMinutes by rememberSaveable { mutableStateOf("") }
    var offerText by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<PreviewResult?>(null) }
    val hasInput = listOf(fare, pickupKm, tripKm, pickupMinutes, tripMinutes, offerText).any { it.isNotBlank() }

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
            addresses = emptyList(),
            texts = offerText.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
        )
        result = PreviewResult(OfferEvaluator.evaluate(offer, savedFilters, currentMinuteOfDay()))
    }

    fun clearAll() {
        fare = ""; pickupKm = ""; tripKm = ""; pickupMinutes = ""; tripMinutes = ""; offerText = ""
        result = null
    }

    DriverPage {
        QannasSectionTitle("جرّب شروطك", "أدخل تفاصيل طلب مشابه لتعرف إن كان يطابق شروطك المحفوظة، من دون التأثير في عملك.")
        QannasMessage("هذه تجربة فقط: لن تغيّر شروطك ولن تنقر على أي طلب.")
        QannasCard(title = "تفاصيل الطلب للتجربة", subtitle = "أدخل ما تعرفه من تفاصيل الطلب، واترك أي خانة فارغة إذا لم تظهر لك.", icon = Icons.Outlined.Visibility) {
            PreviewField(fare, "سعر الطلب", KeyboardType.Decimal) { fare = it; result = null }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewField(pickupKm, "مسافة الوصول (كم)", KeyboardType.Decimal, Modifier.weight(1f)) { pickupKm = it; result = null }
                PreviewField(pickupMinutes, "وقت الوصول (دقائق)", KeyboardType.Number, Modifier.weight(1f)) { pickupMinutes = it; result = null }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewField(tripKm, "مسافة الرحلة (كم)", KeyboardType.Decimal, Modifier.weight(1f)) { tripKm = it; result = null }
                PreviewField(tripMinutes, "مدة الرحلة (دقائق)", KeyboardType.Number, Modifier.weight(1f)) { tripMinutes = it; result = null }
            }
            PreviewField(offerText, "كلمات في وصف الطلب (اختياري)", KeyboardType.Text, singleLine = false) { offerText = it; result = null }
            QannasButton(text = "اعرف هل يناسبك", onClick = ::evaluate, enabled = hasInput, icon = Icons.Outlined.Visibility)
            AnimatedVisibility(hasInput) {
                QannasButton(text = "مسح التجربة", onClick = ::clearAll, style = QannasButtonStyle.Text, icon = Icons.Outlined.Refresh)
            }
        }
        AnimatedContent(
            targetState = result,
            transitionSpec = { fadeIn(tween(QannasMotion.MEDIUM)) togetherWith fadeOut(tween(QannasMotion.SHORT)) },
            label = "preview-verdict",
        ) { preview ->
            // Each new result turns over into view like a card, then leans with the finger.
            if (preview != null) key(preview) { Box(Modifier.qannasFlipIn().tilt3D(maxDegrees = 6f, idle = false)) { PreviewVerdictCard(preview) } }
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
        QannasMessage(result.error, tone = QannasTone.Error)
        return
    }
    val verdict = result.verdict
    QannasCard(
        title = if (verdict.accept) "نتيجة التجربة: يناسبك" else "نتيجة التجربة: لا يناسبك",
        subtitle = if (verdict.accept) "بحسب شروطك المحفوظة وتفاصيل التجربة." else "لن يطابق هذا الطلب شروطك للأسباب التالية.",
        icon = if (verdict.accept) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
    ) {
        QannasMessage(
            if (verdict.accept) "يطابق شروطك المحفوظة." else "لا يطابق ${ArabicCount.conditionsObject(verdict.reasons.size)} من شروطك.",
            tone = if (verdict.accept) QannasTone.Success else QannasTone.Error,
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
    if (!filters.minFare.isNullOrBlank()) add(Unit)
    if (!filters.maxFare.isNullOrBlank()) add(Unit)
    if (!filters.minFarePerKm.isNullOrBlank()) add(Unit)
    if (!filters.maxPickupKm.isNullOrBlank()) add(Unit)
    if (filters.maxPickupMinutes != null) add(Unit)
    if (!filters.minTripKm.isNullOrBlank()) add(Unit)
    if (!filters.maxTripKm.isNullOrBlank()) add(Unit)
    if (filters.maxTripMinutes != null) add(Unit)
    if (filters.excludeKeywords.any { it.isNotBlank() }) add(Unit)
    if (filters.scheduleEnabled) add(Unit)
}.size

internal fun parsePreviewDecimal(raw: String): BigDecimal? {
    if (raw.isBlank() || raw.length > 32) return null
    val normalized = app.qannas.core.Text.normalize(raw)
    if (!Regex("(?:${app.qannas.core.Text.NUMBER})").matches(normalized)) return null
    val parsed = app.qannas.core.Text.parseNumber(normalized) ?: return null
    return parsed.takeIf { it.signum() >= 0 }
}

internal fun parsePreviewMinutes(raw: String): Int? {
    if (raw.isBlank() || raw.length > 10) return null
    val normalized = app.qannas.core.Text.normalize(raw)
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
    addresses = emptyList(),
    texts = emptyList(),
)

private fun currentMinuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

private fun reasonArabic(code: ReasonCode): String = when (code) {
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
    ReasonCode.TRIP_KM_MISSING -> "مسافة الرحلة غير ظاهرة."
    ReasonCode.TRIP_TOO_SHORT -> "مسافة الرحلة أقصر مما حددته."
    ReasonCode.TRIP_TOO_LONG -> "مسافة الرحلة أطول مما حددته."
    ReasonCode.TRIP_MIN_MISSING -> "مدة الرحلة غير ظاهرة."
    ReasonCode.TRIP_MIN_TOO_LONG -> "مدة الرحلة أطول مما حددته."
    ReasonCode.EXCLUDED_KEYWORD -> "وصف الطلب يحتوي كلمة لا تناسبك."
}

private fun redeemErrorMessage(error: Throwable?): String = when (error?.message) {
    "الرمز غير صحيح أو استُخدم مسبقًا",
    "محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجددًا",
    "هذا الحساب مرتبط بجهاز آخر" -> error.message.orEmpty()
    else -> "تعذر تفعيل الرمز. تحقق من الرمز واتصال الإنترنت ثم أعد المحاولة."
}

private fun Context.isQannasAccessibilityEnabled(): Boolean {
    val expectedId = ComponentName(this, SniperA11yService::class.java).flattenToString()
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == expectedId }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

package app.qannas.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
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
enum class DriverDestination { Dashboard, Filters, Simulator, Account }

private data class DriverStatus(val message: String, val isError: Boolean = false)
private data class PreviewResult(val verdict: Verdict, val error: String? = null)
private data class DriverNavItem(
    val destination: DriverDestination,
    val label: String,
    val icon: ImageVector,
)

private val driverDestinations = listOf(
    DriverNavItem(DriverDestination.Dashboard, "لوحة القيادة", Icons.Outlined.Tune),
    DriverNavItem(DriverDestination.Filters, "المرشحات", Icons.Outlined.FilterAlt),
    DriverNavItem(DriverDestination.Simulator, "معاينة القرار", Icons.Outlined.Visibility),
    DriverNavItem(DriverDestination.Account, "الحساب", Icons.Outlined.AccountCircle),
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
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

    fun stopKeeper(message: String? = null) {
        val stopResult = latestToggleKeeper(false)
        // الإيقاف المحلي دائم حتى إن لم تؤكد الخدمة ذلك؛ لا نترك واجهة توحي بتشغيل غير مؤكد.
        Settings.isActive = false
        isActive = false
        runtimeMessage = when {
            stopResult.isFailure -> DriverStatus(
                "تم تعطيل التشغيل محلياً، لكن تعذر تأكيد إيقاف خدمة الخلفية. أعد فتح التطبيق إن استمرت المشكلة.",
                isError = true,
            )
            message != null -> DriverStatus(message)
            else -> DriverStatus("تم إيقاف القناص. يمكنك تعديله أو تشغيله لاحقاً بعد تحقق المتطلبات.")
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
                    !a11y -> "تم إيقاف القناص لأن صلاحية إمكانية الوصول لم تعد مفعّلة."
                    !notifications -> "تم إيقاف القناص لأن إذن الإشعارات لم يعد مفعّلاً."
                    else -> "تم إيقاف القناص لأن الترخيص الذاكري لم يعد صالحاً."
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
            stopKeeper("تم إيقاف القناص قبل تعديل المرشحات؛ احفظ القواعد الجديدة ثم وافق على تشغيل جديد.")
        }
        draft = next
        filtersMessage = null
    }

    fun saveDraft(onSaved: () -> Unit = {}) {
        if (isSavingFilters) return
        val filters = draftValidation.filters
        if (filters == null) {
            filtersMessage = DriverStatus("صحح الحقول المعلَّمة قبل حفظ المرشحات.", isError = true)
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
                    filtersMessage = DriverStatus("حُفظت المرشحات كوثيقة واحدة. ستستخدم الخدمة هذه القواعد عند التشغيل التالي.")
                    onSaved()
                } else {
                    filtersMessage = DriverStatus("تعذر حفظ المرشحات على الجهاز. لم تتغير القواعد المستخدمة.", isError = true)
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
            runtimeMessage = DriverStatus("احفظ مسودة المرشحات أو تجاهلها قبل التشغيل.", isError = true)
            return
        }
        if (!draftValidation.isValid || UiFormValidation.filtersError(savedFilters) != null) {
            runtimeMessage = DriverStatus("صحح المرشحات واحفظها قبل تشغيل القناص.", isError = true)
            return
        }
        if (!savedFilters.anyActive()) {
            runtimeMessage = DriverStatus("أضف معيار قبول واحداً محفوظاً على الأقل قبل التشغيل.", isError = true)
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
                        "تعذر التحقق من الترخيص. جدّد الاشتراك أو سجّل الدخول مجدداً ثم أعد المحاولة.",
                        isError = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runtimeMessage = DriverStatus("تعذر التحقق من الترخيص. تحقق من اتصال الإنترنت ثم أعد المحاولة.", isError = true)
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
            runtimeMessage = DriverStatus("تغيّرت متطلبات التشغيل؛ تحقق منها ثم ابدأ من جديد.", isError = true)
            return
        }
        val result = latestToggleKeeper(true)
        if (result.isFailure) {
            Settings.isActive = false
            isActive = false
            runtimeMessage = DriverStatus("تعذر تشغيل القناص في الخلفية. تحقق من إعدادات النظام ثم أعد المحاولة.", isError = true)
        } else {
            isActive = true
            runtimeMessage = DriverStatus("القناص يعمل وفق المرشحات المحفوظة. الإيقاف متاح في أي وقت.")
        }
    }

    BackHandler(enabled = destination == DriverDestination.Filters) {
        navigateTo(DriverDestination.Dashboard)
    }

    if (showAccessibilityRequired) {
        AlertDialog(
            onDismissRequest = { showAccessibilityRequired = false },
            title = { Text("يلزم تفعيل إمكانية الوصول") },
            text = { Text("لا يمكن تشغيل القناص قبل تفعيل خدمة قناص الطلبات من إعدادات إمكانية الوصول في النظام.") },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityRequired = false
                    if (onOpenA11y().isFailure) {
                        runtimeMessage = DriverStatus("تعذر فتح إعدادات إمكانية الوصول. افتحها يدوياً من إعدادات الجهاز.", true)
                    }
                }) { Text("فتح الإعدادات") }
            },
            dismissButton = { TextButton(onClick = { showAccessibilityRequired = false }) { Text("إلغاء") } },
        )
    }

    if (showNotificationRequired) {
        AlertDialog(
            onDismissRequest = { showNotificationRequired = false },
            title = { Text("يلزم إذن الإشعارات") },
            text = { Text("يتطلب التشغيل إشعاراً دائماً وواضحاً يبيّن أن القناص يعمل.") },
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
            title = { Text("تأكيد تشغيل القناص") },
            text = {
                Text(
                    "عند التشغيل، تستخدم خدمة إمكانية الوصول ما يظهر في التطبيق المستهدف وفق المرشحات المحفوظة، " +
                        "وقد تنفذ الضغط على العرض المطابق. يمكنك الإيقاف في أي وقت."
                )
            },
            confirmButton = { TextButton(onClick = ::confirmActivation) { Text("موافق وتشغيل") } },
            dismissButton = { TextButton(onClick = { showActivationConsent = false }) { Text("إلغاء") } },
        )
    }

    if (showUnsavedChanges) {
        AlertDialog(
            onDismissRequest = { if (!isSavingFilters) { showUnsavedChanges = false; pendingDestination = null } },
            title = { Text("تغييرات مرشحات غير محفوظة") },
            text = { Text("احفظ المسودة لتطبيقها، أو تجاهلها للعودة إلى القواعد المحفوظة حالياً.") },
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
            title = { Text("إعادة ضبط المسودة؟") },
            text = { Text("سيُفرَّغ ما يظهر في محرر المرشحات. لن تتغير القواعد المحفوظة إلا إذا حفظت المسودة بعد ذلك.") },
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
        when (destination) {
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
                        runtimeMessage = DriverStatus("تعذر فتح إعدادات إمكانية الوصول. افتحها يدوياً من إعدادات الجهاز.", true)
                    }
                },
                onRequestNotifications = onRequestNotificationPermission,
                onOpenFilters = { navigateTo(DriverDestination.Filters) },
                onOpenAccount = { navigateTo(DriverDestination.Account) },
            )

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
            )
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
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
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
                                icon = { Icon(item.icon, contentDescription = item.label) },
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
) = DriverPage {
    QannasSectionTitle("لوحة القيادة", "تحكم واضح بالقناص وشروط تشغيله الفعلية.")
    OperationHero(
        isActive = isActive,
        isChecking = isCheckingActivation,
        onToggle = onToggle,
    )
    message?.let { QannasMessage(it.message, isError = it.isError) }

    QannasCard(
        title = "جاهزية التشغيل",
        subtitle = "لن يبدأ القناص تلقائياً. راجع كل شرط ثم وافق على التشغيل.",
        icon = Icons.Outlined.VerifiedUser,
    ) {
        ReadinessRow(
            icon = Icons.Outlined.AccessibilityNew,
            title = "إمكانية الوصول",
            ready = accessibilityEnabled,
            readyText = "مفعّلة",
            missingText = "غير مفعّلة",
            actionLabel = if (accessibilityEnabled) "فتح الإعدادات" else "تفعيل الصلاحية",
            onAction = onOpenA11y,
        )
        ReadinessRow(
            icon = Icons.Outlined.Notifications,
            title = "الإشعارات",
            ready = notificationsEnabled,
            readyText = "مسموح بها",
            missingText = "تحتاج سماحاً",
            actionLabel = if (notificationsEnabled) "مراجعة الإذن" else "السماح بالإشعارات",
            onAction = onRequestNotifications,
        )
        ReadinessRow(
            icon = Icons.Outlined.VerifiedUser,
            title = "الترخيص في الذاكرة",
            ready = licenseInMemory,
            readyText = "صالح الآن",
            missingText = "غير متحقق",
            actionLabel = "فتح الحساب",
            onAction = onOpenAccount,
        )
        ReadinessRow(
            icon = Icons.Outlined.FilterAlt,
            title = "المرشحات المحفوظة",
            ready = activeFilterCount > 0,
            readyText = "$activeFilterCount مرشح فعّال",
            missingText = "لا توجد قواعد قبول",
            actionLabel = "إدارة المرشحات",
            onAction = onOpenFilters,
        )
    }

    QannasCard(title = "كيف يعمل القناص؟", icon = Icons.Outlined.Info) {
        Text(
            "بعد موافقتك، تراقب خدمة إمكانية الوصول العروض الظاهرة وتطبّق المرشحات المحفوظة. " +
                "لا تُقبل العروض من دون قاعدة قبول صالحة، ويمكنك إيقاف التشغيل في أي وقت.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun OperationHero(
    isActive: Boolean,
    isChecking: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF142C37),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = if (isActive) Icons.Outlined.CheckCircle else Icons.Outlined.Stop,
                    contentDescription = if (isActive) "القناص يعمل" else "القناص متوقف",
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isActive) "القناص يعمل" else "القناص متوقف", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (isActive) "يطبّق المرشحات المحفوظة فقط." else "لن تبدأ المراقبة قبل مراجعة الشروط وموافقتك.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = isActive,
                    onCheckedChange = if (isChecking) null else onToggle,
                    enabled = !isChecking,
                )
            }
            Button(
                onClick = { onToggle(!isActive) },
                enabled = !isChecking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF142C37),
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("جارٍ التحقق من الترخيص")
                } else {
                    Icon(if (isActive) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (isActive) "إيقاف القناص" else "مراجعة الشروط والتشغيل")
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    icon: ImageVector,
    title: String,
    ready: Boolean,
    readyText: String,
    missingText: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = title, tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (ready) readyText else missingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        QannasStatusPill(if (ready) "جاهز" else "يلزم إجراء", ready = ready)
    }
    OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(actionLabel) }
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
        title = "المرشحات",
        subtitle = if (dirty) "لديك تعديلات غير محفوظة." else "تعرض القواعد المحفوظة حالياً.",
    )
    message?.let { QannasMessage(it.message, isError = it.isError) }
    if (validationErrors.isNotEmpty()) {
        QannasMessage("راجع الحقول المعلَّمة؛ لن تُحفظ قواعد غير صالحة ولن تُستبدل القواعد المستخدمة.", isError = true)
    }
    AdvancedFiltersEditor(
        draft = draft,
        errors = validationErrors,
        onDraftChange = onDraftChange,
    )
    QannasCard(title = "مراجعة وحفظ", subtitle = "الحفظ يكتب كل المرشحات دفعة واحدة بعد اكتمال التحقق.", icon = Icons.Outlined.Save) {
        Button(
            onClick = onSave,
            enabled = dirty && !isSaving && validationErrors.isEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (isSaving) "جارٍ الحفظ" else "حفظ المرشحات")
        }
        OutlinedButton(
            onClick = onRevert,
            enabled = dirty && !isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("استعادة القواعد المحفوظة") }
        TextButton(
            onClick = onReset,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("إعادة ضبط المسودة")
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
                error = "أدخل قيماً غير سالبة بصيغة صحيحة أو اترك الحقل فارغاً لمحاكاة قيمة غير مقروءة.",
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

    DriverPage {
        QannasSectionTitle("معاينة القرار", "اختبار محلي للمرشحات المحفوظة؛ لا يتصل بالخدمة ولا ينفذ أي ضغط.")
        QannasMessage("هذا ليس عرضاً حياً. تستخدم المعاينة فقط القيم التي تدخلها هنا والمرشحات المحفوظة.")
        QannasCard(title = "بيانات عرض محلي", subtitle = "اترك القياس فارغاً لاختبار كيفية تعامل القواعد مع بيانات غير مقروءة.", icon = Icons.Outlined.Visibility) {
            PreviewField(fare, "السعر", KeyboardType.Decimal) { fare = it; result = null }
            PreviewField(pickupKm, "مسافة الوصول (كم)", KeyboardType.Decimal) { pickupKm = it; result = null }
            PreviewField(tripKm, "مسافة الرحلة (كم)", KeyboardType.Decimal) { tripKm = it; result = null }
            PreviewField(pickupMinutes, "زمن الوصول (دقائق)", KeyboardType.Number) { pickupMinutes = it; result = null }
            PreviewField(tripMinutes, "مدة الرحلة (دقائق)", KeyboardType.Number) { tripMinutes = it; result = null }
            PreviewField(offerText, "نص العرض لاختبار الكلمات (اختياري)", KeyboardType.Text, singleLine = false) { offerText = it; result = null }
            Button(onClick = ::evaluate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Visibility, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("تقييم المعاينة محلياً")
            }
        }
        result?.let { preview -> PreviewVerdictCard(preview) }
    }
}

@Composable
private fun PreviewField(
    value: String,
    label: String,
    keyboardType: KeyboardType,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(2048)) },
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PreviewVerdictCard(result: PreviewResult) {
    if (result.error != null) {
        QannasMessage(result.error, isError = true)
        return
    }
    val verdict = result.verdict
    QannasCard(
        title = if (verdict.accept) "قرار المعاينة: قبول" else "قرار المعاينة: رفض",
        subtitle = if (verdict.accept) "وفق المرشحات المحفوظة والقيم المحلية المدخلة." else "الأسباب التالية من محرك القرار الفعلي.",
        icon = if (verdict.accept) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
    ) {
        QannasStatusPill(if (verdict.accept) "مقبول محلياً" else "مرفوض محلياً", ready = verdict.accept)
        if (verdict.reasons.isNotEmpty()) {
            verdict.reasons.forEach { reason ->
                Text("• ${reasonArabic(reason.code)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        verdict.farePerKm?.let { perKm ->
            Text("السعر المحتسب لكل كيلومتر: ${perKm.stripTrailingZeros().toPlainString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AccountPage(
    licenseInMemory: Boolean,
    message: DriverStatus?,
    onLicenseChanged: (Boolean) -> Unit,
    onMessage: (DriverStatus?) -> Unit,
) {
    SubscriptionScreen(
        licenseInMemory = licenseInMemory,
        externalMessage = message?.message,
        externalMessageIsError = message?.isError == true,
        onLicenseChanged = onLicenseChanged,
        onMessage = { nextMessage, isError ->
            onMessage(nextMessage?.let { DriverStatus(it, isError) })
        },
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
    ReasonCode.NO_FILTERS -> "لا توجد قاعدة قبول مفعّلة."
    ReasonCode.OUTSIDE_SCHEDULE -> "الوقت الحالي خارج جدول التشغيل المحفوظ."
    ReasonCode.INVALID_FILTER -> "إحدى القواعد المحفوظة غير صالحة."
    ReasonCode.FARE_MISSING -> "لا يمكن قراءة سعر العرض."
    ReasonCode.FARE_TOO_LOW -> "سعر العرض أدنى من الحد المطلوب."
    ReasonCode.FARE_TOO_HIGH -> "سعر العرض أعلى من الحد الأقصى."
    ReasonCode.PER_KM_MISSING -> "لا يمكن احتساب سعر الكيلومتر من البيانات المتاحة."
    ReasonCode.PER_KM_TOO_LOW -> "السعر لكل كيلومتر أقل من الحد المطلوب."
    ReasonCode.PICKUP_KM_MISSING -> "لا يمكن قراءة مسافة الوصول."
    ReasonCode.PICKUP_TOO_FAR -> "مسافة الوصول تتجاوز الحد المحفوظ."
    ReasonCode.PICKUP_MIN_MISSING -> "لا يمكن قراءة زمن الوصول."
    ReasonCode.PICKUP_TOO_LONG -> "زمن الوصول يتجاوز الحد المحفوظ."
    ReasonCode.TRIP_KM_MISSING -> "لا يمكن قراءة مسافة الرحلة."
    ReasonCode.TRIP_TOO_SHORT -> "مسافة الرحلة أدنى من الحد المطلوب."
    ReasonCode.TRIP_TOO_LONG -> "مسافة الرحلة تتجاوز الحد المحفوظ."
    ReasonCode.TRIP_MIN_MISSING -> "لا يمكن قراءة مدة الرحلة."
    ReasonCode.TRIP_MIN_TOO_LONG -> "مدة الرحلة تتجاوز الحد المحفوظ."
    ReasonCode.EXCLUDED_KEYWORD -> "يحتوي نص العرض على كلمة مستبعدة."
}

private fun redeemErrorMessage(error: Throwable?): String = when (error?.message) {
    "الكود غير صحيح أو استُخدم مسبقاً",
    "محاولات كثيرة؛ يرجى الانتظار قبل المحاولة مجدداً",
    "هذا الحساب مرتبط بجهاز آخر" -> error.message.orEmpty()
    else -> "تعذر تفعيل الكود. تحقق من الكود واتصال الإنترنت ثم أعد المحاولة."
}

private fun Context.isQannasAccessibilityEnabled(): Boolean {
    val expectedId = ComponentName(this, SniperA11yService::class.java).flattenToString()
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.id == expectedId }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

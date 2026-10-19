package app.qannas.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.qannas.Settings
import app.qannas.core.Filters
import app.qannas.core.Text
import app.qannas.network.SupabaseClient
import app.qannas.service.SniperA11yService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenA11y: () -> Result<Unit>,
    onToggleKeeper: (Boolean) -> Result<Unit>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var filters by remember { mutableStateOf(Settings.filters) }
    var minFareInput by remember { mutableStateOf(filters.minFare.orEmpty()) }
    var pickupMinutesInput by remember { mutableStateOf(filters.maxPickupMinutes?.toString().orEmpty()) }
    var minFareError by remember { mutableStateOf<String?>(null) }
    var pickupMinutesError by remember { mutableStateOf<String?>(null) }
    var advancedFiltersValid by remember { mutableStateOf(true) }
    var isActive by remember { mutableStateOf(Settings.isActive) }
    var activationCode by remember { mutableStateOf("") }
    var codeStatus by remember { mutableStateOf<InlineStatus?>(null) }
    var statusMessage by remember { mutableStateOf<InlineStatus?>(null) }
    var showActivationConsent by remember { mutableStateOf(false) }
    var showAccessibilityRequired by remember { mutableStateOf(false) }
    var showNotificationPermissionRequired by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember(context) { mutableStateOf(context.isQannasAccessibilityEnabled()) }
    var notificationPermissionAvailable by remember(context) { mutableStateOf(context.hasNotificationPermission()) }
    var isRedeeming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestToggleKeeper by rememberUpdatedState(onToggleKeeper)
    val latestIsActive by rememberUpdatedState(isActive)

    fun refreshRuntimePrerequisites() {
        val accessibilityIsEnabled = context.isQannasAccessibilityEnabled()
        val notificationsAreAllowed = context.hasNotificationPermission()
        accessibilityEnabled = accessibilityIsEnabled
        notificationPermissionAvailable = notificationsAreAllowed
        if ((!accessibilityIsEnabled || !notificationsAreAllowed || !SupabaseClient.hasValidLicense() || !Settings.isActive) && latestIsActive) {
            latestToggleKeeper(false)
            isActive = false
            Settings.isActive = false
            statusMessage = InlineStatus(
                if (!accessibilityIsEnabled) {
                    "تم إيقاف القناص لأن صلاحية إمكانية الوصول لم تعد مفعّلة. فعّلها ثم وافق على التشغيل مرة أخرى."
                } else if (!SupabaseClient.hasValidLicense()) {
                    "تم إيقاف القناص إلى أن يُتحقق من اشتراكك. جدّد الاشتراك أو سجّل الدخول مجدداً."
                } else {
                    "تم إيقاف القناص لأن إذن الإشعارات لم يعد مفعّلاً. اسمح بالإشعارات ثم وافق على التشغيل مرة أخرى."
                },
                isError = true
            )
        }
    }

    LaunchedEffect(context, notificationPermissionGranted) {
        refreshRuntimePrerequisites()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            refreshRuntimePrerequisites()
        }
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshRuntimePrerequisites()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun setKeeperActive(enabled: Boolean) {
        if (enabled && !SupabaseClient.hasValidLicense()) {
            statusMessage = InlineStatus("تحقق من الاشتراك قبل التشغيل؛ سجّل الدخول مجدداً عند انتهاء الجلسة.", true)
            return
        }
        val result = latestToggleKeeper(enabled)
        if (enabled && result.isFailure) {
            statusMessage = InlineStatus(
                "تعذر تشغيل القناص في الخلفية. تحقق من صلاحية الإشعارات وإعدادات النظام ثم أعد المحاولة.",
                isError = true
            )
            return
        }
        isActive = enabled
        Settings.isActive = enabled
        statusMessage = if (enabled) {
            InlineStatus("القناص قيد التشغيل. يمكنك إيقافه من هذا المفتاح في أي وقت.", isError = false)
        } else if (result.isFailure) {
            InlineStatus("تم إيقاف القناص محلياً، لكن تعذر إيقاف خدمة الخلفية. أعد فتح التطبيق إذا استمرت المشكلة.", isError = true)
        } else {
            InlineStatus("تم إيقاف القناص.", isError = false)
        }
    }

    if (showAccessibilityRequired) {
        AlertDialog(
            onDismissRequest = { showAccessibilityRequired = false },
            title = { Text("يلزم تفعيل إمكانية الوصول") },
            text = {
                Text("لا يمكن تشغيل القناص قبل تفعيل خدمة قناص الطلبات من إعدادات إمكانية الوصول في النظام.")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onOpenA11y().isSuccess) {
                        showAccessibilityRequired = false
                    } else {
                        showAccessibilityRequired = false
                        statusMessage = InlineStatus(
                            "تعذر فتح إعدادات إمكانية الوصول. افتحها يدوياً من إعدادات الجهاز.",
                            isError = true
                        )
                    }
                }) { Text("فتح الإعدادات") }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityRequired = false }) { Text("إلغاء") }
            }
        )
    }

    if (showActivationConsent) {
        AlertDialog(
            onDismissRequest = { showActivationConsent = false },
            title = { Text("تأكيد تشغيل القناص") },
            text = {
                Text(
                    "عند التشغيل، تستخدم خدمة إمكانية الوصول ما يظهر في التطبيق المستهدف وفق المرشحات التي حددتها، " +
                        "وقد تنفذ الضغط على العرض المطابق. يمكنك إيقافها في أي وقت من هذا المفتاح."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showActivationConsent = false
                    setKeeperActive(true)
                }) { Text("موافق وتشغيل") }
            },
            dismissButton = {
                TextButton(onClick = { showActivationConsent = false }) { Text("إلغاء") }
            }
        )
    }

    if (showNotificationPermissionRequired) {
        AlertDialog(
            onDismissRequest = { showNotificationPermissionRequired = false },
            title = { Text("يلزم إذن الإشعارات") },
            text = {
                Text("يتطلب التشغيل إشعاراً دائماً وواضحاً يبيّن أن القناص يعمل. اسمح بالإشعارات ثم اختر التشغيل مرة أخرى.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationPermissionRequired = false
                    onRequestNotificationPermission()
                }) { Text("السماح بالإشعارات") }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationPermissionRequired = false }) { Text("إلغاء") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("إعدادات قناص الطلبات", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = minFareInput,
            onValueChange = { value ->
                minFareInput = value
                val normalized = Text.normalize(value)
                when {
                    normalized.isEmpty() -> {
                        minFareError = null
                        filters = filters.copy(minFare = null)
                        Settings.filters = filters
                    }
                    Text.parseNumber(normalized) != null -> {
                        minFareError = null
                        filters = filters.copy(minFare = normalized)
                        Settings.filters = filters
                    }
                    else -> {
                        minFareError = "أدخل رقماً صالحاً أو اترك الحقل فارغاً."
                        if (isActive) setKeeperActive(false)
                    }
                }
            },
            label = { Text("الحد الأدنى للسعر (مثال: 10)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = minFareError != null,
            supportingText = minFareError?.let { error -> { Text(error) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = pickupMinutesInput,
            onValueChange = { value ->
                pickupMinutesInput = value
                val normalized = Text.normalize(value)
                val minutes = normalized.toIntOrNull()
                when {
                    normalized.isEmpty() -> {
                        pickupMinutesError = null
                        filters = filters.copy(maxPickupMinutes = null)
                        Settings.filters = filters
                    }
                    minutes != null && minutes >= 0 -> {
                        pickupMinutesError = null
                        filters = filters.copy(maxPickupMinutes = minutes)
                        Settings.filters = filters
                    }
                    else -> {
                        pickupMinutesError = "أدخل عدداً صحيحاً غير سالب أو اترك الحقل فارغاً."
                        if (isActive) setKeeperActive(false)
                    }
                }
            },
            label = { Text("أقصى مسافة للراكب (بالدقائق)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = pickupMinutesError != null,
            supportingText = pickupMinutesError?.let { error -> { Text(error) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
        AdvancedFiltersEditor(filters = filters, onChange = {
            filters = it
            Settings.filters = it
        }, onValidityChange = { valid ->
            advancedFiltersValid = valid
            if (!valid && isActive) setKeeperActive(false)
        })
        Spacer(modifier = Modifier.height(32.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("تشغيل القناص", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = isActive,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        setKeeperActive(false)
                    } else if (minFareError != null || pickupMinutesError != null || !advancedFiltersValid || UiFormValidation.filtersError(filters) != null) {
                        statusMessage = InlineStatus("صحح قيم المرشحات غير الصالحة قبل تشغيل القناص.", isError = true)
                    } else if (!filters.anyActive()) {
                        statusMessage = InlineStatus(
                            "أضف معيار قبول واحداً على الأقل قبل التشغيل؛ من دون مرشحات لن يلتقط القناص أي عرض.",
                            isError = true
                        )
                    } else if (!accessibilityEnabled) {
                        showAccessibilityRequired = true
                    } else if (!notificationPermissionAvailable) {
                        showNotificationPermissionRequired = true
                    } else {
                        showActivationConsent = true
                    }
                }
            )
        }

        statusMessage?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            StatusCard(status)
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                val result = onOpenA11y()
                if (result.isFailure) {
                    statusMessage = InlineStatus(
                        "تعذر فتح إعدادات إمكانية الوصول. افتحها يدوياً من إعدادات الجهاز.",
                        isError = true
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (accessibilityEnabled) "صلاحية إمكانية الوصول مفعّلة" else "تفعيل صلاحية إمكانية الوصول")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

        Text("تجديد الاشتراك", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = activationCode,
                onValueChange = { activationCode = UiFormValidation.normalizeActivationCode(it) },
                label = { Text("كود التفعيل (مثال: QAN-XXXX)") },
                enabled = !isRedeeming,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(enabled = !isRedeeming, onClick = {
                val validationError = UiFormValidation.activationCodeError(activationCode)
                if (validationError != null) {
                    codeStatus = InlineStatus(validationError, isError = true)
                } else if (!SupabaseClient.hasRedeemableSession()) {
                    codeStatus = InlineStatus(
                        "انتهت جلسة التفعيل. سجّل الدخول مجدداً ثم أعد إدخال الكود.",
                        isError = true
                    )
                } else {
                    isRedeeming = true
                    codeStatus = null
                    scope.launch {
                        try {
                            val result = SupabaseClient.redeemCode(activationCode)
                            if (result.getOrNull() == true && SupabaseClient.revalidateLicense().getOrNull() == true) {
                                codeStatus = InlineStatus("تم تفعيل الاشتراك بنجاح. يمكنك الآن إعداد المرشحات وتشغيل القناص.", isError = false)
                                activationCode = ""
                            } else {
                                codeStatus = InlineStatus(redeemCodeErrorMessage(result.exceptionOrNull()), isError = true)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            codeStatus = InlineStatus("تعذر التحقق من الكود. تحقق من اتصال الإنترنت ثم أعد المحاولة.", isError = true)
                        } finally {
                            isRedeeming = false
                        }
                    }
                }
            }) {
                if (isRedeeming) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("تفعيل")
            }
        }
        codeStatus?.let { status ->
            Spacer(modifier = Modifier.height(8.dp))
            StatusCard(status)
        }
    }
}

private data class InlineStatus(val message: String, val isError: Boolean)

@Composable
private fun StatusCard(status: InlineStatus) {
    val containerColor = if (status.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (status.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Text(
            text = status.message,
            modifier = Modifier.padding(12.dp),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun Context.isQannasAccessibilityEnabled(): Boolean {
    val expectedId = ComponentName(this, SniperA11yService::class.java).flattenToString()
    val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return accessibilityManager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.id == expectedId }
}

private fun Context.hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun redeemCodeErrorMessage(error: Throwable?): String = when (error?.message) {
    "الكود غير صحيح أو مستخدم مسبقاً",
    "محاولات كثيرة خاطئة، يرجى الانتظار",
    "هذا الحساب مرتبط بجهاز آخر" -> error.message.orEmpty()
    else -> "تعذر تفعيل الكود. تحقق من الكود واتصال الإنترنت ثم أعد المحاولة."
}

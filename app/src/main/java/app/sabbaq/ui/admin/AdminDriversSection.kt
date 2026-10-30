package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.admin.AdminDriver
import app.sabbaq.admin.AdminDriverVersion
import app.sabbaq.admin.AdminInputs
import app.sabbaq.admin.AdminRepository
import app.sabbaq.ui.SabbaqAnimatedMessage
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqStatusPill
import java.time.Instant
import kotlinx.coroutines.launch

internal fun driverStatusLabel(status: String): String = when (status) {
    "active" -> (if (LocaleManager.isArabic()) "مفعّل" else "Active")
    "pending" -> (if (LocaleManager.isArabic()) "بانتظار التفعيل" else "Pending activation")
    "rejected" -> (if (LocaleManager.isArabic()) "مرفوض" else "Rejected")
    "banned" -> (if (LocaleManager.isArabic()) "محظور" else "Banned")
    else -> status
}

/**
 * «السائقون»: a page of 100 accounts, newest first, each with the build it runs and its device.
 * The search filters the loaded page by name or phone without another request.
 */
@Composable
internal fun AdminDriversSection(session: AdminSession, open: (AdminRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var drivers by remember { mutableStateOf<List<AdminDriver>>(emptyList()) }
    var versions by remember { mutableStateOf<Map<String, AdminDriverVersion>?>(null) }
    var versionsFailed by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    // An action's result survives the reload it triggers instead of giving way to «تم تحميل».
    var kept by remember { mutableStateOf<AdminNotice?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf<AdminDriver?>(null) }
    var resetting by remember { mutableStateOf<String?>(null) }
    var zoneLimitFor by remember { mutableStateOf<AdminDriver?>(null) }

    LaunchedEffect(offset, reload) {
        loading = true
        AdminRepository.drivers(offset).fold(
            onSuccess = { page ->
                drivers = page.items; hasMore = page.hasMore
                notice = kept ?: success(if (LocaleManager.isArabic()) "تم تحميل ${page.items.size} سجل من السائقين." else "Loaded ${page.items.size} driver records.")
                kept = null
                versions = null; versionsFailed = false
                AdminRepository.driverVersions(page.items.map { it.id }).fold(
                    onSuccess = { versions = it },
                    onFailure = { versionsFailed = true; session.failureOf(it, "") },
                )
            },
            onFailure = { notice = session.failureOf(it, (if (LocaleManager.isArabic()) "تعذر تحميل بيانات السائقين. تحقق من الاتصال ثم أعد المحاولة." else "Could not load the driver data. Check the connection, then try again.")) },
        )
        loading = false
    }

    val needle = query.trim()
    val shown = if (needle.isEmpty()) drivers else drivers.filter { it.name.contains(needle, ignoreCase = true) || it.phone.contains(needle) }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-drivers"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (LocaleManager.isArabic()) "راجع بيانات الحسابات وأعد ربط الجهاز عند الحاجة." else "Review the account data and re-link the device when needed."), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text((if (LocaleManager.isArabic()) "بحث بالاسم أو الهاتف في هذه الصفحة" else "Search by name or phone on this page")) }, modifier = Modifier.fillMaxWidth(),
                )
                SabbaqButton((if (LocaleManager.isArabic()) "تحديث بيانات السائقين" else "Refresh driver data"), onClick = { offset = 0; reload++ }, style = SabbaqButtonStyle.Secondary,
                    busy = loading, busyText = (if (LocaleManager.isArabic()) "جارٍ التحميل…" else "Loading…"))
                SabbaqAnimatedMessage(notice?.text, notice?.tone ?: app.sabbaq.ui.SabbaqTone.Info)
                AdminPager(offset, AdminRepository.PAGE_SIZE, hasMore, loading) { direction ->
                    offset = (offset + direction * AdminRepository.PAGE_SIZE).coerceAtLeast(0)
                }
            }
        }
        if (!loading && shown.isEmpty()) item(key = "empty") {
            Text(if (needle.isEmpty()) (if (LocaleManager.isArabic()) "لا توجد سجلات في هذه الصفحة." else "No records on this page.") else (if (LocaleManager.isArabic()) "لا يوجد سائق يطابق البحث في هذه الصفحة." else "No driver matches the search on this page."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shown, key = { it.id }) { driver ->
            DriverCard(
                driver = driver,
                version = when {
                    versions != null -> AdminInputs.describeVersion(versions?.get(driver.id), ::formatDateTime)
                    versionsFailed -> (if (LocaleManager.isArabic()) "تعذر التحميل" else "Load failed")
                    else -> "…"
                },
                resetting = resetting == driver.id,
                onDiagnose = { open(AdminRequest.DiagnoseDriver(driver.id, driver.name)) },
                onNotify = { open(AdminRequest.NotifyDriver(driver.id, driver.name)) },
                onMessage = { open(AdminRequest.MessageDriver(driver.id)) },
                onReset = { confirmReset = driver },
                onZoneLimit = { zoneLimitFor = driver },
            )
        }
        if (shown.size > 5) item(key = "foot") {
            AdminPager(offset, AdminRepository.PAGE_SIZE, hasMore, loading) { direction ->
                offset = (offset + direction * AdminRepository.PAGE_SIZE).coerceAtLeast(0)
            }
        }
    }

    zoneLimitFor?.let { driver -> AdminDriverZoneLimitDialog(session, driver.id, driver.name) { zoneLimitFor = null } }
    confirmReset?.let { driver ->
        AdminConfirm(
            title = (if (LocaleManager.isArabic()) "فك ربط الجهاز" else "Unlink the device"),
            text = if (LocaleManager.isArabic()) "هل أنت متأكد من فك ربط جهاز ${driver.name}؟ سيُطلب منه الدخول من جديد، ويُربط حسابه بأول جهاز يدخل منه." else "Are you sure you want to unlink ${driver.name}'s device? They will be asked to sign in again, and their account links to the first device they sign in from.",
            confirm = (if (LocaleManager.isArabic()) "فك الربط" else "Unlink"),
            onConfirm = {
                scope.launch {
                    resetting = driver.id
                    AdminRepository.resetDevice(driver.id).fold(
                        onSuccess = { kept = success((if (LocaleManager.isArabic()) "تم فك ربط الجهاز." else "The device was unlinked.")); notice = kept; reload++ },
                        onFailure = { notice = session.failureOf(it, (if (LocaleManager.isArabic()) "تعذر فك ربط الجهاز." else "Could not unlink the device.")) },
                    )
                    resetting = null
                }
            },
            onDismiss = { confirmReset = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriverCard(
    driver: AdminDriver,
    version: String,
    resetting: Boolean,
    onDiagnose: () -> Unit,
    onNotify: () -> Unit,
    onMessage: () -> Unit,
    onReset: () -> Unit,
    onZoneLimit: () -> Unit,
) {
    SabbaqCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(driver.name, style = MaterialTheme.typography.titleMedium)
                AdminFact((if (LocaleManager.isArabic()) "الهاتف" else "Phone"), driver.phone.ifEmpty { (if (LocaleManager.isArabic()) "غير محدد" else "Not set") }, ltr = true)
            }
            SabbaqStatusPill(driverStatusLabel(driver.status), ready = driver.status == "active")
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AdminFact((if (LocaleManager.isArabic()) "إصدار سبّاق" else "Sabbaq version"), version)
            AdminFact((if (LocaleManager.isArabic()) "نهاية الاشتراك" else "Subscription end"), subscriptionText(driver.proUntil))
            driver.trialUntil?.let { AdminFact((if (LocaleManager.isArabic()) "التجربة حتى" else "Trial until"), formatDate(it)) }
            AdminFact((if (LocaleManager.isArabic()) "معرف الجهاز" else "Device ID"), driver.deviceId ?: (if (LocaleManager.isArabic()) "غير مرتبط" else "Not linked"), ltr = driver.deviceId != null)
            AdminFact((if (LocaleManager.isArabic()) "تاريخ التسجيل" else "Registration date"), formatDate(driver.createdAt))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onDiagnose) { Text((if (LocaleManager.isArabic()) "تشخيص الشكوى" else "Diagnose complaint")) }
            OutlinedButton(onClick = onNotify) { Text((if (LocaleManager.isArabic()) "إرسال إشعار" else "Send notification")) }
            OutlinedButton(onClick = onMessage) { Text((if (LocaleManager.isArabic()) "مراسلة" else "Message")) }
            OutlinedButton(onClick = onZoneLimit) { Text((if (LocaleManager.isArabic()) "حدّ المناطق" else "Zone limit")) }
            SabbaqButton((if (LocaleManager.isArabic()) "فك ربط الجهاز" else "Unlink the device"), onClick = onReset, style = SabbaqButtonStyle.Destructive, fillWidth = false,
                busy = resetting, busyText = (if (LocaleManager.isArabic()) "جارٍ فك الربط…" else "Unlinking…"))
        }
    }
}

private fun subscriptionText(until: Instant?): String = when {
    until == null -> (if (LocaleManager.isArabic()) "غير محدد" else "Not set")
    until.isBefore(Instant.now()) -> (if (LocaleManager.isArabic()) "${formatDate(until)} (منتهٍ)" else "${formatDate(until)} (expired)")
    else -> formatDate(until)
}

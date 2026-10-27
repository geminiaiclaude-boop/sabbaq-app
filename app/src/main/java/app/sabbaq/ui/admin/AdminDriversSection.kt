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
    "active" -> "مفعّل"
    "pending" -> "بانتظار التفعيل"
    "rejected" -> "مرفوض"
    "banned" -> "محظور"
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
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf<AdminDriver?>(null) }
    var resetting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(offset, reload) {
        loading = true
        AdminRepository.drivers(offset).fold(
            onSuccess = { page ->
                drivers = page.items; hasMore = page.hasMore
                notice = success("تم تحميل ${page.items.size} سجل من السائقين.")
                versions = null; versionsFailed = false
                AdminRepository.driverVersions(page.items.map { it.id }).fold(
                    onSuccess = { versions = it },
                    onFailure = { versionsFailed = true; session.failureOf(it, "") },
                )
            },
            onFailure = { notice = session.failureOf(it, "تعذر تحميل بيانات السائقين. تحقق من الاتصال ثم أعد المحاولة.") },
        )
        loading = false
    }

    val needle = query.trim()
    val shown = if (needle.isEmpty()) drivers else drivers.filter { it.name.contains(needle, ignoreCase = true) || it.phone.contains(needle) }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-drivers"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("راجع بيانات الحسابات وأعد ربط الجهاز عند الحاجة.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text("بحث بالاسم أو الهاتف في هذه الصفحة") }, modifier = Modifier.fillMaxWidth(),
                )
                SabbaqButton("تحديث بيانات السائقين", onClick = { offset = 0; reload++ }, style = SabbaqButtonStyle.Secondary,
                    busy = loading, busyText = "جارٍ التحميل…")
                SabbaqAnimatedMessage(notice?.text, notice?.tone ?: app.sabbaq.ui.SabbaqTone.Info)
                AdminPager(offset, AdminRepository.PAGE_SIZE, hasMore, loading) { direction ->
                    offset = (offset + direction * AdminRepository.PAGE_SIZE).coerceAtLeast(0)
                }
            }
        }
        if (!loading && shown.isEmpty()) item(key = "empty") {
            Text(if (needle.isEmpty()) "لا توجد سجلات في هذه الصفحة." else "لا يوجد سائق يطابق البحث في هذه الصفحة.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(shown, key = { it.id }) { driver ->
            DriverCard(
                driver = driver,
                version = when {
                    versions != null -> AdminInputs.describeVersion(versions?.get(driver.id), ::formatDateTime)
                    versionsFailed -> "تعذر التحميل"
                    else -> "…"
                },
                resetting = resetting == driver.id,
                onDiagnose = { open(AdminRequest.DiagnoseDriver(driver.id, driver.name)) },
                onNotify = { open(AdminRequest.NotifyDriver(driver.id, driver.name)) },
                onMessage = { open(AdminRequest.MessageDriver(driver.id)) },
                onReset = { confirmReset = driver },
            )
        }
        if (shown.size > 5) item(key = "foot") {
            AdminPager(offset, AdminRepository.PAGE_SIZE, hasMore, loading) { direction ->
                offset = (offset + direction * AdminRepository.PAGE_SIZE).coerceAtLeast(0)
            }
        }
    }

    confirmReset?.let { driver ->
        AdminConfirm(
            title = "فك ربط الجهاز",
            text = "هل أنت متأكد من فك ربط جهاز ${driver.name}؟ سيُطلب منه الدخول من جديد، ويُربط حسابه بأول جهاز يدخل منه.",
            confirm = "فك الربط",
            onConfirm = {
                scope.launch {
                    resetting = driver.id
                    AdminRepository.resetDevice(driver.id).fold(
                        onSuccess = { notice = success("تم فك ربط الجهاز."); reload++ },
                        onFailure = { notice = session.failureOf(it, "تعذر فك ربط الجهاز.") },
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
) {
    SabbaqCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(driver.name, style = MaterialTheme.typography.titleMedium)
                AdminFact("الهاتف", driver.phone.ifEmpty { "غير محدد" }, ltr = true)
            }
            SabbaqStatusPill(driverStatusLabel(driver.status), ready = driver.status == "active")
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AdminFact("إصدار سبّاق", version)
            AdminFact("نهاية الاشتراك", subscriptionText(driver.proUntil))
            driver.trialUntil?.let { AdminFact("التجربة حتى", formatDate(it)) }
            AdminFact("معرف الجهاز", driver.deviceId ?: "غير مرتبط", ltr = driver.deviceId != null)
            AdminFact("تاريخ التسجيل", formatDate(driver.createdAt))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onDiagnose) { Text("تشخيص الشكوى") }
            OutlinedButton(onClick = onNotify) { Text("إرسال إشعار") }
            OutlinedButton(onClick = onMessage) { Text("مراسلة") }
            SabbaqButton("فك ربط الجهاز", onClick = onReset, style = SabbaqButtonStyle.Destructive, fillWidth = false,
                busy = resetting, busyText = "جارٍ فك الربط…")
        }
    }
}

private fun subscriptionText(until: Instant?): String = when {
    until == null -> "غير محدد"
    until.isBefore(Instant.now()) -> "${formatDate(until)} (منتهٍ)"
    else -> formatDate(until)
}

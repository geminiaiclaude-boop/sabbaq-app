package app.sabbaq.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.admin.AdminConsole
import app.sabbaq.admin.AdminDriverDetail
import app.sabbaq.admin.AdminDriverList
import app.sabbaq.admin.AdminDriverRow
import app.sabbaq.admin.AdminRepository
import app.sabbaq.admin.DriverFilter
import app.sabbaq.admin.LiveState
import app.sabbaq.admin.PlanState
import app.sabbaq.ui.SabbaqAnimatedMessage
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun driverStatusLabel(status: String): String = when (status) {
    "active" -> (if (LocaleManager.isArabic()) "مفعّل" else "Active")
    "pending" -> (if (LocaleManager.isArabic()) "بانتظار التفعيل" else "Pending activation")
    "rejected" -> (if (LocaleManager.isArabic()) "مرفوض" else "Rejected")
    "banned" -> (if (LocaleManager.isArabic()) "محظور" else "Banned")
    else -> status
}

private val Monitoring = Color(0xFF1E9E5A)
private val OnlineIdle = Color(0xFFE0A100)

@Composable
private fun liveColor(state: LiveState): Color = when (state) {
    LiveState.MONITORING -> Monitoring
    LiveState.ONLINE -> OnlineIdle
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun planColors(plan: PlanState): Pair<Color, Color> = when (plan) {
    PlanState.SUBSCRIBED -> Monitoring.copy(alpha = 0.16f) to Monitoring
    PlanState.TRIAL -> OnlineIdle.copy(alpha = 0.18f) to Color(0xFF8A6200)
    PlanState.EXPIRED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    PlanState.NONE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
}

/** The plan as a small coloured label: one word per state, never a bare date. */
@Composable
private fun PlanPill(plan: PlanState) {
    val (bg, fg) = planColors(plan)
    Text(
        AdminConsole.planLabel(plan),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun LiveDot(state: LiveState, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(liveColor(state)))
}

/**
 * «السائقون»: a table of every account, newest activity first. Each row reads at a glance (live
 * state, name and phone, plan, last seen) and opens the driver's own page. Search and filters run
 * on the server, over every account, not just the loaded page.
 */
@Composable
internal fun AdminDriversSection(session: AdminSession, consume: () -> AdminRequest?, open: (AdminRequest) -> Unit) {
    var openDriver by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { (consume() as? AdminRequest.OpenDriver)?.let { openDriver = it.driverId } }
    BackHandler(enabled = openDriver != null) { openDriver = null }
    val id = openDriver
    if (id != null) {
        AdminDriverPage(session, id, open, onBack = { openDriver = null })
    } else {
        DriversTable(session, onOpen = { openDriver = it })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriversTable(session: AdminSession, onOpen: (String) -> Unit) {
    val ar = LocaleManager.isArabic()
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(DriverFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableStateOf<AdminDriverList?>(null) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(query, filter, offset, reload) {
        // Typing waits a moment so every letter is not a request.
        if (query.isNotEmpty()) delay(400L)
        loading = true
        AdminRepository.driverList(query, filter, offset, PAGE).fold(
            onSuccess = { page = it; notice = null },
            onFailure = { notice = session.failureOf(it, if (ar) "تعذر تحميل السائقين. تحقق من الاتصال ثم أعد المحاولة." else "Could not load the drivers. Check the connection, then try again.") },
        )
        loading = false
    }
    // The live column ages on its own: refresh every minute while the table is open.
    LaunchedEffect(Unit) { while (true) { delay(60_000L); reload++ } }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-drivers"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40); offset = 0 }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text(if (ar) "بحث بالاسم أو رقم الجوال" else "Search by name or phone") },
                    modifier = Modifier.fillMaxWidth().testTag("admin-drivers-search"),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DriverFilter.entries.forEach { f ->
                        val count = page?.counts?.get(f)
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f; offset = 0 },
                            label = { Text(AdminConsole.filterLabel(f) + (count?.let { " $it" } ?: ""), maxLines = 1) },
                            modifier = Modifier.testTag("admin-drivers-filter-${f.wire}"),
                        )
                    }
                }
                SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
                page?.let { p ->
                    Text(
                        if (ar) "${p.total} سائق" + (if (loading) " · جارٍ التحديث…" else "") else "${p.total} drivers" + (if (loading) " · updating…" else ""),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "columns") { TableHeader() }
        val items = page?.items.orEmpty()
        if (!loading && page != null && items.isEmpty()) item(key = "empty") {
            Text(if (ar) "لا يوجد سائق يطابق البحث أو التصفية." else "No driver matches the search or filter.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        }
        items(items, key = { it.id }) { row ->
            DriverTableRow(row) { onOpen(row.id) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "pager") {
            Box(Modifier.padding(top = 12.dp)) {
                AdminPager(offset, PAGE, page?.hasMore == true, loading) { direction -> offset = (offset + direction * PAGE).coerceAtLeast(0) }
            }
        }
    }
}

private const val PAGE = 50

@Composable
private fun TableHeader() {
    val ar = LocaleManager.isArabic()
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ar) "السائق" else "Driver", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(if (ar) "الاشتراك" else "Plan", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(86.dp))
            Text(if (ar) "آخر ظهور" else "Last seen", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
        }
    }
}

@Composable
private fun DriverTableRow(row: AdminDriverRow, onClick: () -> Unit) {
    val ar = LocaleManager.isArabic()
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (ar) "فتح صفحة السائق" else "Open driver page", onClick = onClick)
            .heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("admin-driver-row-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveDot(row.live)
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = buildList {
                add(row.phone.ifEmpty { if (ar) "بلا رقم" else "no phone" })
                if (row.status != "active") add(driverStatusLabel(row.status))
                AdminConsole.issueLabel(row.issue)?.takeIf { row.live == LiveState.ONLINE }?.let { add(it) }
            }.joinToString(" · ")
            Text(second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(86.dp)) { PlanPill(row.plan) }
        Text(
            if (row.live == LiveState.MONITORING) (if (ar) "يراقب الآن" else "live") else AdminConsole.ago(row.lastSeen),
            style = MaterialTheme.typography.labelMedium,
            color = if (row.live == LiveState.MONITORING) Monitoring else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.width(72.dp),
        )
    }
}

/** Everything about one driver, and every action on the account, on its own page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminDriverPage(session: AdminSession, driverId: String, open: (AdminRequest) -> Unit, onBack: () -> Unit) {
    val ar = LocaleManager.isArabic()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var detail by remember { mutableStateOf<AdminDriverDetail?>(null) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var zoneLimit by remember { mutableStateOf(false) }

    LaunchedEffect(driverId, reload) {
        loading = true
        AdminRepository.driverDetail(driverId).fold(
            onSuccess = { detail = it },
            onFailure = { notice = session.failureOf(it, if (ar) "تعذر تحميل صفحة السائق." else "Could not load the driver page.") },
        )
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-driver-page"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "back") {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onBack).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
                Text(if (ar) "كل السائقين" else "All drivers", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        item(key = "notice") { SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info) }
        val d = detail
        if (d == null) {
            item(key = "loading") {
                Text(if (loading) (if (ar) "جارٍ التحميل…" else "Loading…") else "", style = MaterialTheme.typography.bodyMedium)
            }
            return@LazyColumn
        }
        val r = d.row
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveDot(r.live, 12)
                    Text(AdminConsole.liveLabel(r.live), style = MaterialTheme.typography.titleSmall, color = liveColor(r.live))
                    PlanPill(r.plan)
                    if (r.status != "active") Text(driverStatusLabel(r.status), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item(key = "live") {
            SabbaqCard(title = if (ar) "التطبيق الآن" else "The app now") {
                AdminFact(if (ar) "الحالة" else "State", AdminConsole.liveLabel(r.live))
                AdminConsole.issueLabel(r.issue)?.let { AdminFact(if (ar) "ما يمنعه" else "What stops it", it) }
                AdminFact(if (ar) "آخر ظهور" else "Last seen", formatDateTime(r.lastSeen) + " (" + AdminConsole.ago(r.lastSeen) + ")")
                AdminFact(if (ar) "آخر نبضة من التطبيق" else "Last app heartbeat", formatDateTime(d.heartbeatAt))
                AdminFact(if (ar) "آخر تحقق من الاشتراك" else "Last licence check", formatDateTime(d.licenceCheckAt))
                AdminFact(if (ar) "إصدار سبّاق" else "Sabbaq version",
                    listOfNotNull(r.versionName, r.versionCode?.let { "($it)" }).joinToString(" ").ifBlank { if (ar) "غير معروف" else "Unknown" }, ltr = true)
                d.androidApi?.let { api -> AdminFact(if (ar) "أندرويد" else "Android", app.sabbaq.admin.AdminInputs.androidRelease(api) ?: "API $api", ltr = true) }
                d.rideAppVersion?.let { AdminFact(if (ar) "إصدار تطبيق الرحلات" else "Ride app version", it, ltr = true) }
                AdminConsole.deviceLine(d.device)?.let { AdminFact(if (ar) "الجهاز" else "Device", it) }
                if (d.flags.isNotEmpty()) FlagsRow(d.flags)
            }
        }
        item(key = "plan") {
            SabbaqCard(title = if (ar) "الاشتراك" else "Subscription") {
                AdminFact(if (ar) "الحالة" else "State", AdminConsole.planLabel(r.plan))
                when (r.plan) {
                    PlanState.SUBSCRIBED -> AdminFact(if (ar) "ينتهي في" else "Ends on", formatDate(r.proUntil) + daysLeftText(r.proUntil))
                    PlanState.TRIAL -> AdminFact(if (ar) "التجربة تنتهي في" else "Trial ends on", formatDate(r.trialUntil) + daysLeftText(r.trialUntil))
                    PlanState.EXPIRED -> AdminFact(if (ar) "انتهى في" else "Ended on", formatDate(r.planUntil))
                    PlanState.NONE -> AdminFact(if (ar) "التفاصيل" else "Details", if (ar) "لم يبدأ تجربة ولم يشترك بعد" else "No trial or subscription yet")
                }
                if (d.codes.isEmpty()) {
                    Text(if (ar) "لم يستخدم أي كود تفعيل." else "No activation code used.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(if (ar) "أكواد التفعيل المستخدمة" else "Activation codes used", style = MaterialTheme.typography.labelLarge)
                    d.codes.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.code, style = MaterialTheme.typography.bodyMedium)
                                Text(listOfNotNull(c.days?.let { if (ar) "$it يوم" else "$it days" }, c.batch, formatDate(c.redeemedAt)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(c.code)); notice = success(if (ar) "نُسخ الكود." else "Code copied.") }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = if (ar) "نسخ الكود" else "Copy code")
                            }
                        }
                    }
                }
            }
        }
        item(key = "activity") {
            SabbaqCard(title = if (ar) "النشاط" else "Activity") {
                Text(if (ar) "آخر 24 ساعة" else "Last 24 hours", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(if (ar) "طلبات رآها" else "Offers seen", d.dayOffers)
                    Stat(if (ar) "طابقت شروطه" else "Matched", d.dayMatched)
                    Stat(if (ar) "رُفضت" else "Rejected", d.dayRejected)
                    Stat(if (ar) "نقرات قبول" else "Accept taps", d.dayTaps)
                    Stat(if (ar) "قبول مؤكد" else "Confirmed", d.dayConfirmed)
                }
                Text(if (ar) "آخر 7 أيام: ${d.weekOffers} طلب، ${d.weekConfirmed} قبول مؤكد" else "Last 7 days: ${d.weekOffers} offers, ${d.weekConfirmed} confirmed",
                    style = MaterialTheme.typography.bodyMedium)
                if (d.rejectReasons.isNotEmpty()) {
                    Text(if (ar) "أكثر أسباب الرفض (7 أيام)" else "Top rejection reasons (7 days)", style = MaterialTheme.typography.labelLarge)
                    d.rejectReasons.forEach { AdminFact(AdminConsole.reasonLabel(it.code), it.count.toString()) }
                }
            }
        }
        item(key = "account") {
            SabbaqCard(title = if (ar) "الحساب" else "Account") {
                AdminFact(if (ar) "الجوال" else "Phone", r.phone.ifEmpty { if (ar) "بلا رقم" else "None" }, ltr = r.phone.isNotEmpty())
                d.email?.let { AdminFact(if (ar) "البريد" else "Email", it, ltr = true) }
                AdminFact(if (ar) "حالة الحساب" else "Account status", driverStatusLabel(r.status))
                AdminFact(if (ar) "تاريخ التسجيل" else "Registered", formatDate(r.createdAt))
                AdminFact(if (ar) "الجهاز المرتبط" else "Linked device", d.deviceId ?: (if (ar) "غير مرتبط" else "Not linked"), ltr = d.deviceId != null)
                d.deviceBoundAt?.let { AdminFact(if (ar) "رُبط في" else "Linked on", formatDate(it)) }
                d.referralCode?.let { AdminFact(if (ar) "رمز الدعوة" else "Invite code", it, ltr = true) }
                d.referredByName?.let { AdminFact(if (ar) "دعاه" else "Invited by", it) }
                AdminFact(if (ar) "دعوات" else "Invites", if (ar) "${d.invited} (كوفئ عن ${d.rewarded})" else "${d.invited} (${d.rewarded} rewarded)")
                AdminFact(if (ar) "حدّ المناطق" else "Zone limit", d.maxActiveZones?.let { if (it == 0) (if (ar) "بلا حد" else "No limit") else it.toString() }
                    ?: (if (ar) "الافتراضي" else "Default"))
                AdminFact(if (ar) "الدعم" else "Support", when (d.supportStatus) {
                    null -> if (ar) "لا محادثة" else "No conversation"
                    "open" -> (if (ar) "مفتوحة" else "Open") + " · ${d.supportMessages}" + (if (d.supportUnread > 0) (if (ar) " · ${d.supportUnread} غير مقروءة" else " · ${d.supportUnread} unread") else "")
                    else -> (if (ar) "مغلقة" else "Closed") + " · ${d.supportMessages}"
                })
                AdminFact(if (ar) "إشعارات (30 يومًا)" else "Notifications (30 days)", d.notifications30d.toString())
                d.note?.let { AdminFact(if (ar) "ملاحظة" else "Note", it) }
            }
        }
        item(key = "actions") {
            SabbaqCard(title = if (ar) "إجراءات" else "Actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { open(AdminRequest.DiagnoseDriver(r.id, r.name)) }) { Text(if (ar) "تشخيص الشكوى" else "Diagnose complaint") }
                    OutlinedButton(onClick = { open(AdminRequest.MessageDriver(r.id)) }) { Text(if (ar) "مراسلة" else "Message") }
                    OutlinedButton(onClick = { open(AdminRequest.NotifyDriver(r.id, r.name)) }) { Text(if (ar) "إرسال إشعار" else "Send notification") }
                    OutlinedButton(onClick = { zoneLimit = true }) { Text(if (ar) "حدّ المناطق" else "Zone limit") }
                    OutlinedButton(onClick = { reload++ }) { Text(if (ar) "تحديث" else "Refresh") }
                }
                if (d.deviceId != null) {
                    SabbaqButton(if (ar) "فك ربط الجهاز" else "Unlink the device", onClick = { confirmReset = true }, style = SabbaqButtonStyle.Destructive,
                        busy = resetting, busyText = if (ar) "جارٍ فك الربط…" else "Unlinking…")
                }
            }
        }
    }

    if (zoneLimit) detail?.let { d -> AdminDriverZoneLimitDialog(session, d.row.id, d.row.name) { zoneLimit = false; reload++ } }
    if (confirmReset) detail?.let { d ->
        AdminConfirm(
            title = if (ar) "فك ربط الجهاز" else "Unlink the device",
            text = if (ar) "هل أنت متأكد من فك ربط جهاز ${d.row.name}؟ سيُطلب منه الدخول من جديد، ويُربط حسابه بأول جهاز يدخل منه."
                else "Are you sure you want to unlink ${d.row.name}'s device? They will be asked to sign in again, and their account links to the first device they sign in from.",
            confirm = if (ar) "فك الربط" else "Unlink",
            onConfirm = {
                confirmReset = false
                scope.launch {
                    resetting = true
                    AdminRepository.resetDevice(d.row.id).fold(
                        onSuccess = { notice = success(if (ar) "تم فك ربط الجهاز." else "The device was unlinked."); reload++ },
                        onFailure = { notice = session.failureOf(it, if (ar) "تعذر فك ربط الجهاز." else "Could not unlink the device.") },
                    )
                    resetting = false
                }
            },
            onDismiss = { confirmReset = false },
        )
    }
}

private fun daysLeftText(until: java.time.Instant?): String {
    val left = AdminConsole.daysLeft(until) ?: return ""
    return if (LocaleManager.isArabic()) " (باقي $left يوم)" else " ($left days left)"
}

@Composable
private fun Stat(label: String, value: Long) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The heartbeat's switches in words: green when on, red when off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlagsRow(flags: Map<String, Boolean>) {
    val ar = LocaleManager.isArabic()
    val names = mapOf(
        "a11y" to (if (ar) "إذن المتابعة" else "Accessibility"), "svc" to (if (ar) "الخدمة متصلة" else "Service"),
        "act" to (if (ar) "المتابعة" else "Monitoring"), "lic" to (if (ar) "الاشتراك" else "Licence"),
        "tgt" to (if (ar) "تطبيق الرحلات" else "Ride app"), "net" to (if (ar) "الإنترنت" else "Network"),
        "bat" to (if (ar) "البطارية بلا قيود" else "Battery free"), "ntf" to (if (ar) "الإشعارات" else "Notifications"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEach { (key, label) ->
            val on = flags[key] ?: return@forEach
            val color = if (on) Monitoring else MaterialTheme.colorScheme.error
            Text(label, style = MaterialTheme.typography.labelSmall, color = color,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

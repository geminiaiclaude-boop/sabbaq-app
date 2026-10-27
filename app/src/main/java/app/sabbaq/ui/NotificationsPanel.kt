package app.sabbaq.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sabbaq.BuildConfig
import app.sabbaq.notifications.AppNotification
import app.sabbaq.notifications.NotificationAction
import app.sabbaq.notifications.NotificationCenter
import app.sabbaq.notifications.NotificationKind
import app.sabbaq.ui.theme.LocalSabbaqBrand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** The top-bar bell; the yellow count says how many notifications are unread. */
@Composable
internal fun NotificationBell(unread: Int, onClick: () -> Unit) {
    val brand = LocalSabbaqBrand.current
    val description = if (unread > 0) "الإشعارات، ${ArabicCount.unreadNotifications(unread)}" else "الإشعارات"
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp).semantics { contentDescription = description }) {
        BadgedBox(badge = {
            if (unread > 0) Badge(containerColor = brand.signature, contentColor = brand.onSignature) {
                Text(if (unread > 99) "99+" else "$unread", fontWeight = FontWeight.Bold)
            }
        }) {
            Icon(if (unread > 0) Icons.Outlined.Notifications else Icons.Outlined.NotificationsNone, contentDescription = null)
        }
    }
}

/**
 * The notification centre sheet. Opening it syncs with the server and, after a short moment so
 * the driver can see what was new, marks everything shown as read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsSheet(onDismiss: () -> Unit, onAction: (NotificationAction) -> Unit) {
    val inbox by NotificationCenter.state.collectAsState()
    var refreshing by remember { mutableStateOf(true) }
    var syncFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        syncFailed = !NotificationCenter.refresh()
        refreshing = false
        delay(1_500)
        NotificationCenter.markAllRead()
    }
    val unread = inbox.items.count { !it.read }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SabbaqSectionTitle("الإشعارات")
                    Text(
                        if (unread > 0) ArabicCount.unreadNotifications(unread) else "كل الإشعارات مقروءة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (unread > 0) TextButton(onClick = NotificationCenter::markAllRead) { Text("تحديد الكل كمقروء") }
                else if (inbox.items.isNotEmpty()) TextButton(onClick = NotificationCenter::clearRead) { Text("مسح المقروءة") }
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            if (syncFailed) {
                SabbaqMessage(
                    "تعذر جلب رسائل الإدارة الآن؛ المعروض محفوظ على جهازك وسيُحدَّث عند الاتصال.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            if (inbox.items.isEmpty() && !refreshing) {
                EmptyNotifications()
            } else {
                val today = LocalDate.now()
                val groups = inbox.items.groupBy { dayLabel(it.createdAtEpochMs, today) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).animateContentSize()) {
                    groups.forEach { (label, group) ->
                        item(key = "h:$label") {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
                            )
                        }
                        items(group, key = { it.id }) { notification ->
                            NotificationRow(notification, onAction = { action ->
                                NotificationCenter.markRead(listOf(notification.id))
                                onAction(action)
                            })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: AppNotification, onAction: (NotificationAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalSabbaqBrand.current
    val (icon, container, ink) = kindStyle(notification.kind)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (notification.read) Color.Transparent else colors.primaryContainer.copy(alpha = 0.35f))
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!notification.read) Box(Modifier.size(8.dp).clip(CircleShape).background(brand.signature))
                Text(
                    notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (notification.read) FontWeight.Medium else FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("· ${relativeTime(notification.createdAtEpochMs)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Text(notification.body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (notification.fromServer) {
                Text("من إدارة سبّاق", style = MaterialTheme.typography.labelSmall, color = colors.primary)
            }
            notification.action?.takeIf(::actionAvailable)?.let { action ->
                TextButton(onClick = { onAction(action) }, modifier = Modifier.padding(top = 2.dp)) { Text(actionLabel(action)) }
            }
        }
        IconButton(onClick = { NotificationCenter.dismiss(notification.id) }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "حذف الإشعار: ${notification.title}", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyNotifications() {
    val brand = LocalSabbaqBrand.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(brand.signature), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.NotificationsNone, contentDescription = null, tint = brand.onSignature, modifier = Modifier.size(36.dp))
        }
        Text("لا توجد إشعارات", style = MaterialTheme.typography.titleMedium)
        Text(
            "ستجد هنا الطلبات التي قبلها سبّاق، وتنبيهات المتابعة والاشتراك، ورسائل الإدارة.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun kindStyle(kind: NotificationKind): Triple<ImageVector, Color, Color> {
    val colors = MaterialTheme.colorScheme
    val brand = LocalSabbaqBrand.current
    return when (kind) {
        NotificationKind.WARNING -> Triple(Icons.Outlined.WarningAmber, colors.errorContainer, colors.onErrorContainer)
        NotificationKind.SUCCESS -> Triple(Icons.Outlined.CheckCircle, brand.successContainer, brand.onSuccessContainer)
        NotificationKind.ACCOUNT -> Triple(Icons.Outlined.WorkspacePremium, brand.signature, brand.onSignature)
        NotificationKind.UPDATE -> Triple(Icons.Outlined.SystemUpdate, colors.primaryContainer, colors.onPrimaryContainer)
        NotificationKind.INFO -> Triple(Icons.Outlined.Info, colors.surfaceVariant, colors.onSurfaceVariant)
    }
}

/** A store page exists only for the Play build; the direct build updates through the administration. */
internal fun actionAvailable(action: NotificationAction): Boolean =
    action != NotificationAction.UPDATE_APP || !BuildConfig.DIRECT_DISTRIBUTION

internal fun actionLabel(action: NotificationAction): String = when (action) {
    NotificationAction.OPEN_ACCOUNT -> "فتح حسابي"
    NotificationAction.OPEN_SETUP -> "إكمال الإعداد"
    NotificationAction.OPEN_FILTERS -> "مراجعة الشروط"
    NotificationAction.UPDATE_APP -> "تحديث التطبيق"
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMMM", SabbaqLocale)

private fun dayLabel(epochMs: Long, today: LocalDate): String {
    val day = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (day) {
        today -> "اليوم"
        today.minusDays(1) -> "أمس"
        else -> day.format(dayFormat)
    }
}

internal fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - epochMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "الآن"
        minutes < 60 -> "قبل $minutes د"
        minutes < 24 * 60 -> "قبل ${minutes / 60} س"
        else -> Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFormat)
    }
}

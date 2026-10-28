package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.admin.AdminNotification
import app.sabbaq.admin.AdminRepository
import app.sabbaq.network.SupabaseClient
import app.sabbaq.network.SupabaseClient.FeatureFailure
import app.sabbaq.ui.SabbaqAnimatedMessage
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqTone
import kotlinx.coroutines.launch

/**
 * «إشعارات السائقين»: a message to every driver (after an explicit confirmation) or to one driver
 * chosen from «السائقون». It shows in the app's notification centre and as a phone alert while
 * monitoring runs. Sent notifications list how many drivers read each, and can be withdrawn.
 */
@Composable
internal fun AdminNotificationsSection(session: AdminSession, consume: () -> AdminRequest?) {
    val scope = rememberCoroutineScope()
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetName by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("info") }
    var action by rememberSaveable { mutableStateOf("") }
    var expiry by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    var sending by remember { mutableStateOf(false) }
    var confirmBroadcast by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<List<AdminNotification>?>(null) }
    var listFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<AdminNotification?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        (consume() as? AdminRequest.NotifyDriver)?.let {
            targetId = it.driverId; targetName = it.name
            runCatching { titleFocus.requestFocus() }
        }
    }
    LaunchedEffect(reload) {
        listFailed = false
        AdminRepository.notifications().fold(
            onSuccess = { sent = it },
            onFailure = { session.failureOf(it, ""); listFailed = true },
        )
    }

    fun send() {
        scope.launch {
            sending = true
            val name = targetName
            AdminRepository.sendNotification(targetId, title, body, kind, action, expiry).fold(
                onSuccess = {
                    notice = success(if (targetId != null) (if (LocaleManager.isArabic()) "أُرسل الإشعار إلى $name." else "The notification was sent to $name.") else (if (LocaleManager.isArabic()) "أُرسل الإشعار لكل السائقين." else "The notification was sent to all drivers."))
                    title = ""; body = ""; kind = "info"; action = ""; expiry = 0; targetId = null; targetName = null
                    reload++
                },
                onFailure = {
                    notice = if (SupabaseClient.featureFailureOf(it) == FeatureFailure.REJECTED) failure((if (LocaleManager.isArabic()) "رفض الخادم الإشعار: تحقق من العنوان والنص والمستلم." else "The server rejected the notification: check the title, text and recipient."))
                    else session.failureOf(it, (if (LocaleManager.isArabic()) "تعذر إرسال الإشعار. تحقق من الاتصال ثم أعد المحاولة." else "Could not send the notification. Check the connection, then try again."))
                },
            )
            sending = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-notifications"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "form") {
            SabbaqCard(title = (if (LocaleManager.isArabic()) "إرسال إشعار" else "Send a notification"), subtitle = (if (LocaleManager.isArabic()) "أرسل رسالة تظهر في مركز إشعارات التطبيق لكل السائقين أو لسائق واحد، وتصل كتنبيه على الجوال أثناء المتابعة." else "Send a message that appears in the app's notification center to all drivers or one driver, and arrives as a phone alert while monitoring runs."), icon = Icons.Outlined.Campaign) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((if (LocaleManager.isArabic()) "المستلم:" else "Recipient:"), style = MaterialTheme.typography.labelLarge)
                    Text(targetName ?: (if (LocaleManager.isArabic()) "كل السائقين" else "All drivers"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (targetId != null) TextButton(onClick = { targetId = null; targetName = null }) { Text((if (LocaleManager.isArabic()) "إرسال للجميع بدلًا من ذلك" else "Send to everyone instead")) }
                }
                Text((if (LocaleManager.isArabic()) "لاختيار سائق واحد اضغط «إرسال إشعار» بجانبه في قسم السائقين." else "To choose one driver, press \u00ab Send notification \u00bb next to them in the drivers section."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AdminField((if (LocaleManager.isArabic()) "العنوان" else "Title"), title, { title = it; error = null }, maxLength = 80, modifier = Modifier.testTag("admin-notification-title").focusRequester(titleFocus))
                AdminField((if (LocaleManager.isArabic()) "النص" else "Text"), body, { body = it; error = null }, maxLength = 500, singleLine = false, minLines = 3,
                    hint = "${body.length} / 500", modifier = Modifier.testTag("admin-notification-body"))
                AdminChoice((if (LocaleManager.isArabic()) "النوع" else "Kind"), AdminRepository.notificationKinds().toList(), kind, onSelect = { kind = it })
                AdminChoice((if (LocaleManager.isArabic()) "زر في الإشعار" else "Button in the notification"), AdminRepository.notificationActions().toList(), action, onSelect = { action = it })
                AdminChoice((if (LocaleManager.isArabic()) "ينتهي بعد" else "Expires after"), AdminRepository.notificationExpiry().toList(), expiry, onSelect = { expiry = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                SabbaqButton(
                    if (targetId != null) (if (LocaleManager.isArabic()) "إرسال إلى $targetName" else "Send to $targetName") else (if (LocaleManager.isArabic()) "إرسال لكل السائقين" else "Send to all drivers"),
                    onClick = {
                        error = when {
                            title.trim().length !in 2..80 -> (if (LocaleManager.isArabic()) "العنوان بين حرفين و80 حرفًا." else "The title must be between 2 and 80 characters.")
                            body.trim().length !in 2..500 -> (if (LocaleManager.isArabic()) "النص بين حرفين و500 حرف." else "The text must be between 2 and 500 characters.")
                            else -> null
                        }
                        if (error == null) { if (targetId == null) confirmBroadcast = true else send() }
                    },
                    busy = sending, busyText = (if (LocaleManager.isArabic()) "جارٍ الإرسال…" else "Sending…"), modifier = Modifier.testTag("admin-notification-send"),
                )
                SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
            }
        }
        item(key = "sent-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (LocaleManager.isArabic()) "الإشعارات المرسلة" else "Sent notifications"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { reload++ }) { Text((if (LocaleManager.isArabic()) "تحديث" else "Refresh")) }
            }
        }
        val list = sent
        when {
            listFailed -> item(key = "failed") { Text((if (LocaleManager.isArabic()) "تعذر تحميل الإشعارات المرسلة." else "Could not load the sent notifications."), color = MaterialTheme.colorScheme.error) }
            list == null -> item(key = "loading") { Text((if (LocaleManager.isArabic()) "جارٍ التحميل…" else "Loading…"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            list.isEmpty() -> item(key = "empty") { Text((if (LocaleManager.isArabic()) "لم يُرسل أي إشعار بعد." else "No notification sent yet."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(list, key = { it.id }) { item ->
                SabbaqCard {
                    Text(item.title.ifEmpty { (if (LocaleManager.isArabic()) "غير محدد" else "Not set") }, style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AdminFact((if (LocaleManager.isArabic()) "الوقت" else "Time"), formatDateTime(item.createdAt))
                        AdminFact((if (LocaleManager.isArabic()) "المستلم" else "Recipient"), if (item.toAll) (if (LocaleManager.isArabic()) "كل السائقين" else "All drivers") else item.driverName ?: (if (LocaleManager.isArabic()) "سائق" else "Driver"))
                        AdminFact((if (LocaleManager.isArabic()) "النوع" else "Kind"), AdminRepository.notificationKinds()[item.kind] ?: AdminRepository.notificationKinds().values.first())
                        AdminFact((if (LocaleManager.isArabic()) "قرأه" else "Read by"), item.readCount.toString())
                    }
                    SabbaqButton((if (LocaleManager.isArabic()) "حذف" else "Delete"), onClick = { confirmDelete = item }, style = SabbaqButtonStyle.Destructive, fillWidth = false,
                        busy = deleting == item.id, busyText = (if (LocaleManager.isArabic()) "جارٍ الحذف…" else "Deleting…"))
                }
            }
        }
    }

    if (confirmBroadcast) AdminConfirm(
        title = (if (LocaleManager.isArabic()) "إرسال لكل السائقين" else "Send to all drivers"),
        text = (if (LocaleManager.isArabic()) "سيصل هذا الإشعار إلى كل السائقين. هل تريد الإرسال؟" else "This notification will reach all drivers. Do you want to send it?"),
        confirm = (if (LocaleManager.isArabic()) "إرسال" else "Send"),
        onConfirm = ::send,
        onDismiss = { confirmBroadcast = false },
    )
    confirmDelete?.let { item ->
        AdminConfirm(
            title = (if (LocaleManager.isArabic()) "حذف الإشعار" else "Delete the notification"),
            text = (if (LocaleManager.isArabic()) "سيختفي هذا الإشعار من تطبيقات السائقين. هل تريد الحذف؟" else "This notification will disappear from the drivers' apps. Do you want to delete it?"),
            confirm = (if (LocaleManager.isArabic()) "حذف" else "Delete"),
            onConfirm = {
                scope.launch {
                    deleting = item.id
                    AdminRepository.deleteNotification(item.id).fold(
                        onSuccess = { notice = success((if (LocaleManager.isArabic()) "حُذف الإشعار." else "The notification was deleted.")); reload++ },
                        onFailure = { notice = session.failureOf(it, (if (LocaleManager.isArabic()) "تعذر حذف الإشعار." else "Could not delete the notification.")) },
                    )
                    deleting = null
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

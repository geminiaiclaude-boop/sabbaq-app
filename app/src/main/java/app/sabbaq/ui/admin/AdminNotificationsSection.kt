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
                    notice = success(if (targetId != null) "أُرسل الإشعار إلى $name." else "أُرسل الإشعار لكل السائقين.")
                    title = ""; body = ""; kind = "info"; action = ""; expiry = 0; targetId = null; targetName = null
                    reload++
                },
                onFailure = {
                    notice = if (SupabaseClient.featureFailureOf(it) == FeatureFailure.REJECTED) failure("رفض الخادم الإشعار: تحقق من العنوان والنص والمستلم.")
                    else session.failureOf(it, "تعذر إرسال الإشعار. تحقق من الاتصال ثم أعد المحاولة.")
                },
            )
            sending = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-notifications"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "form") {
            SabbaqCard(title = "إرسال إشعار", subtitle = "أرسل رسالة تظهر في مركز إشعارات التطبيق لكل السائقين أو لسائق واحد، وتصل كتنبيه على الجوال أثناء المتابعة.", icon = Icons.Outlined.Campaign) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("المستلم:", style = MaterialTheme.typography.labelLarge)
                    Text(targetName ?: "كل السائقين", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (targetId != null) TextButton(onClick = { targetId = null; targetName = null }) { Text("إرسال للجميع بدلًا من ذلك") }
                }
                Text("لاختيار سائق واحد اضغط «إرسال إشعار» بجانبه في قسم السائقين.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AdminField("العنوان", title, { title = it; error = null }, maxLength = 80, modifier = Modifier.testTag("admin-notification-title").focusRequester(titleFocus))
                AdminField("النص", body, { body = it; error = null }, maxLength = 500, singleLine = false, minLines = 3,
                    hint = "${body.length} / 500", modifier = Modifier.testTag("admin-notification-body"))
                AdminChoice("النوع", AdminRepository.NOTIFICATION_KINDS.toList(), kind, onSelect = { kind = it })
                AdminChoice("زر في الإشعار", AdminRepository.NOTIFICATION_ACTIONS.toList(), action, onSelect = { action = it })
                AdminChoice("ينتهي بعد", AdminRepository.NOTIFICATION_EXPIRY.toList(), expiry, onSelect = { expiry = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                SabbaqButton(
                    if (targetId != null) "إرسال إلى $targetName" else "إرسال لكل السائقين",
                    onClick = {
                        error = when {
                            title.trim().length !in 2..80 -> "العنوان بين حرفين و80 حرفًا."
                            body.trim().length !in 2..500 -> "النص بين حرفين و500 حرف."
                            else -> null
                        }
                        if (error == null) { if (targetId == null) confirmBroadcast = true else send() }
                    },
                    busy = sending, busyText = "جارٍ الإرسال…", modifier = Modifier.testTag("admin-notification-send"),
                )
                SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
            }
        }
        item(key = "sent-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الإشعارات المرسلة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { reload++ }) { Text("تحديث") }
            }
        }
        val list = sent
        when {
            listFailed -> item(key = "failed") { Text("تعذر تحميل الإشعارات المرسلة.", color = MaterialTheme.colorScheme.error) }
            list == null -> item(key = "loading") { Text("جارٍ التحميل…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            list.isEmpty() -> item(key = "empty") { Text("لم يُرسل أي إشعار بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(list, key = { it.id }) { item ->
                SabbaqCard {
                    Text(item.title.ifEmpty { "غير محدد" }, style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AdminFact("الوقت", formatDateTime(item.createdAt))
                        AdminFact("المستلم", if (item.toAll) "كل السائقين" else item.driverName ?: "سائق")
                        AdminFact("النوع", AdminRepository.NOTIFICATION_KINDS[item.kind] ?: "معلومة")
                        AdminFact("قرأه", item.readCount.toString())
                    }
                    SabbaqButton("حذف", onClick = { confirmDelete = item }, style = SabbaqButtonStyle.Destructive, fillWidth = false,
                        busy = deleting == item.id, busyText = "جارٍ الحذف…")
                }
            }
        }
    }

    if (confirmBroadcast) AdminConfirm(
        title = "إرسال لكل السائقين",
        text = "سيصل هذا الإشعار إلى كل السائقين. هل تريد الإرسال؟",
        confirm = "إرسال",
        onConfirm = ::send,
        onDismiss = { confirmBroadcast = false },
    )
    confirmDelete?.let { item ->
        AdminConfirm(
            title = "حذف الإشعار",
            text = "سيختفي هذا الإشعار من تطبيقات السائقين. هل تريد الحذف؟",
            confirm = "حذف",
            onConfirm = {
                scope.launch {
                    deleting = item.id
                    AdminRepository.deleteNotification(item.id).fold(
                        onSuccess = { notice = success("حُذف الإشعار."); reload++ },
                        onFailure = { notice = session.failureOf(it, "تعذر حذف الإشعار.") },
                    )
                    deleting = null
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

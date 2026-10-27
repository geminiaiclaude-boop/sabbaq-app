package app.sabbaq.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Badge
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sabbaq.admin.AdminConversation
import app.sabbaq.admin.AdminRepository
import app.sabbaq.admin.AdminThread
import app.sabbaq.ui.SabbaqAnimatedMessage
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_MILLIS = 20_000L

/**
 * «الدعم»: the drivers' in-app conversations. The list and the open conversation refresh every
 * 20 seconds while visible. A reply keeps one client id until the server confirms it, so a retry
 * after a lost response never sends it twice.
 */
@Composable
internal fun AdminSupportSection(session: AdminSession, consume: () -> AdminRequest?, open: (AdminRequest) -> Unit, onUnread: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("open") }
    var threads by remember { mutableStateOf<List<AdminThread>?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var conversation by remember { mutableStateOf<AdminConversation?>(null) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    var reply by rememberSaveable { mutableStateOf("") }
    var pendingClientId by rememberSaveable { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var toggling by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var loadingList by remember { mutableStateOf(false) }

    var focusReply by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        (consume() as? AdminRequest.MessageDriver)?.let { selected = it.driverId; reply = ""; pendingClientId = null; focusReply = true }
    }

    suspend fun loadList(quiet: Boolean) {
        loadingList = !quiet
        AdminRepository.threads(filter.takeIf { it != "all" }).fold(
            onSuccess = { list ->
                threads = list
                if (filter == "open") onUnread(list.sumOf { it.unread })
                else AdminRepository.threads("open").onSuccess { open -> onUnread(open.sumOf { it.unread }) }
            },
            onFailure = { val text = session.failureOf(it, "تعذر تحميل المحادثات. تحقق من الاتصال."); if (!quiet) notice = text },
        )
        loadingList = false
    }

    suspend fun loadConversation(driverId: String, quiet: Boolean) {
        AdminRepository.conversation(driverId).fold(
            onSuccess = { if (selected == driverId) conversation = it },
            onFailure = { val text = session.failureOf(it, "تعذر فتح المحادثة."); if (!quiet) notice = text },
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, filter, selected, refreshTick) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var quiet = false
            while (true) {
                loadList(quiet)
                selected?.let { loadConversation(it, quiet) }
                quiet = true
                delay(POLL_MILLIS)
            }
        }
    }

    val current = selected
    if (current != null) {
        BackHandler { selected = null; conversation = null; pendingClientId = null }
        ConversationView(
            conversation = conversation?.takeIf { selected == current },
            notice = notice,
            reply = reply,
            sending = sending,
            toggling = toggling,
            onBack = { selected = null; conversation = null; pendingClientId = null },
            onReplyChange = { reply = it.take(1000); pendingClientId = null },
            onSend = {
                val body = reply.trim()
                if (body.isEmpty() || body.length > 1000) {
                    notice = failure("اكتب ردًا بين حرف و1000 حرف.")
                } else scope.launch {
                    val clientId = pendingClientId ?: AdminRepository.newClientId().also { pendingClientId = it }
                    sending = true
                    AdminRepository.reply(current, clientId, body).fold(
                        onSuccess = {
                            pendingClientId = null; reply = ""
                            notice = success("أُرسل الرد وسيصل السائق كإشعار.")
                            loadConversation(current, quiet = true); loadList(quiet = true)
                        },
                        onFailure = { notice = session.failureOf(it, "تعذر إرسال الرد. أعد المحاولة؛ لن يتكرر الرد.") },
                    )
                    sending = false
                }
            },
            onToggle = {
                val closing = conversation?.closed != true
                scope.launch {
                    toggling = true
                    AdminRepository.setThreadClosed(current, closing).fold(
                        onSuccess = {
                            notice = success(if (closing) "أُغلقت المحادثة. ستُفتح تلقائيًا إذا كتب السائق." else "أُعيد فتح المحادثة.")
                            refreshTick++
                        },
                        onFailure = { notice = session.failureOf(it, "تعذر تغيير حالة المحادثة.") },
                    )
                    toggling = false
                }
            },
            onDiagnose = { conversation?.correlationId?.let { open(AdminRequest.DiagnoseSupportId(it)) } },
            focusReply = focusReply,
            onReplyFocused = { focusReply = false },
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize().testTag("admin-support"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("محادثات السائقين داخل التطبيق. يصل ردك للسائق في المحادثة وكإشعار على جواله. تتحدث القائمة تلقائيًا كل 20 ثانية.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AdminChoice("العرض", listOf("open" to "المفتوحة", "closed" to "المغلقة", "all" to "الكل"), filter, onSelect = { filter = it })
                OutlinedButton(onClick = { refreshTick++ }, enabled = !loadingList) { Text(if (loadingList) "جارٍ التحديث…" else "تحديث") }
                SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
            }
        }
        val list = threads
        when {
            list == null -> item(key = "loading") { Text("جارٍ تحميل المحادثات…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            list.isEmpty() -> item(key = "empty") { Text("لا توجد محادثات في هذا العرض.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> items(list, key = { it.driverId }) { thread ->
                ThreadRow(thread, onClick = { notice = null; reply = ""; pendingClientId = null; conversation = null; selected = thread.driverId })
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: AdminThread, onClick: () -> Unit) {
    SabbaqCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(thread.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                fontWeight = if (thread.unread > 0) FontWeight.Bold else FontWeight.Normal)
            if (thread.unread > 0) Badge { Text(thread.unread.toString()) }
        }
        Text(thread.preview?.let { (if (thread.previewFromAdmin) "أنت: " else "") + it } ?: "لا رسائل",
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(buildString {
            append(formatDateTime(thread.lastAt))
            if (thread.unread > 0) append(" · ${thread.unread} جديدة")
            if (thread.closed) append(" · مغلقة")
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConversationView(
    conversation: AdminConversation?,
    notice: AdminNotice?,
    reply: String,
    sending: Boolean,
    toggling: Boolean,
    onBack: () -> Unit,
    onReplyChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggle: () -> Unit,
    onDiagnose: () -> Unit,
    focusReply: Boolean,
    onReplyFocused: () -> Unit,
) {
    val listState = rememberLazyListState()
    val replyFocus = remember { FocusRequester() }
    LaunchedEffect(focusReply) { if (focusReply) { runCatching { replyFocus.requestFocus() }; onReplyFocused() } }
    val count = conversation?.messages?.size ?: 0
    LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
    Column(Modifier.fillMaxSize().imePadding().testTag("admin-conversation")) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "العودة إلى المحادثات") }
                    Column(Modifier.weight(1f)) {
                        Text(conversation?.name ?: "…", style = MaterialTheme.typography.titleMedium)
                        val meta = listOfNotNull(conversation?.phone?.takeIf { it.isNotEmpty() }, conversation?.appVersion?.let { "الإصدار $it" }).joinToString(" · ")
                        Text(meta.ifEmpty { "لا توجد بيانات إضافية" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                    OutlinedButton(onClick = onDiagnose, enabled = conversation?.correlationId != null) { Text("تشخيص الشكوى") }
                    OutlinedButton(onClick = onToggle, enabled = conversation != null && !toggling) {
                        Text(if (conversation?.closed == true) "إعادة فتح المحادثة" else "إغلاق المحادثة")
                    }
                }
            }
        }
        SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                conversation == null -> item { Text("جارٍ فتح المحادثة…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                conversation.messages.isEmpty() -> item { Text("لا رسائل بعد. يمكنك بدء المحادثة مع السائق.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> items(conversation.messages) { message ->
                    val colors = MaterialTheme.colorScheme
                    // The administration's messages sit on the reading side's end, like the driver's own view.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.fromAdmin) Alignment.End else Alignment.Start) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (message.fromAdmin) colors.primary else colors.surfaceVariant,
                            contentColor = if (message.fromAdmin) colors.onPrimary else colors.onSurface,
                            modifier = Modifier.widthIn(max = 320.dp).semantics { contentDescription = (if (message.fromAdmin) "الإدارة: " else "السائق: ") + message.body },
                        ) { Text(message.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                        Text("${if (message.fromAdmin) "الإدارة" else "السائق"} · ${formatDateTime(message.at)}",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = reply, onValueChange = onReplyChange, placeholder = { Text("اكتب ردك…") }, maxLines = 5,
                supportingText = { Text("${reply.length} / 1000") }, modifier = Modifier.weight(1f).testTag("admin-reply").focusRequester(replyFocus),
            )
            FilledIconButton(onClick = onSend, enabled = !sending && reply.isNotBlank() && conversation != null, modifier = Modifier.size(52.dp).testTag("admin-reply-send")) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "إرسال الرد")
            }
        }
    }
}

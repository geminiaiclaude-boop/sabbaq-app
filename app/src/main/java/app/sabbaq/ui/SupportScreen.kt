package app.sabbaq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sabbaq.support.SupportConversation
import app.sabbaq.support.SupportDelivery
import app.sabbaq.support.SupportMessage
import app.sabbaq.support.SupportRepository
import app.sabbaq.support.SupportSender
import app.sabbaq.ui.theme.LocalSabbaqBrand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SUPPORT_POLL_MILLIS = 15_000L

private val SUPPORT_TOPICS = listOf(
    "سبّاق لا يقبل الطلبات" to "سبّاق لا يقبل الطلبات رغم أن المتابعة تعمل. ",
    "الاشتراك والدفع" to "لدي سؤال عن الاشتراك أو الدفع: ",
    "نقل الحساب لجوال آخر" to "أريد نقل حسابي إلى جوال جديد. ",
    "اقتراح أو ملاحظة" to "لدي اقتراح: ",
)

/**
 * «مراسلة الدعم»: one conversation with the administration. The thread refreshes every few
 * seconds while the page is visible (not in the background); messages typed offline wait in the
 * outbox and go out by themselves, with a visible state and retry on each.
 */
@Composable
internal fun SupportPage() {
    val ui by SupportRepository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var draft by rememberSaveable { mutableStateOf("") }
    var sendRefused by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                SupportRepository.refresh()
                delay(SUPPORT_POLL_MILLIS)
            }
        }
    }
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        SupportHeader(closed = ui.closed)
        if (ui.loadFailed) {
            SabbaqMessage(featureFailureText(ui.failure, "المحادثة"), isError = true,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.messages.isEmpty()) {
                SupportEmptyState(loading = ui.loading && !ui.loaded, onTopic = { draft = it })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("support-messages"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "welcome") { SupportWelcomeBubble() }
                    var previousDay: LocalDate? = null
                    ui.messages.forEach { message ->
                        val day = Instant.ofEpochMilli(message.createdAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
                        if (day != previousDay) {
                            val label = supportDayLabel(day)
                            item(key = "d:$day") { DaySeparator(label) }
                            previousDay = day
                        }
                        item(key = message.clientId) {
                            SupportBubble(
                                message = message,
                                onRetry = { scope.launch { SupportRepository.retry(message.clientId) } },
                                onDiscard = { SupportRepository.discard(message.clientId) },
                            )
                        }
                    }
                }
            }
        }
        if (ui.closed) {
            Text(
                "أُغلقت هذه المحادثة. اكتب رسالة جديدة لفتحها مرة أخرى.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        SupportComposer(
            value = draft,
            onValueChange = { draft = it.take(SupportConversation.MAX_LENGTH); sendRefused = false },
            refused = sendRefused,
            onSend = {
                val text = draft
                scope.launch {
                    if (SupportRepository.send(text)) draft = "" else sendRefused = true
                }
            },
        )
    }
}

@Composable
private fun SupportHeader(closed: Boolean) {
    val brand = LocalSabbaqBrand.current
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(brand.signature), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.SupportAgent, contentDescription = null, tint = brand.onSignature)
            }
            Column(Modifier.weight(1f)) {
                Text("دعم سبّاق", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (closed) "المحادثة مغلقة" else "نرد عادة خلال ساعات العمل. لا تشارك كلمات مرور أو بيانات بطاقة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportEmptyState(loading: Boolean, onTopic: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (loading) {
            Text("جارٍ تحميل المحادثة…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        SupportWelcomeBubble()
        Text("اختر موضوعًا أو اكتب مشكلتك:", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SUPPORT_TOPICS.forEach { (label, starter) ->
                AssistChip(onClick = { onTopic(starter) }, label = { Text(label) })
            }
        }
    }
}

/** The greeting every conversation opens with; local, so it shows even before the first sync. */
@Composable
private fun SupportWelcomeBubble() {
    SupportBubble(
        message = SupportMessage(
            clientId = "welcome",
            sender = SupportSender.ADMIN,
            body = "مرحبًا بك في دعم سبّاق 👋\nاكتب مشكلتك أو سؤالك وسيرد عليك فريقنا هنا خلال ساعات العمل، ويصلك إشعار عند الرد. " +
                "لتسريع الحل نرفق تلقائيًا إصدار التطبيق ومعرّف الدعم، دون أي بيانات من طلباتك.",
            createdAtEpochMs = 0,
        ),
        onRetry = {},
        onDiscard = {},
        showTime = false,
    )
}

@Composable
private fun DaySeparator(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SupportBubble(message: SupportMessage, onRetry: () -> Unit, onDiscard: () -> Unit, showTime: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val mine = message.sender == SupportSender.DRIVER
    val time = Instant.ofEpochMilli(message.createdAtEpochMs).atZone(ZoneId.systemDefault()).format(supportTimeFormat)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine) {
            Text("إدارة سبّاق", style = MaterialTheme.typography.labelSmall, color = colors.primary, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 18.dp,
            ),
            color = if (mine) colors.primary else colors.surfaceVariant,
            contentColor = if (mine) colors.onPrimary else colors.onSurface,
            modifier = Modifier.widthIn(max = 300.dp).semantics {
                contentDescription = (if (mine) "أنت: " else "إدارة سبّاق: ") + message.body
            },
        ) {
            Text(message.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (message.delivery) {
                SupportDelivery.SENT -> if (showTime) Text(time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                SupportDelivery.SENDING -> {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text("جارٍ الإرسال…", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                SupportDelivery.FAILED, SupportDelivery.RATE_LIMITED -> {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(14.dp))
                    Text(
                        if (message.delivery == SupportDelivery.RATE_LIMITED) "رسائل كثيرة خلال ساعة؛ أعد المحاولة لاحقًا" else "لم تُرسل",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error,
                    )
                    TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                    TextButton(onClick = onDiscard) { Text("حذف") }
                }
            }
        }
    }
}

@Composable
private fun SupportComposer(value: String, onValueChange: (String) -> Unit, refused: Boolean, onSend: () -> Unit) {
    val canSend = SupportConversation.normalize(value) != null
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("اكتب رسالتك…") },
                    maxLines = 5,
                    modifier = Modifier.weight(1f).testTag("support-input"),
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.size(52.dp).testTag("support-send"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "إرسال الرسالة")
                }
            }
            val remaining = SupportConversation.MAX_LENGTH - value.length
            Text(
                when {
                    refused -> "تعذر إضافة الرسالة؛ انتظر حتى تُرسل الرسائل السابقة."
                    remaining < 200 -> "متبقٍ $remaining حرف"
                    else -> "يُرفق تلقائيًا إصدار التطبيق ومعرّف الدعم لتسريع التشخيص."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

private val supportTimeFormat = DateTimeFormatter.ofPattern("HH:mm", SabbaqLocale)
private val supportDayFormat = DateTimeFormatter.ofPattern("d MMMM", SabbaqLocale)

private fun supportDayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "اليوم"
        today.minusDays(1) -> "أمس"
        else -> day.format(supportDayFormat)
    }
}

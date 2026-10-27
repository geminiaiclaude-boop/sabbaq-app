package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import android.os.SystemClock
import app.sabbaq.network.SupabaseClient
import app.sabbaq.network.SupabaseClient.FeatureFailure
import app.sabbaq.ui.SabbaqLocale
import app.sabbaq.ui.SabbaqTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A feedback line under a section: the text and how it reads (info, success, error). */
data class AdminNotice(val text: String, val tone: SabbaqTone)

internal fun info(text: String) = AdminNotice(text, SabbaqTone.Info)
internal fun success(text: String) = AdminNotice(text, SabbaqTone.Success)
internal fun failure(text: String) = AdminNotice(text, SabbaqTone.Error)

/**
 * The admin session as the screen sees it: when the server refuses the session the whole panel
 * locks, and inactivity locks it after 15 minutes like the web panel.
 */
@Stable
class AdminSession {
    var locked by mutableStateOf<String?>(null)
        private set
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()

    fun touch() { lastActivity = SystemClock.elapsedRealtime() }

    fun idleMillis(): Long = SystemClock.elapsedRealtime() - lastActivity

    fun lock(reason: String) { if (locked == null) locked = reason }

    fun unlock() { locked = null; touch() }

    /**
     * The message for a failed operation. A refused session locks the panel at once, so nothing
     * else is attempted on a session the server no longer accepts.
     */
    fun failureOf(error: Throwable?, fallback: String): AdminNotice {
        val kind = SupabaseClient.featureFailureOf(error)
        if (kind == FeatureFailure.SESSION) lock("انتهت الجلسة أو لم يعد الحساب مخوّلًا لتنفيذ هذه العملية.")
        return failure(when (kind) {
            FeatureFailure.OFFLINE -> "لا يوجد اتصال بالشبكة؛ لم تُرسل أي عملية."
            FeatureFailure.SESSION -> "انتهت الجلسة أو لم يعد الحساب مخوّلًا لتنفيذ هذه العملية."
            else -> fallback
        })
    }

    companion object {
        const val IDLE_LIMIT_MILLIS = 15 * 60 * 1000L
    }
}

private val dateTimeFormat = DateTimeFormatter.ofPattern("MM/dd HH:mm", SabbaqLocale)
private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd", SabbaqLocale)
private val fullFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", SabbaqLocale)

internal fun formatDateTime(value: Instant?): String = value?.atZone(ZoneId.systemDefault())?.format(dateTimeFormat) ?: "غير محدد"
internal fun formatDate(value: Instant?): String = value?.atZone(ZoneId.systemDefault())?.format(dateFormat) ?: "غير محدد"
internal fun formatFull(value: Instant?): String = value?.atZone(ZoneId.systemDefault())?.format(fullFormat) ?: "غير محدد"

internal val monoStyle @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

/** Asks before an action that reaches every driver or cannot be undone. */
@Composable
internal fun AdminConfirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** One choice among a few, as chips: easier on a phone than a drop-down. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> AdminChoice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) }, enabled = enabled)
            }
        }
    }
}

/** A field with its hint and its own error line, as the web panel shows them. */
@Composable
internal fun AdminField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    maxLength: Int = 200,
    keyboard: KeyboardType = KeyboardType.Text,
    ltr: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle? = null,
) {
    val support = error ?: hint
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.take(maxLength)) },
            label = { Text(label) },
            supportingText = if (support == null) null else {
                { Text(support, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            isError = error != null,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = textStyle ?: MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = modifier.fillMaxWidth(),
        )
    }
    if (ltr) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { field() } else field()
}

/** Previous / page n / next, disabled while a page loads. */
@Composable
internal fun AdminPager(offset: Int, pageSize: Int, hasMore: Boolean, loading: Boolean, onMove: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onMove(-1) }, enabled = !loading && offset > 0) { Text("السابق") }
        Text("الصفحة ${offset / pageSize + 1}", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onMove(1) }, enabled = !loading && hasMore) { Text("التالي") }
    }
}

/** A label and value on one line, the value allowed to wrap. */
@Composable
internal fun AdminFact(label: String, value: String, ltr: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ltr) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, style = MaterialTheme.typography.bodySmall)
        } else Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

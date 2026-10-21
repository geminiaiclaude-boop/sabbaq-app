package app.qannas.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.qannas.Settings
import app.qannas.voice.OfferEvents
import app.qannas.voice.VoiceAnnouncer
import app.qannas.voice.VoicePreferences
import kotlin.math.roundToInt

/**
 * «التنبيهات الصوتية»: one switch for all, one per kind of alert, silent-mode behaviour,
 * volume, a test button, and a clear way out when the phone has no Arabic voice.
 */
@Composable
internal fun VoiceAlertsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(Settings.voicePreferences) }
    val readiness by VoiceAnnouncer.readiness.collectAsState()
    LaunchedEffect(Unit) { VoiceAnnouncer.probe() }
    fun update(next: VoicePreferences) {
        prefs = next
        Settings.voicePreferences = next
    }

    QannasCard(
        title = "التنبيهات الصوتية",
        subtitle = "يخبرك قناص بصوته بما يحدث دون أن تنظر إلى الجوال.",
        icon = Icons.Outlined.RecordVoiceOver,
        modifier = modifier,
    ) {
        VoiceToggle("تشغيل التنبيهات الصوتية", null, prefs.enabled) { update(prefs.copy(enabled = it)) }

        AnimatedVisibility(visible = prefs.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (readiness) {
                    VoiceAnnouncer.Readiness.NoArabicVoice -> VoiceProblem(
                        "لا يوجد صوت عربي في جوالك، فستسمع نغمة التنبيه فقط. ثبّت الصوت العربي من محرك تحويل النص إلى كلام.",
                        "تثبيت الصوت العربي",
                    ) {
                        runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .recoverCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    VoiceAnnouncer.Readiness.Unavailable -> VoiceProblem(
                        "لا يوجد محرك لتحويل النص إلى كلام في جوالك. ثبّت «خدمات Google للتحدث» من المتجر لتسمع التنبيهات.",
                        "فتح إعدادات الصوت",
                    ) {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    else -> Unit
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VoiceToggle("بدء المتابعة وإيقافها", "عند التشغيل والإيقاف والاستئناف بعد إعادة التشغيل", prefs.status) {
                    update(prefs.copy(status = it))
                }
                VoiceToggle("عندما يحتاج قناص إليك", "إذن أُغلق، أو توقفت المتابعة بسبب الاشتراك أو الدخول", prefs.problems) {
                    update(prefs.copy(problems = it))
                }
                VoiceToggle("انقطاع الإنترنت وعودته", "أثناء المتابعة فقط، وبعد انقطاع يستمر 20 ثانية", prefs.connection) {
                    update(prefs.copy(connection = it))
                }
                VoiceToggle("قرب انتهاء الاشتراك", "قبل يوم، ثم في الساعة الأخيرة", prefs.subscription) {
                    update(prefs.copy(subscription = it))
                }
                if (OfferEvents.ENGINE_REPORTS_ACCEPTS) {
                    VoiceToggle("عند قبول طلب", "الأجرة ومسافة الوصول", prefs.offers) { update(prefs.copy(offers = it)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VoiceToggle("التحدث في الوضع الصامت", "لا يتحدث أثناء المكالمات في كل الأحوال", prefs.evenWhenSilent) {
                    update(prefs.copy(evenWhenSilent = it))
                }
                val percent = (prefs.volume * 100).roundToInt()
                Text("مستوى الصوت: $percent٪", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = prefs.volume,
                    onValueChange = { prefs = prefs.copy(volume = it) },
                    onValueChangeFinished = { update(prefs) },
                    valueRange = 0.2f..1f,
                    modifier = Modifier.semantics {
                        contentDescription = "مستوى صوت التنبيهات"
                        stateDescription = "$percent٪"
                    },
                )
                QannasButton(
                    "جرّب الصوت",
                    onClick = { VoiceAnnouncer.test() },
                    style = QannasButtonStyle.Secondary,
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun VoiceToggle(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun VoiceProblem(text: String, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        QannasMessage(text, isError = true)
        QannasButton(action, onClick = onAction, style = QannasButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
    }
}

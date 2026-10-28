package app.sabbaq.ui

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
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import app.sabbaq.Settings
import app.sabbaq.voice.OfferEvents
import app.sabbaq.voice.VoiceAnnouncer
import app.sabbaq.voice.VoicePreferences
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

    SabbaqCard(
        title = stringResource(R.string.vac_title),
        subtitle = stringResource(R.string.vac_sub),
        icon = Icons.Outlined.RecordVoiceOver,
        modifier = modifier,
    ) {
        VoiceToggle(stringResource(R.string.vac_enable), null, prefs.enabled) { update(prefs.copy(enabled = it)) }

        AnimatedVisibility(visible = prefs.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (readiness) {
                    VoiceAnnouncer.Readiness.NoArabicVoice -> VoiceProblem(
                        stringResource(R.string.vac_no_arabic_voice),
                        stringResource(R.string.vac_install_arabic),
                    ) {
                        runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .recoverCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    VoiceAnnouncer.Readiness.Unavailable -> VoiceProblem(
                        stringResource(R.string.vac_no_tts),
                        stringResource(R.string.vac_open_tts),
                    ) {
                        runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    else -> Unit
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VoiceToggle(stringResource(R.string.vac_status_title), stringResource(R.string.vac_status_detail), prefs.status) {
                    update(prefs.copy(status = it))
                }
                VoiceToggle(stringResource(R.string.vac_problems_title), stringResource(R.string.vac_problems_detail), prefs.problems) {
                    update(prefs.copy(problems = it))
                }
                VoiceToggle(stringResource(R.string.vac_connection_title), stringResource(R.string.vac_connection_detail), prefs.connection) {
                    update(prefs.copy(connection = it))
                }
                VoiceToggle(stringResource(R.string.vac_subscription_title), stringResource(R.string.vac_subscription_detail), prefs.subscription) {
                    update(prefs.copy(subscription = it))
                }
                if (OfferEvents.ENGINE_REPORTS_ACCEPTS) {
                    VoiceToggle(stringResource(R.string.vac_offers_title), stringResource(R.string.vac_offers_detail), prefs.offers) { update(prefs.copy(offers = it)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VoiceToggle(stringResource(R.string.vac_silent_title), stringResource(R.string.vac_silent_detail), prefs.evenWhenSilent) {
                    update(prefs.copy(evenWhenSilent = it))
                }
                val percent = (prefs.volume * 100).roundToInt()
                val volumeCd = stringResource(R.string.vac_volume_cd)
                val volumeState = stringResource(R.string.vac_percent, percent)
                Text(stringResource(R.string.vac_volume, percent), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = prefs.volume,
                    onValueChange = { prefs = prefs.copy(volume = it) },
                    onValueChangeFinished = { update(prefs) },
                    valueRange = 0.2f..1f,
                    modifier = Modifier.semantics {
                        contentDescription = volumeCd
                        stateDescription = volumeState
                    },
                )
                SabbaqButton(
                    stringResource(R.string.vac_test),
                    onClick = { VoiceAnnouncer.test() },
                    style = SabbaqButtonStyle.Secondary,
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
        SabbaqMessage(text, isError = true)
        SabbaqButton(action, onClick = onAction, style = SabbaqButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
    }
}

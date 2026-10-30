package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sabbaq.R
import app.sabbaq.Settings
import app.sabbaq.network.ReferralSummary
import app.sabbaq.network.SupabaseClient
import kotlinx.coroutines.launch

/**
 * «هل دعاك زميل إلى سبّاق؟»: a page of its own, once, right after signing in, only for an account
 * that may still attach an invite code (the server's `can_claim`: within the claim window and
 * before any paid subscription). Existing subscribers never see it. If the server cannot be
 * reached it stays closed and is tried again on the next launch. The same claim remains under
 * the menu's «الإحالات والمكافآت» for the rest of the window.
 */
@Composable
internal fun InviteCodePrompt() {
    var summary by remember { mutableStateOf<ReferralSummary?>(null) }
    LaunchedEffect(Unit) {
        if (Settings.referralPromptDone) return@LaunchedEffect
        SupabaseClient.myReferrals().onSuccess { s ->
            if (s.enabled && s.canClaim && !s.alreadyClaimed) summary = s else Settings.referralPromptDone = true
        }
    }
    val s = summary ?: return
    InviteCodePage(s, onDone = { Settings.referralPromptDone = true; summary = null })
}

@Composable
private fun InviteCodePage(s: ReferralSummary, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("invite-prompt"), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SabbaqBrand(compact = true)
                SabbaqCard(title = stringResource(R.string.ref_prompt_title), icon = Icons.Outlined.Redeem) {
                    Text(stringResource(R.string.ref_prompt_intro), style = MaterialTheme.typography.bodyMedium)
                    Point(stringResource(R.string.ref_prompt_point_price))
                    Point(stringResource(R.string.ref_prompt_point_reward, s.rewardDays))
                    Point(stringResource(R.string.ref_prompt_point_window, s.claimWindowDays))
                    Text(stringResource(R.string.ref_enter_privacy), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (linked) {
                    SabbaqMessage(stringResource(R.string.ref_claim_ok), tone = SabbaqTone.Success)
                    SabbaqButton(stringResource(R.string.ref_prompt_continue), onClick = onDone, modifier = Modifier.testTag("invite-continue"))
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { raw -> code = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(20); message = null },
                        enabled = !busy, singleLine = true,
                        label = { Text(stringResource(R.string.ref_code)) },
                        placeholder = { Text("SB-7K9M2X4P") },
                        modifier = Modifier.fillMaxWidth().testTag("invite-code"),
                    )
                    message?.let { SabbaqMessage(it, tone = SabbaqTone.Error) }
                    SabbaqButton(stringResource(R.string.ref_enter_action), busy = busy, enabled = code.length >= 4 && !busy,
                        modifier = Modifier.testTag("invite-link"), onClick = {
                            scope.launch {
                                busy = true
                                SupabaseClient.claimReferralCode(code).fold(
                                    { if (it.ok) linked = true else message = claimOutcomeTextFor(context, it.reason) },
                                    { message = context.getString(R.string.ref_load_failed) })
                                busy = false
                            }
                        })
                    SabbaqButton(stringResource(R.string.ref_prompt_skip), onClick = onDone, enabled = !busy,
                        style = SabbaqButtonStyle.Text, modifier = Modifier.testTag("invite-skip"))
                    Text(stringResource(R.string.ref_prompt_later), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Point(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

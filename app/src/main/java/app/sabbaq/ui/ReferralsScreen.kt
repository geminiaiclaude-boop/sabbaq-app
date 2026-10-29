package app.sabbaq.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sabbaq.R
import app.sabbaq.Settings
import app.sabbaq.network.ReferralSummary
import app.sabbaq.network.ReferredDriver
import app.sabbaq.network.SupabaseClient
import java.net.URLEncoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Optional invite code on the sign-in screen; it is attached right after the account exists. */
@Composable
internal fun ReferralCodeField(enabled: Boolean) {
    var code by rememberSaveable { mutableStateOf(Settings.pendingReferralCode.orEmpty()) }
    OutlinedTextField(
        value = code,
        onValueChange = { raw ->
            code = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(20)
            Settings.pendingReferralCode = code.takeIf { it.length >= 4 }
        },
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.ref_code_optional)) },
        placeholder = { Text("SB-7K9M2X4P") },
        modifier = Modifier.fillMaxWidth().testTag("login-referral-code"),
    )
}

/**
 * «الإحالات والمكافآت»: the driver's own code with copy and WhatsApp share, the totals, entering
 * a colleague's code while allowed, and the drivers who used the code with their state.
 */
@Composable
internal fun ReferralsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<ReferralSummary?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var claimCode by rememberSaveable { mutableStateOf("") }
    var claiming by remember { mutableStateOf(false) }
    val outcomeText = claimOutcomeText(Settings.referralClaimOutcome)

    LaunchedEffect(reload) {
        failed = false
        SupabaseClient.myReferrals().fold({ summary = it }, { failed = true })
        if (Settings.referralClaimOutcome != null) { message = outcomeText; Settings.referralClaimOutcome = null }
    }

    SabbaqSectionTitle(title = stringResource(R.string.ref_title), subtitle = stringResource(R.string.ref_subtitle))
    val s = summary
    if (s == null) {
        SabbaqCard {
            Text(stringResource(if (failed) R.string.ref_load_failed else R.string.ref_loading), style = MaterialTheme.typography.bodyMedium)
            if (failed) TextButton(onClick = { reload++ }) { Text(stringResource(R.string.ref_retry)) }
        }
        return
    }
    message?.let { SabbaqCard { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ref-message")) } }
    if (!s.enabled) SabbaqCard { Text(stringResource(R.string.ref_paused), style = MaterialTheme.typography.bodyMedium) }

    SabbaqCard(title = stringResource(R.string.ref_my_code), icon = Icons.Outlined.CardGiftcard) {
        Text(s.myCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("ref-my-code"))
        Text(stringResource(R.string.ref_how, s.rewardDays), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SabbaqButton(stringResource(R.string.ref_copy), onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("code", s.myCode))
                message = context.getString(R.string.ref_copied)
            }, style = SabbaqButtonStyle.Secondary, modifier = Modifier.weight(1f).testTag("ref-copy"))
            SabbaqButton(stringResource(R.string.ref_share), onClick = {
                openWhatsApp(context, null, context.getString(R.string.ref_share_text, s.myCode))
            }, modifier = Modifier.weight(1f).testTag("ref-share"))
        }
    }

    SabbaqCard(title = stringResource(R.string.ref_stats), icon = Icons.Outlined.Groups) {
        Text(stringResource(R.string.ref_stats_line, s.invited, s.rewarded, s.daysEarned), style = MaterialTheme.typography.bodyMedium)
    }

    if (s.canClaim) {
        SabbaqCard(title = stringResource(R.string.ref_enter_title), icon = Icons.Outlined.Redeem) {
            Text(stringResource(R.string.ref_enter_sub, s.claimWindowDays), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = claimCode, onValueChange = { claimCode = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(20) },
                singleLine = true, label = { Text(stringResource(R.string.ref_code)) }, modifier = Modifier.fillMaxWidth().testTag("ref-claim-code"))
            Text(stringResource(R.string.ref_enter_privacy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SabbaqButton(stringResource(R.string.ref_enter_action), busy = claiming, enabled = claimCode.length >= 4 && !claiming,
                modifier = Modifier.testTag("ref-claim"), onClick = {
                    scope.launch {
                        claiming = true
                        SupabaseClient.claimReferralCode(claimCode).fold(
                            { message = claimOutcomeTextFor(context, if (it.ok) "ok" else it.reason); if (it.ok) reload++ },
                            { message = context.getString(R.string.ref_load_failed) })
                        claiming = false
                    }
                })
        }
    } else if (s.alreadyClaimed) {
        SabbaqCard { Text(stringResource(R.string.ref_already_linked), style = MaterialTheme.typography.bodyMedium) }
    }

    SabbaqCard(title = stringResource(R.string.ref_list_title), subtitle = stringResource(R.string.ref_list_sub)) {
        if (s.referrals.isEmpty()) Text(stringResource(R.string.ref_list_empty), style = MaterialTheme.typography.bodySmall)
        s.referrals.forEachIndexed { i, r -> ReferredRow(r, i) }
    }
}

@Composable
private fun ReferredRow(r: ReferredDriver, index: Int) {
    val context = LocalContext.current
    val date = r.claimedAt?.let { DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault()).format(it) }.orEmpty()
    Column(Modifier.fillMaxWidth().testTag("ref-row-$index"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name.ifBlank { "—" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val (label, color) = when {
            r.status == "rejected_fraud" -> stringResource(R.string.ref_state_rejected) to MaterialTheme.colorScheme.error
            r.subscription == "awaiting_first_payment" -> stringResource(R.string.ref_state_waiting) to MaterialTheme.colorScheme.tertiary
            r.subscription == "active" -> stringResource(R.string.ref_state_active, r.rewardDays) to MaterialTheme.colorScheme.primary
            else -> stringResource(R.string.ref_state_expired) to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        if (r.phone != null && r.status != "rejected_fraud" && r.subscription != "active") {
            TextButton(onClick = {
                openWhatsApp(context, r.phone, context.getString(
                    if (r.subscription == "expired") R.string.ref_remind_text else R.string.ref_encourage_text, r.name))
            }, modifier = Modifier.testTag("ref-whatsapp-$index")) {
                Text(stringResource(if (r.subscription == "expired") R.string.ref_remind else R.string.ref_encourage))
            }
        }
    }
}

/** WhatsApp chat with [phone] (or the share picker inside WhatsApp when null) with [text]. */
private fun openWhatsApp(context: Context, phone: String?, text: String) {
    val digits = phone?.filter(Char::isDigit).orEmpty()
    val url = "https://wa.me/$digits?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun claimOutcomeText(code: String?): String? = code?.let { claimOutcomeTextFor(LocalContext.current, it) }

private fun claimOutcomeTextFor(context: Context, code: String?): String = context.getString(when (code) {
    "ok" -> R.string.ref_claim_ok
    "referrals_paused" -> R.string.ref_claim_paused
    "self_referral" -> R.string.ref_claim_self
    "same_device" -> R.string.ref_claim_device
    "already_claimed" -> R.string.ref_claim_already
    "claim_window_expired" -> R.string.ref_claim_window
    "already_paid" -> R.string.ref_claim_paid
    else -> R.string.ref_claim_invalid
})

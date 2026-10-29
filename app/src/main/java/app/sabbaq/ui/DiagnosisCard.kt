package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sabbaq.R
import app.sabbaq.core.engine.FunnelStep
import app.sabbaq.core.engine.FunnelVerdict
import app.sabbaq.service.DiagnosisRepository
import kotlinx.coroutines.delay

/** Where the verdict's button leads. */
internal enum class DiagnosisAction { ACCESSIBILITY, ACCOUNT, CONDITIONS, NONE }

/**
 * "Why are no orders accepted?": the step where offers stopped in the last hour, in the
 * driver's words, with the one action that fixes it. [detailed] adds the hour's counts.
 */
@Composable
internal fun DiagnosisCard(onAction: (DiagnosisAction) -> Unit, modifier: Modifier = Modifier, detailed: Boolean = false) {
    val state by DiagnosisRepository.state.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { DiagnosisRepository.refresh(force = true); delay(5_000L) }
    }
    val verdict = state.verdict
    val (title, body) = diagnosisText(verdict)
    val action = diagnosisAction(verdict)
    val icon = when (verdict.step) {
        FunnelStep.WORKING -> Icons.Outlined.CheckCircle
        FunnelStep.EVENTS -> if (verdict.code == "waiting_for_offers") Icons.Outlined.HourglassEmpty else Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.ErrorOutline
    }
    SabbaqCard(title = stringResource(R.string.diag_card_title), subtitle = title, icon = icon, modifier = modifier.testTag("diagnosis-card")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("diagnosis-${verdict.code}"))
            if (detailed) {
                val s = state.snapshot
                Text(
                    stringResource(R.string.diag_counts, s.events, s.offers, s.rejected, s.taps, s.confirmed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != DiagnosisAction.NONE) {
                TextButton(onClick = { onAction(action) }, modifier = Modifier.testTag("diagnosis-action")) {
                    Text(stringResource(when (action) {
                        DiagnosisAction.ACCESSIBILITY -> R.string.diag_action_a11y
                        DiagnosisAction.ACCOUNT -> R.string.diag_action_account
                        DiagnosisAction.CONDITIONS -> R.string.diag_action_conditions
                        DiagnosisAction.NONE -> R.string.diag_action_conditions
                    }))
                }
            }
        }
    }
}

internal fun diagnosisAction(v: FunnelVerdict): DiagnosisAction = when {
    v.code == "service_off" -> DiagnosisAction.ACCESSIBILITY
    v.code == "gate_disclosure_required" -> DiagnosisAction.ACCESSIBILITY
    v.code.startsWith("gate_subscription") || v.code == "gate_clock_tampered" -> DiagnosisAction.ACCOUNT
    v.code == "all_rejected" -> DiagnosisAction.CONDITIONS
    v.code == "tap_not_sent" && v.reason == "gate" -> DiagnosisAction.ACCOUNT
    else -> DiagnosisAction.NONE
}

@Composable
private fun diagnosisText(v: FunnelVerdict): Pair<String, String> {
    val reason = v.reason
    return when (v.code) {
        "service_off" -> stringResource(R.string.diag_service_off_t) to stringResource(R.string.diag_service_off_b)
        "gate_service_paused" -> stringResource(R.string.diag_paused_t) to stringResource(R.string.diag_paused_b)
        "gate_disclosure_required" -> stringResource(R.string.diag_disclosure_t) to stringResource(R.string.diag_disclosure_b)
        "gate_subscription_unknown" -> stringResource(R.string.diag_sub_unknown_t) to stringResource(R.string.diag_sub_unknown_b)
        "gate_subscription_expired" -> stringResource(R.string.diag_sub_expired_t) to stringResource(R.string.diag_sub_expired_b)
        "gate_clock_tampered" -> stringResource(R.string.diag_clock_t) to stringResource(R.string.diag_clock_b)
        "no_ride_app_events" -> stringResource(R.string.diag_no_events_t) to stringResource(R.string.diag_no_events_b)
        "offer_not_read" -> stringResource(R.string.diag_not_read_t) to stringResource(R.string.diag_not_read_b, readReason(reason))
        "all_rejected" -> stringResource(R.string.diag_rejected_t) to stringResource(R.string.diag_rejected_b, reason?.let { reasonLabel(it) } ?: "—")
        "held_back" -> stringResource(R.string.diag_held_t) to stringResource(R.string.diag_held_b)
        "tap_not_sent" -> stringResource(R.string.diag_not_sent_t) to stringResource(R.string.diag_not_sent_b, tapReason(reason))
        "tap_not_confirmed" -> stringResource(R.string.diag_not_confirmed_t) to stringResource(R.string.diag_not_confirmed_b)
        "tap_pending" -> stringResource(R.string.diag_pending_t) to stringResource(R.string.diag_pending_b)
        "working" -> stringResource(R.string.diag_working_t) to stringResource(R.string.diag_working_b)
        else -> if (v.code.startsWith("gate_")) stringResource(R.string.diag_paused_t) to stringResource(R.string.diag_paused_b)
        else stringResource(R.string.diag_waiting_t) to stringResource(R.string.diag_waiting_b)
    }
}

@Composable
private fun readReason(reason: String?): String = stringResource(when (reason) {
    "no_accept" -> R.string.diag_read_no_accept
    "no_minutes" -> R.string.diag_read_no_minutes
    "no_price" -> R.string.diag_read_no_price
    "online_screen" -> R.string.diag_read_online
    "root_unavailable" -> R.string.diag_read_root
    "other_app" -> R.string.diag_read_other_app
    else -> R.string.diag_read_other
})

@Composable
private fun tapReason(reason: String?): String = stringResource(when (reason) {
    "gate" -> R.string.diag_tap_gate
    "foreign_node" -> R.string.diag_tap_foreign
    "empty_bounds" -> R.string.diag_tap_bounds
    "gesture_refused" -> R.string.diag_tap_gesture
    else -> R.string.diag_read_other
})

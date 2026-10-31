package app.sabbaq.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureInPicture
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sabbaq.BuildConfig
import app.sabbaq.LocaleManager
import app.sabbaq.R
import app.sabbaq.core.UpdateState
import app.sabbaq.fleet.ApkUpdater
import app.sabbaq.fleet.FleetRepository
import app.sabbaq.fleet.FloatingBubble
import kotlinx.coroutines.launch

/**
 * The published release on the dashboard: a newer version is offered with one button; below the
 * administration's minimum the card says accepting has stopped until the update is installed.
 */
@Composable
internal fun UpdateBanner(modifier: Modifier = Modifier) {
    val release by FleetRepository.release.collectAsState()
    val progress by ApkUpdater.progress.collectAsState()
    val state = release.state(BuildConfig.VERSION_CODE.toLong())
    if (state == UpdateState.NONE) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val required = state == UpdateState.REQUIRED
    val notes = if (LocaleManager.isArabic()) release.notesAr else release.notesEn
    SabbaqCard(
        title = stringResource(if (required) R.string.upd_required_t else R.string.upd_available_t),
        subtitle = stringResource(if (required) R.string.upd_required_b else R.string.upd_available_b, release.latestName),
        icon = Icons.Outlined.SystemUpdate,
        modifier = modifier.testTag(if (required) "update-required" else "update-available"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notes.isNotBlank()) Text(notes, style = MaterialTheme.typography.bodyMedium)
            when (val p = progress) {
                is ApkUpdater.State.Downloading -> {
                    LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.upd_downloading, p.percent), style = MaterialTheme.typography.labelSmall)
                }
                is ApkUpdater.State.Failed -> Text(stringResource(R.string.upd_failed, p.reason),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            if (BuildConfig.DIRECT_DISTRIBUTION) {
                Text(stringResource(R.string.upd_install_hint), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { scope.launch { ApkUpdater.update(context, release) } },
                enabled = progress !is ApkUpdater.State.Downloading,
                modifier = Modifier.fillMaxWidth().testTag("update-now"),
            ) { Text(stringResource(R.string.upd_now)) }
        }
    }
}

/**
 * The reference dashboard's «الزر العائم»: shows the floating control; without "display over other
 * apps" it asks for the permission and opens the setting.
 */
@Composable
internal fun FloatingControlButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Shown, hidden or waiting for the overlay permission, re-read while the page is on screen: the
    // control's own close button and the system permission page both change it outside this screen.
    val state by androidx.compose.runtime.produceState(floatingState(context), context) {
        while (true) {
            val now = floatingState(context)
            // The permission was just granted in the system settings: the control appears at once.
            if (now == FloatingState.SHOWN && value != FloatingState.SHOWN) FloatingBubble.attach(context)
            value = now
            kotlinx.coroutines.delay(1_000L)
        }
    }
    // One row, half the height of a full card (owner, 2026-10-31): badge, name and state, switch.
    SabbaqCard(modifier = modifier.testTag("floating-control-card")) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SabbaqIconBadge(Icons.Outlined.PictureInPicture)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.float_button), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    stringResource(when (state) {
                        FloatingState.SHOWN -> R.string.float_state_on
                        FloatingState.HIDDEN -> R.string.float_state_off
                        FloatingState.NEEDS_PERMISSION -> R.string.float_state_permission
                    }),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (state == FloatingState.NEEDS_PERMISSION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val name = stringResource(R.string.float_button)
            androidx.compose.material3.Switch(
                checked = state == FloatingState.SHOWN,
                onCheckedChange = { on ->
                    if (!on) FloatingBubble.hide()
                    else if (!FloatingBubble.show(context)) {
                        FloatingBubble.openPermission(context)
                    }
                },
                modifier = Modifier.testTag("floating-control-show").semantics { contentDescription = name },
            )
        }
    }
}

private enum class FloatingState { SHOWN, HIDDEN, NEEDS_PERMISSION }

private fun floatingState(context: android.content.Context): FloatingState = when {
    !FloatingBubble.permitted(context) -> FloatingState.NEEDS_PERMISSION
    app.sabbaq.Settings.floatingBubble -> FloatingState.SHOWN
    else -> FloatingState.HIDDEN
}


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
    SabbaqButton(
        text = stringResource(R.string.float_button),
        onClick = {
            if (!FloatingBubble.show(context)) {
                Toast.makeText(context, context.getString(R.string.float_needs_permission), Toast.LENGTH_LONG).show()
                runCatching { context.startActivity(FloatingBubble.permissionIntent(context)) }
                    .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
            }
        },
        style = SabbaqButtonStyle.Secondary,
        icon = Icons.Outlined.PictureInPicture,
        modifier = modifier.testTag("floating-control-show"),
    )
}


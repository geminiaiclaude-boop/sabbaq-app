package app.sabbaq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.sabbaq.BuildConfig
import app.sabbaq.LocaleManager
import app.sabbaq.R
import app.sabbaq.core.StandbyBucket
import app.sabbaq.core.UpdateState
import app.sabbaq.fleet.ApkUpdater
import app.sabbaq.fleet.FleetRepository
import app.sabbaq.fleet.KeepAlive
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

/** Android settings that end monitoring on their own: app hibernation and the restricted standby buckets. */
@Composable
internal fun KeepAliveBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hibernation by remember { mutableStateOf(KeepAlive.hibernationOn(context)) }
    var restricted by remember { mutableStateOf(StandbyBucket.atRisk(KeepAlive.standbyBucket(context))) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hibernation = KeepAlive.hibernationOn(context)
        restricted = StandbyBucket.atRisk(KeepAlive.standbyBucket(context))
    }
    if (!hibernation && !restricted) return
    SabbaqCard(
        title = stringResource(if (hibernation) R.string.keep_hib_t else R.string.keep_standby_t),
        subtitle = stringResource(if (hibernation) R.string.keep_hib_b else R.string.keep_standby_b),
        icon = Icons.Outlined.BatteryAlert,
        modifier = modifier.testTag("keep-alive-banner"),
    ) {
        TextButton(onClick = {
            runCatching { context.startActivity(if (hibernation) KeepAlive.hibernationIntent(context) else PowerSetup.batteryIntent(context)) }
                .recoverCatching { context.startActivity(PowerSetup.appDetailsIntent(context)) }
        }, modifier = Modifier.testTag("keep-alive-open")) { Text(stringResource(R.string.keep_open)) }
    }
}

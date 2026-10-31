package app.sabbaq.ui

import app.sabbaq.LocaleManager
import androidx.compose.ui.platform.testTag

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sabbaq.BuildConfig
import app.sabbaq.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sabbaq.ThemeMode
import app.sabbaq.telemetry.TelemetryRepository
import app.sabbaq.ui.theme.sabbaqEntrance
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import app.sabbaq.support.SupportRepository

/** Settings categories; each opens its own page inside the «الإعدادات» destination. */
internal enum class SettingsSection(val titleRes: Int, val icon: ImageVector) {
    Voice(R.string.settings_voice, Icons.Outlined.RecordVoiceOver),
    Power(R.string.settings_power, Icons.Outlined.Bolt),
    Permissions(R.string.settings_permissions, Icons.Outlined.AccessibilityNew),
    Appearance(R.string.settings_appearance, Icons.Outlined.Palette),
    Diagnostics(R.string.settings_diagnostics, Icons.Outlined.BugReport),
    Platforms(R.string.settings_platforms, Icons.Outlined.Apps),
    More(R.string.settings_more, Icons.Outlined.Info),
}

/**
 * Central settings: a category list, then one focused page per category. Everything that
 * configures the app (not the live work state) lives here, so «حالة العمل» shows only what
 * the driver needs while working.
 */
@Composable
internal fun SettingsHubPage(
    permissions: List<ReadinessItem>,
    power: List<ReadinessItem>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenSimulator: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSetupGuide: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    BackHandler(enabled = section != null) { section = null }
    val current = section
    if (current == null) {
        SabbaqPageHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_intro))
        val missingPermissions = permissions.count { !it.ready }
        val missingPower = power.count { !it.ready }
        SabbaqCard(modifier = Modifier.sabbaqEntrance(0)) {
            SettingsSection.entries.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val (summary, attention) = when (item) {
                    SettingsSection.Voice -> stringResource(R.string.settings_sum_voice) to false
                    SettingsSection.Power -> (if (missingPower == 0) stringResource(R.string.settings_power_ok)
                        else stringResource(R.string.settings_power_fix, pluralStringResource(R.plurals.permissions_count, missingPower, missingPower))) to (missingPower > 0)
                    SettingsSection.Permissions -> (if (missingPermissions == 0) stringResource(R.string.settings_perms_ok)
                        else stringResource(R.string.settings_perms_fix, pluralStringResource(R.plurals.permissions_count, missingPermissions, missingPermissions))) to (missingPermissions > 0)
                    SettingsSection.Appearance -> (when (themeMode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    } + " · " + stringResource(if (LocaleManager.isArabic()) R.string.language_arabic else R.string.language_english)) to false
                    SettingsSection.Diagnostics -> stringResource(R.string.settings_sum_diagnostics) to false
                    SettingsSection.Platforms -> stringResource(R.string.settings_sum_platforms) to false
                    SettingsSection.More -> stringResource(R.string.settings_sum_more) to false
                }
                SettingsCategoryRow(item.icon, stringResource(item.titleRes), summary, attention) { section = item }
            }
        }
        Text(
            stringResource(R.string.app_version_line, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.settings_back), role = Role.Button) { section = null }
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Auto-mirrored: points left in English, right in Arabic (the way back in each).
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    SabbaqPageHeader(stringResource(current.titleRes))
    when (current) {
        SettingsSection.Voice -> VoiceAlertsCard(Modifier.sabbaqEntrance(0))

        SettingsSection.Power -> SabbaqCard(
            title = if (power.all { it.ready }) stringResource(R.string.power_ready_title) else stringResource(R.string.power_fix_title),
            subtitle = if (power.all { it.ready }) stringResource(R.string.power_ready_sub)
                else stringResource(R.string.power_fix_sub),
            icon = Icons.Outlined.Bolt,
            modifier = Modifier.sabbaqEntrance(0),
        ) {
            power.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ReadinessRow(item)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(R.string.power_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection.Permissions -> SabbaqCard(
            title = if (permissions.all { it.ready }) stringResource(R.string.perms_ready_title) else stringResource(R.string.perms_needed_title),
            subtitle = stringResource(R.string.perms_sub),
            icon = Icons.Outlined.AccessibilityNew,
            modifier = Modifier.sabbaqEntrance(0),
        ) {
            permissions.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ReadinessRow(item)
            }
        }

        SettingsSection.Appearance -> SabbaqCard(modifier = Modifier.sabbaqEntrance(0)) {
            Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleSmall)
            LanguageSwitch(Modifier.fillMaxWidth().testTag("settings-language"), fill = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.ma_sec_appearance), style = MaterialTheme.typography.titleSmall)
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.RadioButton) { onThemeChange(mode) }
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = mode == themeMode, onClick = null)
                    Text(when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        SettingsSection.Diagnostics -> {
            LiveAcceptTestEntry(Modifier.sabbaqEntrance(0))
            SupportIdCard(Modifier.sabbaqEntrance(1))
        }

        SettingsSection.Platforms -> SupportedPlatformsCard(Modifier.sabbaqEntrance(0))

        SettingsSection.More -> {
            val context = LocalContext.current
            SabbaqCard(modifier = Modifier.sabbaqEntrance(0)) {
                SettingsCategoryRow(Icons.Outlined.Visibility, stringResource(R.string.more_sim_title), stringResource(R.string.more_sim_sub), false, onOpenSimulator)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsCategoryRow(Icons.Outlined.History, stringResource(R.string.more_hist_title), stringResource(R.string.more_hist_sub), false, onOpenHistory)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsCategoryRow(Icons.Outlined.Tune, stringResource(R.string.more_guide_title), stringResource(R.string.more_guide_sub), false, onOpenSetupGuide)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsCategoryRow(Icons.Outlined.PrivacyTip, stringResource(R.string.more_privacy_title), stringResource(R.string.more_privacy_sub), false) {
                    PublicLinks.open(context, PublicLinks.PRIVACY_POLICY)
                }
            }
        }
    }
}

/** Opens the live acceptance test: proves on this phone that Sabbaq can read and accept an offer. */
@Composable
internal fun LiveAcceptTestEntry(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SabbaqCard(
        title = stringResource(R.string.live_test_title),
        subtitle = stringResource(R.string.live_test_sub),
        icon = Icons.Outlined.TaskAlt,
        modifier = modifier,
    ) {
        SabbaqButton(
            text = stringResource(R.string.live_test_start),
            onClick = { runCatching { context.startActivity(android.content.Intent(context, LiveAcceptTestActivity::class.java)) } },
            icon = Icons.Outlined.PlayArrow,
        )
    }
}

/** The support id from the diagnostics page, so a complaint can be linked without opening «حسابي». */
@Composable
private fun SupportIdCard(modifier: Modifier = Modifier) {
    val id = TelemetryRepository.currentCorrelationId() ?: return
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by rememberSaveable { mutableStateOf(false) }
    SabbaqCard(
        title = stringResource(R.string.support_id_title),
        subtitle = stringResource(R.string.support_id_sub),
        icon = Icons.Outlined.Info,
        modifier = modifier,
    ) {
        Text(id, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr))
        SabbaqButton(
            text = if (copied) stringResource(R.string.support_id_copied) else stringResource(R.string.support_id_copy),
            onClick = { clipboard.setText(AnnotatedString(id)); copied = true },
            style = SabbaqButtonStyle.Secondary,
            icon = Icons.Outlined.ContentCopy,
        )
    }
}

@Composable
private fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    attention: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.open_section, title), role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SabbaqIconBadge(icon, destructive = attention)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Auto-mirrored: points right in English, left in Arabic (the way forward in each).
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

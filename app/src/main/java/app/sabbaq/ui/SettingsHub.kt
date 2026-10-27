package app.sabbaq.ui

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
import app.sabbaq.ThemeMode
import app.sabbaq.telemetry.TelemetryRepository
import app.sabbaq.ui.theme.sabbaqEntrance
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import app.sabbaq.support.SupportRepository
import app.sabbaq.service.ScreenSketchStore

/** Settings categories; each opens its own page inside the «الإعدادات» destination. */
internal enum class SettingsSection(val title: String, val icon: ImageVector) {
    Voice("الصوت والتنبيهات", Icons.Outlined.RecordVoiceOver),
    Power("التشغيل والطاقة", Icons.Outlined.Bolt),
    Permissions("الأذونات", Icons.Outlined.AccessibilityNew),
    Appearance("المظهر", Icons.Outlined.Palette),
    Diagnostics("التشخيص والدعم", Icons.Outlined.BugReport),
    Platforms("التطبيقات المدعومة", Icons.Outlined.Apps),
    More("المزيد", Icons.Outlined.Info),
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
        Text("الإعدادات", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            "اضبط سبّاق على طريقتك. كل تغيير يُحفظ فورًا على هذا الجهاز.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val missingPermissions = permissions.count { !it.ready }
        val missingPower = power.count { !it.ready }
        SabbaqCard(modifier = Modifier.sabbaqEntrance(0)) {
            SettingsSection.entries.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val (summary, attention) = when (item) {
                    SettingsSection.Voice -> "تنبيهات صوتية عربية لحالة العمل والطلبات" to false
                    SettingsSection.Power -> (if (missingPower == 0) "يعمل مع قفل الشاشة وبعد إعادة التشغيل"
                        else "ينقصه ${ArabicCount.permissions(missingPower)} ليعمل بكامل طاقته") to (missingPower > 0)
                    SettingsSection.Permissions -> (if (missingPermissions == 0) "كل الأذونات مفعّلة"
                        else "ينقصه ${ArabicCount.permissions(missingPermissions)}") to (missingPermissions > 0)
                    SettingsSection.Appearance -> when (themeMode) {
                        ThemeMode.SYSTEM -> "حسب النظام"
                        ThemeMode.LIGHT -> "فاتح"
                        ThemeMode.DARK -> "داكن"
                    } to false
                    SettingsSection.Diagnostics -> "لماذا لا يقبل الطلبات؟ ومعرّف الدعم" to false
                    SettingsSection.Platforms -> "جيني وبولت وبترا، وما هو مثبت على جوالك" to false
                    SettingsSection.More -> "تجربة الطلب، سجل القرارات، دليل الإعداد والخصوصية" to false
                }
                SettingsCategoryRow(item.icon, item.title, summary, attention) { section = item }
            }
        }
        Text(
            "سبّاق ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "رجوع إلى الإعدادات", role = Role.Button) { section = null }
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // RTL: «back» points to the right.
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        Text("الإعدادات", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Text(current.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    when (current) {
        SettingsSection.Voice -> VoiceAlertsCard(Modifier.sabbaqEntrance(0))

        SettingsSection.Power -> SabbaqCard(
            title = if (power.all { it.ready }) "يعمل بكامل طاقته" else "ليعمل بكامل طاقته",
            subtitle = if (power.all { it.ready }) "يستمر مع قفل الشاشة، ويعود تلقائيًا بعد إعادة تشغيل الجوال."
                else "حتى لا يوقفه أندرويد أثناء قفل الشاشة أو بعد إعادة التشغيل.",
            icon = Icons.Outlined.Bolt,
            modifier = Modifier.sabbaqEntrance(0),
        ) {
            power.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ReadinessRow(item)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "الاستئناف بعد إعادة التشغيل تلقائي إذا تركت المتابعة تعمل. وعند انقطاع الإنترنت تتوقف المتابعة مؤقتًا وتعود وحدها عند الاتصال.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection.Permissions -> SabbaqCard(
            title = if (permissions.all { it.ready }) "كل شيء جاهز" else "أذونات مطلوبة",
            subtitle = "يحتاجها سبّاق ليقرأ الطلب ويقبل المطابق لشروطك.",
            icon = Icons.Outlined.AccessibilityNew,
            modifier = Modifier.sabbaqEntrance(0),
        ) {
            permissions.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ReadinessRow(item)
            }
        }

        SettingsSection.Appearance -> SabbaqCard(modifier = Modifier.sabbaqEntrance(0)) {
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
                        ThemeMode.SYSTEM -> "حسب النظام"
                        ThemeMode.LIGHT -> "فاتح"
                        ThemeMode.DARK -> "داكن"
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        SettingsSection.Diagnostics -> {
            LiveAcceptTestEntry(Modifier.sabbaqEntrance(0))
            UnreadScreenCard()
            TelemetryDiagnosticsCard(Modifier.sabbaqEntrance(1))
            SupportIdCard(Modifier.sabbaqEntrance(2))
        }

        SettingsSection.Platforms -> SupportedPlatformsCard(Modifier.sabbaqEntrance(0))

        SettingsSection.More -> {
            val context = LocalContext.current
            SabbaqCard(modifier = Modifier.sabbaqEntrance(0)) {
                SettingsCategoryRow(Icons.Outlined.Visibility, "تجربة الطلب", "جرّب شروطك على طلب افتراضي قبل العمل", false, onOpenSimulator)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsCategoryRow(Icons.Outlined.History, "سجل القرارات والإحصاءات", "ما قبله سبّاق ورفضه ولماذا، وإحصاءات اليوم والأسبوع والشهر", false, onOpenHistory)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsCategoryRow(Icons.Outlined.Tune, "دليل الإعداد", "تجهيز سبّاق خطوة بخطوة", false, onOpenSetupGuide)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsCategoryRow(Icons.Outlined.PrivacyTip, "سياسة الخصوصية", "ما الذي يقرؤه سبّاق وما لا يجمعه", false) {
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
        title = "اختبار القبول الحي",
        subtitle = "يتأكد خلال ثوانٍ أن سبّاق يقرأ الطلب ويضغط القبول على جوالك، ويحدد الخطوة المتعطلة إن وُجدت.",
        icon = Icons.Outlined.TaskAlt,
        modifier = modifier,
    ) {
        SabbaqButton(
            text = "ابدأ الاختبار",
            onClick = { runCatching { context.startActivity(android.content.Intent(context, LiveAcceptTestActivity::class.java)) } },
            icon = Icons.Outlined.PlayArrow,
        )
    }
}

/**
 * Shown after Jeeny displayed what looked like an offer that Sabbaq could not read: one tap
 * sends its anonymous outline to support, who can fix the reading profile without a release.
 */
@Composable
private fun UnreadScreenCard() {
    val sketch by ScreenSketchStore.latest.collectAsState()
    val current = sketch ?: return
    val scope = rememberCoroutineScope()
    var sent by remember(current.atEpochMs) { mutableStateOf<Boolean?>(null) }
    SabbaqCard(
        title = "شاشة من جيني لم يفهمها سبّاق",
        subtitle = "ظهر ما يشبه طلبًا لكن سبّاق لم يستطع قراءته. أرسل مخططها المجهّل للدعم: لا يحتوي أسماء أو عناوين أو أسعارًا.",
        icon = Icons.Outlined.BugReport,
    ) {
        SabbaqButton(
            text = when (sent) { true -> "أُرسلت للدعم"; false -> "تعذر الإرسال؛ أعد المحاولة"; null -> "إرسال للدعم" },
            enabled = sent != true,
            onClick = {
                scope.launch {
                    sent = SupportRepository.send("مخطط شاشة جيني غير المقروءة (${current.reason}):\n${current.text}".take(1000))
                }
            },
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
        title = "معرّف الدعم",
        subtitle = "أرسله للإدارة عند الإبلاغ عن مشكلة؛ لا يكشف بياناتك.",
        icon = Icons.Outlined.Info,
        modifier = modifier,
    ) {
        Text(id, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr))
        SabbaqButton(
            text = if (copied) "تم النسخ" else "نسخ معرّف الدعم",
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
            .clickable(onClickLabel = "فتح $title", role = Role.Button, onClick = onClick)
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
        // RTL: «forward» points to the left.
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

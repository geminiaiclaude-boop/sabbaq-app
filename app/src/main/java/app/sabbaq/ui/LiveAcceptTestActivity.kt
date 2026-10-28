package app.sabbaq.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.sabbaq.LocaleManager
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import app.sabbaq.Settings
import app.sabbaq.ThemeMode
import app.sabbaq.core.LiveAcceptProbe
import app.sabbaq.core.LiveAcceptProbe.Step
import app.sabbaq.core.TelemetryStage
import app.sabbaq.service.LiveAcceptTest
import app.sabbaq.support.SupportRepository
import app.sabbaq.telemetry.TelemetryRepository
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.ui.theme.SabbaqTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «اختبار القبول الحي»: Sabbaq draws a mock offer and the accessibility service accepts it
 * through the same path it uses on Jeeny, step by step, on the driver's own phone. The result
 * names the first step that failed and how to fix it, and can be sent to support in one tap.
 */
class LiveAcceptTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = when (Settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SabbaqTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        LiveAcceptTestScreen(
                            onOpenAccessibility = {
                                runCatching { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            },
                            onClose = ::finish,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        LiveAcceptTest.finish()
        super.onDestroy()
    }
}

private val STEP_LABELS = linkedMapOf(
    Step.EVENT to R.string.lat_step_event,
    Step.READ to R.string.lat_step_read,
    Step.PARSED to R.string.lat_step_parsed,
    Step.ACCEPT_FOUND to R.string.lat_step_accept,
    Step.DISPATCHED to R.string.lat_step_dispatched,
    Step.CLICK_RECEIVED to R.string.lat_step_received,
    Step.CONFIRMED to R.string.lat_step_confirmed,
)

@Composable
private fun LiveAcceptTestScreen(onOpenAccessibility: () -> Unit, onClose: () -> Unit) {
    val state by LiveAcceptTest.state.collectAsState()
    val scope = rememberCoroutineScope()
    var run by remember { mutableIntStateOf(0) }
    var showOffer by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    var sentToSupport by remember { mutableStateOf<Boolean?>(null) }
    val serviceReady = TelemetryRepository.serviceConnected && Settings.a11yDisclosureAccepted

    LaunchedEffect(run) {
        showOffer = false
        accepted = false
        timedOut = false
        reported = false
        sentToSupport = null
        if (!serviceReady) return@LaunchedEffect
        LiveAcceptTest.arm()
        // Give the service a moment to apply its window filter before the offer appears.
        delay(700)
        showOffer = true
        delay(LiveAcceptProbe.TIMEOUT_MS - 700)
        if (!LiveAcceptTest.state.value.succeeded) {
            timedOut = true
            LiveAcceptTest.finish()
        }
    }
    DisposableEffect(Unit) { onDispose { LiveAcceptTest.finish() } }

    val finished = state.succeeded || timedOut
    LaunchedEffect(finished) {
        if (!finished || reported) return@LaunchedEffect
        reported = true
        val total = state.elapsed(Step.CONFIRMED)
        TelemetryRepository.trace(
            TelemetryStage.CLICK_RESULT,
            reasonCode = if (state.succeeded) "selftest_ok" else "selftest_fail",
            detail = LiveAcceptProbe.summary(state.reached.keys, state.method, total),
        )
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SabbaqSectionTitle(
                stringResource(R.string.lat_title),
                stringResource(R.string.lat_subtitle),
            )
            if (!serviceReady) {
                SabbaqMessage(stringResource(R.string.lat_service_off), isError = true)
                SabbaqButton(text = stringResource(R.string.lat_open_a11y), onClick = onOpenAccessibility)
                SabbaqButton(text = stringResource(R.string.lat_retry), onClick = { run++ }, style = SabbaqButtonStyle.Secondary)
                return@Column
            }
            if (showOffer) MockOfferCard(accepted = accepted, onAccept = {
                LiveAcceptTest.reach(Step.CLICK_RECEIVED, SystemClock.elapsedRealtime())
                accepted = true
            })
            StepsCard(state = state, running = !finished)
            if (finished) {
                val diagnosis = LiveAcceptProbe.diagnosis(state.reached.keys, state.method)
                if (diagnosis == null) {
                    val seconds = (state.elapsed(Step.CONFIRMED) ?: 0) / 1000.0
                    SabbaqMessage(
                        stringResource(R.string.lat_success, "%.1f".format(SabbaqLocale, seconds),
                            if (state.method == LiveAcceptProbe.Method.GESTURE) stringResource(R.string.lat_via_gesture) else stringResource(R.string.lat_via_direct)),
                        tone = SabbaqTone.Success,
                    )
                } else {
                    SabbaqMessage(diagnosis, isError = true)
                }
                SabbaqButton(text = stringResource(R.string.lat_retry), onClick = { run++ })
                SabbaqButton(
                    text = when (sentToSupport) { true -> stringResource(R.string.lat_send_sent); false -> stringResource(R.string.lat_send_failed); null -> stringResource(R.string.lat_send) },
                    enabled = sentToSupport != true,
                    style = SabbaqButtonStyle.Secondary,
                    onClick = {
                        val summary = LiveAcceptProbe.summary(state.reached.keys, state.method, state.elapsed(Step.CONFIRMED))
                        scope.launch {
                            sentToSupport = SupportRepository.send(
                                (if (LocaleManager.isArabic()) "نتيجة اختبار القبول الحي: " else "Live accept test result: ") + (if (state.succeeded) (if (LocaleManager.isArabic()) "نجح" else "passed") else (if (LocaleManager.isArabic()) "فشل" else "failed")) + " ($summary)" +
                                    (diagnosis?.let { "\n$it" } ?: ""),
                            )
                        }
                    },
                )
            }
            SabbaqButton(text = stringResource(R.string.lat_close), onClick = onClose, style = SabbaqButtonStyle.Text)
        }
    }
}

/** The mock offer, drawn with the same kind of texts a Jeeny offer shows. */
@Composable
private fun MockOfferCard(accepted: Boolean, onAccept: () -> Unit) {
    val brand = LocalSabbaqBrand.current
    SabbaqCard {
        Text("طلب تجريبي من سبّاق", style = MaterialTheme.typography.titleMedium)
        Text("25 ر.س", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text("الوصول 1.2 كم · 4 دقائق", style = MaterialTheme.typography.bodyMedium)
        Text("الرحلة 8.5 كم · 15 دقيقة", style = MaterialTheme.typography.bodyMedium)
        Text("شارع الملك فهد", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("طريق الأمير محمد بن سلمان", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (accepted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = brand.success)
                Text(LiveAcceptProbe.CONFIRM_LABEL, style = MaterialTheme.typography.titleMedium, color = brand.success)
            }
        } else {
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(LiveAcceptProbe.ACCEPT_LABEL)
            }
        }
    }
}

@Composable
private fun StepsCard(state: LiveAcceptTest.State, running: Boolean) {
    val brand = LocalSabbaqBrand.current
    SabbaqCard(title = if (running) stringResource(R.string.lat_running) else stringResource(R.string.lat_result_title)) {
        STEP_LABELS.forEach { (step, label) ->
            val ms = state.elapsed(step)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(
                    if (ms != null) brand.successContainer else MaterialTheme.colorScheme.surfaceVariant,
                ), contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            ms != null -> Icons.Outlined.CheckCircle
                            running -> Icons.Outlined.HourglassEmpty
                            else -> Icons.Outlined.RadioButtonUnchecked
                        },
                        contentDescription = if (ms != null) stringResource(R.string.lat_done) else stringResource(R.string.lat_not_done),
                        tint = if (ms != null) brand.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (ms != null) Text("$ms ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.dispatchRefused) {
            Text(stringResource(R.string.lat_dispatch_refused), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

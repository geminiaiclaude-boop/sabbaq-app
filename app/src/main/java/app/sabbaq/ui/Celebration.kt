package app.sabbaq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sabbaq.R
import app.sabbaq.network.LicenseState
import app.sabbaq.network.SubscriptionSnapshot
import app.sabbaq.ui.theme.LocalReducedMotion
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.voice.VoiceAnnouncer
import app.sabbaq.voice.VoiceKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What just happened: a paid activation or the start of a free trial. */
internal enum class CelebrationKind { Subscribed, TrialStarted }

internal data class CelebrationEvent(val kind: CelebrationKind, val until: Instant?)

/**
 * The moment access is granted, shown once over the whole app: wherever activation happens
 * (sign-in or the account page) it is announced here and rendered by MainActivity.
 */
internal object Celebrations {
    private val state = MutableStateFlow<CelebrationEvent?>(null)
    val current: StateFlow<CelebrationEvent?> = state.asStateFlow()

    /** Called only after the server confirmed the new access (license re-checked). */
    fun accessGranted(snapshot: SubscriptionSnapshot?) {
        val event = CelebrationCopy.eventFor(snapshot) ?: return
        state.value = event
    }

    fun dismiss() {
        state.value = null
    }
}

/** Wording and numbers, kept pure for tests. */
internal object CelebrationCopy {
    fun eventFor(snapshot: SubscriptionSnapshot?): CelebrationEvent? = when (snapshot?.state) {
        LicenseState.ACTIVE -> CelebrationEvent(CelebrationKind.Subscribed, snapshot.proUntil ?: snapshot.validUntil)
        LicenseState.TRIAL -> CelebrationEvent(CelebrationKind.TrialStarted, snapshot.trialUntil ?: snapshot.validUntil)
        else -> null
    }

    fun headline(kind: CelebrationKind): String = when (kind) {
        CelebrationKind.Subscribed -> "مبروك! أنت الآن من سائقي سبّاق"
        CelebrationKind.TrialStarted -> "انطلقت تجربتك في سبّاق"
    }

    fun badge(kind: CelebrationKind): String = when (kind) {
        CelebrationKind.Subscribed -> "عضوية سبّاق المميّزة"
        CelebrationKind.TrialStarted -> "تجربة كاملة المزايا"
    }

    fun spoken(kind: CelebrationKind): String = when (kind) {
        CelebrationKind.Subscribed -> "مبروك! اشتراكك في سبّاق فعّال. سبّاق جاهز ليلتقط لك الطلبات المناسبة."
        CelebrationKind.TrialStarted -> "انطلقت تجربتك في سبّاق. جرّب قبول الطلبات المناسبة لشروطك."
    }

    /** Whole days left, rounded up, so "29 days and 3 hours" reads as 30. */
    fun daysLeft(until: Instant, now: Instant): Long {
        val millis = Duration.between(now, until).toMillis()
        if (millis <= 0) return 0
        val day = 24 * 60 * 60 * 1000L
        return (millis + day - 1) / day
    }

    fun validity(until: Instant?, now: Instant): String? {
        until ?: return null
        val days = daysLeft(until, now)
        if (days <= 0) return null
        val date = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ar", "SA"))
            .withZone(ZoneId.of("Asia/Riyadh"))
            .format(until)
        return "فعّال حتى $date · ${ArabicCount.days(days.toInt())}"
    }
}

private data class Confetto(val x: Float, val delay: Float, val speed: Float, val sway: Float, val spin: Float, val color: Color, val wide: Boolean)

/**
 * Full-screen celebration: the Sabbaq medal turns into view in 3D with a glow, confetti
 * falls across the screen, and the driver sees what they unlocked and until when. A short
 * vibration and the spoken line (subject to the driver's voice settings) mark the moment.
 * With reduced motion the same content appears without movement.
 */
@Composable
internal fun ActivationCelebration(event: CelebrationEvent, onDone: () -> Unit) {
    val brand = LocalSabbaqBrand.current
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val gold = Color(0xFFF2C14E)
    BackHandler(onBack = onDone)

    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    val medalTurn = remember { Animatable(if (reduced) 0f else 180f) }
    LaunchedEffect(event) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        VoiceAnnouncer.announce(VoiceKind.Status, CelebrationCopy.spoken(event.kind))
        if (!reduced) {
            medalTurn.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessVeryLow))
        }
    }
    LaunchedEffect(event) {
        if (!reduced) reveal.animateTo(1f, tween(900))
    }
    val loop = rememberInfiniteTransition(label = "celebration")
    val fall by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(4_200, easing = LinearEasing)), label = "confetti")
    val glow by loop.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "glow")
    val confetti = remember {
        val random = Random(7)
        val palette = listOf(gold, brand.heroAccent, Color.White, Color(0xFFFF8A65), Color(0xFF9FA8FF))
        List(70) {
            Confetto(random.nextFloat(), random.nextFloat(), 0.7f + random.nextFloat() * 0.6f, random.nextFloat() * 2f - 1f,
                random.nextFloat() * 720f - 360f, palette[it % palette.size], random.nextBoolean())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(brand.heroStart, brand.heroEnd)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        if (!reduced) {
            Canvas(Modifier.fillMaxSize()) {
                confetti.forEach { c ->
                    val t = ((fall * c.speed + c.delay) % 1f)
                    val y = -20f * density + t * (size.height + 40f * density)
                    val x = c.x * size.width + sin((t * 2f * PI + c.delay * 6f).toFloat()) * 18f * density * c.sway
                    val w = (if (c.wide) 10f else 6f) * density
                    val h = (if (c.wide) 5f else 9f) * density
                    rotate(degrees = c.spin * t, pivot = Offset(x, y)) {
                        drawRect(c.color.copy(alpha = 0.9f), topLeft = Offset(x - w / 2, y - h / 2), size = Size(w, h))
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(196.dp)) {
                // Halo behind the medal.
                Canvas(Modifier.size(196.dp)) {
                    drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.45f * glow), Color.Transparent)), radius = size.minDimension / 2f)
                }
                Box(
                    Modifier
                        .size(132.dp)
                        .graphicsLayer {
                            rotationY = medalTurn.value
                            cameraDistance = 12f * density
                        }
                        .shadow(24.dp, CircleShape, spotColor = gold)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFFFE08A), gold, Color(0xFFB9862B)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(104.dp).clip(CircleShape),
                    )
                }
            }

            Surface(shape = RoundedCornerShape(50), color = gold.copy(alpha = 0.18f), contentColor = gold) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(CelebrationCopy.badge(event.kind), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                CelebrationCopy.headline(event.kind),
                style = MaterialTheme.typography.headlineMedium,
                color = brand.onHero,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .semantics { heading() }
                    .graphicsLayer {
                        alpha = reveal.value
                        translationY = (1f - reveal.value) * 24f * density
                    },
            )
            CelebrationCopy.validity(event.until, Instant.now())?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = gold, textAlign = TextAlign.Center)
            }
            Text(
                "سبّاق الآن يعمل لك: يلتقط الطلب المناسب لشروطك قبل غيرك، وأنت تركّز على الطريق.",
                style = MaterialTheme.typography.bodyLarge,
                color = brand.onHeroMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )

            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Perk(Icons.Outlined.Bolt, "قبول تلقائي للطلبات المطابقة لشروطك", gold, brand.onHero)
                Perk(Icons.Outlined.Autorenew, "يعمل مع قفل الشاشة ويعود بعد إعادة التشغيل", gold, brand.onHero)
                Perk(Icons.Outlined.RecordVoiceOver, "تنبيهات صوتية بالعربية أثناء العمل", gold, brand.onHero)
            }

            Spacer(Modifier.height(6.dp))
            SabbaqButton(
                "انطلق الآن",
                onClick = onDone,
                modifier = Modifier.widthIn(max = 420.dp),
                icon = Icons.Outlined.Bolt,
            )
        }
    }
}

@Composable
private fun Perk(icon: ImageVector, text: String, accent: Color, content: Color) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.08f), contentColor = content) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

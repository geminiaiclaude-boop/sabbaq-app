package app.qannas.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.qannas.ui.theme.LocalQannasBrand
import app.qannas.ui.theme.LocalReducedMotion
import app.qannas.ui.theme.QannasMotion
import app.qannas.ui.theme.tilt3D
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Signature visuals. Every scene draws a meaningful still frame when the system asks for
 * reduced motion, so the depth and composition stay; only the movement is removed.
 */

/** Soft light drifting behind every screen: three brand-tinted glows that move very slowly. */
@Composable
internal fun QannasAmbientBackground(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalQannasBrand.current
    val reduced = LocalReducedMotion.current
    val drift = if (reduced) null else rememberInfiniteTransition(label = "ambient").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing)),
        label = "ambient-drift",
    )
    Canvas(modifier.clipToBounds()) {
        val p = drift?.value ?: 0.9f
        val r = size.maxDimension
        glow(Offset(size.width * (0.86f + 0.08f * cos(p)), size.height * (0.06f + 0.05f * sin(p))), r * 0.55f, colors.primary.copy(alpha = 0.12f))
        glow(Offset(size.width * (0.08f + 0.07f * sin(p * 1.3f)), size.height * (0.52f + 0.06f * cos(p))), r * 0.48f, brand.heroAccent.copy(alpha = 0.10f))
        glow(Offset(size.width * (0.72f + 0.06f * sin(p)), size.height * (0.98f + 0.03f * cos(p * 0.7f))), r * 0.45f, colors.tertiary.copy(alpha = 0.07f))
    }
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color) {
    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius), radius, center)
}

/**
 * The product in one picture: a road running to the horizon in perspective, a radar
 * sweeping across it, and order pins floating above the ground — one of them matched.
 * Faster and brighter while monitoring runs ([active]).
 */
@Composable
internal fun QannasRadarScene(active: Boolean, modifier: Modifier = Modifier, pins: Boolean = true) {
    val brand = LocalQannasBrand.current
    val reduced = LocalReducedMotion.current
    val clock = if (reduced) null else rememberInfiniteTransition(label = "radar-scene").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "radar-scene-clock",
    )
    val density = LocalDensity.current.density
    Canvas(modifier.clipToBounds()) {
        val t = clock?.value ?: 0.35f
        val speed = if (active) 2f else 1f
        val accent = brand.heroAccent
        val light = brand.onHero
        val w = size.width
        val h = size.height
        val horizon = h * 0.16f
        val ground = h - horizon
        val vx = w * 0.5f
        fun at(x: Float, u: Float) = Offset(vx + x * w * 0.62f * u, horizon + ground * u)

        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, accent.copy(alpha = if (active) 0.16f else 0.09f), Color.Transparent),
                startY = horizon - h * 0.14f, endY = horizon + h * 0.22f,
            ),
        )
        for (i in -6..6) {
            if (i == 0) continue
            drawLine(accent.copy(alpha = 0.10f), at(i / 2f, 0.01f), at(i / 2f, 1.25f), strokeWidth = density)
        }
        val rows = 9
        for (r in 0 until rows) {
            val f = ((r + (t * speed) % 1f) / rows)
            val u = f * f
            drawLine(accent.copy(alpha = 0.04f + 0.18f * f), at(-3f, u), at(3f, u), strokeWidth = (0.6f + 1.4f * f) * density)
        }
        val road = Path().apply {
            moveTo(at(-0.45f, 0.01f).x, at(-0.45f, 0.01f).y)
            lineTo(at(0.45f, 0.01f).x, at(0.45f, 0.01f).y)
            lineTo(at(0.45f, 1.25f).x, at(0.45f, 1.25f).y)
            lineTo(at(-0.45f, 1.25f).x, at(-0.45f, 1.25f).y)
            close()
        }
        drawPath(road, Brush.verticalGradient(listOf(accent.copy(alpha = 0.02f), accent.copy(alpha = 0.14f)), startY = horizon, endY = h))
        drawLine(accent.copy(alpha = 0.45f), at(-0.45f, 0.01f), at(-0.45f, 1.25f), strokeWidth = 1.5f * density)
        drawLine(accent.copy(alpha = 0.45f), at(0.45f, 0.01f), at(0.45f, 1.25f), strokeWidth = 1.5f * density)
        for (d in 0 until 6) {
            val f0 = ((d + (t * speed * 2f) % 1f) / 6f)
            val u0 = f0 * f0
            val u1 = (f0 + 0.06f).let { it * it }
            drawLine(light.copy(alpha = 0.25f + 0.45f * f0), at(0f, u0), at(0f, u1), strokeWidth = (1f + 3f * f0) * density, cap = StrokeCap.Round)
        }

        // Radar dish lying on the ground: a circle seen at a low angle is an ellipse.
        val center = at(0f, 0.58f)
        val rx = w * 0.30f
        val ry = rx * 0.30f
        for (ring in 1..3) {
            val k = ring / 3f
            drawOval(accent.copy(alpha = 0.30f * (1.1f - k)), Offset(center.x - rx * k, center.y - ry * k), Size(2 * rx * k, 2 * ry * k), style = Stroke(1.2f * density))
        }
        if (active || reduced) {
            val pulse = (t * 2f) % 1f
            drawOval(accent.copy(alpha = 0.5f * (1f - pulse)), Offset(center.x - rx * pulse * 1.2f, center.y - ry * pulse * 1.2f),
                Size(2 * rx * pulse * 1.2f, 2 * ry * pulse * 1.2f), style = Stroke(2f * density))
        }
        drawArc(
            Brush.radialGradient(listOf(accent.copy(alpha = if (active) 0.55f else 0.30f), Color.Transparent), center = center, radius = rx),
            startAngle = t * 360f * speed, sweepAngle = 55f, useCenter = true,
            topLeft = Offset(center.x - rx, center.y - ry), size = Size(2 * rx, 2 * ry),
        )

        // Floating order pins: ground shadow, a beam, and a chip that bobs in depth.
        // Pins only where the scene stands alone (the intro); behind text they would compete with it.
        val pinSpots = if (pins) listOf(Triple(-1.35f, 0.40f, false), Triple(1.15f, 0.52f, true), Triple(-0.75f, 0.82f, false)) else emptyList()
        pinSpots.forEachIndexed { index, (x, u, matched) ->
            val base = at(x, u)
            val bob = sin((t * 2f + index * 0.33f) * 2f * PI.toFloat()) * 4f * density * u
            val lift = 56f * density * u + bob
            val chipW = 40f * density * u
            val chipH = 20f * density * u
            drawOval(Color.Black.copy(alpha = 0.22f), Offset(base.x - chipW * 0.45f, base.y - chipH * 0.15f), Size(chipW * 0.9f, chipH * 0.3f))
            drawLine((if (matched) accent else light).copy(alpha = 0.45f), base, Offset(base.x, base.y - lift), strokeWidth = 1.5f * density)
            val top = Offset(base.x - chipW / 2f, base.y - lift - chipH)
            if (matched) {
                val halo = 1f + 0.25f * sin(t * 4f * PI.toFloat())
                drawCircle(accent.copy(alpha = 0.22f), radius = chipW * 0.75f * halo, center = Offset(base.x, top.y + chipH / 2f))
            }
            drawRoundRect(
                if (matched) accent else light.copy(alpha = 0.60f), top, Size(chipW, chipH), CornerRadius(chipH / 2f),
            )
            drawCircle(if (matched) brand.heroStart else brand.heroStart.copy(alpha = 0.6f), radius = chipH * 0.22f,
                center = Offset(top.x + chipW * 0.78f, top.y + chipH / 2f))
        }
    }
}

/** The app mark (ring + arrow) drawn as vectors so it can animate piece by piece. */
private fun DrawScope.qannasMark(ringSweep: Float, arrowAlpha: Float, accent: Color, light: Color) {
    val s = size.minDimension / 108f
    translate((size.width - 108f * s) / 2f, (size.height - 108f * s) / 2f) {
        scale(s, pivot = Offset.Zero) {
            drawArc(accent, -90f, ringSweep, useCenter = false, topLeft = Offset(24f, 24f), size = Size(60f, 60f),
                style = Stroke(width = 4f, cap = StrokeCap.Round))
            if (arrowAlpha > 0f) {
                drawPath(Path().apply { moveTo(54f, 30f); lineTo(71f, 72f); lineTo(54f, 63f); lineTo(37f, 72f); close() }, light.copy(alpha = arrowAlpha))
                drawPath(Path().apply { moveTo(54f, 42f); lineTo(54f, 63f); lineTo(37f, 72f); close() }, accent.copy(alpha = arrowAlpha))
            }
        }
    }
}

/**
 * Opening moment: the ring draws itself, the arrow turns into view in 3D, a radar pulse
 * leaves the mark, then the name settles and the whole scene lifts away into the app.
 * Tapping skips it. With reduced motion it is not shown at all (unless [staticFrame]).
 */
@Composable
internal fun QannasLaunchIntro(onFinished: () -> Unit, staticFrame: Boolean = false) {
    val brand = LocalQannasBrand.current
    val reduced = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinished)
    val progress = remember { Animatable(if (staticFrame || reduced) 0.8f else 0f) }
    LaunchedEffect(staticFrame, reduced) {
        if (staticFrame) return@LaunchedEffect
        if (reduced) { finish(); return@LaunchedEffect }
        progress.animateTo(1f, tween(2_300, easing = LinearEasing))
        finish()
    }
    if (reduced && !staticFrame) return
    fun phase(start: Float, end: Float) = ((progress.value - start) / (end - start)).coerceIn(0f, 1f)
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(brand.heroStart, brand.heroEnd)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!staticFrame) finish() }
            .graphicsLayer {
                val exit = phase(0.86f, 1f)
                alpha = 1f - exit
                scaleX = 1f + 0.12f * exit
                scaleY = 1f + 0.12f * exit
            }
            .semantics { contentDescription = "قناص" },
        contentAlignment = Alignment.Center,
    ) {
        QannasRadarScene(active = true, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f * phase(0.3f, 0.7f) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(168.dp)) {
                    val pulse = phase(0.42f, 0.9f)
                    if (pulse > 0f) {
                        listOf(pulse, (pulse - 0.25f).coerceAtLeast(0f)).forEach { p ->
                            if (p > 0f) drawCircle(brand.heroAccent.copy(alpha = 0.55f * (1f - p)), radius = size.minDimension / 2f * (0.55f + 0.45f * p), style = Stroke(3f * density))
                        }
                    }
                }
                Canvas(
                    Modifier
                        .size(132.dp)
                        .graphicsLayer {
                            val tilt = 1f - phase(0f, 0.5f)
                            rotationX = 24f * tilt
                            rotationY = 90f * (1f - phase(0.18f, 0.52f)).let { it * it }
                            cameraDistance = 10f * density
                        },
                ) {
                    drawCircle(Color.Black.copy(alpha = 0.18f), radius = size.minDimension * 0.42f, center = Offset(size.width / 2f, size.height * 0.56f))
                    qannasMark(ringSweep = 360f * phase(0f, 0.36f), arrowAlpha = phase(0.18f, 0.40f), accent = brand.heroAccent, light = brand.onHero)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "قناص",
                style = MaterialTheme.typography.displayMedium,
                color = brand.onHero,
                modifier = Modifier.graphicsLayer {
                    val p = phase(0.48f, 0.72f)
                    alpha = p
                    translationY = (1f - p) * 22f * density
                    rotationX = (1f - p) * 50f
                    cameraDistance = 10f * density
                },
            )
            Text(
                "طلبات تناسبك. وقتك أولى.",
                style = MaterialTheme.typography.titleMedium,
                color = brand.onHeroMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val p = phase(0.6f, 0.82f)
                    alpha = p
                    translationY = (1f - p) * 14f * density
                },
            )
        }
    }
}

private data class SampleOrder(val price: String, val pickup: String, val trip: String, val minutes: String, val matches: Boolean, val verdict: String)

private val sampleOrders = listOf(
    SampleOrder("٤٥ ر.س", "٣ كم", "١٥ كم", "٣٠ د", true, "يطابق شروطك"),
    SampleOrder("١٨ ر.س", "٩ كم", "٤ كم", "١٢ د", false, "الوصول أبعد من حدّك"),
    SampleOrder("٦٢ ر.س", "٢ كم", "٢١ كم", "٣٥ د", true, "يطابق شروطك"),
)

/**
 * A floating stack of example ride requests. Every few seconds the top card turns over
 * to the next one and a verdict stamp lands on it, showing what Qannas decides and why.
 * Clearly labelled as an example; it is never a real order.
 */
@Composable
internal fun QannasOrderShowcase(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableIntStateOf(0) }
    val flip = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        while (true) {
            delay(3_200)
            flip.animateTo(90f, tween(240, easing = QannasMotion.EmphasizedAccelerate))
            shown = (shown + 1) % sampleOrders.size
            flip.snapTo(-90f)
            flip.animateTo(0f, tween(460, easing = QannasMotion.EmphasizedDecelerate))
        }
    }
    val order = sampleOrders[shown]
    val density = LocalDensity.current.density
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 22.dp, start = 18.dp, end = 18.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "مثال توضيحي لطلب: ${order.price}، الوصول ${order.pickup}، المشوار ${order.trip}. ${order.verdict}"
            }
            .tilt3D(maxDegrees = 12f, restX = 10f, restY = -12f),
        contentAlignment = Alignment.Center,
    ) {
        for (depth in 2 downTo 1) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationY = -depth * 11f * density
                        scaleX = 1f - depth * 0.06f
                        scaleY = 1f - depth * 0.06f
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = if (depth == 2) 0.10f else 0.20f)),
            )
        }
        OrderCardFace(order, Modifier.graphicsLayer { rotationY = flip.value; cameraDistance = 12f * density }, stampKey = shown)
    }
}

@Composable
private fun OrderCardFace(order: SampleOrder, modifier: Modifier, stampKey: Int) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalQannasBrand.current
    val reduced = LocalReducedMotion.current
    val stamp = remember(stampKey) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(stampKey) {
        if (!reduced) {
            delay(420)
            stamp.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = colors.surface, contentColor = colors.onSurface, shadowElevation = 18.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.NearMe, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Text("طلب جديد", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("مثال", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Text(order.price, style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                OrderMetric("الوصول", order.pickup)
                OrderMetric("المشوار", order.trip)
                OrderMetric("المدة", order.minutes)
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = if (order.matches) brand.successContainer else colors.errorContainer,
                contentColor = if (order.matches) brand.onSuccessContainer else colors.onErrorContainer,
                modifier = Modifier.graphicsLayer {
                    val p = stamp.value
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = 1.5f - 0.5f * p
                    scaleY = 1.5f - 0.5f * p
                    rotationZ = -8f * (1f - p)
                },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (order.matches) Icons.Outlined.CheckCircle else Icons.Outlined.DoNotDisturbOn, null, Modifier.size(18.dp))
                    Text(order.verdict, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun OrderMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Setup progress as a coin standing in depth: a tilted ring with a shadow on the ground,
 * filling as steps complete, the count in the middle.
 */
@Composable
internal fun QannasProgressCoin(done: Int, total: Int, modifier: Modifier = Modifier) {
    val brand = LocalQannasBrand.current
    val reduced = LocalReducedMotion.current
    val fill = remember { Animatable(if (reduced) done.toFloat() / total else 0f) }
    LaunchedEffect(done, total) {
        fill.animateTo(done.toFloat() / total.coerceAtLeast(1), if (reduced) tween<Float>(0) else tween<Float>(900, easing = QannasMotion.Emphasized))
    }
    val density = LocalDensity.current.density
    Box(modifier.size(96.dp).tilt3D(maxDegrees = 14f, restX = 16f, restY = -14f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 9f * density
            drawOval(Color.Black.copy(alpha = 0.22f), Offset(size.width * 0.18f, size.height * 0.88f), Size(size.width * 0.64f, size.height * 0.10f))
            val inset = stroke / 2f + 4f * density
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2 - 8f * density)
            drawArc(brand.onHero.copy(alpha = 0.16f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                Brush.sweepGradient(listOf(brand.heroAccent, brand.onHero, brand.heroAccent)),
                -90f, 360f * fill.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text("$done/$total", style = MaterialTheme.typography.titleLarge, color = brand.onHero, modifier = Modifier.padding(bottom = 8.dp))
    }
}

private data class PipelineStage(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val detail: String)

/**
 * How Qannas works, as three cards standing in depth: an offer appears, it is compared with
 * the driver's conditions, the matching one is accepted. A light travels along the path and
 * lifts each card in turn; with reduced motion the third (the outcome) stays highlighted.
 */
@Composable
internal fun QannasHowItWorksScene(modifier: Modifier = Modifier) {
    val brand = LocalQannasBrand.current
    val reduced = LocalReducedMotion.current
    val stages = listOf(
        PipelineStage(Icons.Outlined.NearMe, "يظهر طلب", "في تطبيق الرحلات"),
        PipelineStage(Icons.Outlined.FilterAlt, "يقارنه بشروطك", "الأجرة والمسافة والوقت"),
        PipelineStage(Icons.Outlined.CheckCircle, "يقبل المطابق", "بنقرة واحدة"),
    )
    val clock = if (reduced) null else rememberInfiniteTransition(label = "pipeline").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(4_200, easing = LinearEasing)),
        label = "pipeline-clock",
    )
    val density = LocalDensity.current.density
    Box(modifier.fillMaxWidth().height(196.dp)) {
        // The path the light travels along, drawn behind the cards.
        Canvas(Modifier.matchParentSize()) {
            val y = size.height * 0.46f
            val start = size.width * 0.14f
            val end = size.width * 0.86f
            drawLine(brand.onHero.copy(alpha = 0.18f), Offset(start, y), Offset(end, y), strokeWidth = 3f * density, cap = StrokeCap.Round)
            val t = clock?.value ?: 2f
            val x = start + (end - start) * (t / 3f).coerceIn(0f, 1f)
            drawCircle(Brush.radialGradient(listOf(brand.heroAccent, Color.Transparent), center = Offset(x, y), radius = 22f * density),
                radius = 22f * density, center = Offset(x, y))
            drawCircle(brand.heroAccent, radius = 4f * density, center = Offset(x, y))
        }
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            stages.forEachIndexed { index, stage ->
                val focus = clock?.let { (1f - kotlin.math.abs(it.value - (index + 0.5f))).coerceIn(0f, 1f) } ?: if (index == 2) 1f else 0f
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (focus > 0.5f) brand.onHero else brand.onHero.copy(alpha = 0.12f),
                    contentColor = if (focus > 0.5f) brand.heroStart else brand.onHero,
                    shadowElevation = (4f + 14f * focus).dp,
                    modifier = Modifier
                        .width(98.dp)
                        .graphicsLayer {
                            // Each card turns slightly toward the middle and rises as the light reaches it.
                            rotationY = (1 - index) * -14f
                            rotationX = 8f
                            translationY = -10f * density * focus
                            scaleX = 0.94f + 0.08f * focus
                            scaleY = 0.94f + 0.08f * focus
                            cameraDistance = 10f * density
                        },
                ) {
                    Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(stage.icon, contentDescription = null, modifier = Modifier.size(26.dp))
                        Text(stage.title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Text(stage.detail, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                            color = LocalContentColor.current.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }
}

/** Page dots for a short tour: the current page stretches into a pill. */
@Composable
internal fun QannasPageDots(page: Int, count: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val width by androidx.compose.animation.core.animateDpAsState(
                if (index == page) 28.dp else 8.dp, tween(QannasMotion.MEDIUM, easing = QannasMotion.Emphasized), label = "dot-$index")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(RoundedCornerShape(50))
                    .background(if (index <= page) colors.primary else colors.outlineVariant),
            )
        }
    }
}

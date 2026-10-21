package app.sabbaq.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Physical depth for a surface: it leans toward the finger while touched and springs back
 * when released, and (with [idle]) breathes a couple of degrees so the scene feels alive.
 * Touches are only observed, never consumed, so buttons inside keep working.
 * With reduced motion it rests at [restX]/[restY] — still three-dimensional, just still.
 */
fun Modifier.tilt3D(
    maxDegrees: Float = 6f,
    idle: Boolean = true,
    restX: Float = 0f,
    restY: Float = 0f,
): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current.density
    if (reduced) {
        return@composed graphicsLayer {
            rotationX = restX
            rotationY = restY
            cameraDistance = SabbaqMotion.CAMERA_DISTANCE * density
        }
    }
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val idlePhase = rememberInfiniteTransition(label = "tilt-idle").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "tilt-idle-phase",
    )
    pointerInput(maxDegrees) {
        awaitPointerEventScope {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                val follow = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                val release = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                if (change.pressed && size.width > 0 && size.height > 0) {
                    val nx = (change.position.x / size.width - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    val ny = (change.position.y / size.height - 0.5f).coerceIn(-0.5f, 0.5f) * 2f
                    scope.launch { tiltY.animateTo(nx * maxDegrees, follow) }
                    scope.launch { tiltX.animateTo(-ny * maxDegrees, follow) }
                } else {
                    scope.launch { tiltY.animateTo(0f, release) }
                    scope.launch { tiltX.animateTo(0f, release) }
                }
            }
        }
    }.graphicsLayer {
        val p = if (idle) idlePhase.value else 0f
        rotationX = restX + tiltX.value + if (idle) sin(p) * 1.8f else 0f
        rotationY = restY + tiltY.value + if (idle) cos(p) * 2.6f else 0f
        cameraDistance = SabbaqMotion.CAMERA_DISTANCE * density
    }
}

/**
 * A slow band of light that sweeps across a surface every few seconds, like a reflection
 * on glass. Purely decorative and drawn over the content, so it never changes layout.
 */
fun Modifier.glint(enabled: Boolean = true, color: Color = Color.White.copy(alpha = 0.28f), periodMillis: Int = 3_800): Modifier = composed {
    val reduced = LocalReducedMotion.current
    if (!enabled || reduced) return@composed this
    val sweep = rememberInfiniteTransition(label = "glint").animateFloat(
        initialValue = -0.6f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = "glint-sweep",
    )
    drawWithContent {
        drawContent()
        val x = sweep.value * size.width
        if (x > -size.width * 0.4f && x < size.width * 1.4f) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, color, Color.Transparent),
                    start = Offset(x - size.width * 0.25f, 0f),
                    end = Offset(x + size.width * 0.25f, size.height),
                ),
            )
        }
    }
}

/** A result card arriving face-down turns over to face the driver (with reduced motion it simply appears). */
fun Modifier.sabbaqFlipIn(): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val turn = remember { Animatable(if (reduced) 0f else 90f) }
    LaunchedEffect(Unit) {
        if (!reduced) turn.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))
    }
    val density = LocalDensity.current.density
    graphicsLayer {
        rotationY = turn.value
        alpha = (1f - turn.value / 90f).coerceIn(0f, 1f) * 0.6f + 0.4f
        cameraDistance = SabbaqMotion.CAMERA_DISTANCE * density
    }
}

/** The selected navigation icon turns once around its vertical axis, like a coin landing. */
fun Modifier.flipWhenSelected(selected: Boolean): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val turn = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected && !reduced) {
            turn.snapTo(0f)
            turn.animateTo(360f, tween(560, easing = SabbaqMotion.Emphasized))
        }
    }
    val density = LocalDensity.current.density
    graphicsLayer {
        rotationY = turn.value
        cameraDistance = SabbaqMotion.CAMERA_DISTANCE * density
    }
}

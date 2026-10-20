package app.qannas.ui.theme

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay

/**
 * Single source for motion timing. Durations follow Material 3 emphasised motion;
 * depth (scale + perspective rotation) is reserved for navigation between places,
 * while in-page feedback stays two-dimensional and short.
 */
object QannasMotion {
    const val SHORT = 160
    const val MEDIUM = 280
    const val LONG = 420
    const val STAGGER = 45
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Degrees of Y rotation for a place-to-place change; small enough to read as depth, not a flip. */
    internal const val DEPTH_ROTATION = 12f
    internal const val CAMERA_DISTANCE = 16f
}

/** True when the user (or a test runner) turned animations off in system settings. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Navigates between places with a perspective "push" in depth: the incoming place
 * rotates in from the reading direction while the outgoing one recedes. Direction is
 * derived from [order], so going back reverses the motion. RTL mirrors the rotation.
 */
@Composable
fun <T> QannasDepthContent(
    target: T,
    order: (T) -> Int,
    modifier: Modifier = Modifier,
    label: String = "depth-navigation",
    content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    // Plain holder, not state: the previous place only decides direction and must not recompose.
    val previous = remember { PreviousValue(target) }
    val forward = remember(target) { order(target) >= order(previous.value) }
    SideEffect { previous.value = target }
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { depthTransform(forward, reduced) },
        label = label,
    ) { state ->
        val scope = this
        Box(Modifier.perspectiveLayer(scope, forward, reduced)) {
            content(scope, state)
        }
    }
}

private class PreviousValue<T>(var value: T)

private fun <S> AnimatedContentTransitionScope<S>.depthTransform(forward: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(snap()) togetherWith fadeOut(snap())
    val enter = fadeIn(tween(QannasMotion.MEDIUM, delayMillis = 60, easing = QannasMotion.EmphasizedDecelerate)) +
        scaleIn(tween(QannasMotion.LONG, easing = QannasMotion.Emphasized), initialScale = if (forward) 0.92f else 1.05f)
    val exit = fadeOut(tween(QannasMotion.SHORT, easing = QannasMotion.EmphasizedAccelerate)) +
        scaleOut(tween(QannasMotion.MEDIUM, easing = QannasMotion.EmphasizedAccelerate), targetScale = if (forward) 1.04f else 0.94f)
    return (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else -1f }
}

@Composable
private fun Modifier.perspectiveLayer(scope: AnimatedVisibilityScope, forward: Boolean, reduced: Boolean): Modifier {
    if (reduced) return this
    val mirror = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val sign = (if (forward) 1f else -1f) * mirror
    val rotation by scope.transition.animateFloat(
        transitionSpec = { tween(QannasMotion.LONG, easing = QannasMotion.Emphasized) },
        label = "depth-rotation",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> QannasMotion.DEPTH_ROTATION * sign
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> -QannasMotion.DEPTH_ROTATION * 0.6f * sign
        }
    }
    val density = LocalDensity.current.density
    return graphicsLayer {
        rotationY = rotation
        cameraDistance = QannasMotion.CAMERA_DISTANCE * density
    }
}

/**
 * First-appearance motion for a block of content: rises 16dp while tilting back
 * from a few degrees, staggered by [index]. Runs once per composition entry.
 */
fun Modifier.qannasEntrance(index: Int = 0): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced && progress.value < 1f) {
            delay((index.coerceIn(0, 8) * QannasMotion.STAGGER).toLong())
            progress.animateTo(1f, tween(QannasMotion.LONG, easing = QannasMotion.EmphasizedDecelerate))
        }
    }
    val density = LocalDensity.current.density
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 16f * density
        rotationX = (1f - p) * 6f
        cameraDistance = QannasMotion.CAMERA_DISTANCE * density
    }
}

/** Physical press response: the control sinks slightly in depth while held. */
fun Modifier.pressDepth(interactionSource: InteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.97f else 1f,
        animationSpec = tween(if (pressed) 90 else QannasMotion.SHORT, easing = QannasMotion.Emphasized),
        label = "press-depth",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

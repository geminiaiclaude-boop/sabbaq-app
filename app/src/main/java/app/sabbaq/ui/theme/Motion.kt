package app.sabbaq.ui.theme

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
import androidx.compose.ui.graphics.TransformOrigin
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
object SabbaqMotion {
    const val SHORT = 160
    const val MEDIUM = 280
    const val LONG = 420
    const val STAGGER = 70
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Degrees of Y rotation for a place-to-place change: the new place swings in like a door. */
    internal const val DEPTH_ROTATION = 26f
    internal const val CAMERA_DISTANCE = 14f
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
fun <T> SabbaqDepthContent(
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
    val enter = fadeIn(tween(SabbaqMotion.MEDIUM, delayMillis = 60, easing = SabbaqMotion.EmphasizedDecelerate)) +
        scaleIn(tween(SabbaqMotion.LONG, easing = SabbaqMotion.Emphasized), initialScale = if (forward) 0.92f else 1.05f)
    val exit = fadeOut(tween(SabbaqMotion.SHORT, easing = SabbaqMotion.EmphasizedAccelerate)) +
        scaleOut(tween(SabbaqMotion.MEDIUM, easing = SabbaqMotion.EmphasizedAccelerate), targetScale = if (forward) 1.04f else 0.94f)
    return (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else -1f }
}

@Composable
private fun Modifier.perspectiveLayer(scope: AnimatedVisibilityScope, forward: Boolean, reduced: Boolean): Modifier {
    if (reduced) return this
    val mirror = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val sign = (if (forward) 1f else -1f) * mirror
    val rotation by scope.transition.animateFloat(
        transitionSpec = { tween(SabbaqMotion.LONG, easing = SabbaqMotion.Emphasized) },
        label = "depth-rotation",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> SabbaqMotion.DEPTH_ROTATION * sign
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> -SabbaqMotion.DEPTH_ROTATION * 0.6f * sign
        }
    }
    val density = LocalDensity.current.density
    return graphicsLayer {
        rotationY = rotation
        // Hinge on the edge the place comes from, so the change reads as turning, not shrinking.
        transformOrigin = TransformOrigin(if (rotation * mirror >= 0f) 0f else 1f, 0.5f)
        cameraDistance = SabbaqMotion.CAMERA_DISTANCE * density
    }
}

/**
 * First-appearance motion for a block of content: it unfolds upward from its bottom edge
 * (a 3D hinge) while rising and settling, staggered by [index]. Runs once per entry.
 */
fun Modifier.sabbaqEntrance(index: Int = 0): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced && progress.value < 1f) {
            delay((index.coerceIn(0, 8) * SabbaqMotion.STAGGER).toLong())
            progress.animateTo(1f, tween(SabbaqMotion.LONG + 180, easing = SabbaqMotion.EmphasizedDecelerate))
        }
    }
    val density = LocalDensity.current.density
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 36f * density
        rotationX = (1f - p) * 38f
        scaleX = 0.94f + 0.06f * p
        scaleY = 0.94f + 0.06f * p
        transformOrigin = TransformOrigin(0.5f, 1f)
        cameraDistance = SabbaqMotion.CAMERA_DISTANCE * density
    }
}

/** Physical press response: the control sinks into the surface and tips back while held. */
fun Modifier.pressDepth(interactionSource: InteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.95f else 1f,
        animationSpec = tween(if (pressed) 90 else SabbaqMotion.SHORT, easing = SabbaqMotion.Emphasized),
        label = "press-depth",
    )
    val density = LocalDensity.current.density
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        rotationX = (1f - scale) * 160f
        cameraDistance = SabbaqMotion.CAMERA_DISTANCE * density
    }
}

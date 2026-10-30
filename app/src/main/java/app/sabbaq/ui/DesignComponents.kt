package app.sabbaq.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sabbaq.R
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.ui.theme.LocalReducedMotion
import app.sabbaq.ui.theme.SabbaqMotion
import app.sabbaq.ui.theme.glint
import androidx.compose.ui.res.stringResource
import app.sabbaq.ui.theme.pressDepth
import app.sabbaq.ui.theme.tilt3D

/** Message tone; drives colour, icon and the screen-reader announcement together. */
enum class SabbaqTone { Info, Success, Error }

@Composable
internal fun SabbaqBrand(compact: Boolean = false, onHero: Boolean = false) {
    val brand = LocalSabbaqBrand.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tile = RoundedCornerShape(if (compact) 12.dp else 18.dp)
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null,
            modifier = Modifier
                .size(if (compact) 40.dp else 56.dp)
                .clip(tile)
                .then(if (onHero) Modifier.border(1.5.dp, brand.signature.copy(alpha = 0.9f), tile) else Modifier))
        Column {
            Text(stringResource(R.string.app_name), style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                color = if (onHero) brand.onHero else MaterialTheme.colorScheme.onBackground,
                // The name is one word: never broken into a column of letters when space is short.
                maxLines = 1, softWrap = false)
            if (!compact) Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium,
                color = if (onHero) brand.onHeroMuted else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SabbaqSectionTitle(title: String, subtitle: String? = null) {
    val brand = LocalSabbaqBrand.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 5.dp, height = 22.dp).clip(RoundedCornerShape(50)).background(brand.signature))
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun SabbaqCard(
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    // Depth: a tinted shadow under the card and light falling on its top edge.
    Card(modifier.fillMaxWidth().shadow(14.dp, shape, ambientColor = colors.primary.copy(alpha = 0.10f), spotColor = colors.primary.copy(alpha = 0.22f)),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, if (destructive) colors.error.copy(alpha = 0.35f) else colors.outlineVariant)) {
        Column(Modifier
            .background(Brush.verticalGradient(listOf((if (destructive) colors.error else colors.primary).copy(alpha = 0.04f), Color.Transparent), endY = 260f))
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) SabbaqIconBadge(icon, destructive = destructive)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            content()
        }
    }
}

@Composable
internal fun SabbaqIconBadge(icon: ImageVector, modifier: Modifier = Modifier, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalSabbaqBrand.current
    val base = if (destructive) colors.errorContainer else brand.signature
    val edge = if (destructive) colors.error else colors.primary
    // A raised signal-yellow coin: lit from above, darker at the base, with its own shadow.
    Box(modifier.size(40.dp).shadow(6.dp, CircleShape, spotColor = edge.copy(alpha = 0.35f)).clip(CircleShape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f).compositeOver(base), base, edge.copy(alpha = 0.14f).compositeOver(base)))),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (destructive) colors.onErrorContainer else brand.onSignature, modifier = Modifier.size(22.dp))
    }
}

/**
 * The deep brand surface for the one most important area of a screen (status hero,
 * sign-in welcome). Use at most once per screen so it keeps its weight. It floats on a
 * glowing shadow, leans toward the finger, and can carry the live radar [scene] behind
 * its content ([sceneActive] speeds it up while monitoring runs).
 */
@Composable
internal fun SabbaqHeroSurface(
    modifier: Modifier = Modifier,
    scene: Boolean = true,
    sceneActive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val brand = LocalSabbaqBrand.current
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier
            .fillMaxWidth()
            .tilt3D(maxDegrees = 5f, idle = true)
            .shadow(24.dp, shape, ambientColor = brand.heroEnd, spotColor = brand.heroEnd),
        shape = shape, color = brand.heroStart, contentColor = brand.onHero,
        border = BorderStroke(2.dp, brand.signature.copy(alpha = 0.85f)),
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(brand.heroStart, brand.heroEnd)))) {
            // The signature yellow disc rising from the lower corner, kept faint behind the text.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-56).dp, y = 72.dp)
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(brand.signature.copy(alpha = 0.16f)),
            )
            if (scene) SabbaqRadarScene(active = sceneActive, pins = false, modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.5f })
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/**
 * Status message whose appearance is tied to a real outcome. A success or error
 * icon "settles" with a short spring so the result reads at a glance.
 */
@Composable
internal fun SabbaqMessage(message: String, isError: Boolean = false, modifier: Modifier = Modifier, tone: SabbaqTone? = null) {
    val resolved = tone ?: if (isError) SabbaqTone.Error else SabbaqTone.Info
    val colors = MaterialTheme.colorScheme
    val brand = LocalSabbaqBrand.current
    val (container, content) = when (resolved) {
        SabbaqTone.Error -> colors.errorContainer to colors.onErrorContainer
        SabbaqTone.Success -> brand.successContainer to brand.onSuccessContainer
        SabbaqTone.Info -> colors.primaryContainer to colors.onPrimaryContainer
    }
    val reduced = LocalReducedMotion.current
    val iconScale = remember(message, resolved) { Animatable(if (reduced || resolved == SabbaqTone.Info) 1f else 0.4f) }
    LaunchedEffect(message, resolved) {
        if (iconScale.value < 1f) iconScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    Surface(modifier.fillMaxWidth().semantics { liveRegion = if (resolved == SabbaqTone.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                when (resolved) {
                    SabbaqTone.Error -> Icons.Outlined.ErrorOutline
                    SabbaqTone.Success -> Icons.Outlined.CheckCircle
                    SabbaqTone.Info -> Icons.Outlined.Info
                },
                null,
                Modifier.size(20.dp).graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value },
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Shows/hides a [SabbaqMessage] with a height-and-fade change instead of a layout jump. */
@Composable
internal fun SabbaqAnimatedMessage(message: String?, tone: SabbaqTone, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = if (reduced) fadeIn(tween(0)) else expandVertically(tween(SabbaqMotion.MEDIUM, easing = SabbaqMotion.Emphasized)) + fadeIn(tween(SabbaqMotion.MEDIUM)),
        exit = if (reduced) fadeOut(tween(0)) else shrinkVertically(tween(SabbaqMotion.SHORT)) + fadeOut(tween(SabbaqMotion.SHORT)),
    ) {
        // Keep the last text while collapsing so the exit animation is not empty.
        val shown = remember { mutableLastNonNull<String>() }
        message?.let { shown.value = it }
        shown.value?.let { SabbaqMessage(it, tone = tone) }
    }
}

private class LastNonNull<T>(var value: T?)
private fun <T> mutableLastNonNull() = LastNonNull<T>(null)

@Composable
internal fun SabbaqStatusPill(text: String, ready: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalSabbaqBrand.current
    Surface(modifier, shape = RoundedCornerShape(50),
        color = if (ready) brand.successContainer else colors.surfaceContainerHigh,
        contentColor = if (ready) brand.onSuccessContainer else colors.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

enum class SabbaqButtonStyle { Primary, Secondary, Text, Destructive }

/**
 * The one button used across the app: consistent height, press depth, and an inline
 * busy state that swaps label and spinner in place so the layout never jumps.
 */
@Composable
internal fun SabbaqButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SabbaqButtonStyle = SabbaqButtonStyle.Primary,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
    icon: ImageVector? = null,
    fillWidth: Boolean = true,
    colors: ButtonColors? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val sized = modifier
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .heightIn(min = 52.dp)
        .pressDepth(interaction)
    val label: @Composable RowScope.() -> Unit = {
        AnimatedContent(
            targetState = busy,
            transitionSpec = { fadeIn(tween(SabbaqMotion.SHORT)) togetherWith fadeOut(tween(SabbaqMotion.SHORT)) },
            label = "button-busy",
        ) { isBusy ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(busyText ?: text)
                } else {
                    icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(text)
                }
            }
        }
    }
    val active = enabled && !busy
    when (style) {
        SabbaqButtonStyle.Primary -> Button(onClick, sized.clip(ButtonDefaults.shape).glint(enabled = active), active, interactionSource = interaction,
            colors = colors ?: ButtonDefaults.buttonColors(), content = label)
        SabbaqButtonStyle.Destructive -> Button(onClick, sized, active, interactionSource = interaction,
            colors = colors ?: ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError), content = label)
        SabbaqButtonStyle.Secondary -> {
            val scheme = MaterialTheme.colorScheme
            val resolved = colors ?: ButtonDefaults.outlinedButtonColors(containerColor = scheme.primary.copy(alpha = 0.05f))
            OutlinedButton(onClick, sized, active, interactionSource = interaction, colors = resolved,
                border = BorderStroke(1.5.dp, if (active) resolved.contentColor.copy(alpha = 0.55f) else scheme.outlineVariant),
                content = label)
        }
        SabbaqButtonStyle.Text -> TextButton(onClick, sized, active, interactionSource = interaction,
            colors = colors ?: ButtonDefaults.textButtonColors(), content = label)
    }
}

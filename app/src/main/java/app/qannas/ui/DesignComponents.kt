package app.qannas.ui

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import app.qannas.R
import app.qannas.ui.theme.LocalQannasBrand
import app.qannas.ui.theme.LocalReducedMotion
import app.qannas.ui.theme.QannasMotion
import app.qannas.ui.theme.glint
import app.qannas.ui.theme.pressDepth
import app.qannas.ui.theme.tilt3D

/** Message tone; drives colour, icon and the screen-reader announcement together. */
enum class QannasTone { Info, Success, Error }

@Composable
internal fun QannasBrand(compact: Boolean = false, onHero: Boolean = false) {
    val brand = LocalQannasBrand.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null,
            modifier = Modifier.size(if (compact) 40.dp else 56.dp).clip(RoundedCornerShape(if (compact) 12.dp else 18.dp)))
        Column {
            Text("قناص", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                color = if (onHero) brand.onHero else MaterialTheme.colorScheme.onBackground)
            if (!compact) Text("طلبات تناسبك. وقتك أولى.", style = MaterialTheme.typography.bodyMedium,
                color = if (onHero) brand.onHeroMuted else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun QannasSectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun QannasCard(
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
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier
            .background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.035f), Color.Transparent), endY = 260f))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) QannasIconBadge(icon, destructive = destructive)
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
internal fun QannasIconBadge(icon: ImageVector, modifier: Modifier = Modifier, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val base = if (destructive) colors.errorContainer else colors.primaryContainer
    val edge = if (destructive) colors.error else colors.primary
    // A small raised tile: lit from above, darker at the base, with its own shadow.
    Box(modifier.size(40.dp).shadow(6.dp, MaterialTheme.shapes.small, spotColor = edge.copy(alpha = 0.35f)).clip(MaterialTheme.shapes.small)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f).compositeOver(base), base, edge.copy(alpha = 0.18f).compositeOver(base)))),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (destructive) colors.onErrorContainer else colors.onPrimaryContainer, modifier = Modifier.size(22.dp))
    }
}

/**
 * The deep brand surface for the one most important area of a screen (status hero,
 * sign-in welcome). Use at most once per screen so it keeps its weight. It floats on a
 * glowing shadow, leans toward the finger, and can carry the live radar [scene] behind
 * its content ([sceneActive] speeds it up while monitoring runs).
 */
@Composable
internal fun QannasHeroSurface(
    modifier: Modifier = Modifier,
    scene: Boolean = true,
    sceneActive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val brand = LocalQannasBrand.current
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier
            .fillMaxWidth()
            .tilt3D(maxDegrees = 5f, idle = true)
            .shadow(24.dp, shape, ambientColor = brand.heroEnd, spotColor = brand.heroEnd),
        shape = shape, color = brand.heroStart, contentColor = brand.onHero,
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(brand.heroStart, brand.heroEnd)))) {
            if (scene) QannasRadarScene(active = sceneActive, pins = false, modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.5f })
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
internal fun QannasMessage(message: String, isError: Boolean = false, modifier: Modifier = Modifier, tone: QannasTone? = null) {
    val resolved = tone ?: if (isError) QannasTone.Error else QannasTone.Info
    val colors = MaterialTheme.colorScheme
    val brand = LocalQannasBrand.current
    val (container, content) = when (resolved) {
        QannasTone.Error -> colors.errorContainer to colors.onErrorContainer
        QannasTone.Success -> brand.successContainer to brand.onSuccessContainer
        QannasTone.Info -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val reduced = LocalReducedMotion.current
    val iconScale = remember(message, resolved) { Animatable(if (reduced || resolved == QannasTone.Info) 1f else 0.4f) }
    LaunchedEffect(message, resolved) {
        if (iconScale.value < 1f) iconScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    Surface(modifier.fillMaxWidth().semantics { liveRegion = if (resolved == QannasTone.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                when (resolved) {
                    QannasTone.Error -> Icons.Outlined.ErrorOutline
                    QannasTone.Success -> Icons.Outlined.CheckCircle
                    QannasTone.Info -> Icons.Outlined.Info
                },
                null,
                Modifier.size(20.dp).graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value },
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Shows/hides a [QannasMessage] with a height-and-fade change instead of a layout jump. */
@Composable
internal fun QannasAnimatedMessage(message: String?, tone: QannasTone, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = if (reduced) fadeIn(tween(0)) else expandVertically(tween(QannasMotion.MEDIUM, easing = QannasMotion.Emphasized)) + fadeIn(tween(QannasMotion.MEDIUM)),
        exit = if (reduced) fadeOut(tween(0)) else shrinkVertically(tween(QannasMotion.SHORT)) + fadeOut(tween(QannasMotion.SHORT)),
    ) {
        // Keep the last text while collapsing so the exit animation is not empty.
        val shown = remember { mutableLastNonNull<String>() }
        message?.let { shown.value = it }
        shown.value?.let { QannasMessage(it, tone = tone) }
    }
}

private class LastNonNull<T>(var value: T?)
private fun <T> mutableLastNonNull() = LastNonNull<T>(null)

@Composable
internal fun QannasStatusPill(text: String, ready: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(50),
        color = if (ready) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (ready) colors.onPrimaryContainer else colors.onSurfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Info, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

enum class QannasButtonStyle { Primary, Secondary, Text, Destructive }

/**
 * The one button used across the app: consistent height, press depth, and an inline
 * busy state that swaps label and spinner in place so the layout never jumps.
 */
@Composable
internal fun QannasButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: QannasButtonStyle = QannasButtonStyle.Primary,
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
            transitionSpec = { fadeIn(tween(QannasMotion.SHORT)) togetherWith fadeOut(tween(QannasMotion.SHORT)) },
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
        QannasButtonStyle.Primary -> Button(onClick, sized.clip(ButtonDefaults.shape).glint(enabled = active), active, interactionSource = interaction,
            colors = colors ?: ButtonDefaults.buttonColors(), content = label)
        QannasButtonStyle.Destructive -> Button(onClick, sized, active, interactionSource = interaction,
            colors = colors ?: ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError), content = label)
        QannasButtonStyle.Secondary -> OutlinedButton(onClick, sized, active, interactionSource = interaction,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(), content = label)
        QannasButtonStyle.Text -> TextButton(onClick, sized, active, interactionSource = interaction,
            colors = colors ?: ButtonDefaults.textButtonColors(), content = label)
    }
}

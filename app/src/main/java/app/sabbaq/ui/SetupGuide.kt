package app.sabbaq.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.ui.theme.LocalReducedMotion
import app.sabbaq.ui.theme.SabbaqMotion
import app.sabbaq.ui.theme.flipWhenSelected
import app.sabbaq.ui.theme.sabbaqEntrance

/** Whether the driver finished or dismissed the setup guide; holds no account data. */
object SetupGuidePreferences {
    private const val FILE_NAME = "sabbaq_setup_guide"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    }.getOrDefault(false)

    fun markDone(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).commit()
    }.getOrDefault(false)
}

/**
 * One requirement in the guide. [done] comes from the same live checks the dashboard uses,
 * so the guide advances by itself when the driver returns from a system settings screen.
 */
internal data class SetupStep(
    val icon: ImageVector,
    val title: String,
    val why: String,
    val doneText: String,
    val actionLabel: String,
    val done: Boolean,
    val onAction: () -> Unit,
    val extra: (@Composable () -> Unit)? = null,
)

/**
 * Walks the driver through getting Sabbaq ready, one step at a time: what each step is
 * for in plain words, a single action, and a visible tick when it is done.
 */
@Composable
internal fun SetupGuidePage(
    steps: List<SetupStep>,
    onTryExample: () -> Unit,
    onFinish: () -> Unit,
    onLater: () -> Unit,
) {
    val brand = LocalSabbaqBrand.current
    val doneCount = steps.count { it.done }
    val current = steps.indexOfFirst { !it.done }
    val allDone = current == -1

    SabbaqHeroSurface(Modifier.sabbaqEntrance(0)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (allDone) "سبّاق جاهز لك" else "لنجهّز سبّاق معًا", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (allDone) "أكملت كل الخطوات. ابدأ متابعة الطلبات متى شئت."
                    else "${ArabicCount.steps(steps.size)} قصيرة، ونرافقك في كل واحدة منها.",
                    style = MaterialTheme.typography.bodyMedium, color = brand.onHeroMuted,
                )
            }
            SabbaqProgressCoin(done = doneCount, total = steps.size,
                modifier = Modifier.semantics { stateDescription = "اكتمل $doneCount من ${steps.size}" })
        }
    }

    steps.forEachIndexed { index, step ->
        SetupStepRow(
            number = index + 1,
            step = step,
            expanded = index == current,
            upcoming = current != -1 && index > current,
            last = index == steps.lastIndex,
            modifier = Modifier.sabbaqEntrance(index + 1),
        )
    }

    if (allDone) {
        SabbaqCard(title = "كل شيء جاهز", subtitle = "شروطك محفوظة والأذونات مفعّلة واشتراكك صالح.",
            icon = Icons.Outlined.Celebration, modifier = Modifier.sabbaqEntrance(steps.size + 1)) {
            SabbaqButton("انتقل إلى حالة العمل", onClick = onFinish)
            SabbaqButton("جرّب طلبًا كمثال أولًا", onClick = onTryExample, style = SabbaqButtonStyle.Secondary, icon = Icons.Outlined.Visibility)
        }
    } else {
        TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text("سأكمل لاحقًا من حالة العمل") }
    }
}

@Composable
private fun SetupStepRow(number: Int, step: SetupStep, expanded: Boolean, upcoming: Boolean, last: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val coinColor by animateColorAsState(
        when { step.done -> colors.primary; expanded -> colors.secondary; else -> colors.surfaceContainerHigh },
        tween(SabbaqMotion.MEDIUM), label = "setup-coin",
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Numbered coin on a vertical track; it turns over to a tick when the step completes.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(40.dp)
                    .flipWhenSelected(step.done)
                    .shadow(if (upcoming) 0.dp else 8.dp, CircleShape, spotColor = coinColor)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(coinColor.copy(alpha = 0.75f), coinColor))),
                contentAlignment = Alignment.Center,
            ) {
                if (step.done) Icon(Icons.Outlined.Check, contentDescription = "مكتملة", tint = colors.onPrimary, modifier = Modifier.size(22.dp))
                else Text("$number", style = MaterialTheme.typography.titleMedium,
                    color = if (expanded) colors.onSecondary else colors.onSurfaceVariant)
            }
            if (!last) Box(Modifier.width(2.dp).heightIn(min = 28.dp).background(if (step.done) colors.primary else colors.outlineVariant))
        }
        Column(Modifier.weight(1f)) {
            if (expanded) {
                SabbaqCard(title = step.title, icon = step.icon) {
                    Text(step.why, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    step.extra?.invoke()
                    SabbaqButton(step.actionLabel, onClick = step.onAction)
                }
            } else {
                Column(Modifier.heightIn(min = 40.dp).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall,
                        color = if (upcoming) colors.onSurfaceVariant else colors.onSurface)
                    AnimatedVisibility(
                        visible = step.done,
                        enter = if (reduced) fadeIn(tween(0)) else expandVertically() + fadeIn(),
                        exit = if (reduced) fadeOut(tween(0)) else shrinkVertically() + fadeOut(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(step.doneText, style = MaterialTheme.typography.bodySmall, color = colors.primary, modifier = Modifier.weight(1f))
                            TextButton(onClick = step.onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text("مراجعة") }
                        }
                    }
                }
            }
        }
    }
}

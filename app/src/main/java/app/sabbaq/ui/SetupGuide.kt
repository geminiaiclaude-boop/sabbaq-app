package app.sabbaq.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sabbaq.ui.theme.LocalSabbaqBrand
import app.sabbaq.ui.theme.sabbaqEntrance
import kotlinx.coroutines.delay

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

/** Walks the driver through setup with one focused, priority-ordered action at a time. */
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
    var successMessage by remember { mutableStateOf<String?>(null) }
    var observedDoneCount by remember { mutableStateOf(doneCount) }

    LaunchedEffect(doneCount) {
        if (doneCount > observedDoneCount) {
            successMessage = "تم إكمال الإعداد بنجاح. ننتقل بك إلى الخطوة التالية."
            delay(2_800)
            successMessage = null
        }
        observedDoneCount = doneCount
    }

    SabbaqHeroSurface(Modifier.sabbaqEntrance(0)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (allDone) "سبّاق جاهز لك" else "لنجهّز سبّاق معًا", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (allDone) "أكملت كل الخطوات. ابدأ متابعة الطلبات متى شئت."
                    else "نرتب الإعدادات حسب الأهمية ونقودك خطوة واحدة في كل مرة.",
                    style = MaterialTheme.typography.bodyMedium, color = brand.onHeroMuted,
                )
            }
            SabbaqProgressCoin(done = doneCount, total = steps.size,
                modifier = Modifier.semantics { stateDescription = "اكتمل $doneCount من ${steps.size}" })
        }
    }

    SabbaqAnimatedMessage(successMessage, SabbaqTone.Success, Modifier.sabbaqEntrance(1))

    if (allDone) {
        SabbaqCard(title = "كل شيء جاهز", subtitle = "شروطك محفوظة والأذونات مفعّلة واشتراكك صالح.",
            icon = Icons.Outlined.Celebration, modifier = Modifier.sabbaqEntrance(1)) {
            SabbaqButton("انتقل إلى حالة العمل", onClick = onFinish)
            SabbaqButton("جرّب طلبًا كمثال أولًا", onClick = onTryExample, style = SabbaqButtonStyle.Secondary, icon = Icons.Outlined.Visibility)
        }
        return
    }

    val step = steps[current]
    val progress = doneCount.toFloat() / steps.size.toFloat()
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().sabbaqEntrance(1),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("الخطوة ${current + 1} من ${steps.size}", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text("${ArabicCount.steps(steps.size - doneCount)} متبقية", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().heightIn(min = 6.dp),
            trackColor = colors.surfaceVariant,
        )
        if (doneCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Text("أكملت $doneCount من الإعدادات المطلوبة", style = MaterialTheme.typography.bodySmall, color = colors.primary)
            }
        }
        SabbaqCard(title = step.title, icon = step.icon) {
            Text("إعداد ذو أولوية", style = MaterialTheme.typography.labelLarge, color = colors.secondary)
            Spacer(Modifier.size(4.dp))
            Text(step.why, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            step.extra?.invoke()
            SabbaqButton(step.actionLabel, onClick = step.onAction)
        }
        Text(
            "بعد إكمال هذه الخطوة والعودة، سيتحقق سبّاق من حالتها وينقلك تلقائيًا إلى الخطوة التالية.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text("سأكمل لاحقًا من حالة العمل") }
    }
}

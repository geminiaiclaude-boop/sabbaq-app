package app.qannas.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.qannas.BuildConfig
import app.qannas.network.PublicOffer
import app.qannas.ui.theme.QannasMotion
import app.qannas.ui.theme.qannasEntrance
import app.qannas.ui.theme.tilt3D

/** What the driver came for, chosen before signing in; it only orders what we show after. */
enum class AccessIntent { None, Trial, Subscribe, SignIn }

/**
 * The first decision on the sign-in screen: try free, subscribe, or continue an existing
 * subscription. A device that has already held a paid subscription is not offered the
 * trial again. The Play build shows no purchase path until Play Billing is integrated.
 */
@Composable
internal fun AccessChoiceCard(
    offer: PublicOffer?,
    knownSubscriber: Boolean,
    selected: AccessIntent,
    onSelect: (AccessIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showTrial = !knownSubscriber && offer?.trialEnabled != false
    val showSubscribe = BuildConfig.DIRECT_DISTRIBUTION && !knownSubscriber
    QannasCard(
        title = if (knownSubscriber) "مرحبًا بعودتك" else "كيف تريد أن تبدأ؟",
        subtitle = if (knownSubscriber) "سجّل الدخول وتابع اشتراكك من حيث توقفت." else "اختر ما يناسبك، ثم سجّل الدخول.",
        modifier = modifier,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showTrial) ChoiceTile(
                icon = Icons.Outlined.CardGiftcard,
                title = "جرّب مجانًا",
                detail = offer?.takeIf { it.trialDays > 0 }?.let { "تجربة كاملة لمدة ${ArabicCount.days(it.trialDays)} دون دفع، للحسابات المؤهلة." }
                    ?: "جرّب قناص دون دفع، للحسابات المؤهلة.",
                selected = selected == AccessIntent.Trial,
                onClick = { onSelect(AccessIntent.Trial) },
                index = 0,
            )
            if (showSubscribe) ChoiceTile(
                icon = Icons.Outlined.WorkspacePremium,
                title = "اشترك الآن",
                detail = offer?.let { CustomerCommerceCopy.offerSummary(it) + "، بكل المزايا." } ?: "اشتراك كامل بكل المزايا.",
                selected = selected == AccessIntent.Subscribe,
                onClick = { onSelect(AccessIntent.Subscribe) },
                index = 1,
            )
            ChoiceTile(
                icon = Icons.AutoMirrored.Outlined.Login,
                title = "لدي اشتراك",
                detail = "سجّل الدخول بالحساب الذي اشتركت به.",
                selected = selected == AccessIntent.SignIn || (knownSubscriber && selected == AccessIntent.None),
                onClick = { onSelect(AccessIntent.SignIn) },
                index = 2,
            )
        }
    }
}

@Composable
private fun ChoiceTile(icon: ImageVector, title: String, detail: String, selected: Boolean, onClick: () -> Unit, index: Int) {
    val colors = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, tween(QannasMotion.MEDIUM), label = "choice-border")
    val lift by animateDpAsState(if (selected) 14.dp else 3.dp, tween(QannasMotion.MEDIUM), label = "choice-lift")
    val scale by animateFloatAsState(if (selected) 1.02f else 1f, tween(QannasMotion.MEDIUM, easing = QannasMotion.Emphasized), label = "choice-scale")
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shadowElevation = lift,
        modifier = Modifier
            .fillMaxWidth()
            .qannasEntrance(index)
            .tilt3D(maxDegrees = 7f, idle = false)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            QannasIconBadge(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, contentDescription = null,
                tint = if (selected) colors.primary else colors.outline)
        }
    }
}

/**
 * How buying works, in three short steps, so the driver never wonders what comes next.
 * The button opens the payment or contact link the administrator configured on the server.
 */
@Composable
internal fun PurchaseSteps(offer: PublicOffer?, enabled: Boolean, onOpenPurchase: (String) -> Unit) {
    val url = offer?.purchaseContactUrl
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (url == null) {
            // No payment link configured on the server: never point to a button that is not there.
            QannasMessage("الاشتراك يتم عبر الإدارة حاليًا. إذا استلمت رمز تفعيل، الصقه أدناه واضغط «تفعيل الوصول».")
            return@Column
        }
        PurchaseStep("١", "اضغط «اشترك الآن» وأكمل الدفع.")
        PurchaseStep("٢", "يصلك رمز التفعيل بعد تأكيد الدفع.")
        PurchaseStep("٣", "الصق الرمز أدناه واضغط «تفعيل الوصول».")
        QannasButton(text = "اشترك الآن", onClick = { onOpenPurchase(url) }, enabled = enabled, icon = Icons.Outlined.WorkspacePremium)
    }
}

@Composable
private fun PurchaseStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(number, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Activation code input with a one-tap paste, since codes usually arrive in a message. */
@Composable
internal fun ActivationCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    error: String?,
    onDone: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        label = { Text("رمز التفعيل") },
        placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { clipboard.getText()?.text?.let { onValueChange(it.trim().take(128)) } }, enabled = enabled) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = "لصق الرمز")
            }
        },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

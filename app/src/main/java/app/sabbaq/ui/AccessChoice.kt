package app.sabbaq.ui

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
import androidx.compose.material.icons.automirrored.outlined.Chat
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import app.sabbaq.BuildConfig
import app.sabbaq.network.PublicOffer
import app.sabbaq.ui.theme.SabbaqMotion
import app.sabbaq.ui.theme.sabbaqEntrance
import app.sabbaq.ui.theme.tilt3D
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import app.sabbaq.ui.theme.LocalSabbaqBrand

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
    SabbaqCard(
        title = if (knownSubscriber) stringResource(R.string.ac_welcome_back) else stringResource(R.string.ac_how_start),
        subtitle = if (knownSubscriber) stringResource(R.string.ac_welcome_back_sub) else stringResource(R.string.ac_how_start_sub),
        modifier = modifier,
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showTrial) ChoiceTile(
                icon = Icons.Outlined.CardGiftcard,
                title = stringResource(R.string.ac_try_free),
                detail = offer?.takeIf { it.trialDays > 0 }?.let { stringResource(R.string.ac_trial_detail_days, ArabicCount.days(it.trialDays)) }
                    ?: stringResource(R.string.ac_trial_detail_nodays),
                selected = selected == AccessIntent.Trial,
                onClick = { onSelect(AccessIntent.Trial) },
                index = 0,
            )
            if (showSubscribe) ChoiceTile(
                icon = Icons.Outlined.WorkspacePremium,
                title = stringResource(R.string.ac_subscribe_now),
                detail = offer?.let { CustomerCommerceCopy.offerSummary(it) + stringResource(R.string.ac_subscribe_suffix) } ?: stringResource(R.string.ac_subscribe_detail_full),
                selected = selected == AccessIntent.Subscribe,
                onClick = { onSelect(AccessIntent.Subscribe) },
                index = 1,
            )
            ChoiceTile(
                icon = Icons.AutoMirrored.Outlined.Login,
                title = stringResource(R.string.ac_have_sub),
                detail = stringResource(R.string.ac_have_sub_detail),
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
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, tween(SabbaqMotion.MEDIUM), label = "choice-border")
    val lift by animateDpAsState(if (selected) 14.dp else 3.dp, tween(SabbaqMotion.MEDIUM), label = "choice-lift")
    val scale by animateFloatAsState(if (selected) 1.02f else 1f, tween(SabbaqMotion.MEDIUM, easing = SabbaqMotion.Emphasized), label = "choice-scale")
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shadowElevation = lift,
        modifier = Modifier
            .fillMaxWidth()
            .sabbaqEntrance(index)
            .tilt3D(maxDegrees = 7f, idle = false)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SabbaqIconBadge(icon)
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
    val target = PurchaseLink.target(offer)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (target == null) {
            // No purchase channel on the server: never point to a button that is not there.
            SabbaqMessage(stringResource(R.string.ac_code_hint))
            return@Column
        }
        if (target.viaWhatsapp) {
            PurchaseStep("1", stringResource(R.string.ac_step_wa1))
            PurchaseStep("2", stringResource(R.string.ac_step_wa2))
        } else {
            PurchaseStep("1", stringResource(R.string.ac_step_pay1))
            PurchaseStep("2", stringResource(R.string.ac_step_pay2))
        }
        PurchaseStep("3", stringResource(R.string.ac_step3))
        SabbaqButton(
            text = if (target.viaWhatsapp) stringResource(R.string.ac_subscribe_wa) else stringResource(R.string.ac_subscribe_now),
            onClick = { onOpenPurchase(target.url) },
            enabled = enabled,
            icon = if (target.viaWhatsapp) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.WorkspacePremium,
        )
    }
}

@Composable
private fun PurchaseStep(number: String, text: String) {
    val brand = LocalSabbaqBrand.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = brand.signature, contentColor = brand.onSignature,
            shadowElevation = 3.dp, modifier = Modifier.size(30.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(number, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
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
        label = { Text(stringResource(R.string.ac_code_label)) },
        placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { clipboard.getText()?.text?.let { onValueChange(it.trim().take(128)) } }, enabled = enabled) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.ac_paste_cd))
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

/** Ways to get access once signed in; the driver can switch between them freely. */
internal enum class AccessTab(val labelRes: Int) { Trial(R.string.ac_tab_trial), Subscribe(R.string.ac_tab_subscribe), Code(R.string.ac_tab_code) }

internal fun AccessIntent.toTab(): AccessTab = when (this) {
    AccessIntent.Subscribe -> AccessTab.Subscribe
    AccessIntent.SignIn -> AccessTab.Code
    else -> AccessTab.Trial
}

/** A row of equal tabs; the chosen one is filled and lifted. */
@Composable
internal fun SabbaqTabs(tabs: List<AccessTab>, selected: AccessTab, onSelect: (AccessTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(50), color = colors.surfaceContainerHigh, modifier = modifier.fillMaxWidth().selectableGroup()) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { tab ->
                val chosen = tab == selected
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (chosen) colors.primary else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (chosen) colors.onPrimary else colors.onSurfaceVariant,
                    shadowElevation = if (chosen) 4.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(tab) }),
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                        Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelLarge, maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

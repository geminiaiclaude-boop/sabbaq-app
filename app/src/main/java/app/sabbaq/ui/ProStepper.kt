package app.sabbaq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.sabbaq.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sabbaq.core.Text as CoreText
import java.math.BigDecimal

/**
 * One condition in its compact form: a title line, then one row with the inputs and the
 * switch. Inputs stay visible (dimmed while the condition is off) so the stored value is seen
 * before switching it on. [framed] draws it as its own card; unframed rows sit inside a
 * shared card, as on «حالة العمل».
 */
@Composable
internal fun ProConditionCard(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    testKey: String,
    modifier: Modifier = Modifier,
    switchEnabled: Boolean = true,
    framed: Boolean = true,
    headerAction: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    inputs: @Composable RowScope.(dimmed: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val body = @Composable {
        Column(
            modifier = if (framed) Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp) else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                headerAction?.invoke()
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            val activateCd = stringResource(R.string.ps_activate, title)
            val switchState = if (enabled) stringResource(R.string.ps_on) else stringResource(R.string.ps_off)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                inputs(!enabled)
                Switch(
                    checked = enabled,
                    enabled = switchEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .testTag("filter-switch-$testKey")
                        .semantics {
                            contentDescription = activateCd
                            stateDescription = switchState
                        },
                )
            }
            footer?.invoke()
        }
    }
    if (!framed) {
        Box(modifier) { body() }
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(
            1.dp,
            if (enabled && switchEnabled) colors.primary.copy(alpha = 0.38f) else colors.outlineVariant,
        ),
    ) { body() }
}

@Composable
private fun StepperEditorDialog(
    label: String,
    editorValue: String,
    keyboardType: KeyboardType,
    testKey: String,
    onEditorChange: (String) -> Unit,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val valid = isValidNumber(editorValue, keyboardType)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ps_edit, label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = editorValue,
                    onValueChange = { onEditorChange(it.take(if (keyboardType == KeyboardType.Number) 10 else 32)) },
                    label = { Text(label) },
                    supportingText = {
                        Text(
                            if (editorValue.isBlank()) stringResource(R.string.ps_leave_blank)
                            else if (!valid) stringResource(R.string.ps_invalid)
                            else stringResource(R.string.ps_edit_hint),
                        )
                    },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth().testTag("filter-stepper-$testKey-editor"),
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(editorValue.trim()) }) { Text(stringResource(R.string.ps_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ss_cancel)) } },
    )
}

/**
 * Compact stepper: label inside the field, 36 dp tall, small −/+ buttons. Same value
 * rules, step and editor dialog everywhere; [dimmed] shows the condition is off while
 * keeping its stored value visible and editable.
 */
@Composable
internal fun CompactStepperValue(
    label: String,
    value: String,
    unit: String,
    step: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    testKey: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    dimmed: Boolean = false,
) {
    var showEditor by rememberSaveable(testKey) { mutableStateOf(false) }
    var editorValue by rememberSaveable(testKey) { mutableStateOf(value) }
    val colors = MaterialTheme.colorScheme
    val editCd = stringResource(R.string.ps_edit, label)
    val stepperState = if (value.isBlank()) stringResource(R.string.ps_undefined) else stringResource(R.string.ps_value_unit, value, unit)
    Surface(
        modifier = modifier.alpha(if (dimmed) 0.5f else 1f),
        shape = MaterialTheme.shapes.small,
        color = colors.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, if (error != null) colors.error else colors.outlineVariant),
    ) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactStepButton(stringResource(R.string.ps_decrease, label), Icons.Outlined.Remove, "filter-stepper-$testKey-decrement") {
                onValueChange(stepValue(value, step, keyboardType, direction = -1))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { editorValue = value; showEditor = true }
                    .testTag("filter-stepper-$testKey-value")
                    .semantics {
                        contentDescription = editCd
                        stateDescription = stepperState
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis)
                Text(
                    (value.ifBlank { "—" }) + if (unit.isNotBlank() && value.isNotBlank()) " $unit" else "",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            CompactStepButton(stringResource(R.string.ps_increase, label), Icons.Outlined.Add, "filter-stepper-$testKey-increment") {
                onValueChange(stepValue(value, step, keyboardType, direction = 1))
            }
        }
    }
    if (showEditor) {
        StepperEditorDialog(label, editorValue, keyboardType, testKey,
            onEditorChange = { editorValue = it },
            onApply = { onValueChange(it); showEditor = false },
            onDismiss = { showEditor = false })
    }
}

@Composable
private fun CompactStepButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, testTag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp).testTag(testTag).semantics { contentDescription = label }) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

private fun isValidNumber(value: String, keyboardType: KeyboardType): Boolean {
    if (value.isBlank()) return true
    val normalized = CoreText.normalize(value)
    return if (keyboardType == KeyboardType.Number) {
        Regex("\\d+").matches(normalized) && normalized.toIntOrNull() != null
    } else {
        normalized.length <= 32 && Regex("(?:${CoreText.NUMBER})").matchEntire(normalized) != null &&
            (CoreText.parseNumber(normalized)?.signum() ?: -1) >= 0
    }
}

private fun stepValue(value: String, step: String, keyboardType: KeyboardType, direction: Int): String {
    return if (keyboardType == KeyboardType.Number) {
        val current = CoreText.normalize(value).toIntOrNull() ?: 0
        val increment = step.toIntOrNull() ?: 1
        val result = current.toLong() + increment.toLong() * direction
        result.coerceIn(0L, Int.MAX_VALUE.toLong()).toString()
    } else {
        val current = CoreText.parseNumber(CoreText.normalize(value)) ?: BigDecimal.ZERO
        val increment = CoreText.parseNumber(step) ?: BigDecimal.ONE
        current.add(increment.multiply(BigDecimal(direction))).max(BigDecimal.ZERO)
            .stripTrailingZeros().toPlainString()
    }
}

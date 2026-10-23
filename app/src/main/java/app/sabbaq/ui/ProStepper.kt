package app.sabbaq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sabbaq.core.Text as CoreText
import java.math.BigDecimal

@Composable
internal fun ProConditionCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchEnabled: Boolean = true,
    testKey: String,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(
            1.dp,
            if (enabled && switchEnabled) colors.primary.copy(alpha = 0.38f) else colors.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                headerAction?.invoke()
                Switch(
                    checked = enabled,
                    enabled = switchEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .testTag("filter-switch-$testKey")
                        .semantics {
                            contentDescription = "تفعيل $title"
                            stateDescription = if (enabled) "مفعّل" else "غير مفعّل"
                        },
                )
            }
            content()
        }
    }
}

@Composable
internal fun ProStepperValue(
    label: String,
    value: String,
    unit: String,
    step: String,
    keyboardType: KeyboardType,
    error: String? = null,
    onValueChange: (String) -> Unit,
    testKey: String,
    modifier: Modifier = Modifier,
) {
    var showEditor by rememberSaveable(testKey) { mutableStateOf(false) }
    var editorValue by rememberSaveable(testKey) { mutableStateOf(value) }
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, if (error != null) colors.error else colors.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                StepperButton(
                    label = "تقليل $label",
                    icon = Icons.Outlined.Remove,
                    testTag = "filter-stepper-$testKey-decrement",
                    onClick = { onValueChange(stepValue(value, step, keyboardType, direction = -1)) },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            editorValue = value
                            showEditor = true
                        }
                        .testTag("filter-stepper-$testKey-value")
                        .semantics {
                            contentDescription = "تعديل $label"
                            stateDescription = if (value.isBlank()) "غير محدد" else "$value $unit"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            value.ifBlank { "—" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            color = if (value.isBlank()) colors.onSurfaceVariant else colors.onSurface,
                        )
                        if (unit.isNotBlank()) {
                            Text(unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                StepperButton(
                    label = "زيادة $label",
                    icon = Icons.Outlined.Add,
                    testTag = "filter-stepper-$testKey-increment",
                    onClick = { onValueChange(stepValue(value, step, keyboardType, direction = 1)) },
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
    }

    if (showEditor) {
        val valid = isValidNumber(editorValue, keyboardType)
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("تعديل $label") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editorValue,
                        onValueChange = { editorValue = it.take(if (keyboardType == KeyboardType.Number) 10 else 32) },
                        label = { Text(label) },
                        supportingText = {
                            Text(
                                if (editorValue.isBlank()) "اتركه فارغًا لإلغاء هذا الحد."
                                else if (!valid) "أدخل رقمًا غير سالب صالحًا."
                                else "يمكنك التعديل مباشرة أو استخدام زري الزيادة والنقصان.",
                            )
                        },
                        isError = !valid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        modifier = Modifier.fillMaxWidth().testTag("filter-stepper-$testKey-editor"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        onValueChange(editorValue.trim())
                        showEditor = false
                    },
                ) { Text("تطبيق") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun StepperButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, testTag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).testTag(testTag).semantics { contentDescription = label },
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
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

package app.sabbaq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Explains Android's sideload protection before the user enters system settings. */
@Composable
fun RestrictedAccessibilityDialog(
    onOpenAppDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("خطوة أمان مطلوبة") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "حماية Android تمنع تفعيل خدمة إمكانية الوصول للتطبيقات المثبتة من خارج Google Play حتى تسمح بها يدويًا.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "لفك الحظر",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        RestrictedSettingsStep(
                            number = "١",
                            icon = Icons.Outlined.Settings,
                            text = "افتح معلومات تطبيق سبّاق.",
                        )
                        RestrictedSettingsStep(
                            number = "٢",
                            icon = Icons.Outlined.MoreVert,
                            text = "اضغط النقاط الثلاث ⋮ أعلى الشاشة.",
                        )
                        RestrictedSettingsStep(
                            number = "٣",
                            icon = Icons.Outlined.LockOpen,
                            text = "اختر «السماح بالإعدادات المقيدة».",
                        )
                        RestrictedSettingsStep(
                            number = "٤",
                            icon = Icons.Outlined.AccessibilityNew,
                            text = "ارجع إلى إعدادات إمكانية الوصول وفعّل سبّاق.",
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "لن يظهر هذا الخيار إذا كان التطبيق مثبتًا من Google Play. لا يمكن لسبّاق تجاوز حماية Android تلقائيًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            SabbaqButton(
                text = "فتح معلومات تطبيق سبّاق",
                onClick = onOpenAppDetails,
                fillWidth = false,
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("ليس الآن") }
        },
    )
}

@Composable
private fun RestrictedSettingsStep(
    number: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number,
            modifier = Modifier.size(26.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(21.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

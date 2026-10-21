package app.qannas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Separate, explicit consent. The host opens Android settings only after acceptance. */
@Composable
fun AccessibilityDisclosureDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كيف يعمل القبول التلقائي؟") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("قراءة الطلبات", style = MaterialTheme.typography.titleSmall)
                Text("عندما تشغّل قناص، يستخدم إذن «إمكانية الوصول» لقراءة شاشة الطلب الظاهرة في التطبيقات المدعومة فقط. يقرأ نصوص الطلب وتفاصيل عناصر الشاشة، مثل وصفها ومعرّفاتها ونوعها وموقع زر القبول وإمكانية الضغط عليه.", style = MaterialTheme.typography.bodyMedium)
                Text("القبول وفق شروطك", style = MaterialTheme.typography.titleSmall)
                Text("إذا طابق الطلب شروطك المحفوظة للأجرة والمسافة والوقت والكلمات المستبعدة، يستطيع قناص الضغط مرة واحدة على زر القبول. أنت تحدد الشروط وتقرر متى تشغّل الميزة أو توقفها. لا تتخذ الميزة قرارات مستقلة ولا تستخدم ذكاءً اصطناعيًا للاختيار.", style = MaterialTheme.typography.bodyMedium)
                Text("خصوصية تفاصيل الطلب", style = MaterialTheme.typography.titleSmall)
                Text("تُعالج تفاصيل الشاشة على جهازك، ولا ترسلها خدمة إمكانية الوصول عبر الإنترنت. تُحفظ بيانات الحساب والاشتراك عبر خدمة الحساب بشكل منفصل، كما توضح سياسة الخصوصية.", style = MaterialTheme.typography.bodyMedium)
                QannasLegalLinks(showDeletion = false)
                Text("الموافقة لا تشغّل الميزة", style = MaterialTheme.typography.titleSmall)
                Text("بعد موافقتك سنفتح إعدادات Android لتفعّل الإذن بنفسك، ثم يمكنك العودة وتشغيل قناص. اختيار «ليس الآن» أو إغلاق هذه النافذة لا يمنح موافقة، ولا يفعّل القبول التلقائي.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        icon = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
        confirmButton = { QannasButton(text = "أوافق، افتح إعدادات الإذن", onClick = onAccept, fillWidth = false) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("ليس الآن") } }
    )
}

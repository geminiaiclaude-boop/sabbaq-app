package app.qannas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * إفصاح مستقل لخدمة AccessibilityService غير المصنفة كأداة إتاحة.
 *
 * يستدعي المضيف [onAccept] فقط بعد فعل إيجابي صريح، ثم يقرر هو ما إذا كان
 * سيسجل القبول ويفتح إعدادات النظام. لا يطلب هذا المكوّن إذن النظام بنفسه.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("قبل تفعيل مراقبة العروض") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "يستخدم قناص الطلبات خدمة إمكانية الوصول لمراقبة شاشات تطبيقات العروض المدعومة فقط أثناء تفعيلك لها.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "يمكن للخدمة قراءة محتوى النافذة الظاهرة، بما فيه نصوص العرض ووصف العناصر ومعرّفاتها ونوعها وحالة قابليتها للنقر وحدودها على الشاشة. ويشمل ذلك زر قبول العرض الذي تتعرف إليه الخدمة.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "تُحوَّل هذه المعلومات داخل التطبيق إلى تفاصيل العرض، ثم تُطبق قواعدك المحفوظة مثل حدود الأجرة والمسافة والمدة والكلمات المستبعدة والجدول الزمني. عند تطابق عرض مع هذه القواعد البشرية المحددة، قد تضغط الخدمة ضغطة واحدة في مركز زر القبول الذي عثر عليه. لا تستخدم هذه الميزة ذكاءً اصطناعيًا أو تخطيطًا أو قرارًا ذاتيًا؛ سلوكها قواعد حتمية فقط.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "تُعالج بيانات العرض على الجهاز، ولا ترسل خدمة إمكانية الوصول في مسار الالتقاط الحالي محتوى الشاشة عبر الشبكة. هذا الإفصاح خاص باستخدام إمكانية الوصول؛ بيانات الحساب والترخيص لها مسار مستقل موضح في سياسة الخصوصية، ولا تعني هذه العبارة أن التطبيق كله لا يجمع بيانات.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "اختيار «أفهم وأتابع» لا يفعّل الخدمة ولا يمنح الإذن بنفسه. بعده فقط يمكن فتح إعدادات Android لتختار أنت تفعيل خدمة إمكانية الوصول. اختيار «عدم الموافقة» أو إغلاق هذه النافذة لا يعد موافقة.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("أفهم وأتابع")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("عدم الموافقة")
            }
        },
    )
}

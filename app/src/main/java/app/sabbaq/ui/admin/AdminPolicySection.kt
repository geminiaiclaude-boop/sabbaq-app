package app.sabbaq.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sabbaq.admin.AdminInputs
import app.sabbaq.admin.AdminPolicy
import app.sabbaq.admin.AdminRepository
import app.sabbaq.network.SupabaseClient
import app.sabbaq.network.SupabaseClient.FeatureFailure
import app.sabbaq.ui.SabbaqAnimatedMessage
import app.sabbaq.ui.SabbaqButton
import app.sabbaq.ui.SabbaqButtonStyle
import app.sabbaq.ui.SabbaqCard
import app.sabbaq.ui.SabbaqSectionTitle
import app.sabbaq.ui.SabbaqTone
import kotlinx.coroutines.launch

private data class PolicyErrors(
    val trialDays: String? = null,
    val price: String? = null,
    val subscriptionDays: String? = null,
    val contact: String? = null,
    val whatsapp: String? = null,
) {
    val any get() = listOf(trialDays, price, subscriptionDays, contact, whatsapp).any { it != null }
}

/**
 * «سياسة العرض»: the trial, the price in Saudi riyals (sent as whole halalas), the subscription
 * length, the purchase link and the WhatsApp number. The form only ever shows values the server
 * confirmed: it stays disabled until a policy loads, and any unconfirmed save disables it again
 * until the policy is reloaded.
 */
@Composable
internal fun AdminPolicySection(session: AdminSession) {
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf<AdminPolicy?>(null) }
    var everLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<AdminNotice?>(null) }
    var errors by remember { mutableStateOf(PolicyErrors()) }
    var trialEnabled by remember { mutableStateOf(false) }
    var trialDays by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var subscriptionDays by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var whatsapp by remember { mutableStateOf("") }

    fun invalidate(message: String) {
        confirmed = null
        notice = failure(message)
    }

    fun handleFailure(error: Throwable?, fallback: String) {
        if (SupabaseClient.featureFailureOf(error) == FeatureFailure.REJECTED) {
            invalidate("رفض الخادم إحدى قيم السياسة. راجع الحقول ثم أعد تحميل السياسة قبل المحاولة مجددًا.")
        } else {
            val text = session.failureOf(error, fallback)
            confirmed = null
            notice = text
        }
    }

    suspend fun load(announce: Boolean): Boolean {
        loading = true
        if (announce) notice = info("يجري تحميل سياسة العرض من الخادم…")
        val result = AdminRepository.policy()
        loading = false
        return result.fold(
            onSuccess = { policy ->
                confirmed = policy
                everLoaded = true
                trialEnabled = policy.trialEnabled
                trialDays = policy.trialDays.toString()
                price = AdminInputs.minorToSar(policy.priceMinor)
                subscriptionDays = policy.subscriptionDays.toString()
                contact = policy.contactUrl.orEmpty()
                whatsapp = policy.whatsapp?.let { "+$it" }.orEmpty()
                errors = PolicyErrors()
                notice = if (announce) success("تم تحميل سياسة العرض المؤكدة من الخادم.") else null
                true
            },
            onFailure = {
                handleFailure(it, if (SupabaseClient.featureFailureOf(it) == FeatureFailure.UNAVAILABLE)
                    "تعذر التحقق من استجابة سياسة العرض. لم تُعرض أي قيم غير مؤكدة." else "تعذر تحميل سياسة العرض. لم تُعرض أي قيم غير مؤكدة.")
                false
            },
        )
    }

    LaunchedEffect(reload) { load(announce = true) }

    val editable = confirmed != null && !loading && !saving
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("admin-policy"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SabbaqSectionTitle("سياسة العرض والاشتراك", "تُحمّل السياسة من الخادم بعد التحقق من صلاحية المشرف، وتُحفظ كاملة في عملية واحدة.")
        Text(confirmed?.let { "آخر تأكيد من الخادم: ${formatFull(it.updatedAt)}" } ?: if (everLoaded) "لا توجد سياسة مؤكدة حاليًا." else "لم تُحمّل سياسة مؤكدة بعد.",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SabbaqCard(title = "إعدادات العرض التجاري", icon = Icons.Outlined.Sell,
            subtitle = "السعر بالريال السعودي ويُرسل إلى الخادم كعدد صحيح من الهللات. لا يشمل هذا العرض ضريبة القيمة المضافة.") {
            SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("تفعيل التجربة المجانية", style = MaterialTheme.typography.titleSmall)
                    Text("إيقافها يمنع منح تجارب جديدة. تبقى المدة مطلوبة لعمليات المنح المستقبلية عند إعادة التفعيل.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = trialEnabled, onCheckedChange = { trialEnabled = it }, enabled = editable)
                    Text(if (confirmed == null) "غير مؤكد" else if (trialEnabled) "مفعلة" else "متوقفة", style = MaterialTheme.typography.labelSmall)
                }
            }
            AdminField("مدة التجربة (أيام)", trialDays, { trialDays = it; errors = errors.copy(trialDays = null) }, enabled = editable,
                maxLength = 3, keyboard = KeyboardType.Number, hint = "من 1 إلى 365 يومًا.", error = errors.trialDays)
            AdminField("سعر الاشتراك (ريال سعودي)", price, { price = it; errors = errors.copy(price = null) }, enabled = editable,
                maxLength = 12, keyboard = KeyboardType.Decimal, ltr = true, error = errors.price,
                hint = "مثال: 18.75. يُحوَّل إلى هللات من النص دون استخدام أرقام عشرية عائمة.")
            AdminField("مدة الاشتراك (أيام)", subscriptionDays, { subscriptionDays = it; errors = errors.copy(subscriptionDays = null) }, enabled = editable,
                maxLength = 3, keyboard = KeyboardType.Number, error = errors.subscriptionDays,
                hint = "من 1 إلى 365 يومًا. تُعرض للعميل كمدة واضحة، مثل «شهري (30 يومًا)».")
            AdminField("رابط التواصل للشراء (اختياري)", contact, { contact = it; errors = errors.copy(contact = null) }, enabled = editable,
                maxLength = 2048, keyboard = KeyboardType.Uri, ltr = true, error = errors.contact,
                hint = "اتركه فارغًا أو استخدم رابط HTTPS صالحًا فقط، من دون مسافات.")
            AdminField("رقم واتساب الإدارة لطلبات الاشتراك", whatsapp, { whatsapp = it; errors = errors.copy(whatsapp = null) }, enabled = editable,
                maxLength = 20, keyboard = KeyboardType.Phone, ltr = true, error = errors.whatsapp,
                hint = "زر «اشترك عبر واتساب» في التطبيق يفتح محادثة مع هذا الرقم برسالة جاهزة فيها السعر والمدة. مثال: 05XXXXXXXX أو +9665XXXXXXXX. فارغ يعني لا يظهر الزر.")
            SabbaqButton("حفظ السياسة كاملة", enabled = editable, busy = saving, busyText = "جارٍ الحفظ…", modifier = Modifier.testTag("admin-policy-save"), onClick = {
                val base = confirmed
                if (base == null) {
                    invalidate("لا يمكن الحفظ قبل تحميل سياسة مؤكدة من الخادم. أعد تحميلها أولًا.")
                    return@SabbaqButton
                }
                val parsedTrial = AdminInputs.boundedInt(trialDays, 1, 365)
                val parsedPrice = AdminInputs.sarToMinor(price)
                val parsedDays = AdminInputs.boundedInt(subscriptionDays, 1, 365)
                val cleanContact = contact.trim()
                val parsedWhatsapp = AdminInputs.whatsapp(whatsapp)
                errors = PolicyErrors(
                    trialDays = if (parsedTrial == null) "أدخل عددًا صحيحًا من 1 إلى 365 يومًا." else null,
                    price = if (parsedPrice == null) "أدخل سعرًا موجبًا حتى 1,000,000.00 ريال، بدقة هللتين كحد أقصى." else null,
                    subscriptionDays = if (parsedDays == null) "أدخل عددًا صحيحًا من 1 إلى 365 يومًا." else null,
                    contact = if (cleanContact.isNotEmpty() && !AdminInputs.isHttpsContact(cleanContact)) "استخدم رابط HTTPS بلا مسافات وبحد أقصى 2048 بايت، أو اترك الحقل فارغًا." else null,
                    whatsapp = if (parsedWhatsapp is AdminInputs.Whatsapp.Invalid) "أدخل رقمًا صحيحًا مثل 05XXXXXXXX أو +9665XXXXXXXX، أو اترك الحقل فارغًا." else null,
                )
                if (errors.any || parsedTrial == null || parsedPrice == null || parsedDays == null) {
                    notice = failure("صحح الحقول المعلَّمة قبل حفظ السياسة. لم تُرسل أي عملية إلى الخادم.")
                    return@SabbaqButton
                }
                val next = base.copy(
                    trialEnabled = trialEnabled, trialDays = parsedTrial, priceMinor = parsedPrice, subscriptionDays = parsedDays,
                    contactUrl = cleanContact.ifEmpty { null }, whatsapp = (parsedWhatsapp as? AdminInputs.Whatsapp.Valid)?.digits,
                )
                scope.launch {
                    saving = true
                    notice = info("يجري حفظ السياسة كاملة في عملية واحدة…")
                    val result = AdminRepository.savePolicy(next)
                    saving = false
                    result.fold(
                        onSuccess = { if (load(announce = false)) notice = success("تم حفظ سياسة العرض ورقم واتساب وتأكيدهما من الخادم.") },
                        onFailure = {
                            if (it is AdminRepository.WhatsappNotSaved) handleFailure(it.reason, "حُفظت السياسة، لكن لم نتأكد من حفظ رقم واتساب. أعد تحميل السياسة ثم حاول مجددًا.")
                            else handleFailure(it, "لم نتأكد من حفظ السياسة؛ لم تُحدَّث الواجهة. أعد تحميل السياسة قبل المحاولة مجددًا.")
                        },
                    )
                }
            })
            SabbaqButton("إعادة تحميل السياسة", onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !loading && !saving,
                busy = loading, busyText = "جارٍ التحميل…")
        }
    }
}

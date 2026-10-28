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
import app.sabbaq.LocaleManager
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
            invalidate((if (LocaleManager.isArabic()) "رفض الخادم إحدى قيم السياسة. راجع الحقول ثم أعد تحميل السياسة قبل المحاولة مجددًا." else "The server rejected one of the policy values. Review the fields, then reload the policy before trying again."))
        } else {
            val text = session.failureOf(error, fallback)
            confirmed = null
            notice = text
        }
    }

    suspend fun load(announce: Boolean): Boolean {
        loading = true
        if (announce) notice = info((if (LocaleManager.isArabic()) "يجري تحميل سياسة العرض من الخادم…" else "Loading the offer policy from the server…"))
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
                notice = if (announce) success((if (LocaleManager.isArabic()) "تم تحميل سياسة العرض المؤكدة من الخادم." else "Loaded the confirmed offer policy from the server.")) else null
                true
            },
            onFailure = {
                handleFailure(it, if (SupabaseClient.featureFailureOf(it) == FeatureFailure.UNAVAILABLE)
                    (if (LocaleManager.isArabic()) "تعذر التحقق من استجابة سياسة العرض. لم تُعرض أي قيم غير مؤكدة." else "Could not verify the offer-policy response. No unconfirmed values were shown.") else (if (LocaleManager.isArabic()) "تعذر تحميل سياسة العرض. لم تُعرض أي قيم غير مؤكدة." else "Could not load the offer policy. No unconfirmed values were shown."))
                false
            },
        )
    }

    LaunchedEffect(reload) { load(announce = true) }

    val editable = confirmed != null && !loading && !saving
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("admin-policy"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SabbaqSectionTitle((if (LocaleManager.isArabic()) "سياسة العرض والاشتراك" else "Offer and subscription policy"), (if (LocaleManager.isArabic()) "تُحمّل السياسة من الخادم بعد التحقق من صلاحية المشرف، وتُحفظ كاملة في عملية واحدة." else "The policy loads from the server after verifying admin access, and is saved in full in one operation."))
        Text(confirmed?.let { (if (LocaleManager.isArabic()) "آخر تأكيد من الخادم: ${formatFull(it.updatedAt)}" else "Last confirmed by the server: ${formatFull(it.updatedAt)}") } ?: if (everLoaded) (if (LocaleManager.isArabic()) "لا توجد سياسة مؤكدة حاليًا." else "No confirmed policy currently.") else (if (LocaleManager.isArabic()) "لم تُحمّل سياسة مؤكدة بعد." else "No confirmed policy loaded yet."),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SabbaqCard(title = (if (LocaleManager.isArabic()) "إعدادات العرض التجاري" else "Commercial offer settings"), icon = Icons.Outlined.Sell,
            subtitle = (if (LocaleManager.isArabic()) "السعر بالريال السعودي ويُرسل إلى الخادم كعدد صحيح من الهللات. لا يشمل هذا العرض ضريبة القيمة المضافة." else "The price is in Saudi riyals and is sent to the server as an integer number of halalas. This offer does not include VAT.")) {
            SabbaqAnimatedMessage(notice?.text, notice?.tone ?: SabbaqTone.Info)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text((if (LocaleManager.isArabic()) "تفعيل التجربة المجانية" else "Enable the free trial"), style = MaterialTheme.typography.titleSmall)
                    Text((if (LocaleManager.isArabic()) "إيقافها يمنع منح تجارب جديدة. تبقى المدة مطلوبة لعمليات المنح المستقبلية عند إعادة التفعيل." else "Turning it off prevents granting new trials. The duration stays required for future grants when re-enabled."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = trialEnabled, onCheckedChange = { trialEnabled = it }, enabled = editable)
                    Text(if (confirmed == null) (if (LocaleManager.isArabic()) "غير مؤكد" else "Unconfirmed") else if (trialEnabled) (if (LocaleManager.isArabic()) "مفعلة" else "Enabled") else (if (LocaleManager.isArabic()) "متوقفة" else "Disabled"), style = MaterialTheme.typography.labelSmall)
                }
            }
            AdminField((if (LocaleManager.isArabic()) "مدة التجربة (أيام)" else "Trial duration (days)"), trialDays, { trialDays = it; errors = errors.copy(trialDays = null) }, enabled = editable,
                maxLength = 3, keyboard = KeyboardType.Number, hint = (if (LocaleManager.isArabic()) "من 1 إلى 365 يومًا." else "From 1 to 365 days."), error = errors.trialDays)
            AdminField((if (LocaleManager.isArabic()) "سعر الاشتراك (ريال سعودي)" else "Subscription price (Saudi riyals)"), price, { price = it; errors = errors.copy(price = null) }, enabled = editable,
                maxLength = 12, keyboard = KeyboardType.Decimal, ltr = true, error = errors.price,
                hint = (if (LocaleManager.isArabic()) "مثال: 18.75. يُحوَّل إلى هللات من النص دون استخدام أرقام عشرية عائمة." else "Example: 18.75. Converted to halalas from the text without using floating-point numbers."))
            AdminField((if (LocaleManager.isArabic()) "مدة الاشتراك (أيام)" else "Subscription duration (days)"), subscriptionDays, { subscriptionDays = it; errors = errors.copy(subscriptionDays = null) }, enabled = editable,
                maxLength = 3, keyboard = KeyboardType.Number, error = errors.subscriptionDays,
                hint = (if (LocaleManager.isArabic()) "من 1 إلى 365 يومًا. تُعرض للعميل كمدة واضحة، مثل «شهري (30 يومًا)»." else "From 1 to 365 days. Shown to the customer as a clear duration, such as \u00ab monthly (30 days) \u00bb."))
            AdminField((if (LocaleManager.isArabic()) "رابط التواصل للشراء (اختياري)" else "Purchase contact link (optional)"), contact, { contact = it; errors = errors.copy(contact = null) }, enabled = editable,
                maxLength = 2048, keyboard = KeyboardType.Uri, ltr = true, error = errors.contact,
                hint = (if (LocaleManager.isArabic()) "اتركه فارغًا أو استخدم رابط HTTPS صالحًا فقط، من دون مسافات." else "Leave it empty or use a valid HTTPS link only, with no spaces."))
            AdminField((if (LocaleManager.isArabic()) "رقم واتساب الإدارة لطلبات الاشتراك" else "Administration WhatsApp number for subscription requests"), whatsapp, { whatsapp = it; errors = errors.copy(whatsapp = null) }, enabled = editable,
                maxLength = 20, keyboard = KeyboardType.Phone, ltr = true, error = errors.whatsapp,
                hint = (if (LocaleManager.isArabic()) "زر «اشترك عبر واتساب» في التطبيق يفتح محادثة مع هذا الرقم برسالة جاهزة فيها السعر والمدة. مثال: 05XXXXXXXX أو +9665XXXXXXXX. فارغ يعني لا يظهر الزر." else "The \u00ab Subscribe via WhatsApp \u00bb button in the app opens a chat with this number with a ready message containing the price and duration. Example: 05XXXXXXXX or +9665XXXXXXXX. Empty means the button does not appear."))
            SabbaqButton((if (LocaleManager.isArabic()) "حفظ السياسة كاملة" else "Save the full policy"), enabled = editable, busy = saving, busyText = (if (LocaleManager.isArabic()) "جارٍ الحفظ…" else "Saving…"), modifier = Modifier.testTag("admin-policy-save"), onClick = {
                val base = confirmed
                if (base == null) {
                    invalidate((if (LocaleManager.isArabic()) "لا يمكن الحفظ قبل تحميل سياسة مؤكدة من الخادم. أعد تحميلها أولًا." else "Cannot save before loading a confirmed policy from the server. Reload it first."))
                    return@SabbaqButton
                }
                val parsedTrial = AdminInputs.boundedInt(trialDays, 1, 365)
                val parsedPrice = AdminInputs.sarToMinor(price)
                val parsedDays = AdminInputs.boundedInt(subscriptionDays, 1, 365)
                val cleanContact = contact.trim()
                val parsedWhatsapp = AdminInputs.whatsapp(whatsapp)
                errors = PolicyErrors(
                    trialDays = if (parsedTrial == null) (if (LocaleManager.isArabic()) "أدخل عددًا صحيحًا من 1 إلى 365 يومًا." else "Enter an integer from 1 to 365 days.") else null,
                    price = if (parsedPrice == null) (if (LocaleManager.isArabic()) "أدخل سعرًا موجبًا حتى 1,000,000.00 ريال، بدقة هللتين كحد أقصى." else "Enter a positive price up to 1,000,000.00 riyals, with at most two halala decimals.") else null,
                    subscriptionDays = if (parsedDays == null) (if (LocaleManager.isArabic()) "أدخل عددًا صحيحًا من 1 إلى 365 يومًا." else "Enter an integer from 1 to 365 days.") else null,
                    contact = if (cleanContact.isNotEmpty() && !AdminInputs.isHttpsContact(cleanContact)) (if (LocaleManager.isArabic()) "استخدم رابط HTTPS بلا مسافات وبحد أقصى 2048 بايت، أو اترك الحقل فارغًا." else "Use an HTTPS link with no spaces and at most 2048 bytes, or leave the field empty.") else null,
                    whatsapp = if (parsedWhatsapp is AdminInputs.Whatsapp.Invalid) (if (LocaleManager.isArabic()) "أدخل رقمًا صحيحًا مثل 05XXXXXXXX أو +9665XXXXXXXX، أو اترك الحقل فارغًا." else "Enter a valid number like 05XXXXXXXX or +9665XXXXXXXX, or leave the field empty.") else null,
                )
                if (errors.any || parsedTrial == null || parsedPrice == null || parsedDays == null) {
                    notice = failure((if (LocaleManager.isArabic()) "صحح الحقول المعلَّمة قبل حفظ السياسة. لم تُرسل أي عملية إلى الخادم." else "Fix the marked fields before saving the policy. No operation was sent to the server."))
                    return@SabbaqButton
                }
                val next = base.copy(
                    trialEnabled = trialEnabled, trialDays = parsedTrial, priceMinor = parsedPrice, subscriptionDays = parsedDays,
                    contactUrl = cleanContact.ifEmpty { null }, whatsapp = (parsedWhatsapp as? AdminInputs.Whatsapp.Valid)?.digits,
                )
                scope.launch {
                    saving = true
                    notice = info((if (LocaleManager.isArabic()) "يجري حفظ السياسة كاملة في عملية واحدة…" else "Saving the full policy in one operation…"))
                    val result = AdminRepository.savePolicy(next)
                    saving = false
                    result.fold(
                        onSuccess = { if (load(announce = false)) notice = success((if (LocaleManager.isArabic()) "تم حفظ سياسة العرض ورقم واتساب وتأكيدهما من الخادم." else "The offer policy and WhatsApp number were saved and confirmed by the server.")) },
                        onFailure = {
                            if (it is AdminRepository.WhatsappNotSaved) handleFailure(it.reason, (if (LocaleManager.isArabic()) "حُفظت السياسة، لكن لم نتأكد من حفظ رقم واتساب. أعد تحميل السياسة ثم حاول مجددًا." else "The policy was saved, but we could not confirm the WhatsApp number was saved. Reload the policy, then try again."))
                            else handleFailure(it, (if (LocaleManager.isArabic()) "لم نتأكد من حفظ السياسة؛ لم تُحدَّث الواجهة. أعد تحميل السياسة قبل المحاولة مجددًا." else "We could not confirm the policy was saved; the interface was not updated. Reload the policy before trying again."))
                        },
                    )
                }
            })
            SabbaqButton((if (LocaleManager.isArabic()) "إعادة تحميل السياسة" else "Reload the policy"), onClick = { reload++ }, style = SabbaqButtonStyle.Secondary, enabled = !loading && !saving,
                busy = loading, busyText = (if (LocaleManager.isArabic()) "جارٍ التحميل…" else "Loading…"))
        }
    }
}

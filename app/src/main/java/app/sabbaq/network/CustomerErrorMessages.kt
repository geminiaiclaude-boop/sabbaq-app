package app.sabbaq.network

/**
 * هندسة إنتاجية مغلقة لمعالجة أخطاء العملاء.
 * تضمن عدم تسريب أي نصوص SQL أو أسرار خادم مع إرفاق رمز خطأ إنتاجي قطعي [AUTH-xxx].
 */
import app.sabbaq.LocaleManager

internal object CustomerErrorMessages {

    data class FormattedError(val message: String, val code: String) {
        val userFacing: String get() = "$message [$code]"
    }

    fun forResponse(status: Int, providerCode: String?, detail: String?, operation: String): String {
        return resolve(status, providerCode, detail, operation).userFacing
    }

    fun resolve(status: Int, providerCode: String?, detail: String?, operation: String): FormattedError {
        if (status == 429) {
            return FormattedError((if (LocaleManager.isArabic()) "محاولات كثيرة خلال وقت قصير. انتظر قليلًا ثم حاول مجددًا." else "Too many attempts in a short time. Wait a little, then try again."), "REQ-429")
        }
        if (status == 408 || status == 504) {
            return FormattedError((if (LocaleManager.isArabic()) "استغرق الاتصال وقتًا أطول من المعتاد. تحقق من الإنترنت وحاول مجددًا." else "The connection took longer than usual. Check the internet and try again."), "NET-TIMEOUT")
        }
        if (status in 500..599) {
            return FormattedError((if (LocaleManager.isArabic()) "الخدمة غير متاحة مؤقتًا. حاول مجددًا بعد قليل." else "The service is temporarily unavailable. Try again shortly."), "SRV-$status")
        }

        val fromProvider = when (providerCode) {
            "otp_expired" -> FormattedError((if (LocaleManager.isArabic()) "رمز التحقق غير صحيح أو انتهت صلاحيته. اطلب رمزًا جديدًا." else "The verification code is incorrect or expired. Request a new code."), "OTP-EXPIRED")
            "over_sms_send_rate_limit", "over_request_rate_limit" ->
                FormattedError((if (LocaleManager.isArabic()) "انتظر قليلًا قبل طلب رمز تحقق جديد." else "Wait a little before requesting a new verification code."), "OTP-RATE-LIMIT")
            "sms_send_failed" ->
                FormattedError((if (LocaleManager.isArabic()) "لم نتمكن من إرسال الرسالة. حاول لاحقًا أو استخدم Google." else "We could not send the message. Try later or use Google."), "SMS-FAIL")
            "phone_provider_disabled" ->
                FormattedError((if (LocaleManager.isArabic()) "الدخول برقم الجوال غير متاح حاليًا. يمكنك الدخول باستخدام Google." else "Phone-number sign-in is unavailable now. You can sign in using Google."), "PHONE-DISABLED")
            "provider_disabled" ->
                FormattedError((if (LocaleManager.isArabic()) "طريقة الدخول هذه غير متاحة حاليًا. جرّب طريقة الدخول الأخرى." else "This sign-in method is unavailable now. Try the other sign-in method."), "AUTH-PROVIDER-DISABLED")
            "invalid_credentials" ->
                FormattedError((if (LocaleManager.isArabic()) "تعذر تسجيل الدخول. تحقق من بياناتك وحاول مجددًا." else "Could not sign in. Check your details and try again."), "AUTH-INVALID-CRED")
            "user_already_exists" ->
                FormattedError((if (LocaleManager.isArabic()) "الحساب مسجل مسبقًا. سجل دخولك بنفس الطريقة الأصلية." else "The account already exists. Sign in with the original method."), "AUTH-USER-EXISTS")
            else -> null
        }
        if (fromProvider != null) return fromProvider

        val cleanDetail = detail?.trim()?.lowercase()
        val fromDetail = when {
            cleanDetail == "not_authenticated" || cleanDetail == "auth_user_missing" ->
                FormattedError((if (LocaleManager.isArabic()) "سجّل الدخول مجددًا لإكمال هذه الخطوة." else "Sign in again to complete this step."), "AUTH-REQUIRED")
            cleanDetail == "unsupported_or_unverified_identity" || cleanDetail == "password_signup_disabled" ->
                FormattedError((if (LocaleManager.isArabic()) "استخدم حساب Google أو تحقق من رقم جوالك السعودي لإكمال الدخول." else "Use a Google account or verify your Saudi phone number to complete sign-in."), "AUTH-IDENTITY-UNVERIFIED")
            cleanDetail == "not_admin" ->
                FormattedError((if (LocaleManager.isArabic()) "هذه الخطوة متاحة لإدارة التطبيق فقط." else "This step is available to the app administration only."), "AUTH-NOT-ADMIN")
            cleanDetail == "bad_code" ->
                FormattedError((if (LocaleManager.isArabic()) "رمز التفعيل غير صحيح. تحقق منه وحاول مجددًا." else "The activation code is incorrect. Check it and try again."), "CODE-INVALID")
            cleanDetail == "bad_device" ->
                FormattedError((if (LocaleManager.isArabic()) "تعذر التحقق من هذا الجهاز. أعد تسجيل الدخول وحاول مجددًا." else "Could not verify this device. Sign in again and try again."), "DEVICE-MISMATCH")
            cleanDetail == "bad_version_code" ->
                FormattedError((if (LocaleManager.isArabic()) "حدّث التطبيق ثم حاول مجددًا." else "Update the app, then try again."), "APP-OUTDATED")
            cleanDetail == "bad_display_name" ->
                FormattedError((if (LocaleManager.isArabic()) "تحقق من اسم حسابك ثم حاول مجددًا." else "Check your account name, then try again."), "NAME-INVALID")
            cleanDetail == "commercial_policy_missing" ->
                FormattedError((if (LocaleManager.isArabic()) "تفاصيل الاشتراك غير متاحة الآن. حاول مجددًا بعد قليل." else "Subscription details are not available now. Try again shortly."), "COMMERCIAL-MISSING")
            cleanDetail != null && cleanDetail.contains("invalid_grant") ->
                FormattedError((if (LocaleManager.isArabic()) "تعذر التحقق من هوية Google لدى مزود الخدمة. تأكد من تفعيل الحساب." else "Could not verify your Google identity with the provider. Make sure the account is active."), "G-INVALID-GRANT")
            cleanDetail != null && cleanDetail.contains("profile") ->
                FormattedError((if (LocaleManager.isArabic()) "تعذر إعداد ملف السائق في النظام. تواصل مع الدعم الفني." else "Could not set up the driver profile in the system. Contact technical support."), "DRIVER-PROFILE-FAIL")
            else -> null
        }
        if (fromDetail != null) return fromDetail

        val opCode = when (operation) {
            "تبادل هوية Google" -> "G-TOKEN"
            "إعداد ملف السائق" -> "G-PROFILE"
            "طلب رمز التحقق" -> "OTP-REQ"
            "التحقق من الرمز" -> "OTP-VERIFY"
            else -> "OP"
        }

        return when (status) {
            400 -> FormattedError((if (LocaleManager.isArabic()) "تعذر إكمال طلب الدخول. تحقق من صحة الحساب المستخدم." else "Could not complete the sign-in request. Check the account used."), "$opCode-400")
            401 -> FormattedError((if (LocaleManager.isArabic()) "جلسة الدخول غير مصرحة أو غير مسجلة في النظام." else "The sign-in session is unauthorized or not registered in the system."), "$opCode-401")
            403 -> FormattedError((if (LocaleManager.isArabic()) "لا يمكنك إكمال هذه الخطوة بحسابك الحالي. تواصل مع الدعم إذا استمرت المشكلة." else "You cannot complete this step with your current account. Contact support if the problem persists."), "$opCode-403")
            404 -> FormattedError((if (LocaleManager.isArabic()) "خدمة التحقق غير متوفرة حاليًا. حاول مجددًا لاحقًا." else "The verification service is unavailable now. Try again later."), "$opCode-404")
            else -> FormattedError((if (LocaleManager.isArabic()) "لم نتمكن من إكمال هذه الخطوة. تحقق من بياناتك وحاول مجددًا." else "We could not complete this step. Check your details and try again."), "$opCode-$status")
        }
    }
}

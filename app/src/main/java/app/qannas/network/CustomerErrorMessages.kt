package app.qannas.network

/**
 * هندسة إنتاجية مغلقة لمعالجة أخطاء العملاء.
 * تضمن عدم تسريب أي نصوص SQL أو أسرار خادم مع إرفاق رمز خطأ إنتاجي قطعي [AUTH-xxx].
 */
internal object CustomerErrorMessages {

    data class FormattedError(val message: String, val code: String) {
        val userFacing: String get() = "$message [$code]"
    }

    fun forResponse(status: Int, providerCode: String?, detail: String?, operation: String): String {
        return resolve(status, providerCode, detail, operation).userFacing
    }

    fun resolve(status: Int, providerCode: String?, detail: String?, operation: String): FormattedError {
        if (status == 429) {
            return FormattedError("محاولات كثيرة خلال وقت قصير. انتظر قليلًا ثم حاول مجددًا.", "REQ-429")
        }
        if (status == 408 || status == 504) {
            return FormattedError("استغرق الاتصال وقتًا أطول من المعتاد. تحقق من الإنترنت وحاول مجددًا.", "NET-TIMEOUT")
        }
        if (status in 500..599) {
            return FormattedError("الخدمة غير متاحة مؤقتًا. حاول مجددًا بعد قليل.", "SRV-$status")
        }

        val fromProvider = when (providerCode) {
            "otp_expired" -> FormattedError("رمز التحقق غير صحيح أو انتهت صلاحيته. اطلب رمزًا جديدًا.", "OTP-EXPIRED")
            "over_sms_send_rate_limit", "over_request_rate_limit" ->
                FormattedError("انتظر قليلًا قبل طلب رمز تحقق جديد.", "OTP-RATE-LIMIT")
            "sms_send_failed" ->
                FormattedError("لم نتمكن من إرسال الرسالة. حاول لاحقًا أو استخدم Google.", "SMS-FAIL")
            "phone_provider_disabled" ->
                FormattedError("الدخول برقم الجوال غير متاح حاليًا. يمكنك الدخول باستخدام Google.", "PHONE-DISABLED")
            "provider_disabled" ->
                FormattedError("طريقة الدخول هذه غير متاحة حاليًا. جرّب طريقة الدخول الأخرى.", "AUTH-PROVIDER-DISABLED")
            "invalid_credentials" ->
                FormattedError("تعذر تسجيل الدخول. تحقق من بياناتك وحاول مجددًا.", "AUTH-INVALID-CRED")
            "user_already_exists" ->
                FormattedError("الحساب مسجل مسبقاً. سجل دخولك بنفس الطريقة الأصلية.", "AUTH-USER-EXISTS")
            else -> null
        }
        if (fromProvider != null) return fromProvider

        val cleanDetail = detail?.trim()?.lowercase()
        val fromDetail = when {
            cleanDetail == "not_authenticated" || cleanDetail == "auth_user_missing" ->
                FormattedError("سجّل الدخول مجددًا لإكمال هذه الخطوة.", "AUTH-REQUIRED")
            cleanDetail == "unsupported_or_unverified_identity" || cleanDetail == "password_signup_disabled" ->
                FormattedError("استخدم حساب Google أو تحقق من رقم جوالك السعودي لإكمال الدخول.", "AUTH-IDENTITY-UNVERIFIED")
            cleanDetail == "not_admin" ->
                FormattedError("هذه الخطوة متاحة لإدارة التطبيق فقط.", "AUTH-NOT-ADMIN")
            cleanDetail == "bad_code" ->
                FormattedError("كود التفعيل غير صحيح. تحقق منه وحاول مجددًا.", "CODE-INVALID")
            cleanDetail == "bad_device" ->
                FormattedError("تعذر التحقق من هذا الجهاز. أعد تسجيل الدخول وحاول مجددًا.", "DEVICE-MISMATCH")
            cleanDetail == "bad_version_code" ->
                FormattedError("حدّث التطبيق ثم حاول مجددًا.", "APP-OUTDATED")
            cleanDetail == "bad_display_name" ->
                FormattedError("تحقق من اسم حسابك ثم حاول مجددًا.", "NAME-INVALID")
            cleanDetail == "commercial_policy_missing" ->
                FormattedError("تفاصيل الاشتراك غير متاحة الآن. حاول مجددًا بعد قليل.", "COMMERCIAL-MISSING")
            cleanDetail != null && cleanDetail.contains("invalid_grant") ->
                FormattedError("تعذر التحقق من هوية Google لدى مزود الخدمة. تأكد من تفعيل الحساب.", "G-INVALID-GRANT")
            cleanDetail != null && cleanDetail.contains("profile") ->
                FormattedError("تعذر إعداد ملف السائق في النظام. تواصل مع الدعم الفني.", "DRIVER-PROFILE-FAIL")
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
            400 -> FormattedError("تعذر إكمال طلب الدخول. تحقق من صحة الحساب المستخدم.", "$opCode-400")
            401 -> FormattedError("جلسة الدخول غير مصرحة أو غير مسجلة في النظام.", "$opCode-401")
            403 -> FormattedError("لا يمكنك إكمال هذه الخطوة بحسابك الحالي. تواصل مع الدعم إذا استمرت المشكلة.", "$opCode-403")
            404 -> FormattedError("خدمة التحقق غير متوفرة حالياً. حاول مجدداً لاحقاً.", "$opCode-404")
            else -> FormattedError("لم نتمكن من إكمال هذه الخطوة. تحقق من بياناتك وحاول مجددًا.", "$opCode-$status")
        }
    }
}

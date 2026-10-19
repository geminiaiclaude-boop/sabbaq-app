package app.qannas.network

/** Customer-facing error copy. Never echo arbitrary provider/SQL response text. */
internal object CustomerErrorMessages {
    fun forResponse(status: Int, providerCode: String?, detail: String?, operation: String): String {
        if (status == 429) return "محاولات كثيرة خلال وقت قصير. انتظر قليلًا ثم حاول مجددًا."
        if (status == 408 || status == 504) return "استغرق الاتصال وقتًا أطول من المعتاد. تحقق من الإنترنت وحاول مجددًا."
        if (status in 500..599) return "الخدمة غير متاحة مؤقتًا. حاول مجددًا بعد قليل."
        val known = when (providerCode) {
            "otp_expired" -> "رمز التحقق غير صحيح أو انتهت صلاحيته. اطلب رمزًا جديدًا."
            "over_sms_send_rate_limit", "over_request_rate_limit" -> "انتظر قليلًا قبل طلب رمز تحقق جديد."
            "sms_send_failed" -> "لم نتمكن من إرسال الرسالة. حاول لاحقًا أو استخدم Google."
            "phone_provider_disabled" -> "الدخول برقم الجوال غير متاح حاليًا. يمكنك الدخول باستخدام Google."
            "provider_disabled" -> "طريقة الدخول هذه غير متاحة حاليًا. جرّب طريقة الدخول الأخرى."
            "invalid_credentials" -> "تعذر تسجيل الدخول. تحقق من بياناتك وحاول مجددًا."
            else -> when (detail?.trim()) {
                "not_authenticated", "auth_user_missing" -> "سجّل الدخول مجددًا لإكمال هذه الخطوة."
                "unsupported_or_unverified_identity", "password_signup_disabled" -> "استخدم حساب Google أو تحقق من رقم جوالك السعودي لإكمال الدخول."
                "not_admin" -> "هذه الخطوة متاحة لإدارة التطبيق فقط."
                "bad_code" -> "كود التفعيل غير صحيح. تحقق منه وحاول مجددًا."
                "bad_device" -> "تعذر التحقق من هذا الجهاز. أعد تسجيل الدخول وحاول مجددًا."
                "bad_version_code" -> "حدّث التطبيق ثم حاول مجددًا."
                "bad_display_name" -> "تحقق من اسم حسابك ثم حاول مجددًا."
                "commercial_policy_missing" -> "تفاصيل الاشتراك غير متاحة الآن. حاول مجددًا بعد قليل."
                else -> null
            }
        }
        return known ?: when (status) {
            401 -> "سجّل الدخول مجددًا لإكمال هذه الخطوة."
            403 -> "لا يمكنك إكمال هذه الخطوة بحسابك الحالي. تواصل مع الدعم إذا استمرت المشكلة."
            else -> "لم نتمكن من إكمال هذه الخطوة. تحقق من بياناتك وحاول مجددًا."
        }
    }
}

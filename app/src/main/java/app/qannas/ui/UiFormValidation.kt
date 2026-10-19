package app.qannas.ui

import app.qannas.core.Filters
import app.qannas.core.Text
import java.util.Locale

/** تحقّق محلي محدود قبل أي طلب شبكة؛ لا يفرض سياسة خادم غير مؤكدة. */
internal object UiFormValidation {
    fun loginError(email: String, password: String): String? = when {
        !isEmailValid(email) -> "أدخل بريداً إلكترونياً صحيحاً."
        password.isBlank() -> "أدخل كلمة المرور."
        else -> null
    }

    fun signUpError(name: String, phone: String, email: String, password: String): String? = when {
        name.trim().isEmpty() -> "أدخل الاسم الكامل."
        phone.trim().isEmpty() -> "أدخل رقم الهاتف."
        name.trim().length !in 2..80 -> "الاسم الكامل يجب أن يكون بين حرفين و80 حرفاً."
        !Regex("^\\+?[0-9]{7,15}$").matches(phone.replace(Regex("[\\s-]"), "")) -> "أدخل رقم هاتف من 7 إلى 15 رقماً."
        !isEmailValid(email) -> "أدخل بريداً إلكترونياً صحيحاً."
        password.isBlank() -> "أدخل كلمة المرور."
        password.length < 8 -> "استخدم كلمة مرور من 8 أحرف على الأقل."
        else -> null
    }

    fun activationCodeError(code: String): String? = when {
        code.trim().isEmpty() -> "أدخل كود التفعيل أولاً."
        !Regex("^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}$").matches(normalizeActivationCode(code)) &&
            !Regex("^[0-9A-HJKMNP-TV-Z]{16}$").matches(normalizeActivationCode(code)) -> "أدخل كوداً صحيحاً من 16 حرفاً ورقماً."
        else -> null
    }

    fun normalizeActivationCode(value: String): String = value.trim().uppercase(Locale.ROOT)

    fun filtersError(filters: Filters): String? {
        val values = listOf(filters.minFare, filters.maxFare, filters.minFarePerKm, filters.maxPickupKm, filters.minTripKm, filters.maxTripKm)
        if (values.any { !it.isNullOrBlank() && (Text.parseNumber(Text.normalize(it))?.signum() ?: -1) < 0 }) return "أدخل قيمة صحيحة أكبر من أو تساوي صفرًا."
        fun exceeds(min: String?, max: String?): Boolean {
            val low = min?.let { Text.parseNumber(Text.normalize(it)) } ?: return false
            val high = max?.let { Text.parseNumber(Text.normalize(it)) } ?: return false
            return low > high
        }
        if (exceeds(filters.minFare, filters.maxFare) || exceeds(filters.minTripKm, filters.maxTripKm)) return "اجعل الحد الأقصى أكبر من أو مساويًا للحد الأدنى."
        if (filters.maxPickupMinutes?.let { it < 0 } == true || filters.maxTripMinutes?.let { it < 0 } == true) return "مدة غير صالحة"
        if (filters.scheduleEnabled && (filters.scheduleStartMinute !in 0..1439 || filters.scheduleEndMinute !in 0..1440)) return "وقت تشغيل غير صالح"
        return null
    }

    private fun isEmailValid(value: String): Boolean =
        EMAIL_PATTERN.matches(value.trim())

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
}

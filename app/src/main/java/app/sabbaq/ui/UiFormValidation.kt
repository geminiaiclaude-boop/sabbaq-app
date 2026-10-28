package app.sabbaq.ui

import app.sabbaq.core.Filters
import app.sabbaq.LocaleManager
import app.sabbaq.core.Text
import java.util.Locale

/** تحقّق محلي محدود قبل أي طلب شبكة؛ لا يفرض سياسة خادم غير مؤكدة. */
internal object UiFormValidation {
    fun loginError(email: String, password: String): String? = when {
        !isEmailValid(email) -> (if (LocaleManager.isArabic()) "أدخل بريدًا إلكترونيًا صحيحًا." else "Enter a valid email address.")
        password.isBlank() -> (if (LocaleManager.isArabic()) "أدخل كلمة المرور." else "Enter the password.")
        else -> null
    }

    fun signUpError(name: String, phone: String, email: String, password: String): String? = when {
        name.trim().isEmpty() -> (if (LocaleManager.isArabic()) "أدخل الاسم الكامل." else "Enter the full name.")
        phone.trim().isEmpty() -> (if (LocaleManager.isArabic()) "أدخل رقم الهاتف." else "Enter the phone number.")
        name.trim().length !in 2..80 -> (if (LocaleManager.isArabic()) "الاسم الكامل يجب أن يكون بين حرفين و80 حرفًا." else "The full name must be between 2 and 80 characters.")
        !Regex("^\\+?[0-9]{7,15}$").matches(phone.replace(Regex("[\\s-]"), "")) -> (if (LocaleManager.isArabic()) "أدخل رقم هاتف من 7 إلى 15 رقمًا." else "Enter a phone number of 7 to 15 digits.")
        !isEmailValid(email) -> (if (LocaleManager.isArabic()) "أدخل بريدًا إلكترونيًا صحيحًا." else "Enter a valid email address.")
        password.isBlank() -> (if (LocaleManager.isArabic()) "أدخل كلمة المرور." else "Enter the password.")
        password.length < 8 -> (if (LocaleManager.isArabic()) "استخدم كلمة مرور من 8 أحرف على الأقل." else "Use a password of at least 8 characters.")
        else -> null
    }

    fun activationCodeError(code: String): String? = when {
        code.trim().isEmpty() -> (if (LocaleManager.isArabic()) "أدخل رمز التفعيل أولًا." else "Enter the activation code first.")
        !Regex("^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}$").matches(normalizeActivationCode(code)) &&
            !Regex("^[0-9A-HJKMNP-TV-Z]{16}$").matches(normalizeActivationCode(code)) -> (if (LocaleManager.isArabic()) "أدخل رمزًا صحيحًا من 16 حرفًا ورقمًا." else "Enter a valid 16-character code.")
        else -> null
    }

    fun normalizeActivationCode(value: String): String = value.trim().uppercase(Locale.ROOT)

    fun filtersError(filters: Filters): String? {
        val values = buildList {
            if (filters.isFareRangeEnabled) addAll(listOf(filters.minFare, filters.maxFare))
            if (filters.isFarePerKmEnabled) add(filters.minFarePerKm)
            if (filters.isPickupDistanceEnabled) add(filters.maxPickupKm)
            if (filters.isTripDistanceEnabled) addAll(listOf(filters.minTripKm, filters.maxTripKm))
            if (filters.highFareOverrideEnabled) add(filters.highFareOverrideAmount)
        }
        if (values.any { !it.isNullOrBlank() && (Text.parseNumber(Text.normalize(it))?.signum() ?: -1) < 0 }) return (if (LocaleManager.isArabic()) "أدخل قيمة صحيحة أكبر من أو تساوي صفرًا." else "Enter a valid value greater than or equal to zero.")
        if (filters.highFareOverrideEnabled && filters.highFareOverrideAmount.isBlank()) return (if (LocaleManager.isArabic()) "أدخل سعرًا صالحًا لتفعيل التجاوز." else "Enter a valid price to enable the override.")
        fun exceeds(min: String?, max: String?): Boolean {
            val low = min?.let { Text.parseNumber(Text.normalize(it)) } ?: return false
            val high = max?.let { Text.parseNumber(Text.normalize(it)) } ?: return false
            return low > high
        }
        fun exceedsMinutes(min: Int?, max: Int?): Boolean = min != null && max != null && min > max
        if ((filters.isFareRangeEnabled && exceeds(filters.minFare, filters.maxFare)) ||
            (filters.isTripDistanceEnabled && exceeds(filters.minTripKm, filters.maxTripKm))) return (if (LocaleManager.isArabic()) "اجعل الحد الأقصى أكبر من أو مساويًا للحد الأدنى." else "Make the maximum greater than or equal to the minimum.")
        if ((filters.isPickupTimeEnabled && exceedsMinutes(filters.minPickupMinutes, filters.maxPickupMinutes)) ||
            (filters.isTripTimeEnabled && exceedsMinutes(filters.minTripMinutes, filters.maxTripMinutes))) return (if (LocaleManager.isArabic()) "اجعل الوقت الأقصى أكبر من أو مساويًا للوقت الأدنى." else "Make the maximum time greater than or equal to the minimum time.")
        if ((filters.isPickupTimeEnabled && listOf(filters.minPickupMinutes, filters.maxPickupMinutes).any { it != null && it < 0 }) ||
            (filters.isTripTimeEnabled && listOf(filters.minTripMinutes, filters.maxTripMinutes).any { it != null && it < 0 })) return (if (LocaleManager.isArabic()) "مدة غير صالحة" else "Invalid duration")
        if (filters.scheduleEnabled && (filters.scheduleStartMinute !in 0..1439 || filters.scheduleEndMinute !in 0..1440)) return (if (LocaleManager.isArabic()) "وقت تشغيل غير صالح" else "Invalid working time")
        return null
    }

    private fun isEmailValid(value: String): Boolean =
        EMAIL_PATTERN.matches(value.trim())

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
}

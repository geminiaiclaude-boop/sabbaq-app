package app.sabbaq.ui

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

    private fun isEmailValid(value: String): Boolean =
        EMAIL_PATTERN.matches(value.trim())

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
}

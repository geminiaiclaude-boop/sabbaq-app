package app.qannas.ui

import app.qannas.core.Filters
import app.qannas.core.PerKmBasis
import app.qannas.core.Text
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/**
 * حالة تحرير فلاتر العرض. تبقى النصوص كما أدخلها السائق حتى تستطيع الشاشة عرض
 * الخطأ نفسه، فيما تبقى الحقول غير المعروضة في [original] عند إعادة بناء العقد.
 * لا يعتمد هذا النموذج على Android أو Compose.
 */
@Serializable
class FilterDraft private constructor(
    val minFare: String,
    val maxFare: String,
    val minFarePerKm: String,
    val maxPickupKm: String,
    val maxPickupMinutes: String,
    val minTripKm: String,
    val maxTripKm: String,
    val maxTripMinutes: String,
    val excludeKeywords: String,
    val scheduleStart: String,
    val scheduleEnd: String,
    val perKmBasis: PerKmBasis,
    val scheduleEnabled: Boolean,
    private val original: Filters,
    private val initialExcludeKeywords: String,
) {
    /**
     * نسخة immutable مناسبة لتحديث حقل واحد من واجهة Compose من دون كشف [original].
     */
    fun copy(
        minFare: String = this.minFare,
        maxFare: String = this.maxFare,
        minFarePerKm: String = this.minFarePerKm,
        maxPickupKm: String = this.maxPickupKm,
        maxPickupMinutes: String = this.maxPickupMinutes,
        minTripKm: String = this.minTripKm,
        maxTripKm: String = this.maxTripKm,
        maxTripMinutes: String = this.maxTripMinutes,
        excludeKeywords: String = this.excludeKeywords,
        scheduleStart: String = this.scheduleStart,
        scheduleEnd: String = this.scheduleEnd,
        perKmBasis: PerKmBasis = this.perKmBasis,
        scheduleEnabled: Boolean = this.scheduleEnabled,
    ): FilterDraft = FilterDraft(
        minFare = minFare,
        maxFare = maxFare,
        minFarePerKm = minFarePerKm,
        maxPickupKm = maxPickupKm,
        maxPickupMinutes = maxPickupMinutes,
        minTripKm = minTripKm,
        maxTripKm = maxTripKm,
        maxTripMinutes = maxTripMinutes,
        excludeKeywords = excludeKeywords,
        scheduleStart = scheduleStart,
        scheduleEnd = scheduleEnd,
        perKmBasis = perKmBasis,
        scheduleEnabled = scheduleEnabled,
        original = original,
        initialExcludeKeywords = initialExcludeKeywords,
    )

    /**
     * يتحقق من المسودة كلها قبل إنشاء Filters. لا يعني عدم وجود مرشح أن المسودة
     * غير صالحة؛ قرار قبول العروض بلا مرشحات مسؤولية وقت التشغيل في OfferEvaluator.
     */
    fun validate(): DraftValidation {
        val errors = linkedMapOf<String, String>()
        val minFareValue = decimal("minFare", minFare, errors)
        val maxFareValue = decimal("maxFare", maxFare, errors)
        decimal("minFarePerKm", minFarePerKm, errors)
        decimal("maxPickupKm", maxPickupKm, errors)
        val maxPickupMinutesValue = nonNegativeInt("maxPickupMinutes", maxPickupMinutes, errors)
        val minTripKmValue = decimal("minTripKm", minTripKm, errors)
        val maxTripKmValue = decimal("maxTripKm", maxTripKm, errors)
        val maxTripMinutesValue = nonNegativeInt("maxTripMinutes", maxTripMinutes, errors)

        if (minFareValue != null && maxFareValue != null && minFareValue > maxFareValue) {
            errors["minFare"] = RANGE_ERROR
            errors["maxFare"] = RANGE_ERROR
        }
        if (minTripKmValue != null && maxTripKmValue != null && minTripKmValue > maxTripKmValue) {
            errors["minTripKm"] = RANGE_ERROR
            errors["maxTripKm"] = RANGE_ERROR
        }
        if (excludeKeywords.length > MAX_KEYWORDS_LENGTH) {
            errors["excludeKeywords"] = "الكلمات المستبعدة أطول من الحد المسموح."
        }

        val schedule = if (scheduleEnabled) {
            val start = scheduleMinute("scheduleStart", scheduleStart, allowEndOfDay = false, errors = errors)
            val end = scheduleMinute("scheduleEnd", scheduleEnd, allowEndOfDay = true, errors = errors)
            if (start != null && end != null) Schedule(start, end) else null
        } else {
            // الحقول مخفية عند التعطيل؛ لا نرفض نصاً قديماً لا يراه السائق.
            null
        }

        if (errors.isNotEmpty()) return DraftValidation(filters = null, errors = errors.toMap())

        return DraftValidation(
            filters = original.copy(
                minFare = optional(minFare),
                maxFare = optional(maxFare),
                minFarePerKm = optional(minFarePerKm),
                perKmBasis = perKmBasis,
                maxPickupKm = optional(maxPickupKm),
                maxPickupMinutes = maxPickupMinutesValue,
                minTripKm = optional(minTripKm),
                maxTripKm = optional(maxTripKm),
                maxTripMinutes = maxTripMinutesValue,
                excludeKeywords = keywordsForFilters(),
                scheduleEnabled = scheduleEnabled,
                scheduleStartMinute = if (scheduleEnabled) schedule!!.start else original.scheduleStartMinute,
                scheduleEndMinute = if (scheduleEnabled) schedule!!.end else original.scheduleEndMinute,
            ),
            errors = emptyMap(),
        )
    }

    /** تكون المسودة المعدلة أو غير الصالحة مختلفة عن Filters المرجعية. */
    fun isDirtyComparedTo(filters: Filters): Boolean = validate().filters?.let { it != filters } ?: true

    private fun keywordsForFilters(): List<String> {
        // إعادة فتح مسودة موجودة لا تفكك كلمة أصلية قد تحتوي فاصلة بالفعل.
        if (excludeKeywords == initialExcludeKeywords) return original.excludeKeywords
        return excludeKeywords
            .split(',', '،', '\n', '\r')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun decimal(field: String, raw: String, errors: MutableMap<String, String>): BigDecimal? {
        val value = Text.normalize(raw)
        if (value.isEmpty()) return null
        if (raw.length > MAX_DECIMAL_LENGTH) {
            errors[field] = "الرقم أطول من الحد المسموح."
            return null
        }
        if (DECIMAL_PATTERN.matchEntire(value) == null) {
            errors[field] = "أدخل رقماً غير سالب صالحاً."
            return null
        }
        val parsed = Text.parseNumber(value)
        if (parsed == null || parsed.signum() < 0) {
            errors[field] = "أدخل رقماً غير سالب صالحاً."
            return null
        }
        return parsed
    }

    private fun nonNegativeInt(field: String, raw: String, errors: MutableMap<String, String>): Int? {
        val value = Text.normalize(raw)
        if (value.isEmpty()) return null
        if (raw.length > MAX_INTEGER_LENGTH) {
            errors[field] = "العدد أطول من الحد المسموح."
            return null
        }
        if (INTEGER_PATTERN.matchEntire(value) == null) {
            errors[field] = "أدخل عدداً صحيحاً غير سالب."
            return null
        }
        return value.toIntOrNull().also {
            if (it == null) errors[field] = "العدد خارج نطاق Int المسموح."
        }
    }

    private fun scheduleMinute(
        field: String,
        raw: String,
        allowEndOfDay: Boolean,
        errors: MutableMap<String, String>,
    ): Int? {
        val value = Text.normalize(raw)
        if (raw.length > MAX_SCHEDULE_LENGTH) {
            errors[field] = "الوقت أطول من الحد المسموح."
            return null
        }
        val match = TIME_PATTERN.matchEntire(value)
        if (match == null) {
            errors[field] = "أدخل وقتاً صالحاً مثل 08:30."
            return null
        }
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour == 24 && minute == 0) {
            if (allowEndOfDay) return MINUTES_PER_DAY
            errors[field] = "24:00 مسموح لوقت النهاية فقط."
            return null
        }
        if (hour !in 0..23 || minute !in 0..59) {
            errors[field] = "أدخل وقتاً صالحاً مثل 08:30."
            return null
        }
        return hour * 60 + minute
    }

    private fun optional(raw: String): String? = raw.takeIf { Text.normalize(it).isNotEmpty() }

    private data class Schedule(val start: Int, val end: Int)

    companion object {
        private const val MAX_DECIMAL_LENGTH = 32
        private const val MAX_INTEGER_LENGTH = 10
        private const val MAX_KEYWORDS_LENGTH = 1_000
        private const val MAX_SCHEDULE_LENGTH = 5
        private const val MINUTES_PER_DAY = 24 * 60
        private const val RANGE_ERROR = "يجب ألا يتجاوز الحد الأدنى الحد الأقصى."

        // Text.NUMBER يدعم ١٬٢٥٠٫٥٠ بعد التطبيع، ويستبعد exponent وNaN بالصيغة الكاملة.
        private val DECIMAL_PATTERN = Regex("(?:${Text.NUMBER})")
        private val INTEGER_PATTERN = Regex("\\d+")
        private val TIME_PATTERN = Regex("(\\d{1,2}):(\\d{2})")

        /** ينشئ مسودة من الإعدادات مع الاحتفاظ بالعقد الكامل لإعادة النسخ بأمان. */
        fun from(filters: Filters): FilterDraft {
            val keywordText = filters.excludeKeywords.joinToString(", ")
            return FilterDraft(
                minFare = filters.minFare.orEmpty(),
                maxFare = filters.maxFare.orEmpty(),
                minFarePerKm = filters.minFarePerKm.orEmpty(),
                maxPickupKm = filters.maxPickupKm.orEmpty(),
                maxPickupMinutes = filters.maxPickupMinutes?.toString().orEmpty(),
                minTripKm = filters.minTripKm.orEmpty(),
                maxTripKm = filters.maxTripKm.orEmpty(),
                maxTripMinutes = filters.maxTripMinutes?.toString().orEmpty(),
                excludeKeywords = keywordText,
                scheduleStart = formatMinute(filters.scheduleStartMinute),
                scheduleEnd = formatMinute(filters.scheduleEndMinute),
                perKmBasis = filters.perKmBasis,
                scheduleEnabled = filters.scheduleEnabled,
                original = filters,
                initialExcludeKeywords = keywordText,
            )
        }

        private fun formatMinute(value: Int): String {
            if (value !in 0..MINUTES_PER_DAY) return value.toString()
            return "${(value / 60).toString().padStart(2, '0')}:${(value % 60).toString().padStart(2, '0')}"
        }
    }
}

/** نتيجة تحقق غير قابلة للتغيير؛ لا تكون [filters] متاحة إلا عند صحة كل الحقول. */
data class DraftValidation(
    val filters: Filters?,
    val errors: Map<String, String>,
) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

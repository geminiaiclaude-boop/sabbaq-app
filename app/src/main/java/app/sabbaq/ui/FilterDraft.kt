package app.sabbaq.ui

import app.sabbaq.core.Filters
import app.sabbaq.core.FilterCombination
import app.sabbaq.core.PerKmBasis
import app.sabbaq.core.Text
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
    val fareRangeEnabled: Boolean,
    val minFarePerKm: String,
    val farePerKmEnabled: Boolean,
    val maxPickupKm: String,
    val pickupDistanceEnabled: Boolean,
    val minPickupMinutes: String,
    val maxPickupMinutes: String,
    val pickupTimeEnabled: Boolean,
    val minTripKm: String,
    val maxTripKm: String,
    val tripDistanceEnabled: Boolean,
    val minTripMinutes: String,
    val maxTripMinutes: String,
    val tripTimeEnabled: Boolean,
    val highFareOverrideEnabled: Boolean,
    val highFareOverrideAmount: String,
    val acceptNoDestination: Boolean,
    val excludeKeywords: String,
    val scheduleStart: String,
    val scheduleEnd: String,
    val perKmBasis: PerKmBasis,
    val scheduleEnabled: Boolean,
    private val original: Filters,
    private val initialExcludeKeywords: String,
    val combination: FilterCombination = FilterCombination.ALL,
) {
    /**
     * نسخة immutable مناسبة لتحديث حقل واحد من واجهة Compose من دون كشف [original].
     */
    fun copy(
        minFare: String = this.minFare,
        maxFare: String = this.maxFare,
        fareRangeEnabled: Boolean = this.fareRangeEnabled,
        minFarePerKm: String = this.minFarePerKm,
        farePerKmEnabled: Boolean = this.farePerKmEnabled,
        maxPickupKm: String = this.maxPickupKm,
        pickupDistanceEnabled: Boolean = this.pickupDistanceEnabled,
        minPickupMinutes: String = this.minPickupMinutes,
        maxPickupMinutes: String = this.maxPickupMinutes,
        pickupTimeEnabled: Boolean = this.pickupTimeEnabled,
        minTripKm: String = this.minTripKm,
        maxTripKm: String = this.maxTripKm,
        tripDistanceEnabled: Boolean = this.tripDistanceEnabled,
        minTripMinutes: String = this.minTripMinutes,
        maxTripMinutes: String = this.maxTripMinutes,
        tripTimeEnabled: Boolean = this.tripTimeEnabled,
        highFareOverrideEnabled: Boolean = this.highFareOverrideEnabled,
        highFareOverrideAmount: String = this.highFareOverrideAmount,
        acceptNoDestination: Boolean = this.acceptNoDestination,
        excludeKeywords: String = this.excludeKeywords,
        scheduleStart: String = this.scheduleStart,
        scheduleEnd: String = this.scheduleEnd,
        perKmBasis: PerKmBasis = this.perKmBasis,
        scheduleEnabled: Boolean = this.scheduleEnabled,
        combination: FilterCombination = this.combination,
    ): FilterDraft = FilterDraft(
        minFare = minFare,
        maxFare = maxFare,
        fareRangeEnabled = fareRangeEnabled,
        minFarePerKm = minFarePerKm,
        farePerKmEnabled = farePerKmEnabled,
        maxPickupKm = maxPickupKm,
        pickupDistanceEnabled = pickupDistanceEnabled,
        minPickupMinutes = minPickupMinutes,
        maxPickupMinutes = maxPickupMinutes,
        pickupTimeEnabled = pickupTimeEnabled,
        minTripKm = minTripKm,
        maxTripKm = maxTripKm,
        tripDistanceEnabled = tripDistanceEnabled,
        minTripMinutes = minTripMinutes,
        maxTripMinutes = maxTripMinutes,
        tripTimeEnabled = tripTimeEnabled,
        highFareOverrideEnabled = highFareOverrideEnabled,
        highFareOverrideAmount = highFareOverrideAmount,
        acceptNoDestination = acceptNoDestination,
        excludeKeywords = excludeKeywords,
        scheduleStart = scheduleStart,
        scheduleEnd = scheduleEnd,
        perKmBasis = perKmBasis,
        scheduleEnabled = scheduleEnabled,
        original = original,
        initialExcludeKeywords = initialExcludeKeywords,
        combination = combination,
    )

    /**
     * يتحقق من المسودة كلها قبل إنشاء Filters. لا يعني عدم وجود مرشح أن المسودة
     * غير صالحة؛ قرار قبول العروض بلا مرشحات مسؤولية وقت التشغيل في OfferEvaluator.
     */
    fun validate(): DraftValidation {
        val errors = linkedMapOf<String, String>()
        val minFareValue = decimal("minFare", minFare, errors, fareRangeEnabled)
        val maxFareValue = decimal("maxFare", maxFare, errors, fareRangeEnabled)
        decimal("minFarePerKm", minFarePerKm, errors, farePerKmEnabled)
        decimal("maxPickupKm", maxPickupKm, errors, pickupDistanceEnabled)
        val highFareOverrideValue = decimal("highFareOverrideAmount", highFareOverrideAmount, errors, highFareOverrideEnabled)
        if (highFareOverrideEnabled && highFareOverrideValue == null && highFareOverrideAmount.isBlank()) {
            errors["highFareOverrideAmount"] = "أدخل سعرًا صالحًا لتفعيل التجاوز."
        }
        val minPickupMinutesValue = if (pickupTimeEnabled) nonNegativeInt("minPickupMinutes", minPickupMinutes, errors)
            else inactiveInt(minPickupMinutes, original.minPickupMinutes)
        val maxPickupMinutesValue = if (pickupTimeEnabled) nonNegativeInt("maxPickupMinutes", maxPickupMinutes, errors)
            else inactiveInt(maxPickupMinutes, original.maxPickupMinutes)
        val minTripKmValue = decimal("minTripKm", minTripKm, errors, tripDistanceEnabled)
        val maxTripKmValue = decimal("maxTripKm", maxTripKm, errors, tripDistanceEnabled)
        val minTripMinutesValue = if (tripTimeEnabled) nonNegativeInt("minTripMinutes", minTripMinutes, errors)
            else inactiveInt(minTripMinutes, original.minTripMinutes)
        val maxTripMinutesValue = if (tripTimeEnabled) nonNegativeInt("maxTripMinutes", maxTripMinutes, errors)
            else inactiveInt(maxTripMinutes, original.maxTripMinutes)

        if (fareRangeEnabled && minFareValue != null && maxFareValue != null && minFareValue > maxFareValue) {
            errors["minFare"] = RANGE_ERROR
            errors["maxFare"] = RANGE_ERROR
        }
        if (tripDistanceEnabled && minTripKmValue != null && maxTripKmValue != null && minTripKmValue > maxTripKmValue) {
            errors["minTripKm"] = RANGE_ERROR
            errors["maxTripKm"] = RANGE_ERROR
        }
        if (pickupTimeEnabled && minPickupMinutesValue != null && maxPickupMinutesValue != null && minPickupMinutesValue > maxPickupMinutesValue) {
            errors["minPickupMinutes"] = RANGE_ERROR
            errors["maxPickupMinutes"] = RANGE_ERROR
        }
        if (tripTimeEnabled && minTripMinutesValue != null && maxTripMinutesValue != null && minTripMinutesValue > maxTripMinutesValue) {
            errors["minTripMinutes"] = RANGE_ERROR
            errors["maxTripMinutes"] = RANGE_ERROR
        }
        if (excludeKeywords.length > MAX_KEYWORDS_LENGTH) {
            errors["excludeKeywords"] = "الكلمات المستبعدة أطول من الحد المسموح."
        }

        val schedule = if (scheduleEnabled) {
            val start = scheduleMinute("scheduleStart", scheduleStart, allowEndOfDay = false, errors = errors)
            val end = scheduleMinute("scheduleEnd", scheduleEnd, allowEndOfDay = true, errors = errors)
            if (start != null && end != null) Schedule(start, end) else null
        } else {
            // الحقول مخفية عند التعطيل؛ لا نرفض نصًا قديمًا لا يراه السائق.
            null
        }

        if (errors.isNotEmpty()) return DraftValidation(filters = null, errors = errors.toMap())

        return DraftValidation(
            filters = original.copy(
                minFare = optional(minFare),
                maxFare = optional(maxFare),
                fareRangeEnabled = fareRangeEnabled,
                minFarePerKm = optional(minFarePerKm),
                farePerKmEnabled = farePerKmEnabled,
                perKmBasis = perKmBasis,
                maxPickupKm = optional(maxPickupKm),
                pickupDistanceEnabled = pickupDistanceEnabled,
                minPickupMinutes = minPickupMinutesValue,
                maxPickupMinutes = maxPickupMinutesValue,
                pickupTimeEnabled = pickupTimeEnabled,
                minTripKm = optional(minTripKm),
                maxTripKm = optional(maxTripKm),
                tripDistanceEnabled = tripDistanceEnabled,
                minTripMinutes = minTripMinutesValue,
                maxTripMinutes = maxTripMinutesValue,
                tripTimeEnabled = tripTimeEnabled,
                highFareOverrideEnabled = highFareOverrideEnabled,
                highFareOverrideAmount = highFareOverrideAmount,
                acceptNoDestination = acceptNoDestination,
                excludeKeywords = keywordsForFilters(),
                scheduleEnabled = scheduleEnabled,
                scheduleStartMinute = if (scheduleEnabled) schedule!!.start else original.scheduleStartMinute,
                scheduleEndMinute = if (scheduleEnabled) schedule!!.end else original.scheduleEndMinute,
                combination = combination,
            ),
            errors = emptyMap(),
        )
    }

    /** تكون المسودة المعدلة أو غير الصالحة مختلفة عن Filters المرجعية. */
    fun isDirtyComparedTo(filters: Filters): Boolean = validate().filters?.let { it != normalizeLegacySwitches(filters) } ?: true

    private fun normalizeLegacySwitches(filters: Filters): Filters = filters.copy(
        fareRangeEnabled = filters.isFareRangeEnabled,
        farePerKmEnabled = filters.isFarePerKmEnabled,
        pickupDistanceEnabled = filters.isPickupDistanceEnabled,
        pickupTimeEnabled = filters.isPickupTimeEnabled,
        tripDistanceEnabled = filters.isTripDistanceEnabled,
        tripTimeEnabled = filters.isTripTimeEnabled,
    )

    private fun keywordsForFilters(): List<String> {
        // إعادة فتح مسودة موجودة لا تفكك كلمة أصلية قد تحتوي فاصلة بالفعل.
        if (excludeKeywords == initialExcludeKeywords) return original.excludeKeywords
        return excludeKeywords
            .split(',', '،', '\n', '\r')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    private fun decimal(field: String, raw: String, errors: MutableMap<String, String>, enabled: Boolean): BigDecimal? {
        if (!enabled) return null
        val value = Text.normalize(raw)
        if (value.isEmpty()) return null
        if (raw.length > MAX_DECIMAL_LENGTH) {
            errors[field] = "الرقم أطول من الحد المسموح."
            return null
        }
        if (DECIMAL_PATTERN.matchEntire(value) == null) {
            errors[field] = "أدخل رقمًا غير سالب صالحًا."
            return null
        }
        val parsed = Text.parseNumber(value)
        if (parsed == null || parsed.signum() < 0) {
            errors[field] = "أدخل رقمًا غير سالب صالحًا."
            return null
        }
        return parsed
    }

    private fun inactiveInt(raw: String, previous: Int?): Int? {
        val value = Text.normalize(raw)
        if (value.isEmpty()) return null
        return value.toIntOrNull()?.takeIf { it >= 0 } ?: previous
    }

    private fun nonNegativeInt(field: String, raw: String, errors: MutableMap<String, String>): Int? {
        val value = Text.normalize(raw)
        if (value.isEmpty()) return null
        if (raw.length > MAX_INTEGER_LENGTH) {
            errors[field] = "العدد أطول من الحد المسموح."
            return null
        }
        if (INTEGER_PATTERN.matchEntire(value) == null) {
            errors[field] = "أدخل عددًا صحيحًا غير سالب."
            return null
        }
        return value.toIntOrNull().also {
            if (it == null) errors[field] = "العدد أكبر من الحد المسموح."
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
            errors[field] = "أدخل وقتًا صالحًا مثل 08:30."
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
            errors[field] = "أدخل وقتًا صالحًا مثل 08:30."
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
                fareRangeEnabled = filters.isFareRangeEnabled,
                minFarePerKm = filters.minFarePerKm.orEmpty(),
                farePerKmEnabled = filters.isFarePerKmEnabled,
                maxPickupKm = filters.maxPickupKm.orEmpty(),
                pickupDistanceEnabled = filters.isPickupDistanceEnabled,
                minPickupMinutes = filters.minPickupMinutes?.toString().orEmpty(),
                maxPickupMinutes = filters.maxPickupMinutes?.toString().orEmpty(),
                pickupTimeEnabled = filters.isPickupTimeEnabled,
                minTripKm = filters.minTripKm.orEmpty(),
                maxTripKm = filters.maxTripKm.orEmpty(),
                tripDistanceEnabled = filters.isTripDistanceEnabled,
                minTripMinutes = filters.minTripMinutes?.toString().orEmpty(),
                maxTripMinutes = filters.maxTripMinutes?.toString().orEmpty(),
                tripTimeEnabled = filters.isTripTimeEnabled,
                highFareOverrideEnabled = filters.highFareOverrideEnabled,
                highFareOverrideAmount = filters.highFareOverrideAmount,
                acceptNoDestination = filters.acceptNoDestination,
                excludeKeywords = keywordText,
                scheduleStart = formatMinute(filters.scheduleStartMinute),
                scheduleEnd = formatMinute(filters.scheduleEndMinute),
                perKmBasis = filters.perKmBasis,
                scheduleEnabled = filters.scheduleEnabled,
                original = filters,
                initialExcludeKeywords = keywordText,
                combination = filters.combination,
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

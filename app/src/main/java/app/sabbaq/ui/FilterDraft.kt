package app.sabbaq.ui

import app.sabbaq.LocaleManager
import app.sabbaq.core.engine.DestinationMode
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.LogicMode
import app.sabbaq.core.engine.PaymentMode
import app.sabbaq.core.engine.TargetApp
import app.sabbaq.core.engine.WaitSaveMode
import kotlinx.serialization.Serializable

/**
 * What the driver is typing on the conditions page: FareSniper's settings as text fields
 * (docs/engine-parity/QANNAS_ENGINE_FACTS.md §16). An empty limit means "no limit" (0), exactly
 * like FareSniper, so a fresh install accepts every offer until the driver sets one.
 */
@Serializable
data class FilterDraft(
    val priceEnabled: Boolean = true,
    val minPrice: String = "",
    val maxPrice: String = "",
    val timeEnabled: Boolean = true,
    val minTime: String = "",
    val maxTime: String = "",
    val logicOr: Boolean = false,
    val destCheckEnabled: Boolean = false,
    val destKeywords: String = "",
    val destIncludeRejectOnUnknown: Boolean = true,
    val destExcludeEnabled: Boolean = false,
    val destExcludeKeywords: String = "",
    val destExcludeRejectOnUnknown: Boolean = false,
    val excludeMode: Boolean = false,
    val snipingNoDest: Boolean = true,
    val bypassTimeEnabled: Boolean = false,
    val bypassTimePrice: String = "",
    val targetApp: TargetApp = TargetApp.JEENY,
    val turboEnabled: Boolean = false,
    val clickX: String = "",
    val clickY: String = "",
    val ratingEnabled: Boolean = false,
    val minRating: String = "",
    val ratingRejectOnUnknown: Boolean = false,
    val excludeWaitSave: Boolean = false,
    val paymentMode: PaymentMode = PaymentMode.ALL,
    val paymentRejectOnUnknown: Boolean = false,
    val smartDelayEnabled: Boolean = false,
    val smartDelayMin: String = "",
    val smartDelayMax: String = "",
) {
    /** [base] supplies the fields this page does not edit (the extra keyword lists). */
    fun validate(base: EngineConfig = EngineConfig()): DraftValidation {
        val errors = linkedMapOf<String, String>()
        val minP = decimal("minPrice", minPrice, errors)
        val maxP = decimal("maxPrice", maxPrice, errors)
        val minT = whole("minTime", minTime, errors)
        val maxT = whole("maxTime", maxTime, errors)
        val bypass = decimal("bypassTimePrice", bypassTimePrice, errors)
        val dMin = whole("smartDelayMin", smartDelayMin, errors)
        val dMax = whole("smartDelayMax", smartDelayMax, errors)
        val rating = decimal("minRating", minRating, errors)
        val cx = whole("clickX", clickX, errors)
        val cy = whole("clickY", clickY, errors)
        if (turboEnabled && (cx == null || cy == null)) { errors["clickX"] = needPoint(); errors["clickY"] = needPoint() }
        if (rating != null && rating > 5.0) errors["minRating"] = invalid()
        if (minP != null && maxP != null && maxP > 0 && minP > maxP) { errors["minPrice"] = rangeError(); errors["maxPrice"] = rangeError() }
        if (minT != null && maxT != null && maxT > 0 && minT > maxT) { errors["minTime"] = rangeError(); errors["maxTime"] = rangeError() }
        if (dMin != null && dMax != null && dMin > dMax) { errors["smartDelayMin"] = rangeError(); errors["smartDelayMax"] = rangeError() }
        if (errors.isNotEmpty()) return DraftValidation(null, errors)
        return DraftValidation(EngineConfig(
            minPrice = minP ?: 0.0, maxPrice = maxP ?: 0.0, minTime = minT ?: 0, maxTime = maxT ?: 0,
            priceEnabled = priceEnabled, timeEnabled = timeEnabled,
            logicMode = if (logicOr) LogicMode.OR else LogicMode.AND,
            destCheckEnabled = destCheckEnabled, destKeywords = destKeywords.trim(),
            destHomeMode = if (excludeMode) DestinationMode.EXCLUDE else DestinationMode.INCLUDE,
            destExcludeEnabled = destExcludeEnabled, destExcludeKeywords = destExcludeKeywords.trim(),
            destIncludeRejectOnUnknown = destIncludeRejectOnUnknown, destExcludeRejectOnUnknown = destExcludeRejectOnUnknown,
            snipingNoDest = snipingNoDest,
            bypassTimeEnabled = bypassTimeEnabled, bypassTimePrice = bypass ?: 0.0,
            paymentFilterMode = paymentMode, paymentRejectOnUnknown = paymentRejectOnUnknown,
            ratingFilterEnabled = ratingEnabled, minRating = rating ?: 0.0,
            ratingRejectOnUnknown = ratingRejectOnUnknown,
            waitSaveFilterMode = if (excludeWaitSave) WaitSaveMode.EXCLUDE else WaitSaveMode.OFF,
            targetApp = targetApp,
            turboModeEnabled = turboEnabled, clickX = cx, clickY = cy,
            smartDelayEnabled = smartDelayEnabled, smartDelayMinMs = dMin ?: 0, smartDelayMaxMs = dMax ?: 0,
            acceptKeywordsExtra = base.acceptKeywordsExtra, timeKeywordsExtra = base.timeKeywordsExtra,
            currencyKeywordsExtra = base.currencyKeywordsExtra, noiseKeywordsExtra = base.noiseKeywordsExtra,
        ), errors)
    }

    fun isDirtyComparedTo(saved: EngineConfig): Boolean = validate(saved).config?.let { it != saved } ?: true

    /** How many conditions are switched on, for the dashboard summary. */
    fun activeCount(): Int = listOf(
        priceEnabled && (minPrice.isNotBlank() || maxPrice.isNotBlank()),
        timeEnabled && (minTime.isNotBlank() || maxTime.isNotBlank()),
        destCheckEnabled && !excludeMode,
        destExcludeEnabled && excludeMode,
        bypassTimeEnabled,
        paymentMode != PaymentMode.ALL,
        ratingEnabled && minRating.isNotBlank(),
        excludeWaitSave,
    ).count { it }

    companion object {
        fun from(c: EngineConfig) = FilterDraft(
            priceEnabled = c.priceEnabled, minPrice = number(c.minPrice), maxPrice = number(c.maxPrice),
            timeEnabled = c.timeEnabled, minTime = whole(c.minTime), maxTime = whole(c.maxTime),
            logicOr = c.logicMode == LogicMode.OR,
            destCheckEnabled = c.destCheckEnabled, destKeywords = c.destKeywords,
            destIncludeRejectOnUnknown = c.destIncludeRejectOnUnknown,
            destExcludeEnabled = c.destExcludeEnabled, destExcludeKeywords = c.destExcludeKeywords,
            destExcludeRejectOnUnknown = c.destExcludeRejectOnUnknown,
            excludeMode = c.destHomeMode == DestinationMode.EXCLUDE, snipingNoDest = c.snipingNoDest,
            bypassTimeEnabled = c.bypassTimeEnabled, bypassTimePrice = number(c.bypassTimePrice),
            paymentMode = c.paymentFilterMode, paymentRejectOnUnknown = c.paymentRejectOnUnknown,
            targetApp = c.targetApp, turboEnabled = c.turboModeEnabled,
            clickX = c.clickX?.toString().orEmpty(), clickY = c.clickY?.toString().orEmpty(),
            ratingEnabled = c.ratingFilterEnabled,
            minRating = if (c.minRating <= 0.0) "" else c.minRating.toString(),
            ratingRejectOnUnknown = c.ratingRejectOnUnknown, excludeWaitSave = c.waitSaveFilterMode == WaitSaveMode.EXCLUDE,
            smartDelayEnabled = c.smartDelayEnabled, smartDelayMin = whole(c.smartDelayMinMs), smartDelayMax = whole(c.smartDelayMaxMs),
        )

        private fun number(v: Double): String = if (v <= 0.0) "" else if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        private fun whole(v: Int): String = if (v <= 0) "" else v.toString()
        private fun rangeError() = if (LocaleManager.isArabic()) "يجب ألا يتجاوز الحد الأدنى الحد الأقصى." else "The minimum must not exceed the maximum."
        private fun needPoint() = if (LocaleManager.isArabic()) "حدد موضع زر القبول أولًا." else "Set the accept button position first."
        private fun invalid() = if (LocaleManager.isArabic()) "اكتب رقمًا صحيحًا موجبًا أو اتركه فارغًا." else "Enter a positive number or leave it empty."

        private fun latin(raw: String): String = raw.trim().map { if (it in '٠'..'٩') '0' + (it - '٠') else if (it == '٫') '.' else it }.joinToString("")

        private fun decimal(field: String, raw: String, errors: MutableMap<String, String>): Double? {
            if (raw.isBlank()) return null
            val v = latin(raw).replace(',', '.').toDoubleOrNull()
            if (v == null || v < 0 || v.isNaN() || v.isInfinite() || v > 100_000) { errors[field] = invalid(); return null }
            return v
        }

        private fun whole(field: String, raw: String, errors: MutableMap<String, String>): Int? {
            if (raw.isBlank()) return null
            val v = latin(raw).toIntOrNull()
            if (v == null || v < 0 || v > 100_000) { errors[field] = invalid(); return null }
            return v
        }
    }
}

data class DraftValidation(val config: EngineConfig?, val errors: Map<String, String>) {
    val isValid: Boolean get() = config != null
}

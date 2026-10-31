package app.sabbaq.ui

import app.sabbaq.LocaleManager
import app.sabbaq.core.engine.DestinationMode
import app.sabbaq.core.engine.EngineConfig
import app.sabbaq.core.engine.LogicMode
import app.sabbaq.core.engine.PaymentMode
import app.sabbaq.core.engine.TargetApp
import app.sabbaq.core.engine.zones.ZoneGeo
import app.sabbaq.core.engine.WaitSaveMode
import kotlinx.serialization.Serializable

/**
 * What the driver is typing on the conditions page: the reference engine's settings as text fields
 * (reference engine notes). An empty limit means "no limit" (0), exactly
 * like the reference engine, so a fresh install accepts every offer until the driver sets one.
 */
@Serializable
data class FilterDraft(
    val priceEnabled: Boolean = false,
    val minPrice: String = "",
    val maxPrice: String = "",
    val timeEnabled: Boolean = false,
    val minTime: String = "",
    val maxTime: String = "",
    val logicOr: Boolean = false,
    val destCheckEnabled: Boolean = false,
    val destKeywords: String = "",
    val destIncludeRejectOnUnknown: Boolean = false,
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
    val zonesEnabled: Boolean = false,
    val zonesJson: String = "[]",
    val zonesRejectOnUnknown: Boolean = false,
    val zonesOnTheWay: Boolean = false,
    val excludeZonesEnabled: Boolean = false,
    val excludeZonesJson: String = "[]",
    val excludeZonesRejectOnUnknown: Boolean = false,
    /** The driver switched the administration’s shared exclude zones off for themselves. */
    val globalZonesOptOut: Boolean = false,
    val smartDestActive: Boolean = false,
    val smartDestLat: Double = 0.0,
    val smartDestLng: Double = 0.0,
    val smartDestAngle: String = "45",
    /** Fine zone settings; blank means the engine default shown as the hint. */
    val zonesBearingEnabled: Boolean = true,
    val zonesBearingMargin: String = "25",
    val travelAngleTol: String = "20",
    val travelOvershootKm: String = "2",
    val corridorHalfWidthKm: String = "1.5",
    val excludeProximityKm: String = "60",
) {
    /** Entering a limit is the driver switching its condition on; nothing is on by itself (owner, 2026-10-30). */
    fun withPriceLimits(min: String = minPrice, max: String = maxPrice): FilterDraft =
        copy(minPrice = min, maxPrice = max, priceEnabled = priceEnabled || min.isNotBlank() || max.isNotBlank())

    fun withTimeLimits(min: String = minTime, max: String = maxTime): FilterDraft =
        copy(minTime = min, maxTime = max, timeEnabled = timeEnabled || min.isNotBlank() || max.isNotBlank())

    val homeSet: Boolean get() = !(smartDestLat == 0.0 && smartDestLng == 0.0)

    /** [base] supplies the fields this page does not edit (extra keyword lists, zones, smart destination). */
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
        val angle = whole("smartDestAngle", smartDestAngle, errors)
        if (smartDestActive && (angle == null || angle !in 1..180)) errors["smartDestAngle"] = invalid()
        val bearingMargin = ranged("zonesBearingMargin", zonesBearingMargin, 25.0, 5.0, 90.0, errors)
        val angleTol = ranged("travelAngleTol", travelAngleTol, 20.0, 5.0, 90.0, errors)
        val overshoot = ranged("travelOvershootKm", travelOvershootKm, 2.0, 0.5, 20.0, errors)
        val corridor = ranged("corridorHalfWidthKm", corridorHalfWidthKm, 1.5, 0.2, 10.0, errors)
        val proximity = ranged("excludeProximityKm", excludeProximityKm, 60.0, 5.0, 300.0, errors)
        if (smartDestActive && !homeSet) errors["smartDest"] = needHome()
        if (zonesEnabled && !ZoneGeo.hasDrawn(zonesJson)) errors["zones"] = needZone()
        if (excludeZonesEnabled && !ZoneGeo.hasDrawn(excludeZonesJson)) errors["excludeZones"] = needZone()
        val cx = whole("clickX", clickX, errors)
        val cy = whole("clickY", clickY, errors)
        if (turboEnabled && (cx == null || cy == null)) { errors["clickX"] = needPoint(); errors["clickY"] = needPoint() }
        if (rating != null && rating > 5.0) errors["minRating"] = invalid()
        if (minP != null && maxP != null && maxP > 0 && minP > maxP) { errors["minPrice"] = rangeError(); errors["maxPrice"] = rangeError() }
        if (minT != null && maxT != null && maxT > 0 && minT > maxT) { errors["minTime"] = rangeError(); errors["maxTime"] = rangeError() }
        if (dMin != null && dMax != null && dMin > dMax) { errors["smartDelayMin"] = rangeError(); errors["smartDelayMax"] = rangeError() }
        if (errors.isNotEmpty()) return DraftValidation(null, errors)
        return DraftValidation(base.copy(
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
            destZonesEnabled = zonesEnabled, destZonesJson = zonesJson, destZonesRejectOnUnknown = zonesRejectOnUnknown,
            destZonesOnTheWayEnabled = zonesOnTheWay,
            destExcludeZonesEnabled = excludeZonesEnabled, destExcludeZonesJson = excludeZonesJson,
            destExcludeZonesRejectOnUnknown = excludeZonesRejectOnUnknown, globalDestExcludeOptOut = globalZonesOptOut,
            smartDestActive = smartDestActive, smartDestLat = smartDestLat, smartDestLng = smartDestLng,
            smartDestAngle = (angle ?: 45).toDouble(),
            destZonesBearingEnabled = zonesBearingEnabled, destZonesBearingMarginDeg = bearingMargin,
            travelAngleTolDeg = angleTol, travelOvershootKm = overshoot, corridorHalfWidthKm = corridor,
            destExcludeProximityMaxKm = proximity,
        ), errors)
    }

    fun isDirtyComparedTo(saved: EngineConfig): Boolean = validate(saved).config?.let { it != saved } ?: true

    /** How many conditions are switched on, for the dashboard summary. */
    fun activeCount(): Int = listOf(
        priceEnabled && (minPrice.isNotBlank() || maxPrice.isNotBlank()),
        timeEnabled && (minTime.isNotBlank() || maxTime.isNotBlank()),
        destCheckEnabled && !excludeMode && destKeywords.isNotBlank(),
        destExcludeEnabled && excludeMode && destExcludeKeywords.isNotBlank(),
        bypassTimeEnabled,
        paymentMode != PaymentMode.ALL,
        ratingEnabled && minRating.isNotBlank(),
        excludeWaitSave,
        zonesEnabled,
        excludeZonesEnabled,
        smartDestActive,
    ).count { it }

    companion object {
        /**
         * «ابدأ» needs at least one condition the driver set (owner: the setup guide's
         * conditions step became a requirement of starting). Nothing is on at first install, so
         * a new driver is taken to the conditions before monitoring can accept anything.
         */
        fun canStartWith(c: EngineConfig): Boolean = from(c).activeCount() > 0

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
            zonesEnabled = c.destZonesEnabled, zonesJson = c.destZonesJson, zonesRejectOnUnknown = c.destZonesRejectOnUnknown,
            zonesOnTheWay = c.destZonesOnTheWayEnabled, excludeZonesEnabled = c.destExcludeZonesEnabled,
            excludeZonesJson = c.destExcludeZonesJson, excludeZonesRejectOnUnknown = c.destExcludeZonesRejectOnUnknown,
            globalZonesOptOut = c.globalDestExcludeOptOut,
            smartDestActive = c.smartDestActive, smartDestLat = c.smartDestLat, smartDestLng = c.smartDestLng,
            smartDestAngle = c.smartDestAngle.toInt().toString(),
            zonesBearingEnabled = c.destZonesBearingEnabled, zonesBearingMargin = plain(c.destZonesBearingMarginDeg),
            travelAngleTol = plain(c.travelAngleTolDeg), travelOvershootKm = plain(c.travelOvershootKm),
            corridorHalfWidthKm = plain(c.corridorHalfWidthKm), excludeProximityKm = plain(c.destExcludeProximityMaxKm),
        )

        private fun number(v: Double): String = if (v <= 0.0) "" else if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        private fun whole(v: Int): String = if (v <= 0) "" else v.toString()
        private fun needHome() = if (LocaleManager.isArabic()) "حدد نقطة الوجهة على الخريطة." else "Set the destination point on the map."
        private fun needZone() = if (LocaleManager.isArabic()) "ارسم منطقة واحدة على الأقل على الخريطة." else "Draw at least one zone on the map."
        private fun rangeError() = if (LocaleManager.isArabic()) "يجب ألا يتجاوز الحد الأدنى الحد الأقصى." else "The minimum must not exceed the maximum."
        private fun needPoint() = if (LocaleManager.isArabic()) "حدد موضع زر القبول أولًا." else "Set the accept button position first."
        /** 25.0 -> "25", 1.5 -> "1.5". */
        private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

        /** Blank means [default]; otherwise a number inside [min]..[max]. */
        private fun ranged(field: String, raw: String, default: Double, min: Double, max: Double, errors: MutableMap<String, String>): Double {
            if (raw.isBlank()) return default
            val v = decimal(field, raw, errors) ?: return default
            if (v < min || v > max) {
                errors[field] = if (LocaleManager.isArabic()) "اختر قيمة بين ${plain(min)} و${plain(max)}." else "Choose a value between ${plain(min)} and ${plain(max)}."
                return default
            }
            return v
        }

        private fun invalid() = if (LocaleManager.isArabic()) "اكتب رقمًا صحيحًا موجبًا أو اتركه فارغًا." else "Enter a positive number or leave it empty."

        private fun latin(raw: String): String = app.sabbaq.core.engine.JeenyLexicon.latinDigits(raw.trim()).replace('٫', '.')

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

package app.sabbaq.core.engine

import kotlinx.serialization.Serializable

@Serializable
enum class LogicMode { AND, OR }

@Serializable
enum class DestinationMode { INCLUDE, EXCLUDE }

/** The reference engine `paymentFilterMode`: all, cash only, card only. */
@Serializable
enum class PaymentMode { ALL, CASH, CARD }

/** The reference engine `waitSaveFilterMode`: off, or exclude "wait & save" (economy) offers (Bolt). */
@Serializable
enum class WaitSaveMode { OFF, EXCLUDE }

/**
 * The driver's settings, field for field the subset of the reference engine's EngineConfig that its
 * decision and reader use (reference engine notes). Defaults are the reference engine's:
 * price and time checks are on but unbounded (0 = no limit), so every offer is accepted until
 * the driver sets a limit. Missing screen data never rejects unless a reject-on-unknown switch
 * is on.
 */
@Serializable
data class EngineConfig(
    val minPrice: Double = 0.0,
    val maxPrice: Double = 0.0,
    val minTime: Int = 0,
    val maxTime: Int = 0,
    val priceEnabled: Boolean = true,
    val timeEnabled: Boolean = true,
    val logicMode: LogicMode = LogicMode.AND,
    /** The reference engine `dest_check_enabled` with `dest_home_mode` include. */
    val destCheckEnabled: Boolean = false,
    val destKeywords: String = "",
    val destHomeMode: DestinationMode = DestinationMode.INCLUDE,
    val destExcludeEnabled: Boolean = false,
    val destExcludeKeywords: String = "",
    val destIncludeRejectOnUnknown: Boolean = true,
    val destExcludeRejectOnUnknown: Boolean = false,
    /** Accept offers whose destination cannot be read. */
    val snipingNoDest: Boolean = true,
    val bypassTimeEnabled: Boolean = false,
    val bypassTimePrice: Double = 0.0,
    val paymentFilterMode: PaymentMode = PaymentMode.ALL,
    val paymentRejectOnUnknown: Boolean = false,
    val ratingFilterEnabled: Boolean = false,
    val minRating: Double = 0.0,
    val ratingRejectOnUnknown: Boolean = false,
    val waitSaveFilterMode: WaitSaveMode = WaitSaveMode.OFF,
    val smartDelayEnabled: Boolean = false,
    val smartDelayMinMs: Int = 0,
    val smartDelayMaxMs: Int = 0,
    val acceptKeywordsExtra: String = "",
    val timeKeywordsExtra: String = "",
    val currencyKeywordsExtra: String = "",
    val noiseKeywordsExtra: String = "",
    val targetApp: TargetApp = TargetApp.JEENY,
    /** The reference engine turbo: tap the saved point the instant an offer word appears. */
    val turboModeEnabled: Boolean = false,
    val clickX: Int? = null,
    val clickY: Int? = null,
    /** Geographic zones (the reference engine `dest_zones_*`): accept only destinations inside these polygons. */
    val destZonesEnabled: Boolean = false,
    val destZonesJson: String = "[]",
    val destZonesRejectOnUnknown: Boolean = true,
    val destZonesBearingEnabled: Boolean = true,
    val destZonesBearingMarginDeg: Double = 25.0,
    val destZonesOnTheWayEnabled: Boolean = false,
    val travelAngleTolDeg: Double = 20.0,
    val travelOvershootKm: Double = 2.0,
    val corridorHalfWidthKm: Double = 1.5,
    val destZonesRouteApiBudgetMs: Long = 0L,
    /** Exclude zones: reject destinations inside these polygons. */
    val destExcludeZonesEnabled: Boolean = false,
    val destExcludeZonesJson: String = "[]",
    val destExcludeZonesRejectOnUnknown: Boolean = false,
    val destExcludeProximityMaxKm: Double = 60.0,
    val destExcludeBearingMarginDeg: Double = 30.0,
    /** Shared exclude zones published to every driver; [globalDestExcludeOptOut] ignores them. */
    val globalDestExcludeZonesJson: String = "[]",
    val globalDestExcludeOptOut: Boolean = false,
    val globalZoneProximityKm: Double = 0.0,
    /** Smart destination ("heading home"): accept only destinations within [smartDestAngle]° of this point's bearing. */
    val smartDestActive: Boolean = false,
    val smartDestLat: Double = 0.0,
    val smartDestLng: Double = 0.0,
    val smartDestAngle: Double = 45.0,
) {
    /** The reference engine `priceEnabled && priceFilterEnabled` (the second flag is always on in its UI). */
    val priceFilterOn: Boolean get() = priceEnabled
    val timeFilterOn: Boolean get() = timeEnabled
    val excludeOn: Boolean get() = destHomeMode == DestinationMode.EXCLUDE && destExcludeEnabled
    val includeOn: Boolean get() = destHomeMode == DestinationMode.INCLUDE && destCheckEnabled

    /**
     * The reference engine `areAnyFiltersActive`: price or time checks switched on, a destination rule, or
     * offers without a destination refused. Turbo's instant tap runs only when none is.
     */
    val anyFilterActive: Boolean get() = priceFilterOn || timeFilterOn || destCheckEnabled || !snipingNoDest

    /** Turbo can tap blind: it is on and a point is saved. */
    val hasBlindCoords: Boolean get() = turboModeEnabled && clickX != null && clickY != null

    /** The instant (before any read) turbo tap of `handleJeenyEvent`. */
    val instantTurbo: Boolean get() = hasBlindCoords && !anyFilterActive

    /** `Pk.execute(config)`: destination rules need the whole tree, never the subtree or view-id shortcut. */
    val needsFullTree: Boolean get() = destCheckEnabled || excludeOn || zoneStageOn

    /** Any geographic rule is switched on, so the decision takes the full path (b9 fast-path guard). */
    val zoneStageOn: Boolean get() = smartDestActive || destZonesEnabled || destExcludeZonesEnabled

    /** The reader records map markers for the direction check (`Pk`: bearing on and some zone list in use). */
    val markersWanted: Boolean get() = destZonesBearingEnabled && (destZonesEnabled ||
        (destExcludeZonesEnabled && destExcludeZonesJson.length > 2) || (!globalDestExcludeOptOut && globalDestExcludeZonesJson.length > 2))

    fun lexicon(): JeenyLexicon = JeenyLexicon(acceptKeywordsExtra, timeKeywordsExtra, currencyKeywordsExtra, noiseKeywordsExtra)
}

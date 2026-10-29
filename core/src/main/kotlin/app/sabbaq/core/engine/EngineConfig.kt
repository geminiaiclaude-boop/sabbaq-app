package app.sabbaq.core.engine

import kotlinx.serialization.Serializable

@Serializable
enum class LogicMode { AND, OR }

@Serializable
enum class DestinationMode { INCLUDE, EXCLUDE }

/** FareSniper `paymentFilterMode`: all, cash only, card only. */
@Serializable
enum class PaymentMode { ALL, CASH, CARD }

/** FareSniper `waitSaveFilterMode`: off, or exclude "wait & save" (economy) offers (Bolt). */
@Serializable
enum class WaitSaveMode { OFF, EXCLUDE }

/**
 * The driver's settings, field for field the subset of FareSniper's EngineConfig that its
 * decision and reader use (QANNAS_ENGINE_FACTS.md §3, §14, §16). Defaults are FareSniper's:
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
    /** FareSniper `dest_check_enabled` with `dest_home_mode` include. */
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
) {
    /** FareSniper `priceEnabled && priceFilterEnabled` (the second flag is always on in its UI). */
    val priceFilterOn: Boolean get() = priceEnabled
    val timeFilterOn: Boolean get() = timeEnabled
    val excludeOn: Boolean get() = destHomeMode == DestinationMode.EXCLUDE && destExcludeEnabled
    val includeOn: Boolean get() = destHomeMode == DestinationMode.INCLUDE && destCheckEnabled

    /** `Pk.execute(config)`: destination rules need the whole tree, never the subtree or view-id shortcut. */
    val needsFullTree: Boolean get() = destCheckEnabled || excludeOn

    fun lexicon(): JeenyLexicon = JeenyLexicon(acceptKeywordsExtra, timeKeywordsExtra, currencyKeywordsExtra, noiseKeywordsExtra)
}

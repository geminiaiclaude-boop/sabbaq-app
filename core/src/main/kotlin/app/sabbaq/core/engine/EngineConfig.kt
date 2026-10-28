package app.sabbaq.core.engine

import kotlinx.serialization.Serializable

@Serializable
enum class LogicMode { AND, OR }

@Serializable
enum class DestinationMode { INCLUDE, EXCLUDE }

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
    val smartDelayEnabled: Boolean = false,
    val smartDelayMinMs: Int = 0,
    val smartDelayMaxMs: Int = 0,
    val acceptKeywordsExtra: String = "",
    val timeKeywordsExtra: String = "",
    val currencyKeywordsExtra: String = "",
    val noiseKeywordsExtra: String = "",
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

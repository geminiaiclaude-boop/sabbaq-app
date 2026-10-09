package app.qannas.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = right <= left || bottom <= top

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * Immutable copy of one accessibility node. The engine never touches the
 * platform node type, which keeps parsing pure and unit-testable.
 */
@Serializable
data class ScreenNode(
    val id: Int,
    val text: String? = null,
    val description: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean = false,
    val bounds: Bounds = Bounds.EMPTY,
    val children: List<ScreenNode> = emptyList(),
) {
    fun walk(visit: (ScreenNode) -> Unit) {
        visit(this)
        children.forEach { it.walk(visit) }
    }
}

@Serializable
enum class DistanceRole { PICKUP, TRIP }

@Serializable
enum class FareSelect { FIRST, MIN, MAX }

/**
 * Describes how to read one ride-hailing app. Profiles ship inside the APK and
 * are replaced by newer versions from the server, so a changed ride-app layout
 * is fixed without a store release.
 */
@Serializable
data class RideProfile(
    val id: String,
    val name: String,
    val packages: List<String>,
    val acceptLabels: List<String>,
    val acceptViewIds: List<String> = emptyList(),
    val confirmLabels: List<String> = emptyList(),
    val currencyTokens: List<String>,
    val kmUnits: List<String> = listOf("km", "كم", "كلم"),
    val meterUnits: List<String> = listOf("m", "متر", "مترا", "أمتار"),
    val minuteUnits: List<String> = listOf("min", "mins", "minute", "minutes", "دقيقة", "دقائق", "دقيقه", "د"),
    val hourUnits: List<String> = listOf("h", "hr", "hrs", "hour", "hours", "ساعة", "ساعات", "س"),
    val distanceOrder: List<DistanceRole> = listOf(DistanceRole.PICKUP, DistanceRole.TRIP),
    val singleDistanceRole: DistanceRole = DistanceRole.TRIP,
    val minuteOrder: List<DistanceRole> = listOf(DistanceRole.PICKUP, DistanceRole.TRIP),
    val singleMinuteRole: DistanceRole = DistanceRole.PICKUP,
    val fareSelect: FareSelect = FareSelect.FIRST,
    val addressCues: List<String> = emptyList(),
    val version: Int = 1,
    val enabled: Boolean = true,
)

data class Offer(
    val profileId: String,
    val fare: BigDecimal?,
    val currency: String?,
    val pickupKm: BigDecimal?,
    val tripKm: BigDecimal?,
    val pickupMinutes: Int?,
    val tripMinutes: Int?,
    val addresses: List<String>,
    val texts: List<String>,
) {
    /**
     * Identity that survives countdown timers ticking on screen, so one offer
     * is clicked once however many frames it is seen in.
     */
    val fingerprint: String
        get() = listOf(
            profileId,
            fare?.stripTrailingZeros()?.toPlainString(),
            tripKm?.stripTrailingZeros()?.toPlainString(),
            pickupKm?.stripTrailingZeros()?.toPlainString(),
            addresses.joinToString("|"),
        ).joinToString("#")
}

data class ParsedScreen(
    val offer: Offer?,
    val acceptNode: ScreenNode?,
    val confirmed: Boolean,
)

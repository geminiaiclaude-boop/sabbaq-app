package app.sabbaq.ui

/**
 * Starting points for the distance and time conditions. A preset only rewrites the
 * pickup/trip fields it owns; fare, keywords and working hours stay as the driver set
 * them. The screen auto-saves a valid preset immediately after it is selected.
 * Presets deliberately carry no fare values — pricing is the driver's own decision.
 */
internal enum class FilterPreset(
    val label: String,
    val description: String,
    val maxPickupKm: String = "",
    val minPickupMinutes: String = "",
    val maxPickupMinutes: String = "",
    val minTripKm: String = "",
    val maxTripKm: String = "",
    val minTripMinutes: String = "",
    val maxTripMinutes: String = "",
) {
    NearAndQuick(
        label = "قريب وسريع",
        description = "وصول خلال 3 كم و7 دقائق، ورحلة حتى 15 كم.",
        maxPickupKm = "3", maxPickupMinutes = "7", maxTripKm = "15",
    ),
    LongTrips(
        label = "مشاوير طويلة",
        description = "رحلة من 15 كم فأكثر، ووصول حتى 6 كم.",
        maxPickupKm = "6", minTripKm = "15",
    ),
    ClosePickupOnly(
        label = "وصول قريب فقط",
        description = "أي رحلة، بشرط وصول خلال 2 كم و5 دقائق.",
        maxPickupKm = "2", maxPickupMinutes = "5",
    );

    fun applyTo(draft: FilterDraft): FilterDraft = draft.copy(
        maxPickupKm = maxPickupKm,
        minPickupMinutes = minPickupMinutes,
        pickupDistanceEnabled = maxPickupKm.isNotBlank(),
        maxPickupMinutes = maxPickupMinutes,
        pickupTimeEnabled = minPickupMinutes.isNotBlank() || maxPickupMinutes.isNotBlank(),
        minTripKm = minTripKm,
        maxTripKm = maxTripKm,
        tripDistanceEnabled = minTripKm.isNotBlank() || maxTripKm.isNotBlank(),
        minTripMinutes = minTripMinutes,
        maxTripMinutes = maxTripMinutes,
        tripTimeEnabled = minTripMinutes.isNotBlank() || maxTripMinutes.isNotBlank(),
    )

    /** True when the draft's distance/time fields are exactly this preset, so the chip shows as selected. */
    fun matches(draft: FilterDraft): Boolean =
        draft.maxPickupKm.trim() == maxPickupKm && draft.maxPickupMinutes.trim() == maxPickupMinutes &&
            draft.pickupDistanceEnabled == maxPickupKm.isNotBlank() &&
            draft.minPickupMinutes.trim() == minPickupMinutes &&
            draft.pickupTimeEnabled == (minPickupMinutes.isNotBlank() || maxPickupMinutes.isNotBlank()) &&
            draft.minTripKm.trim() == minTripKm && draft.maxTripKm.trim() == maxTripKm &&
            draft.tripDistanceEnabled == (minTripKm.isNotBlank() || maxTripKm.isNotBlank()) &&
            draft.minTripMinutes.trim() == minTripMinutes && draft.maxTripMinutes.trim() == maxTripMinutes &&
            draft.tripTimeEnabled == (minTripMinutes.isNotBlank() || maxTripMinutes.isNotBlank())
}

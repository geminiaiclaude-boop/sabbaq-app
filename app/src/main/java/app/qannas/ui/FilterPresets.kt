package app.qannas.ui

/**
 * Starting points for the distance and time conditions. A preset only rewrites the
 * pickup/trip fields it owns; fare, keywords and working hours stay as the driver set
 * them. Nothing is saved: the result is a draft the driver reviews and saves explicitly.
 * Presets deliberately carry no fare values — pricing is the driver's own decision.
 */
internal enum class FilterPreset(
    val label: String,
    val description: String,
    val maxPickupKm: String = "",
    val maxPickupMinutes: String = "",
    val minTripKm: String = "",
    val maxTripKm: String = "",
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
        maxPickupMinutes = maxPickupMinutes,
        minTripKm = minTripKm,
        maxTripKm = maxTripKm,
        maxTripMinutes = maxTripMinutes,
    )

    /** True when the draft's distance/time fields are exactly this preset, so the chip shows as selected. */
    fun matches(draft: FilterDraft): Boolean =
        draft.maxPickupKm.trim() == maxPickupKm && draft.maxPickupMinutes.trim() == maxPickupMinutes &&
            draft.minTripKm.trim() == minTripKm && draft.maxTripKm.trim() == maxTripKm &&
            draft.maxTripMinutes.trim() == maxTripMinutes
}

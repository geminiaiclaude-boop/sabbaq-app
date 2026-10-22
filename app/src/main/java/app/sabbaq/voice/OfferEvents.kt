package app.sabbaq.voice

import java.math.BigDecimal

/**
 * The one entry point for «تم قبول طلب» announcements. The acceptance engine
 * (SabbaqA11yService) calls this only after the matching confirmation screen is observed.
 *
 */
object OfferEvents {
    const val ENGINE_REPORTS_ACCEPTS = true

    fun accepted(fare: BigDecimal?, pickupKm: BigDecimal?) {
        VoiceAnnouncer.announce(VoiceKind.Offer, VoiceLines.offerAccepted(fare, pickupKm))
    }
}

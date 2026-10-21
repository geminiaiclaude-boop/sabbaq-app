package app.sabbaq.voice

import java.math.BigDecimal

/**
 * The one entry point for «تم قبول طلب» announcements. The acceptance engine
 * (SabbaqA11yService) is owned by the product owner and does not call this yet, so the
 * option stays hidden from drivers until it does. To enable it:
 *
 *   1. In SabbaqA11yService, where the accept gesture is dispatched, report a dispatched tap:
 *        if (!performClick(acceptNode.bounds)) { Log.w(...) }
 *        else app.sabbaq.voice.OfferEvents.accepted(offer.fare, offer.pickupKm)
 *   2. Set [ENGINE_REPORTS_ACCEPTS] to true.
 */
object OfferEvents {
    const val ENGINE_REPORTS_ACCEPTS = false

    fun accepted(fare: BigDecimal?, pickupKm: BigDecimal?) {
        VoiceAnnouncer.announce(VoiceKind.Offer, VoiceLines.offerAccepted(fare, pickupKm))
    }
}

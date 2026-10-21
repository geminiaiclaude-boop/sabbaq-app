package app.qannas.ui

import app.qannas.network.PublicOffer
import java.net.URLEncoder

/**
 * Where «اشترك» takes the driver. The administration's WhatsApp number (set from the admin
 * panel) comes first: a chat opens with a ready message stating the price and length, and the
 * administration replies with an activation code. A configured purchase page is the fallback.
 * Null means the server offers no purchase channel, and the UI must not show a purchase button.
 */
internal object PurchaseLink {
    private val whatsappDigits = Regex("^[1-9][0-9]{7,14}$")

    fun whatsappMessage(offer: PublicOffer): String =
        "مرحبًا، أرغب في الاشتراك في قناص (${CustomerCommerceCopy.offerSummary(offer)}). أرجو إرسال طريقة الدفع ورمز التفعيل."

    fun whatsappUrl(number: String, offer: PublicOffer): String? = chatUrl(number, whatsappMessage(offer))

    /** A chat with the administration about moving an account to a new device. */
    fun supportChatUrl(number: String): String? =
        chatUrl(number, "مرحبًا، أحتاج إلى نقل حسابي في قناص إلى جهاز جديد.")

    private fun chatUrl(number: String, message: String): String? {
        if (!whatsappDigits.matches(number)) return null
        val text = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        return "https://wa.me/$number?text=$text"
    }

    fun target(offer: PublicOffer?): Target? {
        offer ?: return null
        offer.adminWhatsapp?.let { number -> whatsappUrl(number, offer)?.let { return Target(it, viaWhatsapp = true) } }
        return offer.purchaseContactUrl?.let { Target(it, viaWhatsapp = false) }
    }

    data class Target(val url: String, val viaWhatsapp: Boolean)
}

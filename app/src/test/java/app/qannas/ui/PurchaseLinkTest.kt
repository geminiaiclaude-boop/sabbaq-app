package app.qannas.ui

import app.qannas.network.PublicOffer
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseLinkTest {
    private val offer = PublicOffer(
        trialEnabled = true,
        trialDays = 3,
        priceMinor = 1875,
        currency = "SAR",
        subscriptionDays = 30,
    )

    @Test
    fun whatsapp_number_opens_a_chat_with_the_price_and_length() {
        val target = PurchaseLink.target(offer.copy(adminWhatsapp = "966512345678"))!!
        assertTrue(target.viaWhatsapp)
        assertTrue(target.url.startsWith("https://wa.me/966512345678?text="))
        val text = URLDecoder.decode(target.url.substringAfter("text="), "UTF-8")
        assertTrue(text.contains("18.75 ر.س"))
        assertTrue(text.contains("30 يومًا"))
        assertFalse("spaces must be %20, not +", target.url.contains("+"))
    }

    @Test
    fun whatsapp_wins_over_a_purchase_page() {
        val target = PurchaseLink.target(offer.copy(adminWhatsapp = "966512345678", purchaseContactUrl = "https://pay.example.test/x"))!!
        assertTrue(target.viaWhatsapp)
    }

    @Test
    fun purchase_page_is_used_when_no_number_is_set() {
        val target = PurchaseLink.target(offer.copy(purchaseContactUrl = "https://pay.example.test/x"))!!
        assertFalse(target.viaWhatsapp)
        assertEquals("https://pay.example.test/x", target.url)
    }

    @Test
    fun no_channel_means_no_purchase_button() {
        assertNull(PurchaseLink.target(offer))
        assertNull(PurchaseLink.target(null))
        assertNull(PurchaseLink.whatsappUrl("0512345678", offer))
    }
}

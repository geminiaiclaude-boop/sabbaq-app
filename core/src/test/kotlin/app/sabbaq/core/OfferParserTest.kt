package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class OfferParserTest {
    private val profiles = Profiles.bundled().associateBy { it.id }
    private var nextId = 0

    private fun node(text: String? = null, desc: String? = null, clickable: Boolean = false, viewId: String? = null) =
        ScreenNode(id = nextId++, text = text, description = desc, clickable = clickable, viewId = viewId)

    private fun screen(vararg children: ScreenNode) = ScreenNode(id = nextId++, children = children.toList())

    @Test
    fun bundledProfilesCoverTheTargetApps() {
        val packages = profiles.values.flatMap { it.packages }
        assertTrue("ee.mtakso.driver" in packages)
        assertTrue("me.com.easytaxista" in packages)
        assertTrue("com.PetraRide_Captain" in packages)
    }

    @Test
    fun englishBoltOfferIsReadCompletely() {
        val root = screen(
            node("JD 4.25"),
            node("3 min • 1.2 km"),
            node("Mecca Street, Amman"),
            node("18 min • 9.6 km"),
            node("Gardens Street"),
            node("Accept", clickable = true),
        )
        val parsed = OfferParser(profiles.getValue("bolt")).parse(root)
        val offer = parsed.offer!!
        assertEquals(BigDecimal("4.25"), offer.fare)
        assertEquals(BigDecimal("1.2"), offer.pickupKm)
        assertEquals(BigDecimal("9.6"), offer.tripKm)
        assertEquals(3, offer.pickupMinutes)
        assertEquals(18, offer.tripMinutes)
        assertEquals("accept", Text.normalize(parsed.acceptNode!!.text))
    }

    @Test
    fun acceptedControlRetainsItsLiveTreePath() {
        val accept = ScreenNode(id = 3, text = "Accept", clickable = true, nodePath = listOf(2, 1))
        val root = screen(node("JD 4"), node("8 km"), accept)
        val parsed = OfferParser(profiles.getValue("bolt")).parse(root)
        assertEquals(listOf(2, 1), parsed.acceptNode?.nodePath)
    }

    @Test
    fun arabicJeenyOfferWithIndicDigitsAndSplitCurrency() {
        val root = screen(
            node("٥٫٥٠"),
            node("د.أ"),
            node("يبعد ٤ دقائق - ٨٥٠ متر"),
            node("شارع الجامعة"),
            node("٢٢ دقيقة - ١٢٫٤ كم"),
            node("قبول الطلب", clickable = true),
        )
        val offer = OfferParser(profiles.getValue("jeeny")).parse(root).offer!!
        assertEquals(BigDecimal("5.50"), offer.fare)
        assertEquals(0, BigDecimal("0.850").compareTo(offer.pickupKm))
        assertEquals(BigDecimal("12.4"), offer.tripKm)
        assertEquals(4, offer.pickupMinutes)
        assertEquals(22, offer.tripMinutes)
        assertTrue(offer.addresses.contains("شارع الجامعة"))
    }

    @Test
    fun hoursAndMinutesInOneLabelAreCombined() {
        val root = screen(node("SAR 60"), node("1 h 15 min"), node("Accept", clickable = true))
        val offer = OfferParser(profiles.getValue("bolt")).parse(root).offer!!
        assertEquals(75, offer.pickupMinutes)
    }

    @Test
    fun screenWithoutAcceptControlIsNotAnOffer() {
        val root = screen(node("JD 4.25"), node("Navigate"), node("End ride"))
        val parsed = OfferParser(profiles.getValue("bolt")).parse(root)
        assertNull(parsed.offer)
        assertTrue(parsed.confirmed)
    }

    @Test
    fun acceptedStateLabelIsNotMistakenForTheButton() {
        val root = screen(node("Ride accepted"), node("JD 3"))
        assertNull(OfferParser(profiles.getValue("bolt")).parse(root).offer)
    }

    @Test
    fun acceptButtonFoundByViewIdWhenUnlabelled() {
        val profile = profiles.getValue("bolt").copy(acceptViewIds = listOf("ee.mtakso.driver:id/accept"))
        val root = screen(node("JD 2"), node(viewId = "ee.mtakso.driver:id/accept", clickable = true))
        val parsed = OfferParser(profile).parse(root)
        assertNotNull(parsed.offer)
        assertEquals("ee.mtakso.driver:id/accept", parsed.acceptNode!!.viewId)
    }

    @Test
    fun countdownDoesNotChangeTheFingerprint() {
        val parser = OfferParser(profiles.getValue("bolt"))
        val a = parser.parse(screen(node("JD 4"), node("2 km"), node("9 km"), node("Mecca Street"), node("Accept (12)", clickable = true))).offer!!
        val b = parser.parse(screen(node("JD 4"), node("2 km"), node("9 km"), node("Mecca Street"), node("Accept (11)", clickable = true))).offer!!
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun minutesTokenDoesNotSwallowMetres() {
        val root = screen(node("JD 3"), node("5 min"), node("Accept", clickable = true))
        val offer = OfferParser(profiles.getValue("bolt")).parse(root).offer!!
        assertNull(offer.pickupKm)
        assertNull(offer.tripKm)
        assertEquals(5, offer.pickupMinutes)
        assertFalse(offer.fare == null)
    }

    @Test
    fun staticAcceptTextIsNotAnActionableOffer() {
        val parsed = OfferParser(profiles.getValue("bolt")).parse(screen(node("JD 4"), node("Accept")))
        assertNull(parsed.offer)
        assertNull(parsed.acceptNode)
    }

    @Test
    fun confirmationStateSuppressesAnOtherwiseActionableOffer() {
        val parsed = OfferParser(profiles.getValue("bolt")).parse(
            screen(node("JD 4"), node("Navigate"), node("Accept", clickable = true)),
        )
        assertTrue(parsed.confirmed)
        assertNull(parsed.offer)
        assertNotNull(parsed.acceptNode)
    }

    @Test
    fun emptyCurrencyConfigurationDoesNotTreatAnyNumberAsFare() {
        val profile = profiles.getValue("bolt").copy(currencyTokens = emptyList())
        val offer = OfferParser(profile).parse(screen(node("4.25"), node("Accept", clickable = true))).offer!!
        assertNull(offer.fare)
        assertNull(offer.currency)
    }

    @Test
    fun multipleHourMinutePairsPreserveBothDurations() {
        val offer = OfferParser(profiles.getValue("bolt")).parse(
            screen(node("JD 60"), node("1 h 15 min; 2 h 10 min"), node("Accept", clickable = true)),
        ).offer!!
        assertEquals(75, offer.pickupMinutes)
        assertEquals(130, offer.tripMinutes)
    }

    @Test
    fun unrepresentableDurationIsLeftUnreadableInsteadOfOverflowing() {
        val offer = OfferParser(profiles.getValue("bolt")).parse(
            screen(node("JD 60"), node("2147483648 min"), node("Accept", clickable = true)),
        ).offer!!
        assertNull(offer.pickupMinutes)
        assertNull(offer.tripMinutes)
    }
}

package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * The Jeeny offer card as it appears on a driver's phone in Saudi Arabia (October 2026): pickup
 * time and distance only, net earnings under «ستربح» with the new riyal sign, and «قبول العرض».
 * Before this fix it scored 7/8 (no fare: the sign was unknown; no pickup cue: «يبعُد» carries a
 * damma) and was dropped as incomplete, and its single distance was taken as the trip distance.
 */
class JeenyFieldOfferTest {
    private val jeeny = Profiles.bundled().first { it.id == "jeeny" }
    private var nextId = 0

    private fun node(text: String? = null, desc: String? = null, clickable: Boolean = false, children: List<ScreenNode> = emptyList()) =
        ScreenNode(id = nextId++, text = text, description = desc, clickable = clickable, children = children)

    private fun offerCard(vararg fare: ScreenNode, mapMarkers: Boolean = false) = node(children = buildList {
        if (mapMarkers) { add(node("4 دقائق")); add(node("2 دقائق")) }
        add(node(clickable = true, desc = "إغلاق"))
        add(node("مشوار داخل المدينة"))
        add(node("يبعُد 4 دقائق"))
        add(node("يبعُد 1.5كم"))
        add(node("ستربح"))
        addAll(fare)
        add(node("صافي الأرباح بعد الرسوم وضريبة القيمة المضافة"))
        add(node("Al-Rehab - Abdullah Bin Al-Ashhab,"))
        add(node("Near 3253, Awwad Al Qabidi"))
        add(node(clickable = true, children = listOf(node("قبول العرض"))))
    })

    private fun assertReadable(root: ScreenNode) {
        val assessment = JeenyFrameClassifier(jeeny).assess(root)
        assertEquals(assessment.flags, JeenyFrameClass.OFFER, assessment.classification)
        val parser = OfferParser(jeeny)
        assertNotNull("quick signal must pass", parser.probeOfferSignal(root))
        val parsed = parser.parse(root)
        val offer = parsed.offer!!
        assertEquals(0, BigDecimal("5.84").compareTo(offer.fare))
        assertEquals(0, BigDecimal("1.5").compareTo(offer.pickupKm))
        assertEquals(4, offer.pickupMinutes)
        assertNull("Jeeny does not show the trip distance", offer.tripKm)
        assertNotNull(parsed.acceptNode)
    }

    @Test
    fun `riyal sign as a character`() = assertReadable(offerCard(node("5.84 ⃁")))

    @Test
    fun `riyal sign drawn as an icon next to the amount`() =
        assertReadable(offerCard(node("5.84"), node(desc = null)))

    @Test
    fun `riyal sign from an icon font`() = assertReadable(offerCard(node("5.84")))

    @Test
    fun `map time labels do not hide the pickup values`() {
        val root = offerCard(node("5.84 ⃁"), mapMarkers = true)
        val offer = OfferParser(jeeny).parse(root).offer!!
        assertEquals(4, offer.pickupMinutes)
        assertEquals(0, BigDecimal("1.5").compareTo(offer.pickupKm))
        assertEquals(0, BigDecimal("5.84").compareTo(offer.fare))
    }

    @Test
    fun `diacritics never decide a match`() {
        assertEquals("يبعد", Text.normalize("يبعُد"))
        assertEquals("جار التوصيل", Text.normalize("جارٍ التوصيل"))
    }
}

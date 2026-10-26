package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JeenyOfferPipelineTest {
    private val profile = Profiles.bundled().first { it.id == "jeeny" }
    private var id = 0

    private fun node(text: String? = null, clickable: Boolean = false, viewId: String? = null) =
        ScreenNode(id++, text = text, clickable = clickable, viewId = viewId)

    private fun screen(vararg children: ScreenNode) = ScreenNode(id++, children = children.toList())

    @Test
    fun positiveFrameNeedsIndependentFareRouteAndActionSignals() {
        val parser = OfferParser(profile)
        val signal = parser.probeOfferSignal(screen(
            node("طلب رحلة"), node("SAR 5.50"), node("يبعد ٤ دقائق - ٨٥٠ متر"),
            node("شارع الجامعة"), node("قبول العرض", clickable = true),
        ))
        assertNotNull(signal)
        assertTrue(signal!!.score >= JeenyFrameClassifier.OFFER_THRESHOLD)
        assertTrue(signal.featureCount >= JeenyFrameClassifier.MIN_INDEPENDENT_FEATURES)
        assertEquals(JeenyOfferSignal.Evidence.LABELLED_CONTROL_WITH_ROUTE, signal.evidence)
    }

    @Test
    fun progressiveFrameIsObservedButNeverProducesActionableSignal() {
        val classifier = JeenyFrameClassifier(profile)
        val assessment = classifier.assess(screen(node("SAR 5.50"), node("قبول العرض", clickable = true)))
        assertEquals(JeenyFrameClass.INCOMPLETE, assessment.classification)
        assertNull(OfferParser(profile).probeOfferSignal(screen(node("SAR 5.50"), node("قبول العرض", clickable = true))))
    }

    @Test
    fun confirmationAndActiveRideAreNeverOfferSignals() {
        val parser = OfferParser(profile)
        val root = screen(node("SAR 5.50"), node("جارٍ التوصيل"), node("قبول العرض", clickable = true))
        assertEquals(JeenyFrameClass.CONFIRMED_OR_ACTIVE, JeenyFrameClassifier(profile).assess(root).classification)
        assertNull(parser.probeOfferSignal(root))
    }

    @Test
    fun offerCardShowingPickupLocationIsStillAnOffer() {
        // Regression: "موقع الإقلال" and "navigate" were strong confirmation cues, so an offer
        // card that labels its pickup line was classified as an active ride and never accepted.
        val root = screen(
            node("طلب رحلة"), node("SAR 5.50"), node("يبعد ٤ دقائق - ٨٥٠ متر"),
            node("موقع الإقلال"), node("شارع الجامعة"), node("قبول العرض", clickable = true),
        )
        assertEquals(JeenyFrameClass.OFFER, JeenyFrameClassifier(profile).assess(root).classification)
        val parsed = OfferParser(profile).parse(root)
        assertFalse(parsed.confirmed)
        assertNotNull(parsed.offer)
    }

    @Test
    fun weakCueConfirmsOnlyOnceTheAcceptControlIsGone() {
        val root = screen(node("موقع الإقلال"), node("شارع الجامعة"), node("عرض الاتجاهات", clickable = true))
        assertTrue(OfferParser(profile).parse(root).confirmed)
        assertEquals(JeenyFrameClass.CONFIRMED_OR_ACTIVE, JeenyFrameClassifier(profile).assess(root).classification)
    }

    @Test
    fun petraDeclineButtonDoesNotHideTheOffer() {
        val petra = Profiles.bundled().first { it.id == "petra" }
        val parsed = OfferParser(petra).parse(screen(
            node("JD 3.25"), node("2.4 km"), node("شارع المدينة"), node("إلغاء", clickable = true), node("قبول", clickable = true),
        ))
        assertFalse(parsed.confirmed)
        assertNotNull(parsed.offer)
    }

    @Test
    fun hintOnlyOrUnrelatedFrameCannotPass() {
        val parser = OfferParser(profile, setOf("me.com.easytaxista:id/accept"))
        val root = screen(node("Home"), node(clickable = true, viewId = "me.com.easytaxista:id/accept"))
        assertNull(parser.probeOfferSignal(root))
        assertEquals(JeenyFrameClass.NONE, JeenyFrameClassifier(profile, setOf("me.com.easytaxista:id/accept")).assess(root).classification)
    }

    @Test
    fun classifierHasHardScanAndTextBudgets() {
        val children = (0 until JeenyFrameClassifier.MAX_NODES + 50).map { node("n$it") }.toTypedArray()
        val assessment = JeenyFrameClassifier(profile).assess(screen(*children))
        assertEquals(JeenyFrameClassifier.MAX_NODES, assessment.scannedNodes)
        assertEquals(JeenyFrameClass.NONE, assessment.classification)
        assertTrue(assessment.flags.endsWith("cap1"))
    }

    @Test
    fun offerCardBelowALargeMapTreeIsStillRecognised() {
        // Regression: the classifier used to stop after 128 nodes, so a card rendered under
        // a map/list of a few hundred nodes was classified NONE and never reached the parser.
        val noise = (0 until 400).map { node("map-tile-$it") }
        val card = listOf(
            node("طلب رحلة"), node("SAR 5.50"), node("يبعد ٤ دقائق - ٨٥٠ متر"),
            node("شارع الجامعة"), node("قبول العرض", clickable = true),
        )
        val root = screen(*(noise + card).toTypedArray())
        val assessment = JeenyFrameClassifier(profile).assess(root)
        assertEquals(JeenyFrameClass.OFFER, assessment.classification)
        assertNotNull(OfferParser(profile).probeOfferSignal(root))
        assertTrue(assessment.flags.startsWith("a1,f1,"))
        assertTrue(assessment.flags.contains(",c0,"))
        assertTrue(assessment.flags.endsWith("cap0"))
    }

    @Test
    fun flagsExplainAMissWithoutAnyScreenText() {
        val assessment = JeenyFrameClassifier(profile).assess(screen(node("SAR 5.50"), node("قبول العرض", clickable = true)))
        assertTrue(assessment.flags, assessment.flags.matches(Regex("a1,f1,d0,t0,p0,o[01],c0,n3,cap0")))
        assertFalse(assessment.flags.contains("5.50"))
    }

    @Test
    fun stateSuppressesSameOfferAndBoundsLateReads() {
        val pipeline = JeenyOfferPipeline()
        val token = pipeline.begin("me.com.easytaxista", "7")
        assertTrue(pipeline.isCurrent(token))
        assertTrue(pipeline.allowLateCapture())
        assertTrue(pipeline.markLateCapture())
        assertTrue(pipeline.markLateCapture())
        assertFalse(pipeline.markLateCapture())
        assertTrue(pipeline.shouldProcess("offer-1"))
        pipeline.remember("offer-1")
        assertFalse(pipeline.shouldProcess("offer-1"))
        assertTrue(pipeline.shouldProcess("offer-2"))
        pipeline.markActionPending()
        assertFalse(pipeline.shouldProcess("offer-2"))
        assertFalse(pipeline.allowLateCapture())
    }

    @Test
    fun everyEventBurstGetsItsOwnLateReadBudgetInTheSameWindow() {
        val pipeline = JeenyOfferPipeline()
        pipeline.begin("me.com.easytaxista", "7")
        assertTrue(pipeline.markLateCapture())
        assertTrue(pipeline.markLateCapture())
        assertFalse(pipeline.markLateCapture())
        pipeline.begin("me.com.easytaxista", "7")
        pipeline.beginBurst()
        assertTrue(pipeline.markLateCapture())
    }

    @Test
    fun windowChangeCancelsOldGenerationAndResetsIdentity() {
        val pipeline = JeenyOfferPipeline()
        val first = pipeline.begin("me.com.easytaxista", "1")
        pipeline.remember("offer-1")
        val second = pipeline.begin("me.com.easytaxista", "2")
        assertFalse(pipeline.isCurrent(first))
        assertTrue(pipeline.isCurrent(second))
        assertTrue(pipeline.shouldProcess("offer-1"))
    }
}

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
    fun hintOnlyOrUnrelatedFrameCannotPass() {
        val parser = OfferParser(profile, setOf("me.com.easytaxista:id/accept"))
        val root = screen(node("Home"), node(clickable = true, viewId = "me.com.easytaxista:id/accept"))
        assertNull(parser.probeOfferSignal(root))
        assertEquals(JeenyFrameClass.NONE, JeenyFrameClassifier(profile, setOf("me.com.easytaxista:id/accept")).assess(root).classification)
    }

    @Test
    fun classifierHasHardScanAndTextBudgets() {
        val children = (0 until 200).map { node("noise-$it") }.toTypedArray()
        val assessment = JeenyFrameClassifier(profile).assess(screen(*children))
        assertEquals(JeenyFrameClassifier.MAX_NODES, assessment.scannedNodes)
        assertEquals(JeenyFrameClass.NONE, assessment.classification)
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

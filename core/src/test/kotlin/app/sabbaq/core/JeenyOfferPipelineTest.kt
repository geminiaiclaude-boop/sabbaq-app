package app.sabbaq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JeenyOfferPipelineTest {
    private val profile = Profiles.bundled().first { it.id == "jeeny" }
    private var id = 0

    private fun node(text: String? = null, clickable: Boolean = false, viewId: String? = null) =
        ScreenNode(id++, text = text, clickable = clickable, viewId = viewId)

    @Test
    fun signalRequiresOfferDataAndAcceptLabel() {
        val parser = OfferParser(profile)
        assertEquals(null, parser.probeOfferSignal(ScreenNode(0, children = listOf(node("قبول العرض", true)))))
        val signal = parser.probeOfferSignal(ScreenNode(1, children = listOf(node("SAR 5.50"), node("قبول العرض", true))))
        assertNotNull(signal)
    }

    @Test
    fun learnedIdDoesNotProveOfferWithoutCurrentLabel() {
        val parser = OfferParser(profile, setOf("me.com.easytaxista:id/accept"))
        val root = ScreenNode(0, children = listOf(node("SAR 5.50"), node(clickable = true, viewId = "me.com.easytaxista:id/accept")))
        assertEquals(null, parser.probeOfferSignal(root))
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

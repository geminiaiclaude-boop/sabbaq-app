package app.sabbaq.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSketchTest {
    private val jeeny = Profiles.bundled().first { it.id == "jeeny" }
    private var nextId = 0

    private fun node(text: String? = null, clickable: Boolean = false, viewId: String? = null, className: String? = null, children: List<ScreenNode> = emptyList()) =
        ScreenNode(id = nextId++, text = text, clickable = clickable, viewId = viewId, className = className, children = children)

    @Test
    fun `structure and profile words stay, personal text is masked`() {
        val root = node(children = listOf(
            node("27.50 ر.س", className = "android.widget.TextView"),
            node("محمد العتيبي"),
            node("شارع الملك فهد، حي العليا"),
            node("1.4 كم"),
            node(clickable = true, viewId = "me.com.easytaxista:id/btn_accept", className = "android.widget.Button",
                children = listOf(node("قبول"))),
        ))
        val sketch = ScreenSketch.render(root, jeeny)
        assertTrue(sketch, sketch.contains("TextView \"99.99 ر.س\""))
        assertTrue(sketch, sketch.contains("Button#btn_accept*"))
        assertTrue(sketch, sketch.contains("\"قبول\""))
        assertTrue(sketch, sketch.contains("\"9.9 كم\""))
        assertFalse(sketch, sketch.contains("محمد"))
        assertFalse(sketch, sketch.contains("العتيبي"))
        assertFalse(sketch, sketch.contains("الملك"))
        assertFalse(sketch, sketch.contains("27.50"))
    }

    @Test
    fun `sketch is bounded`() {
        val root = node(children = List(500) { node("نص طويل جدا رقم $it") })
        assertTrue(ScreenSketch.render(root, jeeny).length <= ScreenSketch.MAX_CHARS)
    }
}

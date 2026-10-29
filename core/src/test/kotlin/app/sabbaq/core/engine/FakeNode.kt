package app.sabbaq.core.engine

import app.sabbaq.core.Bounds

/** A hand-built accessibility node for engine tests. */
class FakeNode(
    override val text: String? = null,
    override val description: String? = null,
    override val className: String? = "android.widget.TextView",
    override val viewId: String? = null,
    override val isClickable: Boolean = false,
    override val isEnabled: Boolean = true,
    private val rect: Bounds = Bounds(0, 2000, 1080, 2100),
    children: List<FakeNode> = emptyList(),
) : UiNode {
    var parentNode: FakeNode? = null
    val kids: List<FakeNode> = children
    var clicks = 0
    var clickResult = true

    init { children.forEach { it.parentNode = this } }

    override val childCount: Int get() = kids.size
    override fun child(index: Int): UiNode? = kids.getOrNull(index)
    override fun parent(): UiNode? = parentNode
    override fun bounds(): Bounds = rect
    override fun click(): Boolean { clicks++; return clickResult }

    override fun findByViewId(viewId: String): List<UiNode> {
        val out = ArrayList<UiNode>()
        fun walk(n: FakeNode) { if (n.viewId == viewId) out += n; n.kids.forEach(::walk) }
        walk(this)
        return out
    }
}

fun row(top: Int) = Bounds(40, top, 1040, top + 90)

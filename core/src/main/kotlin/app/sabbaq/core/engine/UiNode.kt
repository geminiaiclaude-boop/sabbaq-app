package app.sabbaq.core.engine

import app.sabbaq.core.Bounds

/**
 * One live node of a ride app's accessibility tree. The service wraps the platform node and the
 * tests use plain fakes, so the reader, the decision and the flow are pure and unit-testable while
 * the node that gets tapped is the very node that was read (never a re-resolved copy).
 */
interface UiNode {
    val text: String?
    val description: String?
    val className: String?
    val viewId: String?
    val isClickable: Boolean
    val isEnabled: Boolean
    val childCount: Int
    fun child(index: Int): UiNode?
    fun parent(): UiNode?
    fun bounds(): Bounds

    /** Every node whose resource id equals [viewId], searched from this node. */
    fun findByViewId(viewId: String): List<UiNode>

    /** Accessibility click on this node itself. */
    fun click(): Boolean
}

package app.sabbaq.service.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.sabbaq.core.Bounds
import app.sabbaq.core.engine.UiNode

/** The engine's view of a live accessibility node; the node read is the node tapped. */
class LiveNode(val info: AccessibilityNodeInfo) : UiNode {
    override val text: String? get() = runCatching { info.text?.toString() }.getOrNull()
    override val description: String? get() = runCatching { info.contentDescription?.toString() }.getOrNull()
    override val className: String? get() = runCatching { info.className?.toString() }.getOrNull()
    override val viewId: String? get() = runCatching { info.viewIdResourceName }.getOrNull()
    override val isClickable: Boolean get() = runCatching { info.isClickable }.getOrDefault(false)
    override val isEnabled: Boolean get() = runCatching { info.isEnabled }.getOrDefault(true)
    override val childCount: Int get() = runCatching { info.childCount }.getOrDefault(0)
    override fun child(index: Int): UiNode? = runCatching { info.getChild(index) }.getOrNull()?.let(::LiveNode)
    override fun parent(): UiNode? = runCatching { info.parent }.getOrNull()?.let(::LiveNode)
    override fun bounds(): Bounds {
        val r = Rect()
        runCatching { info.getBoundsInScreen(r) }
        return Bounds(r.left, r.top, r.right, r.bottom)
    }
    override fun findByViewId(viewId: String): List<UiNode> =
        runCatching { info.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull().orEmpty().map(::LiveNode)
    override fun click(): Boolean = runCatching { info.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    val packageName: String? get() = runCatching { info.packageName?.toString() }.getOrNull()
}

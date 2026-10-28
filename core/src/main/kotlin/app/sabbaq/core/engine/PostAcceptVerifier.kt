package app.sabbaq.core.engine

sealed class PostAcceptOutcome {
    /** The ride app moved to the trip screen: the tap was accepted. */
    data class Confirmed(val pickupAddress: String?) : PostAcceptOutcome()
    /** An accept control is still on screen. */
    object StillOffer : PostAcceptOutcome()
    object Inconclusive : PostAcceptOutcome()
}

/** FareSniper's post-tap check for Jeeny (`AbstractC2279pu`, QANNAS_ENGINE_FACTS.md §15). */
object PostAcceptVerifier {
    private val STILL_OFFER = listOf("accept offer", "accept the offer", "قبول الطلب", "قبول العرض")
    private val ACTIVE_RIDE = listOf(
        "arrived at pickup", "arrived at pickup location", "cancel ride", "end ride", "navigate", "picking",
        "pickup location", "in progress", "وصلت إلى نقطة الانطلاق", "وصلت الى نقطة الانطلاق",
        "وصلت إلى نقطة الإقلاع", "نقطة الانطلاق", "إنهاء المشوار", "انهاء المشوار", "إلغاء المشوار",
        "الغاء المشوار", "عرض الاتجاهات", "عرض الإتجاهات", "التوصيل", "في التوصيل", "جارٍ التوصيل",
    )
    private const val MAX_NODES = 400

    fun verify(root: UiNode): PostAcceptOutcome {
        val texts = collectTexts(root)
        if (texts.any { t -> STILL_OFFER.any { it in t } }) return PostAcceptOutcome.StillOffer
        if (texts.any { t -> ACTIVE_RIDE.any { it in t } }) return PostAcceptOutcome.Confirmed(null)
        return PostAcceptOutcome.Inconclusive
    }

    private fun collectTexts(root: UiNode): List<String> {
        val out = ArrayList<String>()
        val queue = ArrayDeque<UiNode>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            val label = (n.text?.takeIf { it.isNotBlank() } ?: n.description.orEmpty()).trim().lowercase()
            if (label.isNotEmpty()) out += label
            for (i in 0 until n.childCount) runCatching { n.child(i) }.getOrNull()?.let(queue::addLast)
        }
        return out
    }
}

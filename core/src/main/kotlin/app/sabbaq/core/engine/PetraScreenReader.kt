package app.sabbaq.core.engine

import java.util.regex.Pattern

/**
 * FareSniper's PetraRide reader (`Et.execute`, read from smali). Petra exposes resource ids:
 * `estimated_trip_price`, `estimated_time_to_pick_up`, `drop_off_location_title_label`,
 * `accept_button` / `reject_button`; trip and cancel screens are recognised by their ids.
 */
class PetraScreenReader {
    /** Why the last [read] returned null (see [JeenyScreenReader.lastMiss]). */
    var lastMiss: String? = null
        private set

    private class Rec(val text: String, val id: String, val top: Int)

    fun read(root: UiNode, screenHeight: Int): ParsedRide? {
        val cap = if (screenHeight <= 1600) 300 else 500
        val records = ArrayList<Rec>(64)
        val queue = ArrayDeque<UiNode>()
        queue.addLast(root)
        var visited = 0
        var accept: UiNode? = null
        var clickX = 0
        var clickY = 0
        while (queue.isNotEmpty() && visited < cap) {
            val node = queue.removeFirst()
            visited++
            val text = BoltScreenReader.clean(node.text ?: node.description.orEmpty())
            val id = runCatching { node.viewId }.getOrNull()?.substringAfterLast('/').orEmpty()
            if (text.isNotBlank() || id.isNotBlank()) {
                records += Rec(text, id, node.bounds().top)
                val acceptText = text == "قبول" || text.startsWith("قبول ")
                if (accept == null && (acceptText || id == "accept_button")) {
                    val target = if (node.isClickable) node else clickableAncestor(node)
                    if (target != null) {
                        val b = target.bounds()
                        accept = target; clickX = b.centerX; clickY = b.centerY
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.child(i) }.getOrNull() ?: continue
                if (queue.size < cap) queue.addLast(child)
            }
        }

        if (records.any { it.id in CANCEL_IDS }) { lastMiss = "cancel_screen"; return null }
        if (records.any { it.id in TRIP_IDS } || records.any { r -> TRIP_WORDS.any { it in r.text } }) {
            return ParsedRide(isActiveRide = true, nodesVisited = visited)
        }
        val hasAcceptId = records.any { it.id == "accept_button" }
        val hasRejectId = records.any { it.id == "reject_button" }

        val priceRec = records.firstOrNull { it.id == "estimated_trip_price" && it.text.isNotBlank() }
            ?: records.firstOrNull { CURRENCY.matcher(it.text).find() && !DISTANCE.matcher(it.text).find() }
        val price = priceRec?.let { r ->
            val m = PRICE.matcher(r.text)
            if (m.find()) (m.group(1)?.takeIf { it.isNotBlank() } ?: m.group(2))?.replace(',', '.')?.toDoubleOrNull() else null
        }
        val floor = priceRec?.let { it.top - 8 } ?: (screenHeight * 0.45).toInt()

        val dropoffRec = records.firstOrNull { it.id == "drop_off_location_title_label" && it.text.isNotBlank() }
        val dropoff = dropoffRec?.text
        val dropoffTop = dropoffRec?.top ?: Int.MAX_VALUE
        val candidates = records.filter { it.top >= floor && isAddress(it) }.sortedBy { it.top }
        val addresses = ArrayList<String>()
        val pickupRaw: String?
        if (dropoff != null) {
            addresses += candidates.map { it.text }
            if (dropoff !in addresses) addresses += dropoff
            pickupRaw = candidates.lastOrNull { it.top < dropoffTop }?.text
        } else {
            addresses += candidates.map { it.text }
            pickupRaw = candidates.firstOrNull()?.text
        }
        val pickup = pickupRaw?.trim()?.takeIf { it.isNotBlank() }
        val ambiguous = dropoff.isNullOrBlank()

        val timeRec = records.firstOrNull { it.id == "estimated_time_to_pick_up" && it.text.isNotBlank() }
            ?: records.filter { it.top >= floor && MINUTES.matcher(it.text).find() }.minByOrNull { it.top }
        val minutes = timeRec?.let { r ->
            val m = MINUTES.matcher(r.text)
            if (m.find()) m.group(1)?.replace(',', '.')?.toDoubleOrNull()?.let { (it + 0.5).toInt().coerceAtLeast(1) } else null
        }

        val node = accept ?: run {
            lastMiss = if (price == null && minutes == null) "not_offer_screen" else "no_accept"
            return null
        }
        if (!hasAcceptId && !hasRejectId) { lastMiss = "no_offer_ids"; return null }
        if (price == null && minutes == null) { lastMiss = "no_data"; return null }
        return ParsedRide(
            price = price, minutes = minutes, destination = dropoff, acceptNode = node, addressAmbiguous = ambiguous,
            clickX = clickX, clickY = clickY, addresses = addresses.distinct(), pickup = pickup, nodesVisited = visited,
        )
    }

    private fun isAddress(r: Rec): Boolean {
        val t = BoltScreenReader.clean(r.text)
        if (t.length < 4 || r.id in IGNORED_IDS) return false
        if (r.id == "drop_off_location_title_label") return true
        if (DISTANCE.matcher(t).find() || MINUTES.matcher(t).find() || PRICE.matcher(t).find()) return false
        val lower = t.lowercase()
        if (NOISE.any { it in lower }) return false
        if (t.count { it.isLetter() } < 3) return false
        return ',' in t || '،' in t || CITY.matcher(t).find() || PLUS_CODE.matcher(t).find()
    }

    private fun clickableAncestor(node: UiNode): UiNode? {
        var p = runCatching { node.parent() }.getOrNull()
        var hops = 0
        while (p != null && hops < 4) {
            if (p.isClickable) return p
            p = runCatching { p!!.parent() }.getOrNull()
            hops++
        }
        return null
    }

    companion object {
        /** Patterns FareSniper builds with `Xw(pattern, flags)` compile CASE_INSENSITIVE | UNICODE_CASE (0x42). */
        val PRICE: Pattern = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:د\\.?\\s?أ|دينار|jod)|(?:د\\.?\\s?أ|دينار|jod)\\s*(\\d+(?:[.,]\\d+)?)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val CURRENCY: Pattern = Pattern.compile("د\\.?\\s?أ|دينار|jod", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val MINUTES: Pattern = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:دقيقة|دقائق|دقيقه|دقائ)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val DISTANCE: Pattern = Pattern.compile("\\d+(?:[.,]\\d+)?\\s*(?:km|كم|متر)", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val CITY: Pattern = Pattern.compile("عمّان|عمان|الأردن|اردن|إربد|اربد|الزرقاء|العقبة|إربد|مادبا|السلط|جرش|عجلون|الكرك|amman|jordan", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val PLUS_CODE: Pattern = Pattern.compile("[A-Z0-9]{3,6}\\+[A-Z0-9]{2,6}")
        val NOISE = listOf("سفير بترا", "قبول", "رفض", "دقيقة", "د.أ", "دينار")
        val IGNORED_IDS = setOf("estimated_trip_price", "estimated_time_to_pick_up", "accept_button", "reject_button", "left_buttons_layout", "map_view")
        val TRIP_IDS = setOf("trip_flow_button", "trip_flow_helper_text", "rider_name", "rider_rating", "call_rider_button",
            "chat_with_rider_button", "request_live_location_card", "cancel_trip_card")
        val CANCEL_IDS = setOf("cancel_trip_button", "keep_button", "item_reason_layout")
        val TRIP_WORDS = listOf("نقطة الإنطلاق", "إلغاء الرحلة")
    }
}

package app.sabbaq.core.engine

import java.util.regex.Pattern

/**
 * FareSniper's Bolt reader (`Y2.execute`, read from smali). Bolt exposes resource ids, so the
 * reader keys on them: `categoryPriceInfo` for the price, `newOrderAddressText` for addresses,
 * `riderInfoText` / `clientRating` for the rating, `acceptButton` / `button` or «قبول» for the
 * accept control, and a few active-trip ids to recognise a running ride.
 */
class BoltScreenReader(private val config: EngineConfig) {

    private class Rec(val text: String, val id: String, val top: Int, val node: UiNode)

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
            val text = clean(node.text ?: node.description.orEmpty())
            val id = runCatching { node.viewId }.getOrNull()?.substringAfterLast('/').orEmpty()
            if (text.isNotBlank() || id.isNotBlank()) {
                records += Rec(text, id, node.bounds().top, node)
                val acceptText = text == "قبول" || text.startsWith("قبول ")
                val acceptId = id == "acceptButton" || id == "button"
                if (accept == null && (acceptText || acceptId)) {
                    val target = if (node.isClickable) node else clickableAncestor(node, 4)
                    if (target != null) {
                        val b = target.bounds()
                        if (acceptText || id != "button" || b.centerY >= (screenHeight * 0.72).toInt()) {
                            accept = target; clickX = b.centerX; clickY = b.centerY
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.child(i) }.getOrNull() ?: continue
                if (queue.size < cap) queue.addLast(child)
            }
        }

        if (records.any { it.id in ACTIVE_IDS } || records.any { r -> ACTIVE_WORDS.any { it in r.text } }) {
            return ParsedRide(isActiveRide = true, nodesVisited = visited)
        }

        val priceRec = records.firstOrNull { it.id == "categoryPriceInfo" && it.text.isNotBlank() }
            ?: records.firstOrNull { PRICE.matcher(it.text).find() }
        val price = priceRec?.let { r ->
            val m = PRICE.matcher(r.text)
            if (m.find()) (m.group(1)?.takeIf { it.isNotBlank() } ?: m.group(2))?.replace(',', '.')?.toDoubleOrNull() else null
        }
        val floor = priceRec?.let { it.top - 8 } ?: (screenHeight * 0.45).toInt()

        val addresses = records.filter { r -> r.top >= floor && isAddress(r) }
            .sortedBy { it.top }.map { it.text }.distinct()
        val pickup = if (addresses.size >= 2) addresses.first() else null
        val destination = addresses.lastOrNull()

        val minutes = records.filter { it.top >= floor }.mapNotNull { r ->
            val m = MINUTES.matcher(r.text)
            if (m.find()) m.group(1)?.toIntOrNull()?.let { r.top to it } else null
        }.minByOrNull { it.first }?.second

        val payment = if (config.paymentFilterMode == PaymentMode.ALL) Payment.UNKNOWN else {
            val all = records.joinToString(" ") { it.text }.lowercase()
            when {
                CARD_WORDS.any { it in all } -> Payment.CARD
                CASH_WORDS.any { it in all } -> Payment.CASH
                else -> Payment.UNKNOWN
            }
        }

        val ratingRec = records.firstOrNull { it.id == "riderInfoText" && it.text.isNotBlank() }
            ?: records.firstOrNull { it.id == "clientRating" && it.text.isNotBlank() }
            ?: records.firstOrNull { '★' in it.text || '☆' in it.text }
        val rating = ratingRec?.let { r ->
            val t = clean(r.text)
            val raw = STAR.matcher(t).let { if (it.find()) it.group(1) else null }
                ?: DASH_RATING.matcher(t).let { if (it.find()) it.group(1) else null }
                ?: if (r.id == "clientRating") LONE_RATING.matcher(t).let { if (it.find()) it.group(1) else null } else null
            raw?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..5.0 }
        }
        val waitAndSave = records.any { isWaitAndSave(it.text) }
        val ambiguous = destination == null

        val node = accept ?: return null
        if (price == null && minutes == null && ambiguous) return null
        return ParsedRide(
            price = price, minutes = minutes, destination = destination, acceptNode = node,
            addressAmbiguous = ambiguous, clickX = clickX, clickY = clickY, addresses = addresses,
            pickup = pickup, payment = payment, rating = rating, waitAndSave = waitAndSave, nodesVisited = visited,
        )
    }

    private fun isAddress(r: Rec): Boolean {
        val t = clean(r.text)
        if (t.length < 4 || r.id in IGNORED_IDS) return false
        if (r.id == "activeOrderDestinationTitle" || r.id == "newOrderAddressText") return true
        if ('★' in t || '☆' in t) return false
        if (DISTANCE.matcher(t).find() || MINUTES.matcher(t).find() || PRICE.matcher(t).find()) return false
        val lower = t.lowercase()
        if (NOISE.any { it in lower }) return false
        if (t.count { it.isLetter() } < 3) return false
        return ',' in t || '،' in t || CITY.matcher(t).find() || POSTCODE.matcher(t).find()
    }

    private fun clickableAncestor(node: UiNode, maxHops: Int): UiNode? {
        var p = runCatching { node.parent() }.getOrNull()
        var hops = 0
        while (p != null && hops < maxHops) {
            if (p.isClickable) return p
            p = runCatching { p!!.parent() }.getOrNull()
            hops++
        }
        return null
    }

    companion object {
        private val BIDI: Pattern = Pattern.compile("[\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]")
        val MINUTES: Pattern = Pattern.compile("(?<!\\d)(\\d+)\\s*(?:دقيقة|دقائق|دقيقه|دقائ|mins|min)")
        val PRICE: Pattern = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:ر\\.?\\s?س|﷼|sar)|(?:ر\\.?\\s?س|﷼|sar)\\s*(\\d+(?:[.,]\\d+)?)")
        val DISTANCE: Pattern = Pattern.compile("\\d+(?:[.,]\\d+)?\\s*(?:km|كم|متر)")
        val CITY: Pattern = Pattern.compile("جدة|الرياض|مكة|المكرمة|المدينة|الدمام|الخبر|الطائف|تبوك|بريدة|خميس|أبها|الجبيل|ينبع|نجران|جازان|حائل|القطيف|الأحساء|عرعر|سكاكا")
        val POSTCODE: Pattern = Pattern.compile("\\d{4,5}")
        val STAR: Pattern = Pattern.compile("(\\d(?:[.,]\\d)?)\\s*[★☆]")
        val DASH_RATING: Pattern = Pattern.compile("[-–—]\\s*(\\d(?:[.,]\\d)?)\\s*$")
        val LONE_RATING: Pattern = Pattern.compile("^\\s*(\\d(?:[.,]\\d)?)\\s*$")
        const val WAIT_SAVE_AR = "ووفر"
        val NOISE = listOf("خارج نطاق", "لن يؤثر", "معدل القبول", "طلب مرتفع", "البطاقة", "البطا", "النقد فقط", "صافي", "الإبلاغ", "قبول", "رفض", "bolt", "التطبيق")
        val CARD_WORDS = listOf("بطاقة", "البطاقة", "البطا", "card", "visa", "mada", "mastercard", "ائتمان", "فيزا", "wallet", "محفظة")
        val CASH_WORDS = listOf("نقد", "كاش", "cash", "نقدي")
        val IGNORED_IDS = setOf("categoryPriceInfo", "activeOrderClientName", "clientRating", "activeOrderTimerText", "slideText",
            "timeoutLabelText", "timeoutLabelTime", "activeOrderMenuButton", "breakButton", "safetyToolkitButton",
            "externalNavigationButton", "internalNavigationButton", "chatButton", "reportBadPickupButton")
        val ACTIVE_IDS = setOf("slideText", "activeOrderTimerText", "activeOrderMenuButton", "activeOrderDestinationTitle", "reportBadPickupButton")
        val ACTIVE_WORDS = listOf("قد وصل", "إنهاء", "انهاء", "الغاء المشوار", "إلغاء المشوار", "في التوصيل", "بدء الرحلة", "قطع الإتصال", "قطع الاتصال")

        /** Bidi marks removed, Arabic-Indic digits to Latin, trimmed (`Y2.connect`). */
        fun clean(raw: String): String {
            val stripped = BIDI.matcher(raw).replaceAll("")
            if (stripped.isBlank()) return ""
            return JeenyLexicon.latinDigits(stripped).trim()
        }

        /** «ووفر», or "wait" and "save" together (`Y2.process`). */
        fun isWaitAndSave(raw: String): Boolean {
            val t = clean(raw)
            if (WAIT_SAVE_AR in t) return true
            val lower = t.lowercase()
            return "wait" in lower && "save" in lower
        }
    }
}

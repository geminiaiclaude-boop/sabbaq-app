package app.sabbaq.ui

import app.sabbaq.LocaleManager

/**
 * Driver-facing text for FareSniper's decision reason keys (DecisionEngine). Unknown keys fall
 * back to a neutral sentence so an older stored reason never shows a raw code.
 */
internal fun reasonLabel(key: String): String {
    val ar = LocaleManager.isArabic()
    val parts = key.removePrefix("failed_").split('_')
    if (key.startsWith("failed_") && parts.all { it in setOf("price", "time", "destination") }) {
        return parts.joinToString(if (ar) "، " else ", ") { part ->
            when (part) {
                "price" -> if (ar) "السعر خارج الحد الذي حددته" else "The price is outside your limits"
                "time" -> if (ar) "وقت الوصول للراكب خارج الحد الذي حددته" else "The time to the rider is outside your limits"
                else -> if (ar) "الوجهة ليست من الوجهات التي حددتها" else "The destination is not one you chose"
            }
        } + "."
    }
    return when (key) {
        "dest_excluded" -> if (ar) "الوجهة من الوجهات التي تتجنبها." else "The destination is one you avoid."
        "exclude_no_dest_safe_reject" -> if (ar) "الوجهة غير ظاهرة وطلبت رفض هذه الحالة." else "The destination is not shown and you chose to reject that case."
        "no_dest_sniping_disabled" -> if (ar) "الوجهة غير ظاهرة وقبول الطلبات بلا وجهة متوقف." else "The destination is not shown and orders without one are off."
        "no_ride_data_detected" -> if (ar) "لم يظهر سعر ولا وقت وصول." else "Neither a price nor a pickup time was shown."
        else -> if (ar) "الطلب لا يطابق شروطك." else "The order does not match your conditions."
    }
}

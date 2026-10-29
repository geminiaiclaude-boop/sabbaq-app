package app.sabbaq.core.engine.zones

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/** A geocoded point with the radius (m) it may be off by. */
data class GeoPoint(val lat: Double, val lng: Double, val precisionM: Double)

/**
 * The reference engine's text-to-point codes (`ja`): Open Location Codes ("Plus Codes", full or short,
 * the short ones recovered around the driver) and the Saudi national short address
 * (four letters + four digits, e.g. RRRD2929).
 */
object PlusCodes {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private val PLUS = Regex(
        "(?<![23456789CFGHJMPQRVWXcfghjmpqrvwx+])([23456789CFGHJMPQRVWXcfghjmpqrvwx]{2,8}\\+[23456789CFGHJMPQRVWXcfghjmpqrvwx]{2,3})(?![23456789CFGHJMPQRVWXcfghjmpqrvwx])",
    )
    private val NATIONAL = Regex("(?<![A-Za-z0-9])([A-Za-z]{4}[0-9]{4})(?![A-Za-z0-9])")

    /** The national short address in [text], lower-cased (`ja.connect` regex group 1). */
    fun nationalCode(text: String?): String? =
        text?.takeIf { it.isNotBlank() }?.let { NATIONAL.find(it)?.groupValues?.get(1)?.lowercase() }

    /**
     * `ja.connect`: the first Plus Code in [text] that decodes to an area no wider than 250 m;
     * precision is the area's half diagonal, at least 15 m. Short codes need the driver position.
     */
    fun resolve(driverLat: Double, driverLng: Double, text: String?): GeoPoint? {
        if (text.isNullOrBlank()) return null
        for (m in PLUS.findAll(text)) {
            val code = m.groupValues[1].uppercase()
            val area = when {
                isFull(code) -> decode(code)
                isShort(code) && !(driverLat == 0.0 && driverLng == 0.0) -> recover(code, driverLat, driverLng)
                else -> null
            } ?: continue
            val radius = area.radiusM()
            if (radius <= 250.0) return GeoPoint(area.lat, area.lng, maxOf(radius, 15.0))
        }
        return null
    }

    private class Area(val lat: Double, val lng: Double, val latHalf: Double, val lngHalf: Double) {
        fun radiusM(): Double {
            val y = latHalf * 110540.0
            val x = cos(Math.toRadians(lat)) * lngHalf * 111320.0
            return sqrt(x * x + y * y)
        }
    }

    private fun isValid(code: String): Boolean {
        val plus = code.indexOf('+')
        if (plus < 0 || plus != code.lastIndexOf('+') || plus > 8 || plus % 2 == 1) return false
        val digits = code.replace("+", "")
        if (digits.any { it !in ALPHABET }) return false
        val after = code.length - plus - 1
        return after != 1
    }

    private fun isFull(code: String): Boolean {
        if (!isValid(code) || code.indexOf('+') != 8) return false
        val first = ALPHABET.indexOf(code[0]) * 20
        val second = ALPHABET.indexOf(code[1]) * 20
        return first < 180 && second < 360
    }

    private fun isShort(code: String): Boolean = isValid(code) && code.indexOf('+') in 1..7

    /** Decodes a full code to the centre of its area. */
    private fun decode(code: String): Area? {
        val digits = code.replace("+", "")
        var lat = -90.0
        var lng = -180.0
        var res = 20.0
        var i = 0
        while (i < minOf(digits.length, 10)) {
            lat += ALPHABET.indexOf(digits[i]) * res
            lng += ALPHABET.indexOf(digits[i + 1]) * res
            if (i + 2 < minOf(digits.length, 10)) res /= 20.0
            i += 2
        }
        var latSize = res
        var lngSize = res
        if (digits.length > 10) {
            for (c in digits.substring(10)) {
                latSize /= 5.0; lngSize /= 4.0
                val v = ALPHABET.indexOf(c)
                lat += (v / 4) * latSize
                lng += (v % 4) * lngSize
            }
        }
        return Area(lat + latSize / 2.0, lng + lngSize / 2.0, latSize / 2.0, lngSize / 2.0)
    }

    /** Recovers a short code to the full code nearest the reference point, as the reference engine does. */
    private fun recover(short: String, refLat: Double, refLng: Double): Area? {
        val padding = 8 - short.indexOf('+')
        val resolution = 20.0.pow(2.0 - padding / 2.0)
        val prefix = encodePairs(refLat, refLng).substring(0, padding)
        val area = decode(prefix + short) ?: return null
        val half = resolution / 2.0
        var lat = area.lat
        if (lat - refLat > half && lat - resolution >= -90.0) lat -= resolution
        else if (refLat - lat > half && lat + resolution <= 90.0) lat += resolution
        var lng = area.lng
        if (lng - refLng > half) lng -= resolution else if (refLng - lng > half) lng += resolution
        return Area(lat, lng, area.latHalf, area.lngHalf)
    }

    /** The first ten digits (with '+') of the code for a point (`ja.connect` prefix builder). */
    private fun encodePairs(latIn: Double, lngIn: Double): String {
        var lat = latIn.coerceIn(-90.0, 90.0) + 90.0
        var lngN = lngIn
        while (lngN >= 180.0) lngN -= 360.0
        while (lngN < -180.0) lngN += 360.0
        var lng = lngN + 180.0
        val sb = StringBuilder()
        var res = 20.0
        repeat(5) {
            val a = (lat / res).toInt().coerceIn(0, 19)
            val b = (lng / res).toInt().coerceIn(0, 19)
            sb.append(ALPHABET[a]).append(ALPHABET[b])
            lat -= a * res
            lng -= b * res
            res /= 20.0
            if (sb.length == 8) sb.append('+')
        }
        return sb.toString()
    }
}

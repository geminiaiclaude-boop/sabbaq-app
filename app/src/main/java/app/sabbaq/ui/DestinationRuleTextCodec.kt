package app.sabbaq.ui

import app.sabbaq.core.DestinationAlias
import app.sabbaq.core.DestinationCorridor
import app.sabbaq.core.DestinationTarget
import app.sabbaq.core.DestinationZone
import app.sabbaq.core.GeoPoint
import app.sabbaq.core.Text
import app.sabbaq.core.ZoneMode
import app.sabbaq.core.Zones
import java.math.BigDecimal

/** Strict, deterministic text format used by the editor; malformed rows are never silently dropped. */
internal object DestinationRuleTextCodec {
    private val decimalPattern = Regex("[+-]?(?:${Text.NUMBER})")

    fun parseZones(raw: String, errors: MutableMap<String, String>): List<DestinationZone> {
        val rows = rows(raw)
        if (rows.size > Zones.MAX_ZONES) errors["destinationZones"] = "الحد الأقصى ${Zones.MAX_ZONES} منطقة."
        val result = rows.mapIndexedNotNull { index, line ->
            val parts = line.split('|').map(String::trim)
            val mode = when (parts.firstOrNull()?.lowercase()) {
                "include", "إدراج" -> ZoneMode.INCLUDE
                "exclude", "استبعاد" -> ZoneMode.EXCLUDE
                else -> null
            }
            val enabled = when {
                parts.size == 5 -> true
                parts.size == 6 && parts[5].equals("true", ignoreCase = true) -> true
                parts.size == 6 && parts[5].equals("false", ignoreCase = true) -> false
                else -> null
            }
            val point = if (parts.size in 5..6) point(parts[2], parts[3]) else null
            val radius = parts.getOrNull(4)?.let(::positiveInt)
            if (parts.size !in 5..6 || mode == null || enabled == null || parts[1].isBlank() || parts[1].length > 100 || point == null || radius == null || radius !in 1..Zones.MAX_ZONE_RADIUS_METERS) {
                errors["destinationZones"] = "السطر ${index + 1}: الصيغة include أو exclude|الاسم|خط العرض|خط الطول|نصف القطر بالمتر|true أو false؛ مثال include|وسط البلد|31.95|35.91|2500|true."
                null
            } else DestinationZone("zone-${index + 1}", parts[1], point, radius, mode, enabled)
        }
        return result
    }

    fun parseAliases(raw: String, errors: MutableMap<String, String>): List<DestinationAlias> {
        val rows = rows(raw)
        if (rows.size > Zones.MAX_ZONES * 4) errors["destinationAliases"] = "الحد الأقصى ${Zones.MAX_ZONES * 4} عبارة وجهة."
        val result = rows.mapIndexedNotNull { index, line ->
            val parts = line.split('|').map(String::trim)
            val point = if (parts.size == 3) point(parts[1], parts[2]) else null
            if (parts.size != 3 || parts[0].isBlank() || parts[0].length > 120 || Text.normalize(parts[0]).isBlank() || point == null) {
                errors["destinationAliases"] = "السطر ${index + 1}: الصيغة عبارة الوجهة|خط العرض|خط الطول؛ لا تضع | داخل العبارة."
                null
            } else DestinationAlias(parts[0], point)
        }
        if (result.groupBy { Text.normalize(it.phrase) }.values.any { it.map(DestinationAlias::point).distinct().size > 1 }) {
            errors["destinationAliases"] = "العبارة نفسها مرتبطة بإحداثيات متعارضة؛ أزل التعارض قبل الحفظ."
        }
        return result
    }

    fun parseTarget(raw: String, errors: MutableMap<String, String>): DestinationTarget? {
        if (raw.isBlank()) return null
        val parts = raw.split('|').map(String::trim)
        val coords = parts.getOrNull(0)?.split(',')?.map(String::trim)
        val center = if (coords?.size == 2) point(coords[0], coords[1]) else null
        val radius = parts.getOrNull(1)?.let(::positiveInt)
        if (parts.size != 2 || center == null || radius == null || radius !in 1..Zones.MAX_ZONE_RADIUS_METERS) {
            errors["smartDestination"] = "أدخلها هكذا خط العرض,خط الطول|نصف القطر بالمتر؛ مثال 31.95,35.91|3000."
            return null
        }
        return DestinationTarget(center, radius)
    }

    fun parseCorridor(raw: String, errors: MutableMap<String, String>): DestinationCorridor? {
        if (raw.isBlank()) return null
        val parts = raw.split('|', limit = 2).map(String::trim)
        val width = parts.firstOrNull()?.let(::positiveInt)
        val coordinates = parts.getOrNull(1)?.split(';')?.map(String::trim).orEmpty()
        val points = coordinates.mapNotNull { token ->
            val xy = token.split(',').map(String::trim)
            if (xy.size == 2) point(xy[0], xy[1]) else null
        }
        val corridor = if (width != null && parts.size == 2 && points.size == coordinates.size)
            DestinationCorridor(points, width) else null
        if (corridor == null || !Zones.isValidCorridor(corridor)) {
            errors["destinationCorridor"] = "الصيغة عرض الممر بالمتر|خط العرض,خط الطول;خط العرض,خط الطول؛ أدخل نقطتين صالحَتين على الأقل."
            return null
        }
        return corridor
    }

    fun formatZones(zones: List<DestinationZone>): String = zones.joinToString("\n") { zone ->
        "${if (zone.mode == ZoneMode.INCLUDE) "include" else "exclude"}|${zone.name}|${num(zone.center.latitude)}|${num(zone.center.longitude)}|${zone.radiusMeters}|${zone.enabled}"
    }

    fun formatAliases(aliases: List<DestinationAlias>): String = aliases.joinToString("\n") {
        "${it.phrase}|${num(it.point.latitude)}|${num(it.point.longitude)}"
    }

    fun formatTarget(target: DestinationTarget?): String = target?.let {
        "${num(it.center.latitude)},${num(it.center.longitude)}|${it.radiusMeters}"
    }.orEmpty()

    fun formatCorridor(corridor: DestinationCorridor?): String = corridor?.let {
        "${it.halfWidthMeters}|${it.points.joinToString(";") { point -> "${num(point.latitude)},${num(point.longitude)}" }}"
    }.orEmpty()

    private fun rows(raw: String): List<String> = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    private fun point(lat: String, lon: String): GeoPoint? {
        val latitude = decimal(lat)?.toDouble() ?: return null
        val longitude = decimal(lon)?.toDouble() ?: return null
        return GeoPoint(latitude, longitude).takeIf(GeoPoint::isValid)
    }

    private fun decimal(raw: String): BigDecimal? {
        val normalized = Text.normalize(raw)
        if (normalized.length > 20 || !decimalPattern.matches(normalized)) return null
        return Text.parseNumber(normalized)?.takeIf { it.precision() <= 12 }
    }

    private fun positiveInt(raw: String): Int? {
        val normalized = Text.normalize(raw)
        if (!Regex("\\d{1,6}").matches(normalized)) return null
        return normalized.toIntOrNull()
    }

    private fun num(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

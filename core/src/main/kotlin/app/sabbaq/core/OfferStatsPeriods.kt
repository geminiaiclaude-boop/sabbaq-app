package app.sabbaq.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** ISO-calendar aggregation windows; weeks start on Monday and end on the supplied date. */
object OfferStatsPeriods {
    fun weekStart(today: LocalDate): LocalDate = today.minusDays((today.dayOfWeek.value - 1).toLong())

    fun monthStart(today: LocalDate): LocalDate = today.withDayOfMonth(1)

    /** Retain the current month plus up to six preceding days needed by a cross-month week. */
    fun retentionStart(today: LocalDate): LocalDate = monthStart(today).minusDays(6)

    fun contains(date: String, start: LocalDate, endInclusive: LocalDate): Boolean {
        val parsed = runCatching { LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
            ?: return false
        return parsed >= start && parsed <= endInclusive
    }
}

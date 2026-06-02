package com.launchpoint.wavdrop.data.model

import java.time.YearMonth
import java.time.ZoneId

/**
 * An inclusive epoch-ms range used to scope listening analytics to a specific time period.
 *
 * Factory methods cover the most common periods (month, year). Callers can also construct
 * arbitrary ranges directly via the primary constructor.
 *
 * [zone] is stored so builders can convert event timestamps to local dates for day-level
 * analytics (busiest day, listening-days count) consistently with the range boundaries.
 */
data class ListeningPeriodRange(
    val fromMs: Long,
    val toMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** True if [epochMs] falls within this range (inclusive on both ends). */
    fun contains(epochMs: Long): Boolean = epochMs in fromMs..toMs

    companion object {
        fun allTime(zone: ZoneId = ZoneId.systemDefault()): ListeningPeriodRange =
            ListeningPeriodRange(Long.MIN_VALUE, Long.MAX_VALUE, zone)

        /**
         * Covers all milliseconds within the specified calendar month in [zone].
         * fromMs = 00:00:00.000 on day 1; toMs = one ms before 00:00:00.000 on day 1 of next month.
         */
        fun month(year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): ListeningPeriodRange {
            val ym = YearMonth.of(year, month)
            val fromMs = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val toMs = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            return ListeningPeriodRange(fromMs, toMs, zone)
        }

        /**
         * Covers all milliseconds within the specified calendar year in [zone].
         * fromMs = 00:00:00.000 on Jan 1; toMs = one ms before 00:00:00.000 on Jan 1 of next year.
         */
        fun year(year: Int, zone: ZoneId = ZoneId.systemDefault()): ListeningPeriodRange {
            val fromMs = YearMonth.of(year, 1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val toMs = YearMonth.of(year + 1, 1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
            return ListeningPeriodRange(fromMs, toMs, zone)
        }
    }
}

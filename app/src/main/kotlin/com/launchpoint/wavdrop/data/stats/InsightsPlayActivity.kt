package com.launchpoint.wavdrop.data.stats

import android.database.Cursor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bounded summary of the full PLAY history for Insights/Statistics (WC-09): the most-active weekday and hour plus the current
 * calendar year's distinct play dates, instead of one timestamp per historical PLAY.
 *
 * Size is bounded: two small enums/ints plus at most 366 date entries, whatever the history length.
 *
 * @property year the calendar year (in the zone the summary was built for) that [currentYearPlayDays] belongs to.
 * @property currentYearPlayDays sorted distinct local dates (as epoch days) with at least one PLAY in [year].
 */
data class InsightsPlayActivity(
    val year: Int,
    val currentYearPlayDays: List<Long>,
    val mostActiveDayOfWeek: DayOfWeek?,
    val mostActiveHour: Int?,
) {
    /**
     * Current-calendar-year play streak ending today or yesterday, evaluated against [today] at read time (so a screen that stays open
     * across midnight keeps the old behaviour of re-evaluating on its next emission). A [today] in a different year than [year] has
     * no plays in its own year in this summary (any such play would have invalidated and rebuilt the summary), so the streak is 0.
     */
    fun currentStreakDays(today: LocalDate): Int {
        if (today.year != year) return 0
        return InsightsSummaryBuilder.currentStreak(currentYearPlayDays.map(LocalDate::ofEpochDay), today)
    }

    companion object {
        val EMPTY = InsightsPlayActivity(year = 0, currentYearPlayDays = emptyList(), mostActiveDayOfWeek = null, mostActiveHour = null)
    }
}

/**
 * Streaming reducer: feed it PLAY timestamps in ANY order, one at a time, and it keeps only 7 weekday + 24 hour buckets and the
 * current year's distinct dates. Weekday/hour/date are derived through the supplied [zone]'s rules (never UTC / SQLite localtime), so
 * midnight, DST and non-whole-hour offsets behave exactly like `Instant.atZone(zone)`.
 *
 * Tie rule (explicit; it used to be incidental map order over most-recent-first input): the winner is the bucket with the highest
 * count, and on equal counts the bucket containing the greatest (latest) PLAY timestamp.
 */
class InsightsPlayActivityAccumulator(
    private val zone: ZoneId,
    today: LocalDate = LocalDate.now(zone),
) {
    private val rules = zone.rules
    private val year = today.year
    private val yearStartMs = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val yearEndMs = today.withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private val weekdayCount = IntArray(7)
    private val weekdayLatest = LongArray(7) { Long.MIN_VALUE }
    private val hourCount = IntArray(24)
    private val hourLatest = LongArray(24) { Long.MIN_VALUE }
    private val yearDays = HashSet<Long>() // at most 366 entries

    var playsSeen: Long = 0L
        private set

    fun add(epochMs: Long) {
        playsSeen++
        // Local epoch-second = UTC epoch-second + the zone's offset at that instant (same rules ZonedDateTime uses).
        val epochSecond = Math.floorDiv(epochMs, 1_000L)
        val offsetSeconds = rules.getOffset(java.time.Instant.ofEpochSecond(epochSecond)).totalSeconds
        val localSecond = epochSecond + offsetSeconds
        val localEpochDay = Math.floorDiv(localSecond, SECONDS_PER_DAY)
        val weekday = Math.floorMod(localEpochDay + THURSDAY_INDEX, 7L).toInt() // 1970-01-01 was a Thursday; Monday = 0
        val hour = (Math.floorMod(localSecond, SECONDS_PER_DAY) / 3_600L).toInt()

        weekdayCount[weekday]++
        if (epochMs > weekdayLatest[weekday]) weekdayLatest[weekday] = epochMs
        hourCount[hour]++
        if (epochMs > hourLatest[hour]) hourLatest[hour] = epochMs

        if (epochMs >= yearStartMs && epochMs < yearEndMs) yearDays.add(localEpochDay)
    }

    fun build(): InsightsPlayActivity {
        if (playsSeen == 0L) return InsightsPlayActivity(year, emptyList(), null, null)
        val dayIndex = winner(weekdayCount, weekdayLatest)
        val hour = winner(hourCount, hourLatest)
        return InsightsPlayActivity(
            year = year,
            currentYearPlayDays = yearDays.sorted(),
            mostActiveDayOfWeek = DayOfWeek.of(dayIndex + 1),
            mostActiveHour = hour,
        )
    }

    /** Number of retained bucket/date slots (for the bounded-state proof). */
    fun retainedSlots(): Int = weekdayCount.size + hourCount.size + yearDays.size

    private fun winner(counts: IntArray, latest: LongArray): Int {
        var best = -1
        for (i in counts.indices) {
            if (counts[i] == 0) continue
            if (best == -1 || counts[i] > counts[best] || (counts[i] == counts[best] && latest[i] > latest[best])) best = i
        }
        return best
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
        const val THURSDAY_INDEX = 3L
    }
}

/** Streams a one-column `occurredAt` cursor into the accumulator; [onProgress] runs every [CHECK_INTERVAL] rows (cancellation point). */
object InsightsPlayActivityReader {
    const val CHECK_INTERVAL = 4_096

    fun reduce(
        cursor: Cursor,
        zone: ZoneId,
        today: LocalDate = LocalDate.now(zone),
        onProgress: () -> Unit = {},
    ): InsightsPlayActivity {
        val acc = InsightsPlayActivityAccumulator(zone, today)
        var n = 0
        while (cursor.moveToNext()) {
            acc.add(cursor.getLong(0))
            if (++n % CHECK_INTERVAL == 0) onProgress()
        }
        return acc.build()
    }

    /** Pure streaming variant for tests and non-cursor sources. */
    fun reduce(timestamps: Sequence<Long>, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): InsightsPlayActivity {
        val acc = InsightsPlayActivityAccumulator(zone, today)
        timestamps.forEach(acc::add)
        return acc.build()
    }
}

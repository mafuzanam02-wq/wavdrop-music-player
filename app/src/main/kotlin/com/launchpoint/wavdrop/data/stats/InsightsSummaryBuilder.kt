package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Streak and most-active day/hour from listen history. The canonical implementations take PLAY TIMESTAMPS (so callers need not
 * load event entities, WC-02); the entity overloads filter PLAY and delegate. Ties in most-active day/hour resolve to the first
 * group in the order the timestamps are supplied (callers supply most-recent-first, as the entity query did).
 *
 * Production Insights/Statistics no longer call the timestamp functions: since WC-09 they read the bounded [InsightsPlayActivity]
 * summary (streamed, grouped through the same ZoneId). These functions remain as the pure reference implementation of the semantics
 * that summary must reproduce (and are used by tests).
 */
object InsightsSummaryBuilder {

    fun currentStreakDays(
        events: List<TrackListenEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int = currentStreakDaysFromPlayTimestamps(playTimestamps(events), zone)

    /** Current-calendar-year play streak ending today or yesterday. [today] is injectable for deterministic tests. */
    fun currentStreakDaysFromPlayTimestamps(
        playTimestamps: Collection<Long>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): Int {
        val yearStart = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yearEnd   = today.withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val sortedPlayDays = playTimestamps
            .filter { it >= yearStart && it < yearEnd }
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .toSortedSet()
            .toList()

        return currentStreak(sortedPlayDays, today)
    }

    fun mostActiveDayOfWeek(
        events: List<TrackListenEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DayOfWeek? = mostActiveDayOfWeekFromPlayTimestamps(playTimestamps(events), zone)

    fun mostActiveDayOfWeekFromPlayTimestamps(
        playTimestamps: Collection<Long>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): DayOfWeek? =
        if (playTimestamps.isEmpty()) null else {
            playTimestamps
                .groupingBy { Instant.ofEpochMilli(it).atZone(zone).dayOfWeek }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        }

    fun mostActiveHour(
        events: List<TrackListenEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? = mostActiveHourFromPlayTimestamps(playTimestamps(events), zone)

    fun mostActiveHourFromPlayTimestamps(
        playTimestamps: Collection<Long>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? =
        if (playTimestamps.isEmpty()) null else {
            playTimestamps
                .groupingBy { Instant.ofEpochMilli(it).atZone(zone).hour }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
        }

    private fun playTimestamps(events: List<TrackListenEventEntity>): List<Long> =
        events.filter { it.eventType == TrackListenEventEntity.TYPE_PLAY }.map { it.occurredAt }

    internal fun currentStreak(sortedDays: List<LocalDate>, today: LocalDate): Int {
        if (sortedDays.isEmpty()) return 0
        if (sortedDays.last() < today.minusDays(1)) return 0
        var streak = 1
        for (i in sortedDays.lastIndex - 1 downTo 0) {
            if (sortedDays[i + 1] == sortedDays[i].plusDays(1)) streak++ else break
        }
        return streak
    }
}

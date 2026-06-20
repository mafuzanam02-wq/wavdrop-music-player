package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object InsightsSummaryBuilder {

    fun currentStreakDays(
        events: List<TrackListenEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val today     = LocalDate.now(zone)
        val yearStart = today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yearEnd   = today.withDayOfYear(1).plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val sortedPlayDays = events
            .filter {
                it.eventType == TrackListenEventEntity.TYPE_PLAY &&
                    it.occurredAt >= yearStart && it.occurredAt < yearEnd
            }
            .map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
            .toSortedSet()
            .toList()

        return currentStreak(sortedPlayDays, today)
    }

    private fun currentStreak(sortedDays: List<LocalDate>, today: LocalDate): Int {
        if (sortedDays.isEmpty()) return 0
        if (sortedDays.last() < today.minusDays(1)) return 0
        var streak = 1
        for (i in sortedDays.lastIndex - 1 downTo 0) {
            if (sortedDays[i + 1] == sortedDays[i].plusDays(1)) streak++ else break
        }
        return streak
    }
}

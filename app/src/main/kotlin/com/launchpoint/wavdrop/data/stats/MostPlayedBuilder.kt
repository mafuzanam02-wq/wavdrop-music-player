package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object MostPlayedBuilder {

    fun build(
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
        events: List<TrackListenEventEntity>,
        period: MostPlayedPeriod,
        limit: MostPlayedDisplayLimit = MostPlayedDisplayLimit.TOP_25,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SongStatsSummary> = when (period) {
        MostPlayedPeriod.ALL_TIME -> ListeningAnalyticsBuilder
            .buildAllTimeAggregateFallback(
                songs = songs,
                stats = stats,
                topListLimit = limit.count,
                zone = zone,
            )
            .topSongs

        MostPlayedPeriod.THIS_MONTH -> {
            val currentMonth = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
            val range = ListeningPeriodRange.month(
                year = currentMonth.year,
                month = currentMonth.monthValue,
                zone = zone,
            )
            ListeningAnalyticsBuilder
                .build(
                    range = range,
                    songs = songs,
                    stats = emptyList(),
                    events = events,
                    topListLimit = limit.count,
                )
                .topSongs
        }
    }

    fun thisMonthPlayCounts(
        songs: List<Song>,
        events: List<TrackListenEventEntity>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<Long, Int> {
        val currentMonth = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
        val range = ListeningPeriodRange.month(
            year = currentMonth.year,
            month = currentMonth.monthValue,
            zone = zone,
        )
        return ListeningAnalyticsBuilder
            .build(
                range = range,
                songs = songs,
                stats = emptyList(),
                events = events,
                topListLimit = songs.size,
            )
            .topSongs
            .associate { it.song.id to it.playCount }
    }
}

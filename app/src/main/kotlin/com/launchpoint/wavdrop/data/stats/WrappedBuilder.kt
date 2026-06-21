package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object WrappedBuilder {

    private const val DEFAULT_TOP_LIST_LIMIT = 10

    fun availableYears(
        events: List<TrackListenEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Int> =
        events
            .filter { it.eventType == TrackListenEventEntity.TYPE_PLAY || it.eventType == TrackListenEventEntity.TYPE_SKIP }
            .map { MonthYear.fromEpochMs(it.occurredAt, zone).year }
            .distinct()
            .sortedDescending()

    fun availableMonths(
        events: List<TrackListenEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthYear> =
        events
            .filter { it.eventType == TrackListenEventEntity.TYPE_PLAY || it.eventType == TrackListenEventEntity.TYPE_SKIP }
            .map { MonthYear.fromEpochMs(it.occurredAt, zone) }
            .distinct()
            .sortedDescending()

    fun buildYear(
        year: Int,
        songs: List<Song>,
        events: List<TrackListenEventEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        topListLimit: Int = DEFAULT_TOP_LIST_LIMIT,
    ): WrappedSummary =
        buildPeriod(
            period = WrappedPeriod.year(year, zone),
            songs = songs,
            events = events,
            topListLimit = topListLimit,
        )

    fun buildAllTime(
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
        topListLimit: Int = DEFAULT_TOP_LIST_LIMIT,
    ): WrappedSummary {
        val analytics = ListeningAnalyticsBuilder.buildAllTimeAggregateFallback(
            songs = songs,
            stats = stats,
            topListLimit = topListLimit,
        )
        return WrappedSummary(
            period = WrappedPeriod.AllTime,
            totalPlayCount = analytics.totalPlayCount,
            totalSkipCount = analytics.totalSkipCount,
            totalListeningTimeMs = analytics.totalListeningTimeMs,
            uniqueSongsPlayedCount = analytics.tracksPlayedCount,
            uniqueArtistsPlayedCount = analytics.artistsPlayedCount,
            uniqueAlbumsPlayedCount = analytics.albumsPlayedCount,
            listeningDaysCount = 0,
            busiestDay = null,
            busiestDayPlayCount = 0,
            averagePlaysPerActiveDay = 0.0,
            topSongs = analytics.topSongs,
            topArtists = analytics.topArtists,
            topAlbums = analytics.topAlbums,
            mostPlayedSong = analytics.topSongs.firstOrNull(),
            mostPlayedArtist = analytics.topArtists.firstOrNull(),
            mostPlayedAlbum = analytics.topAlbums.firstOrNull(),
            mostSkippedTrack = analytics.mostSkippedTrack,
            recentlyPlayed = analytics.recentlyPlayed,
            emptyState = analytics.emptyState,
            longestStreak = 0,
            currentStreak = 0,
            mostActiveDayOfWeek = null,
            mostActiveHour = null,
            averageListeningTimePerActiveDayMs = 0L,
            mostReplayedTrack = analytics.topSongs.firstOrNull(),
        )
    }

    fun buildPeriod(
        period: WrappedPeriod,
        songs: List<Song>,
        events: List<TrackListenEventEntity>,
        topListLimit: Int = DEFAULT_TOP_LIST_LIMIT,
    ): WrappedSummary {
        val zone = period.range.zone
        val analytics = ListeningAnalyticsBuilder.build(
            range = period.range,
            songs = songs,
            stats = emptyList(),
            events = events,
            topListLimit = topListLimit,
        )

        val periodPlayEvents = events.filter {
            period.range.contains(it.occurredAt) && it.eventType == TrackListenEventEntity.TYPE_PLAY
        }

        val sortedPlayDays: List<LocalDate> = periodPlayEvents
            .map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
            .toSortedSet()
            .toList()

        val mostActiveDayOfWeek: DayOfWeek? = periodPlayEvents
            .groupingBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfWeek }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val mostActiveHour: Int? = periodPlayEvents
            .groupingBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).hour }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        val averageListeningTimePerActiveDayMs: Long = if (analytics.listeningDaysCount > 0) {
            analytics.totalListeningTimeMs / analytics.listeningDaysCount
        } else {
            0L
        }

        return WrappedSummary(
            period = period,
            totalPlayCount = analytics.totalPlayCount,
            totalSkipCount = analytics.totalSkipCount,
            totalListeningTimeMs = analytics.totalListeningTimeMs,
            uniqueSongsPlayedCount = analytics.uniqueSongsPlayedCount,
            uniqueArtistsPlayedCount = analytics.uniqueArtistsPlayedCount,
            uniqueAlbumsPlayedCount = analytics.uniqueAlbumsPlayedCount,
            listeningDaysCount = analytics.listeningDaysCount,
            busiestDay = analytics.busiestDay,
            busiestDayPlayCount = analytics.busiestDayPlayCount,
            averagePlaysPerActiveDay = analytics.averagePlaysPerActiveDay,
            topSongs = analytics.topSongs,
            topArtists = analytics.topArtists,
            topAlbums = analytics.topAlbums,
            mostPlayedSong = analytics.topSongs.firstOrNull(),
            mostPlayedArtist = analytics.topArtists.firstOrNull(),
            mostPlayedAlbum = analytics.topAlbums.firstOrNull(),
            mostSkippedTrack = analytics.mostSkippedTrack,
            recentlyPlayed = analytics.recentlyPlayed,
            emptyState = analytics.emptyState,
            longestStreak = computeLongestStreak(sortedPlayDays),
            currentStreak = computeCurrentStreak(sortedPlayDays),
            mostActiveDayOfWeek = mostActiveDayOfWeek,
            mostActiveHour = mostActiveHour,
            averageListeningTimePerActiveDayMs = averageListeningTimePerActiveDayMs,
            mostReplayedTrack = analytics.topSongs.firstOrNull(),
        )
    }

    private fun computeLongestStreak(sortedDays: List<LocalDate>): Int {
        if (sortedDays.isEmpty()) return 0
        var longest = 1
        var current = 1
        for (i in 1 until sortedDays.size) {
            if (sortedDays[i] == sortedDays[i - 1].plusDays(1)) {
                current++
                if (current > longest) longest = current
            } else {
                current = 1
            }
        }
        return longest
    }

    private fun computeCurrentStreak(sortedDays: List<LocalDate>): Int {
        if (sortedDays.isEmpty()) return 0
        var streak = 1
        for (i in sortedDays.lastIndex - 1 downTo 0) {
            if (sortedDays[i + 1] == sortedDays[i].plusDays(1)) {
                streak++
            } else {
                break
            }
        }
        return streak
    }
}

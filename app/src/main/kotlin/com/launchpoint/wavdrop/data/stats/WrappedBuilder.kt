package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
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

    fun buildPeriod(
        period: WrappedPeriod,
        songs: List<Song>,
        events: List<TrackListenEventEntity>,
        topListLimit: Int = DEFAULT_TOP_LIST_LIMIT,
    ): WrappedSummary {
        val analytics = ListeningAnalyticsBuilder.build(
            range = period.range,
            songs = songs,
            stats = emptyList(),
            events = events,
            topListLimit = topListLimit,
        )

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
        )
    }
}

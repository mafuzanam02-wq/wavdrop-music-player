package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.MonthlyReportSummary
import com.launchpoint.wavdrop.data.model.MonthlyReportSummary.DataAccuracy
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.Song
import java.time.ZoneId

/**
 * Builds accurate monthly reports from event history.
 *
 * TrackStatsEntity is accepted for API stability but ignored. Monthly reports do not use
 * aggregate stats or imported BlackPlayer counts because those values have no per-month history.
 */
object MonthlyReportBuilder {

    private const val REPORT_LIST_LIMIT = 10

    @Suppress("UNUSED_PARAMETER")
    fun availableMonths(
        stats: List<TrackStatsEntity>,
        events: List<TrackListenEventEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthYear> =
        events
            .filter { it.eventType == TrackListenEventEntity.TYPE_PLAY || it.eventType == TrackListenEventEntity.TYPE_SKIP }
            .map { MonthYear.fromEpochMs(it.occurredAt, zone) }
            .distinct()
            .sortedDescending()

    @Suppress("UNUSED_PARAMETER")
    fun build(
        month: MonthYear,
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
        events: List<TrackListenEventEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): MonthlyReportSummary {
        val range = ListeningPeriodRange.month(month.year, month.month, zone)
        val period = ListeningAnalyticsBuilder.build(
            range = range,
            songs = songs,
            stats = emptyList(),
            events = events,
            topListLimit = REPORT_LIST_LIMIT,
        )

        return MonthlyReportSummary(
            month = month,
            dataAccuracy = if (period.emptyState.reason == ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE) {
                DataAccuracy.NO_EVENT_HISTORY
            } else {
                DataAccuracy.EVENT_BACKED
            },
            totalPlayCount = period.totalPlayCount,
            totalSkipCount = period.totalSkipCount,
            totalListeningTimeMs = period.totalListeningTimeMs,
            activeSongCount = period.tracksPlayedCount,
            activeArtistCount = period.artistsPlayedCount,
            activeAlbumCount = period.albumsPlayedCount,
            listeningDaysCount = period.listeningDaysCount,
            busiestDay = period.busiestDay,
            busiestDayPlayCount = period.busiestDayPlayCount,
            averagePlaysPerActiveDay = period.averagePlaysPerActiveDay,
            emptyStateReason = period.emptyState.reason,
            topSongs = period.topSongs,
            topArtists = period.topArtists,
            topAlbums = period.topAlbums,
            mostSkippedTrack = period.mostSkippedTrack,
            recentlyPlayedInMonth = period.recentlyPlayed,
        )
    }
}

package com.launchpoint.wavdrop.data.model

import java.time.LocalDate

/**
 * Aggregated listening analytics for an arbitrary time window defined by [range].
 *
 * Event-scoped totals are derived from TrackListenEventEntity rows. Aggregate
 * TrackStatsEntity values are only represented by explicit all-time fallback builders.
 */
data class ListeningPeriodSummary(
    val range: ListeningPeriodRange,
    val totalPlayCount: Int,
    val totalSkipCount: Int,
    val totalListeningTimeMs: Long,
    val tracksPlayedCount: Int,
    val artistsPlayedCount: Int,
    val albumsPlayedCount: Int,
    val topSongs: List<SongStatsSummary>,
    val topArtists: List<ArtistReportSummary>,
    val topAlbums: List<AlbumReportSummary>,
    val mostSkippedTrack: SongStatsSummary?,
    val recentlyPlayed: List<SongStatsSummary>,
    val listeningDaysCount: Int,
    val busiestDay: LocalDate?,
    val busiestDayPlayCount: Int,
    val averagePlaysPerActiveDay: Double,
    val emptyState: ListeningAnalyticsEmptyState,
) {
    val hasActivity: Boolean get() = totalPlayCount > 0 || totalSkipCount > 0

    val uniqueSongsPlayedCount: Int get() = tracksPlayedCount
    val uniqueArtistsPlayedCount: Int get() = artistsPlayedCount
    val uniqueAlbumsPlayedCount: Int get() = albumsPlayedCount
}

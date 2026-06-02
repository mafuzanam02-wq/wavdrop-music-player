package com.launchpoint.wavdrop.data.model

import java.time.LocalDate

/**
 * Accurate report for a single calendar month.
 *
 * Monthly values are derived from TrackListenEventEntity rows only. Aggregate
 * TrackStatsEntity rows, including imported BlackPlayer counts, are intentionally excluded.
 */
data class MonthlyReportSummary(
    val month: MonthYear,
    val dataAccuracy: DataAccuracy,
    val totalPlayCount: Int,
    val totalSkipCount: Int,
    val totalListeningTimeMs: Long,
    val activeSongCount: Int,
    val activeArtistCount: Int,
    val activeAlbumCount: Int,
    val listeningDaysCount: Int,
    val busiestDay: LocalDate?,
    val busiestDayPlayCount: Int,
    val averagePlaysPerActiveDay: Double,
    val emptyStateReason: ListeningAnalyticsEmptyReason,
    val topSongs: List<SongStatsSummary>,
    val topArtists: List<ArtistReportSummary>,
    val topAlbums: List<AlbumReportSummary>,
    val mostSkippedTrack: SongStatsSummary?,
    val recentlyPlayedInMonth: List<SongStatsSummary>,
) {
    enum class DataAccuracy {
        EVENT_BACKED,
        NO_EVENT_HISTORY,
    }
}

package com.launchpoint.wavdrop.data.model

import java.time.DayOfWeek
import java.time.LocalDate

data class WrappedSummary(
    val period: WrappedPeriod,
    val totalPlayCount: Int,
    val totalSkipCount: Int,
    val totalListeningTimeMs: Long,
    val uniqueSongsPlayedCount: Int,
    val uniqueArtistsPlayedCount: Int,
    val uniqueAlbumsPlayedCount: Int,
    val listeningDaysCount: Int,
    val busiestDay: LocalDate?,
    val busiestDayPlayCount: Int,
    val averagePlaysPerActiveDay: Double,
    val topSongs: List<SongStatsSummary>,
    val topArtists: List<ArtistReportSummary>,
    val topAlbums: List<AlbumReportSummary>,
    val mostPlayedSong: SongStatsSummary?,
    val mostPlayedArtist: ArtistReportSummary?,
    val mostPlayedAlbum: AlbumReportSummary?,
    val mostSkippedTrack: SongStatsSummary?,
    val recentlyPlayed: List<SongStatsSummary>,
    val emptyState: ListeningAnalyticsEmptyState,
    // V2 insights
    val longestStreak: Int,
    val currentStreak: Int,
    val mostActiveDayOfWeek: DayOfWeek?,
    val mostActiveHour: Int?,
    val averageListeningTimePerActiveDayMs: Long,
    val mostReplayedTrack: SongStatsSummary?,
) {
    val hasActivity: Boolean get() = totalPlayCount > 0 || totalSkipCount > 0
}

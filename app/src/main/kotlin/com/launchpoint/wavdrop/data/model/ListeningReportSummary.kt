package com.launchpoint.wavdrop.data.model

data class ListeningReportSummary(
    val totalListeningTimeMs: Long,
    val totalPlayCount: Int,
    val totalSkipCount: Int,
    val tracksPlayed: Int,
    val artistsPlayed: Int,
    val albumsPlayed: Int,
    val topSongs: List<SongStatsSummary>,
    val topArtists: List<ArtistReportSummary>,
    val topAlbums: List<AlbumReportSummary>,
    val mostPlayedTrack: SongStatsSummary?,
    val mostPlayedArtist: ArtistReportSummary?,
    val mostPlayedAlbum: AlbumReportSummary?,
    val mostSkippedTrack: SongStatsSummary?,
    val recentlyPlayedSongs: List<SongStatsSummary>,
    val recentlyActiveArtists: List<ArtistReportSummary>,
) {
    val hasListeningStats: Boolean
        get() = totalPlayCount > 0 || totalSkipCount > 0 || totalListeningTimeMs > 0L
}

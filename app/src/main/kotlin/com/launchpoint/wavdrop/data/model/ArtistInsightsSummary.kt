package com.launchpoint.wavdrop.data.model

data class ArtistInsightsSummary(
    val totalSongs: Int,
    val totalAlbums: Int,
    val totalPlayCount: Int,
    val totalSkipCount: Int,
    val totalListeningTimeMs: Long,
    val lastPlayedAt: Long?,
    val topSongs: List<SongStatsSummary>,
    val topAlbums: List<ArtistAlbumInsight>,
    val recentActivity: List<SongStatsSummary>,
) {
    val hasListeningStats: Boolean
        get() = totalPlayCount > 0 || totalSkipCount > 0 || totalListeningTimeMs > 0L
}

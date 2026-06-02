package com.launchpoint.wavdrop.data.model

data class StatsDashboardSummary(
    val totalSongs: Int,
    val totalPlayedTracks: Int,
    val totalPlayCount: Int,
    val totalSkipCount: Int,
    val totalListeningTimeMs: Long,
    val mostPlayedSongs: List<SongStatsSummary>,
    val recentlyPlayedSongs: List<SongStatsSummary>,
    val mostSkippedSongs: List<SongStatsSummary>,
)

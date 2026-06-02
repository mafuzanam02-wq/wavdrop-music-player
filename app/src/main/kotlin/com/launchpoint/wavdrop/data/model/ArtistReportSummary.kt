package com.launchpoint.wavdrop.data.model

data class ArtistReportSummary(
    val artistKey: String,
    val songCount: Int,
    val albumCount: Int,
    val playCount: Int,
    val skipCount: Int,
    val totalListeningTimeMs: Long,
    val lastPlayedAt: Long?,
)

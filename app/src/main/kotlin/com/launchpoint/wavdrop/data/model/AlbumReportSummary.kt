package com.launchpoint.wavdrop.data.model

data class AlbumReportSummary(
    val albumKey: String,
    val artist: String,
    val songCount: Int,
    val playCount: Int,
    val skipCount: Int,
    val totalListeningTimeMs: Long,
    val lastPlayedAt: Long?,
)

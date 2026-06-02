package com.launchpoint.wavdrop.data.model

data class SongStatsSummary(
    val song: Song,
    val playCount: Int,
    val skipCount: Int,
    val lastPlayedAt: Long?,
    val totalListeningTimeMs: Long,
)

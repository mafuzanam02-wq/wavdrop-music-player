package com.launchpoint.wavdrop.data.model

data class TrackStats(
    val songId: Long,
    val contentUri: String,
    val playCount: Int,
    val skipCount: Int,
    val lastPlayedAt: Long,           // epoch ms; 0 = never played
    val totalListeningTimeMs: Long,
    val isFavorite: Boolean,
)

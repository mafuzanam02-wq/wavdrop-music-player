package com.launchpoint.wavdrop.data.model

data class AlbumSummary(
    val albumId: Long,          // from song.albumId (may be 0)
    val albumKey: String,       // trimmed album name used for display and nav
    val artist: String,         // primary (most frequent) artist in the album
    val songCount: Int,
    val totalDurationMs: Long,
)

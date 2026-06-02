package com.launchpoint.wavdrop.data.model

data class ArtistSummary(
    val artistKey: String,      // trimmed artist name used for display and nav
    val songCount: Int,
    val albumCount: Int,        // distinct album keys within this artist
    val totalDurationMs: Long,
)

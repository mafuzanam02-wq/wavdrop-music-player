package com.launchpoint.wavdrop.data.model

/** Exactly what the Home Wrapped card renders (WC-05): the year label, the play total, the top artist and the fallback top track. */
data class HomeWrappedPreview(
    val year: Int,
    val displayLabel: String,
    val totalPlayCount: Int,
    val topArtistKey: String?,
    val topSong: Song?,
)

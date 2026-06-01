package com.launchpoint.wavdrop.data.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,       // milliseconds
    val uri: String,          // content:// URI — use for playback and art lookup
    val dateAdded: Long,      // epoch seconds (from MediaStore)
    val trackNumber: Int,
    val year: Int,
)

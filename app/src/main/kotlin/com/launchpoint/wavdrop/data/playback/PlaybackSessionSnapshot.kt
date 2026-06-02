package com.launchpoint.wavdrop.data.playback

import com.launchpoint.wavdrop.playback.RepeatMode

data class PlaybackSessionSnapshot(
    val queueSongIds: List<Long>,
    val currentSongId: Long?,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    val updatedAtMs: Long,
)

package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

data class NowPlayingState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isSeekable: Boolean = false,
)

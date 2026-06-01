package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

data class NowPlayingState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
)

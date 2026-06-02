package com.launchpoint.wavdrop.data.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.RepeatMode

internal object PlaybackSessionRules {

    fun parseQueueIds(raw: String): List<Long> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    fun clampIndex(index: Int, queueSize: Int): Int {
        if (queueSize == 0) return 0
        return index.coerceIn(0, queueSize - 1)
    }

    fun parseRepeatMode(raw: String?): RepeatMode = when (raw) {
        RepeatMode.ALL.name -> RepeatMode.ALL
        RepeatMode.ONE.name -> RepeatMode.ONE
        else                -> RepeatMode.OFF
    }

    fun resolveStartSong(
        sessionSongId: Long?,
        sessionIndex: Int,
        mappedQueue: List<Song>,
    ): Song? {
        if (mappedQueue.isEmpty()) return null
        if (sessionSongId != null) {
            val byId = mappedQueue.firstOrNull { it.id == sessionSongId }
            if (byId != null) return byId
        }
        return mappedQueue.getOrNull(clampIndex(sessionIndex, mappedQueue.size))
    }
}

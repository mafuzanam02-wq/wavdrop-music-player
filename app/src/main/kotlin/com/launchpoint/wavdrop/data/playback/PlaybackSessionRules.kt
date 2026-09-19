package com.launchpoint.wavdrop.data.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.playback.QueueNavigator
import com.launchpoint.wavdrop.playback.RepeatMode

internal object PlaybackSessionRules {

    fun parseQueueIds(raw: String): List<Long> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    fun parsePlaybackOrder(raw: String): List<Int> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun validatePlaybackOrder(playbackOrder: List<Int>?, queueSize: Int): List<Int>? {
        if (playbackOrder == null) return null
        if (queueSize == 0) return null
        if (playbackOrder.size != queueSize) return null
        if (playbackOrder.toSet().size != queueSize) return null
        if (playbackOrder.any { it !in 0 until queueSize }) return null
        return playbackOrder
    }

    fun mapSavedQueue(
        queueSongIds: List<Long>,
        availableSongs: List<Song>,
    ): List<Song>? {
        if (queueSongIds.isEmpty()) return null
        val songsById = availableSongs.associateBy { it.id }
        return queueSongIds.map { songId -> songsById[songId] ?: return null }
    }

    fun restorePlaybackOrder(
        savedPlaybackOrder: List<Int>?,
        queueSize: Int,
        currentQueueIndex: Int,
        shuffleEnabled: Boolean,
    ): List<Int> {
        validatePlaybackOrder(savedPlaybackOrder, queueSize)
            ?.takeIf { currentQueueIndex in it }
            ?.let { return it }

        return QueueNavigator.buildPlaybackOrder(
            queueSize = queueSize,
            currentIndex = currentQueueIndex,
            shuffleEnabled = shuffleEnabled,
        )
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

    /**
     * Transforms [snapshot] according to [settings] before it is handed to the session-restore
     * path in PlayerController.
     *
     * Returns null when [settings.rememberLastTrack] is false — the caller should skip restore
     * entirely in that case.
     *
     * Transformation rules (applied in order):
     * 1. rememberLastTrack = false  → return null (skip restore)
     * 2. restoreQueue = false       → collapse queue to the single playing song; currentIndex = 0
     * 3. rememberPosition = false   → zero the saved position
     */
    fun applyResumeBehavior(
        snapshot: PlaybackSessionSnapshot,
        settings: ResumeBehaviorSettings,
    ): PlaybackSessionSnapshot? {
        if (!settings.rememberLastTrack) return null

        var adjusted = snapshot

        if (!settings.restoreQueue) {
            val startIndex = resolveStartIndex(
                sessionSongId = adjusted.currentSongId,
                sessionIndex = adjusted.currentIndex,
                queueSongIds = adjusted.queueSongIds,
            )
            if (startIndex != null) {
                adjusted = adjusted.copy(
                    queueSongIds = listOf(adjusted.queueSongIds[startIndex]),
                    playbackOrder = null,
                    currentIndex = 0,
                )
            }
        }

        if (!settings.rememberPosition) {
            adjusted = adjusted.copy(positionMs = 0L)
        }

        return adjusted
    }

    fun resolveStartLibraryIndex(
        sessionSongId: Long?,
        sessionIndex: Int,
        mappedQueue: List<Song>,
    ): Int? = resolveStartIndex(
        sessionSongId = sessionSongId,
        sessionIndex = sessionIndex,
        queueSongIds = mappedQueue.map { it.id },
    )

    private fun resolveStartIndex(
        sessionSongId: Long?,
        sessionIndex: Int,
        queueSongIds: List<Long>,
    ): Int? {
        if (sessionIndex in queueSongIds.indices &&
            (sessionSongId == null || queueSongIds[sessionIndex] == sessionSongId)
        ) {
            return sessionIndex
        }

        if (sessionSongId == null) return null
        return queueSongIds.indices
            .filter { queueSongIds[it] == sessionSongId }
            .singleOrNull()
    }
}

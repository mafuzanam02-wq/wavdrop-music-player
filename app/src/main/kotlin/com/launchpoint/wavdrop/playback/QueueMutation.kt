package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/** Pure queue-mutation helpers used by [PlayerController]. Extracted for unit-testability. */
internal object QueueMutation {

    data class RemoveResult(val queue: List<Song>, val currentIndex: Int)

    /**
     * Removes the item at [playbackIndex] from [playbackQueue].
     * Returns [RemoveResult] with the new queue and adjusted current index, or null when
     * [playbackIndex] equals [currentPlaybackIndex] or is out of bounds.
     */
    fun remove(
        playbackQueue: List<Song>,
        playbackIndex: Int,
        currentPlaybackIndex: Int,
    ): RemoveResult? {
        if (playbackIndex == currentPlaybackIndex) return null
        if (playbackIndex !in playbackQueue.indices) return null
        val newQueue = playbackQueue.toMutableList().also { it.removeAt(playbackIndex) }
        val newCurrentIndex = if (playbackIndex < currentPlaybackIndex) {
            currentPlaybackIndex - 1
        } else {
            currentPlaybackIndex
        }
        return RemoveResult(newQueue, newCurrentIndex)
    }

    /**
     * Moves the item at [playbackIndex] to immediately after [currentPlaybackIndex].
     * Returns the reordered queue, or null if the operation is a no-op or invalid.
     */
    fun moveToPlayNext(
        playbackQueue: List<Song>,
        playbackIndex: Int,
        currentPlaybackIndex: Int,
    ): List<Song>? {
        val immediateNextIndex = currentPlaybackIndex + 1
        if (playbackIndex <= currentPlaybackIndex) return null
        if (playbackIndex == immediateNextIndex) return null
        if (playbackIndex !in playbackQueue.indices) return null
        val newQueue = playbackQueue.toMutableList()
        val song = newQueue.removeAt(playbackIndex)
        newQueue.add(immediateNextIndex, song)
        return newQueue
    }

    fun insertAfterCurrent(
        playbackQueue: List<Song>,
        song: Song,
        currentPlaybackIndex: Int,
    ): List<Song>? {
        if (playbackQueue.isEmpty()) return listOf(song)
        if (currentPlaybackIndex !in playbackQueue.indices) return null
        val insertIndex = (currentPlaybackIndex + 1).coerceAtMost(playbackQueue.size)
        return playbackQueue.toMutableList().also { it.add(insertIndex, song) }
    }

    fun append(
        playbackQueue: List<Song>,
        song: Song,
    ): List<Song> =
        playbackQueue.toMutableList().also { it.add(song) }

    /**
     * Swaps [playbackIndex] with [otherIndex] in [playbackQueue].
     * Both indices must be strictly after [currentPlaybackIndex] and within bounds.
     * Returns the reordered queue or null if invalid.
     */
    fun swapAdjacent(
        playbackQueue: List<Song>,
        playbackIndex: Int,
        otherIndex: Int,
        currentPlaybackIndex: Int,
    ): List<Song>? {
        if (playbackIndex <= currentPlaybackIndex || otherIndex <= currentPlaybackIndex) return null
        if (playbackIndex !in playbackQueue.indices || otherIndex !in playbackQueue.indices) return null
        val newQueue = playbackQueue.toMutableList()
        val tmp = newQueue[playbackIndex]
        newQueue[playbackIndex] = newQueue[otherIndex]
        newQueue[otherIndex] = tmp
        return newQueue
    }
}

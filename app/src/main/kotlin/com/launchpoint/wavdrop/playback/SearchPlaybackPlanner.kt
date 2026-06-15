package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

internal data class SearchPlaybackPlan(
    val queue: List<Song>,
    val currentIndex: Int,
)

internal object SearchPlaybackPlanner {
    fun preserveQueue(
        playbackQueue: List<Song>,
        currentPlaybackIndex: Int?,
        song: Song,
    ): SearchPlaybackPlan {
        val queue = QueueMutation.searchPreserveQueue(
            playbackQueue = playbackQueue,
            currentPlaybackIndex = currentPlaybackIndex,
            song = song,
        )
        return SearchPlaybackPlan(
            queue = queue,
            currentIndex = queue.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: 0,
        )
    }

    fun replaceQueue(
        searchContext: List<Song>,
        song: Song,
    ): SearchPlaybackPlan {
        val queue = searchContext.ifEmpty { listOf(song) }
        return SearchPlaybackPlan(
            queue = queue,
            currentIndex = queue.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: 0,
        )
    }
}

package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

internal data class SearchPlaybackPlan(
    val queue: List<Song>,
    val currentIndex: Int,
) {
    val currentSongId: Long?
        get() = queue.getOrNull(currentIndex)?.id
}

internal enum class PreserveSearchSyncAction {
    UsePlan,
    ClearPlan,
}

internal data class PreserveSearchSyncDecision(
    val action: PreserveSearchSyncAction,
    val plan: SearchPlaybackPlan?,
)

internal object SearchPlaybackPlanner {
    fun preserveQueue(
        playbackQueue: List<Song>,
        currentPlaybackIndex: Int?,
        song: Song,
    ): SearchPlaybackPlan? {
        if (playbackQueue.isEmpty()) {
            return SearchPlaybackPlan(queue = listOf(song), currentIndex = 0)
        }
        val resolvedCurrentIndex = currentPlaybackIndex
            ?.takeIf { it in playbackQueue.indices }
            ?: return null
        val queue = QueueMutation.searchPreserveQueue(
            playbackQueue = playbackQueue,
            currentPlaybackIndex = resolvedCurrentIndex,
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

    fun preserveSyncDecision(
        plan: SearchPlaybackPlan?,
        activeQueue: List<Song>,
        mediaSongId: Long?,
        mediaIndex: Int?,
        fromTransition: Boolean,
    ): PreserveSearchSyncDecision {
        if (plan == null) {
            return PreserveSearchSyncDecision(PreserveSearchSyncAction.ClearPlan, null)
        }
        val plannedSongId = plan.currentSongId
            ?: return PreserveSearchSyncDecision(PreserveSearchSyncAction.ClearPlan, null)
        val activeMatchesPlan = activeQueue.map { it.id } == plan.queue.map { it.id }
        if (!activeMatchesPlan || plan.currentIndex !in activeQueue.indices) {
            return PreserveSearchSyncDecision(PreserveSearchSyncAction.ClearPlan, null)
        }
        val confirmed = mediaSongId == plannedSongId && mediaIndex == plan.currentIndex
        if (confirmed) {
            return PreserveSearchSyncDecision(PreserveSearchSyncAction.ClearPlan, null)
        }
        if (fromTransition && mediaSongId != null && mediaSongId != plannedSongId) {
            return PreserveSearchSyncDecision(PreserveSearchSyncAction.ClearPlan, null)
        }
        return PreserveSearchSyncDecision(PreserveSearchSyncAction.UsePlan, plan)
    }
}

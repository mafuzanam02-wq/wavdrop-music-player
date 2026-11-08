package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRules
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.playback.SavedQueueRestorePlanner
import com.launchpoint.wavdrop.data.playback.SavedQueueRestoreResult
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings

internal enum class PlaybackResumptionFailure {
    NO_SAVED_SESSION,
    DISABLED_BY_SETTINGS,
    SAVED_QUEUE_UNAVAILABLE,
    CURRENT_OCCURRENCE_UNAVAILABLE,
}

internal data class PlaybackResumptionPlan(
    val libraryQueue: List<Song>,
    val playbackOrder: List<Int>,
    val playbackQueue: List<Song>,
    val mediaItems: List<MediaItem>,
    val currentLibraryIndex: Int,
    val startPlaybackIndex: Int,
    val startPositionMs: Long,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
    /**
     * P5-E: a plan for a queue that is installed as a bounded physical window. [mediaItems] is then EMPTY (the full queue is never
     * materialized for Media3): the controller builds the window, installs it, and hands Media3 the window items with the PHYSICAL
     * start index ([PlayerController.adoptPlaybackResumption]). [startPlaybackIndex] is always the LOGICAL index.
     */
    val bounded: Boolean = false,
    /** P5-F16B: some saved occurrences were unavailable; adopting this plan must protect the persisted session. */
    val restoredPartially: Boolean = false,
)

internal sealed interface PlaybackResumptionResult {
    data class Ready(val plan: PlaybackResumptionPlan) : PlaybackResumptionResult
    data class Unavailable(val reason: PlaybackResumptionFailure) : PlaybackResumptionResult
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun PlaybackResumptionPlan.toMedia3Resumption(): MediaSession.MediaItemsWithStartPosition =
    if (bounded) error("a bounded plan is converted by PlayerController.adoptPlaybackResumption (physical window, physical start index)") else
    MediaSession.MediaItemsWithStartPosition(
        mediaItems,
        startPlaybackIndex,
        startPositionMs,
    )

/** Maps the persisted occurrence-safe Wavdrop session into Media3 playback order. */
internal object PlaybackResumptionMapper {
    fun map(
        snapshot: PlaybackSessionSnapshot?,
        settings: ResumeBehaviorSettings,
        availableSongs: List<Song>,
        /** Whether a queue of this size is installed as a bounded window (the policy, plus the topology that can present it). */
        boundedModeFor: (queueSize: Int) -> Boolean = { false },
    ): PlaybackResumptionResult {
        val rawSnapshot = snapshot
            ?: return PlaybackResumptionResult.Unavailable(PlaybackResumptionFailure.NO_SAVED_SESSION)
        val adjusted = PlaybackSessionRules.applyResumeBehavior(rawSnapshot, settings)
            ?: return PlaybackResumptionResult.Unavailable(PlaybackResumptionFailure.DISABLED_BY_SETTINGS)
        // The same pure rule as PlayerController hydration, so both routes interpret a partly available queue identically.
        val restore = when (val planned = SavedQueueRestorePlanner.plan(adjusted, availableSongs)) {
            is SavedQueueRestoreResult.Restored -> planned.restore
            is SavedQueueRestoreResult.Unresolvable -> return PlaybackResumptionResult.Unavailable(
                if (planned.reason == SavedQueueRestoreResult.Reason.CURRENT_OCCURRENCE_UNIDENTIFIABLE) {
                    PlaybackResumptionFailure.CURRENT_OCCURRENCE_UNAVAILABLE
                } else {
                    PlaybackResumptionFailure.SAVED_QUEUE_UNAVAILABLE
                },
            )
        }
        val libraryQueue = restore.songs
        val currentLibraryIndex = restore.currentLibraryIndex
        val playbackOrder = restore.playbackOrder
        val startPlaybackIndex = playbackOrder.indexOf(currentLibraryIndex)
        if (startPlaybackIndex < 0) {
            return PlaybackResumptionResult.Unavailable(
                PlaybackResumptionFailure.CURRENT_OCCURRENCE_UNAVAILABLE,
            )
        }
        val playbackQueue = playbackOrder.map(libraryQueue::get)
        val bounded = boundedModeFor(playbackQueue.size)
        return PlaybackResumptionResult.Ready(
            PlaybackResumptionPlan(
                libraryQueue = libraryQueue,
                playbackOrder = playbackOrder,
                playbackQueue = playbackQueue,
                mediaItems = if (bounded) emptyList() else playbackQueue.map(Song::toPlaybackMediaItem),
                currentLibraryIndex = currentLibraryIndex,
                startPlaybackIndex = startPlaybackIndex,
                startPositionMs = restore.positionMs,
                repeatMode = adjusted.repeatMode,
                shuffleEnabled = adjusted.shuffleEnabled,
                bounded = bounded,
                restoredPartially = restore.isPartial,
            )
        )
    }
}

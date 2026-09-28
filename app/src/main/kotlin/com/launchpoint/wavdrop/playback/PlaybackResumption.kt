package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRules
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
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
)

internal sealed interface PlaybackResumptionResult {
    data class Ready(val plan: PlaybackResumptionPlan) : PlaybackResumptionResult
    data class Unavailable(val reason: PlaybackResumptionFailure) : PlaybackResumptionResult
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun PlaybackResumptionPlan.toMedia3Resumption(): MediaSession.MediaItemsWithStartPosition =
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
    ): PlaybackResumptionResult {
        val rawSnapshot = snapshot
            ?: return PlaybackResumptionResult.Unavailable(PlaybackResumptionFailure.NO_SAVED_SESSION)
        val adjusted = PlaybackSessionRules.applyResumeBehavior(rawSnapshot, settings)
            ?: return PlaybackResumptionResult.Unavailable(PlaybackResumptionFailure.DISABLED_BY_SETTINGS)
        val libraryQueue = PlaybackSessionRules.mapSavedQueue(
            queueSongIds = adjusted.queueSongIds,
            availableSongs = availableSongs,
        ) ?: return PlaybackResumptionResult.Unavailable(
            PlaybackResumptionFailure.SAVED_QUEUE_UNAVAILABLE,
        )
        val currentLibraryIndex = PlaybackSessionRules.resolveStartLibraryIndex(
            sessionSongId = adjusted.currentSongId,
            sessionIndex = adjusted.currentIndex,
            mappedQueue = libraryQueue,
        ) ?: return PlaybackResumptionResult.Unavailable(
            PlaybackResumptionFailure.CURRENT_OCCURRENCE_UNAVAILABLE,
        )
        val playbackOrder = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = adjusted.playbackOrder,
            queueSize = libraryQueue.size,
            currentQueueIndex = currentLibraryIndex,
            shuffleEnabled = adjusted.shuffleEnabled,
        )
        val startPlaybackIndex = playbackOrder.indexOf(currentLibraryIndex)
        if (startPlaybackIndex < 0) {
            return PlaybackResumptionResult.Unavailable(
                PlaybackResumptionFailure.CURRENT_OCCURRENCE_UNAVAILABLE,
            )
        }
        val playbackQueue = playbackOrder.map(libraryQueue::get)
        return PlaybackResumptionResult.Ready(
            PlaybackResumptionPlan(
                libraryQueue = libraryQueue,
                playbackOrder = playbackOrder,
                playbackQueue = playbackQueue,
                mediaItems = playbackQueue.map(Song::toPlaybackMediaItem),
                currentLibraryIndex = currentLibraryIndex,
                startPlaybackIndex = startPlaybackIndex,
                startPositionMs = adjusted.positionMs,
                repeatMode = adjusted.repeatMode,
                shuffleEnabled = adjusted.shuffleEnabled,
            )
        )
    }
}

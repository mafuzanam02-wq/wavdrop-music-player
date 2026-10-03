package com.launchpoint.wavdrop.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRules
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import com.launchpoint.wavdrop.data.settings.PreviousButtonBehavior
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal enum class PlaybackSessionPersistenceAction {
    NONE,
    CLEAR,
    SAVE,
}

internal fun playbackSessionPersistenceAction(
    isExternalPlayback: Boolean,
    queueIsEmpty: Boolean,
): PlaybackSessionPersistenceAction = when {
    isExternalPlayback -> PlaybackSessionPersistenceAction.NONE
    queueIsEmpty       -> PlaybackSessionPersistenceAction.CLEAR
    else               -> PlaybackSessionPersistenceAction.SAVE
}

internal data class PlaybackRequest(
    val queue: List<Song>,
    val startIndex: Int,
    val preservePlaybackOrder: Boolean = false,
    val source: PlaybackQueueSource = PlaybackQueueSource.Other,
)

internal data class QueueStart(
    val queue: List<Song>,
    val startIndex: Int,
) {
    val startSong: Song get() = queue[startIndex]
}

internal fun resolveQueueStart(queue: List<Song>, startIndex: Int): QueueStart? =
    startIndex.takeIf { it in queue.indices }?.let { QueueStart(queue, it) }

/**
 * Compatibility resolver for callers that only know the song: an empty queue normalizes to
 * `[startSong]`; otherwise the song must match exactly ONE occurrence. Absent or ambiguous (duplicate
 * ids) yields null - never the first match, never index 0.
 */
internal fun resolveQueueStartBySong(queue: List<Song>, startSong: Song): QueueStart? {
    if (queue.isEmpty()) return QueueStart(listOf(startSong), 0)
    var match = -1
    queue.forEachIndexed { index, song ->
        if (song.id == startSong.id) {
            if (match >= 0) return null
            match = index
        }
    }
    return if (match >= 0) QueueStart(queue, match) else null
}

internal fun resolveSessionCurrentLibraryIndex(
    libraryQueue: List<Song>,
    playbackOrder: List<Int>,
    currentPlaybackIndex: Int?,
    exactCurrentLibraryIndex: Int?,
    currentSongId: Long?,
): Int? {
    val validatedOrder = PlaybackSessionRules.validatePlaybackOrder(
        playbackOrder = playbackOrder,
        queueSize = libraryQueue.size,
    )
    if (validatedOrder != null) {
        currentPlaybackIndex
            ?.takeIf { it in validatedOrder.indices }
            ?.let { return validatedOrder[it] }
    }

    exactCurrentLibraryIndex
        ?.takeIf { it in libraryQueue.indices }
        ?.let { return it }

    if (currentSongId == null) return null
    return libraryQueue.indices
        .filter { libraryQueue[it].id == currentSongId }
        .singleOrNull()
}

internal class PlaybackSessionPersistenceGate {
    private val mutex = Mutex()
    private val revisionLock = Any()
    private var latestRevision = 0L

    fun nextRevision(): Long = synchronized(revisionLock) {
        ++latestRevision
    }

    suspend fun runIfLatest(revision: Long, operation: suspend () -> Unit): Boolean =
        mutex.withLock {
            val isLatest = synchronized(revisionLock) { revision == latestRevision }
            if (!isLatest) return@withLock false
            operation()
            true
        }
}

internal fun repeatModeFromPlayerMode(playerRepeatMode: Int): RepeatMode = when (playerRepeatMode) {
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    else                   -> RepeatMode.OFF
}

internal fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
}

/**
 * Returns the logical repeat mode to adopt when Media3 reports [incoming] while the current
 * logical mode is [current], or null when no change is required.
 *
 * Returning null when the values already match is what prevents an infinite callback loop:
 * Wavdrop's own writes to controller.repeatMode fire onRepeatModeChanged with a value that
 * already equals the logical field, so nothing is re-applied or re-emitted.
 */
internal fun externalRepeatModeUpdate(current: RepeatMode, incoming: RepeatMode): RepeatMode? =
    incoming.takeIf { it != current }

/**
 * Wavdrop keeps Media3 shuffle permanently OFF and models shuffle logically via playbackOrder.
 * Whenever an external surface enables native shuffle it must be reasserted false. Reasserting
 * false re-enters the callback with `false`, which returns false here — so there is no loop.
 */
internal fun shouldReassertMediaShuffleOff(shuffleModeEnabled: Boolean): Boolean = shuffleModeEnabled

internal fun resolveCurrentPlaybackIndex(
    playbackQueue: List<Song>,
    controllerIndex: Int?,
    controllerSongId: Long?,
    stateIndex: Int?,
    stateSongId: Long?,
    playerQueueNeedsSync: Boolean,
): Int? {
    if (playbackQueue.isEmpty()) return null

    val controllerIndexMatchesSong = controllerIndex != null &&
        controllerIndex in playbackQueue.indices &&
        controllerSongId != null &&
        playbackQueue[controllerIndex].id != controllerSongId

    if (!playerQueueNeedsSync && controllerIndex != null && controllerIndex in playbackQueue.indices) {
        if (controllerIndexMatchesSong) return null
        return controllerIndex
    }

    if (stateIndex != null && stateIndex in playbackQueue.indices) {
        if (stateSongId == null || playbackQueue[stateIndex].id == stateSongId) {
            return stateIndex
        }
    }

    return uniquePlaybackIndexForSongId(playbackQueue, controllerSongId)
        ?: uniquePlaybackIndexForSongId(playbackQueue, stateSongId)
}

/** The song at an already-resolved playback occurrence, or null when it is unresolved/out of range. */
internal fun songAtResolvedPlaybackIndex(playbackQueue: List<Song>, resolvedIndex: Int?): Song? =
    resolvedIndex?.let { playbackQueue.getOrNull(it) }

/**
 * Which duties a Media3 callback owns when it synchronizes Now Playing. Every sync still happens; the
 * flags decide who performs the StatsTracker song transition and session persistence so a single
 * state change is never owned twice.
 */
internal data class CallbackOwnership(
    val notifiesStats: Boolean,
    val persistsSession: Boolean,
)

internal object PlaybackCallbackOwnership {
    /**
     * `DISCONTINUITY_REASON_REMOVE` is emitted (before the media-item transition) when WavDrop replaces
     * the playlist, e.g. current-song deletion; the PLAYLIST_CHANGED media-item transition owns that
     * song change, so REMOVE only refreshes state. AUTO_TRANSITION keeps its pre-existing handling
     * (stats are handled explicitly in the callback; the sync itself does not notify). Everything else
     * is unchanged.
     */
    fun forPositionDiscontinuity(reason: Int): CallbackOwnership = when (reason) {
        Player.DISCONTINUITY_REASON_REMOVE -> CallbackOwnership(notifiesStats = false, persistsSession = false)
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> CallbackOwnership(notifiesStats = false, persistsSession = true)
        else -> CallbackOwnership(notifiesStats = true, persistsSession = true)
    }

    /** The media-item transition is the authoritative song-transition callback (incl. PLAYLIST_CHANGED). */
    fun forMediaItemTransition(reason: Int): CallbackOwnership =
        CallbackOwnership(notifiesStats = true, persistsSession = true)
}

internal enum class SongDeletionRoute { Current, NonCurrent, Unresolved }

/**
 * Routes a library-song deletion by the RESOLVED current occurrence (never a possibly stale Now Playing
 * song). The occurrence is positional; only its song id is compared with [deletedSongId].
 */
internal fun routeSongDeletion(
    playbackQueue: List<Song>,
    resolvedCurrentIndex: Int?,
    deletedSongId: Long,
): SongDeletionRoute {
    val current = songAtResolvedPlaybackIndex(playbackQueue, resolvedCurrentIndex)
        ?: return SongDeletionRoute.Unresolved
    return if (current.id == deletedSongId) SongDeletionRoute.Current else SongDeletionRoute.NonCurrent
}

private fun uniquePlaybackIndexForSongId(playbackQueue: List<Song>, songId: Long?): Int? {
    if (songId == null) return null
    var match: Int? = null
    playbackQueue.forEachIndexed { index, song ->
        if (song.id == songId) {
            if (match != null) return null
            match = index
        }
    }
    return match
}

internal enum class BatchQueuePlayerSyncAction {
    NoOp,
    ReplaceFutureSuffix,
    FullQueueSync,
    MarkDirty,
}

internal data class BatchQueuePlayerSyncPlan(
    val action: BatchQueuePlayerSyncAction,
    val futureStartIndex: Int,
    val suffix: List<Song> = emptyList(),
)

internal fun planBatchQueuePlayerSync(
    oldPlaybackQueue: List<Song>,
    newPlaybackQueue: List<Song>,
    currentPlaybackIndex: Int,
    controllerAvailable: Boolean,
    playerQueueNeedsSync: Boolean,
    controllerCurrentIndex: Int?,
    controllerSongId: Long?,
    controllerMediaItemCount: Int?,
): BatchQueuePlayerSyncPlan {
    val futureStartIndex = currentPlaybackIndex + 1
    if (!controllerAvailable) {
        return BatchQueuePlayerSyncPlan(
            action = BatchQueuePlayerSyncAction.MarkDirty,
            futureStartIndex = futureStartIndex,
        )
    }
    if (playerQueueNeedsSync ||
        currentPlaybackIndex !in oldPlaybackQueue.indices ||
        currentPlaybackIndex !in newPlaybackQueue.indices ||
        controllerCurrentIndex != currentPlaybackIndex ||
        controllerMediaItemCount != oldPlaybackQueue.size ||
        (controllerSongId != null && newPlaybackQueue[currentPlaybackIndex].id != controllerSongId) ||
        oldPlaybackQueue.take(futureStartIndex) != newPlaybackQueue.take(futureStartIndex)
    ) {
        return BatchQueuePlayerSyncPlan(
            action = BatchQueuePlayerSyncAction.FullQueueSync,
            futureStartIndex = futureStartIndex,
        )
    }

    val suffix = newPlaybackQueue.drop(futureStartIndex)
    return if (oldPlaybackQueue.drop(futureStartIndex) == suffix) {
        BatchQueuePlayerSyncPlan(
            action = BatchQueuePlayerSyncAction.NoOp,
            futureStartIndex = futureStartIndex,
        )
    } else {
        BatchQueuePlayerSyncPlan(
            action = BatchQueuePlayerSyncAction.ReplaceFutureSuffix,
            futureStartIndex = futureStartIndex,
            suffix = suffix,
        )
    }
}

enum class PlayerHydrationResult {
    AlreadyHydrated,
    Hydrated,
    NoSavedSession,
    FilteredBySettings,
    NoResolvableSong,
    ControllerUnavailable,
    MediaSetupFailed,
    SkippedActiveQueue,
}

internal fun playerHydrationAllowsPlay(result: PlayerHydrationResult): Boolean =
    result == PlayerHydrationResult.Hydrated ||
        result == PlayerHydrationResult.AlreadyHydrated

internal const val PLAYBACK_POSITION_CHECKPOINT_INTERVAL_MS = 10_000L
internal const val PLAYBACK_POSITION_CHECKPOINT_MIN_DELTA_MS = 5_000L

internal fun shouldCheckpointPlaybackPosition(
    isPlaying: Boolean,
    isExternalPlayback: Boolean,
    queueIsEmpty: Boolean,
    nowElapsedRealtimeMs: Long,
    lastCheckpointElapsedRealtimeMs: Long,
    currentPositionMs: Long,
    lastCheckpointPositionMs: Long,
): Boolean {
    if (!isPlaying || isExternalPlayback || queueIsEmpty) return false
    if (lastCheckpointElapsedRealtimeMs < 0L || lastCheckpointPositionMs < 0L) return false
    if (nowElapsedRealtimeMs - lastCheckpointElapsedRealtimeMs <
        PLAYBACK_POSITION_CHECKPOINT_INTERVAL_MS
    ) return false
    return kotlin.math.abs(currentPositionMs - lastCheckpointPositionMs) >=
        PLAYBACK_POSITION_CHECKPOINT_MIN_DELTA_MS
}

internal sealed interface PlayAllNextPlan {
    data object NoOp : PlayAllNextPlan
    data class StartQueue(
        val queue: List<Song>,
        val startSong: Song,
    ) : PlayAllNextPlan
    data class InsertAfterCurrent(
        val songs: List<Song>,
    ) : PlayAllNextPlan
}

internal fun planPlayAllNext(
    songs: List<Song>,
    hasActiveCurrentItem: Boolean,
): PlayAllNextPlan {
    if (songs.isEmpty()) return PlayAllNextPlan.NoOp
    return if (hasActiveCurrentItem) {
        PlayAllNextPlan.InsertAfterCurrent(songs)
    } else {
        PlayAllNextPlan.StartQueue(queue = songs, startSong = songs.first())
    }
}

internal enum class QueueAddPlan {
    /** Empty batch: nothing to do. */
    NoOp,

    /** Genuinely empty queue: start a new queue with the supplied song(s). */
    StartNewQueue,

    /** An existing queue: append to its tail, preserving every existing item and the current song. */
    AppendPreservingQueue,
}

/**
 * Decides how Add-to-Queue / Add-All-to-Queue should be handled.
 *
 * [currentIndexResolvable] is accepted to make the Phase 3 invariant explicit and testable: a
 * transient inability to resolve the current playback index must NEVER be treated as "there is no
 * queue". For any non-empty queue the outcome is [QueueAddPlan.AppendPreservingQueue] regardless of
 * [currentIndexResolvable]; only a genuinely empty queue starts a new one. Appending to the tail
 * does not need the current index.
 */
internal fun planQueueAdd(
    hasExistingQueue: Boolean,
    isBatchEmpty: Boolean,
    @Suppress("UNUSED_PARAMETER") currentIndexResolvable: Boolean,
): QueueAddPlan = when {
    isBatchEmpty -> QueueAddPlan.NoOp
    // An existing queue is always preserved by appending — resolvability of the current index does
    // NOT gate this (that `||` was the Phase 3 defect). Only a genuinely empty queue starts anew.
    hasExistingQueue -> QueueAddPlan.AppendPreservingQueue
    else -> QueueAddPlan.StartNewQueue
}

/**
 * Resolves which occurrence of [songId] a song-selection tap should jump to, given the active
 * [currentPlaybackIndex]. A song id does NOT uniquely identify a queue occurrence once duplicates
 * are legal, so selection is positional relative to the current playback position:
 *
 * 1. the CURRENT occurrence, if the current item is [songId];
 * 2. otherwise the NEAREST UPCOMING occurrence strictly after current;
 * 3. otherwise the NEAREST HISTORICAL occurrence strictly before current (nearest, not oldest);
 * 4. otherwise -1 (not present).
 *
 * When [currentPlaybackIndex] is null or out of bounds the position is unknown, so a single
 * occurrence is returned unambiguously but multiple occurrences yield -1 (the intended occurrence
 * cannot be determined; callers fall back to their normal playback path rather than guess).
 *
 * Operates on the playback queue (the active playback sequence), so it is correct under shuffle.
 * Pure so it can be tested without a MediaController.
 */
internal fun resolveQueueOccurrenceIndex(
    queue: List<Song>,
    currentPlaybackIndex: Int?,
    songId: Long,
): Int {
    if (queue.isEmpty()) return -1
    val current = currentPlaybackIndex?.takeIf { it in queue.indices }
    if (current == null) {
        // Position unknown: only a single occurrence is unambiguous. Multiple occurrences cannot be
        // safely disambiguated, so report not-found and let the caller use its normal path.
        val matches = queue.indices.filter { queue[it].id == songId }
        return matches.singleOrNull() ?: -1
    }
    // 1. current occurrence
    if (queue[current].id == songId) return current
    // 2. nearest upcoming occurrence (strictly after current)
    for (i in current + 1 until queue.size) {
        if (queue[i].id == songId) return i
    }
    // 3. nearest historical occurrence (strictly before current, nearest first)
    for (i in current - 1 downTo 0) {
        if (queue[i].id == songId) return i
    }
    // 4. not found
    return -1
}

/** Outcome of a [PlayerController.jumpToSongById] command decision. */
internal enum class QueueJumpAction {
    /** The occurrence cannot be safely resolved; the caller should use its fallback path. */
    Reject,

    /** A live controller is available; seek the resolved occurrence immediately. */
    ExecuteNow,

    /**
     * The occurrence is valid but the controller is temporarily unavailable; accept the command,
     * retain it as a bounded pending jump, and trigger demand-driven reconnection.
     */
    DeferAndReconnect,
}

/**
 * Decides how a resolved queue-jump command should be handled.
 *
 * [resolvedIndex] is the output of [resolveQueueOccurrenceIndex] (< 0 means absent/ambiguous). The
 * fix: a valid occurrence with no controller must NOT be treated as immediately executed (the
 * pre-fix contract returned true while [jumpToQueueItem] silently no-oped) — it is deferred and a
 * reconnection is requested instead.
 */
internal fun planQueueJump(
    resolvedIndex: Int,
    controllerAvailable: Boolean,
): QueueJumpAction = when {
    resolvedIndex < 0 -> QueueJumpAction.Reject
    controllerAvailable -> QueueJumpAction.ExecuteNow
    else -> QueueJumpAction.DeferAndReconnect
}

/** How a pending queue jump should be resolved against the (possibly mutated) queue on reconnect. */
internal sealed interface PendingQueueJumpResolution {
    data class Seek(val playbackIndex: Int) : PendingQueueJumpResolution
    data object Discard : PendingQueueJumpResolution
}

/**
 * Validates a deferred queue jump against the current [queue] when the controller reconnects.
 *
 * The queue may have mutated while the controller was unavailable (e.g. a remove shifted indices),
 * so [resolvedPlaybackIndex] can no longer be trusted blindly:
 * 1. if it still points at the same [songId], seek that exact occurrence;
 * 2. otherwise re-resolve the occurrence by [songId] using the Phase 4 contract and seek that;
 * 3. otherwise discard — never seek a different song because the queue moved.
 */
internal fun resolvePendingQueueJump(
    queue: List<Song>,
    currentPlaybackIndex: Int?,
    songId: Long,
    resolvedPlaybackIndex: Int,
): PendingQueueJumpResolution {
    if (resolvedPlaybackIndex in queue.indices && queue[resolvedPlaybackIndex].id == songId) {
        return PendingQueueJumpResolution.Seek(resolvedPlaybackIndex)
    }
    val reResolved = resolveQueueOccurrenceIndex(
        queue = queue,
        currentPlaybackIndex = currentPlaybackIndex,
        songId = songId,
    )
    return if (reResolved >= 0) {
        PendingQueueJumpResolution.Seek(reResolved)
    } else {
        PendingQueueJumpResolution.Discard
    }
}

/**
 * Singleton bridge between the UI layer and PlaybackService.
 *
 * Connects to PlaybackService via MediaController asynchronously on first
 * instantiation. Any playSong() call received before the connection is
 * ready is queued and executed once the controller is available.
 *
 * Queue model
 * -----------
 * libraryQueue   – songs in their original source order (playlist / album / all-songs).
 *                  Used for session persistence and as the authoritative song list.
 * playbackOrder  – indices into libraryQueue that define the playback sequence.
 *                  Identity [0,1,2,...] when shuffle is OFF; shuffled when ON.
 * playbackQueue  – songs in playback order (derived: playbackOrder.map { libraryQueue[it] }).
 *                  THIS is what ExoPlayer is always loaded with.
 *
 * Because ExoPlayer holds playbackQueue, controller.currentMediaItemIndex equals the
 * playback index directly. Auto-transitions therefore follow the shuffle order naturally.
 * Manual seekTo(playbackIndex, 0L) also requires no conversion.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsTracker: StatsTracker,
    private val sessionRepository: PlaybackSessionRepository,
    private val resumeBehaviorRepository: ResumeBehaviorSettingsRepository,
    private val appSettingsRepository: AppSettingsRepository,
) {
    private companion object {
        const val TAG = "WavStats-PC"
        const val SEARCH_TAG = "WavdropSearchPlayback"
        const val GAPLESS_TAG = "WavdropGapless"
        const val RESUME_TAG = "WavdropResume"
        const val QUEUE_PERF_TAG = "WavdropQueuePerf"
        const val EXTERNAL_AUDIO_SONG_ID = Long.MIN_VALUE
        const val BLUETOOTH_RESUME_DEBOUNCE_MS = 1_500L
        const val MEDIA_ITEM_CACHE_MAX_SIZE = 12_288

        const val DEBUG_STATS = false
    }

    private enum class ResumeAttemptResult {
        STARTED,
        SKIPPED_BY_SETTING,
        SKIPPED_NO_SESSION,
        SKIPPED_CONTROLLER_NOT_READY,
        ALREADY_PLAYING,
        FAILED,
    }

    private var mediaController: MediaController? = null
    private val _progressPlayer = MutableStateFlow<Player?>(null)
    val progressPlayer: StateFlow<Player?> = _progressPlayer.asStateFlow()

    // Connection lifecycle for [mediaController]. Mutated only on the application main thread
    // (init, the buildAsync completion listener, and MediaController.Listener callbacks all run
    // there), so no additional synchronization is required. Modelling this explicitly is what
    // makes a failed/lost connection recover: a failed attempt returns to Disconnected and the
    // next demand ([awaitMediaController]) starts a fresh build.
    private var controllerConnectionState = ControllerConnectionState.Disconnected

    // Monotonic identity for the in-flight buildAsync attempt. Incremented when an attempt starts
    // and when release() invalidates all in-flight attempts. Only the completion whose captured
    // generation still equals this value is authoritative (see ControllerAttemptOwnership), so a
    // late completion from a superseded/released attempt can neither install a stale controller
    // nor reset the state of a newer attempt. Main-thread confined like controllerConnectionState.
    private var controllerConnectionGeneration = 0L

    private var pendingPlaybackRequest: PlaybackRequest? = null
    private var pendingPreserveSearchRequest: PreserveSearchRequest? = null
    private var pendingExternalPlaybackRequest: ExternalPlaybackRequest? = null
    private var pendingPreserveSearchPlan: SearchPlaybackPlan? = null
    // Single bounded pending queue-jump (Recently Played tap deferred while the controller is
    // unavailable). Latest tap wins; drained on reconnect after the queue-replacing requests.
    private var pendingQueueJumpRequest: QueueJumpRequest? = null

    // Bounded, latest-wins transport intents captured while the controller is unavailable (Phase 7).
    // Each is a single O(1) slot — no unbounded command list. Drained once in drainPendingRequests
    // (lowest precedence: a queue-replacing/restore/jump request supersedes them). See
    // [PendingTransport] for the supersession/stale rules.
    private var pendingPlayWhenReady: Boolean? = null       // STATE_INTENT: desired play(true)/pause(false)
    private var pendingSeek: PendingSeek? = null            // latest seek, bound to its queue occurrence
    private var pendingNavigation: NavigationIntent? = null // latest next/previous; recomputed on drain
    private val sessionHydrationMutex = Mutex()
    private val sessionPersistenceGate = PlaybackSessionPersistenceGate()
    private var isExternalPlayback = false

    // libraryQueue: source order. playbackOrder: indices into libraryQueue (identity or shuffled).
    // playbackQueue: songs in playback order = playbackOrder.map { libraryQueue[it] }.
    // ExoPlayer is ALWAYS loaded with playbackQueue, so currentMediaItemIndex == playback index.
    private var libraryQueue: List<Song> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var playbackQueue: List<Song> = emptyList()
    private var playerQueueNeedsSync: Boolean = false
    private val mediaItemCache = object : LinkedHashMap<MediaItemCacheKey, MediaItem>(
        MEDIA_ITEM_CACHE_MAX_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaItemCacheKey, MediaItem>?): Boolean =
            size > MEDIA_ITEM_CACHE_MAX_SIZE
    }
    private var shuffleEnabled: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF
    private var previousButtonBehavior: PreviousButtonBehavior = PreviousButtonBehavior.DEFAULT

    // Last position observed by the 500 ms ticker. Starts at -1 (uninitialized).
    // Reset to -1 whenever a new song/session starts so the loop detector doesn't see
    // a false wrap from the old song's late position to the new song's early position.
    private var lastKnownPositionMs: Long = -1L
    private var lastPositionCheckpointElapsedRealtimeMs: Long = -1L
    private var lastPositionCheckpointMs: Long = -1L

    private val _nowPlayingState = MutableStateFlow(NowPlayingState())
    val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()

    // Transient, fire-and-forget user-facing playback messages (Phase 8 bad-media recovery). A
    // single-slot conflating buffer: recovery emits at most one message per episode, and a UI that
    // is not currently collecting (e.g. Now Playing closed) simply misses it rather than backlogging
    // a queue of stale toasts. Not a second global snackbar framework — just a message source the
    // existing Now Playing snackbar consumes.
    private val _userMessages = MutableSharedFlow<PlaybackUserMessage>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val userMessages: SharedFlow<PlaybackUserMessage> = _userMessages.asSharedFlow()

    // Bad-media recovery episode state (Phase 8). null == no episode in progress. Bounded: the
    // planner guarantees at most `playbackQueue.size` attempted occurrences per episode, so there
    // is no infinite skip/retry loop. Reset on successful playback, queue replacement, explicit
    // user selection/navigation, or a queue-generation change. Main-thread confined.
    private var badMediaRecoveryEpisode: BadMediaRecoveryState? = null
    private var badMediaRecoverySkipNotified = false
    private var badMediaRecoveryExhaustionNotified = false

    // Monotonic queue identity. Bumped whenever the queue is replaced or structurally mutated, so a
    // recovery episode opened against an older generation is invalidated (occurrence indices are no
    // longer meaningful). Occurrence identity is (queueGeneration, playbackIndex).
    private var queueGeneration: Long = 0L

    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    // Set to true by onBluetoothDeviceRemoved / onWiredDeviceRemoved when the device
    // disconnects while playback is active. In-memory only; the persisted counterpart in
    // ResumeBehaviorSettingsRepository survives process death.
    private var wasInterruptedByBluetooth = false
    private var wasInterruptedByWired = false

    // Epoch-ms of the last Bluetooth resume attempt. Used to debounce devices that fire
    // separate A2DP/headset/hearing-aid connection events within a few hundred milliseconds.
    private var lastBluetoothResumeAttemptAtMs = 0L

    // Request ownership for automatic Bluetooth resume (debounce is not identity).
    private val automaticResumeAuthority = AutomaticResumeAuthority()

    // Serializes automatic Bluetooth resume attempts + entitlement settlement only (not user
    // transport, wired resume, or queue work), so the newest request mutates entitlement last.
    private val bluetoothResumeMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTickerJob: Job? = null
    private var wiredResumeJob: Job? = null
    private var sleepTimerJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (DEBUG_STATS) {
                val songId = _nowPlayingState.value.song?.id
                val pos = mediaController?.currentPosition ?: -1
                Log.d(TAG, "[isPlayingChanged] isPlaying=$isPlaying songId=$songId pos=$pos repeatMode=$repeatMode")
            }
            syncNowPlayingState()
            if (isPlaying) {
                if (!isExternalPlayback) {
                    statsTracker.onPlaybackStarted()
                }
                if (lastPositionCheckpointElapsedRealtimeMs < 0L) {
                    lastPositionCheckpointElapsedRealtimeMs = SystemClock.elapsedRealtime()
                    lastPositionCheckpointMs =
                        mediaController?.currentPosition?.coerceAtLeast(0L)
                            ?: _nowPlayingState.value.positionMs.coerceAtLeast(0L)
                }
                startPositionTicker()
            } else {
                if (!isExternalPlayback) {
                    statsTracker.onPlaybackPaused()
                }
                stopPositionTicker()
                syncPosition()
                saveSessionAsync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[mediaItemTransition] reason=$reason mediaId=${mediaItem?.mediaId} repeatMode=$repeatMode isPlaying=${mediaController?.isPlaying}")
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && handlePendingAutomaticTransition()) {
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                _sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG
            ) {
                triggerSleepTimer()
                return
            }
            // Reset position tracking so the ticker doesn't mistake the old song's
            // late position for a loop wrap on the new song.
            lastKnownPositionMs = -1L
            // Belt-and-suspenders: covers queue auto-advance and any REPEAT_ONE loop
            // where Media3 does fire this callback. The primary loop-boundary signal
            // is the position ticker below; this handles whatever IPC delivers.
            val ownership = PlaybackCallbackOwnership.forMediaItemTransition(reason)
            syncNowPlayingState(fromTransition = ownership.notifiesStats)
            if (ownership.persistsSession) saveSessionAsync()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[posDiscontinuity] reason=$reason old=${oldPosition.positionMs} new=${newPosition.positionMs} oldIdx=${oldPosition.mediaItemIndex} newIdx=${newPosition.mediaItemIndex} repeatMode=$repeatMode isPlaying=${mediaController?.isPlaying}")
            }
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                if (handlePendingAutomaticTransition()) {
                    return
                }
                if (_sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG) {
                    triggerSleepTimer()
                    return
                }
                // Reset ticker tracking so it doesn't double-detect this same boundary.
                lastKnownPositionMs = -1L
                // newPosition.mediaItemIndex is a playback index (ExoPlayer holds playbackQueue).
                val song = playbackQueue.getOrNull(newPosition.mediaItemIndex) ?: return
                if (DEBUG_STATS) Log.d(TAG, "[posDiscontinuity] AUTO_TRANSITION → songId=${song.id}")
                if (!isExternalPlayback) {
                    statsTracker.onSongSelected(song)
                    if (mediaController?.isPlaying == true) {
                        statsTracker.onPlaybackStarted()
                    }
                }
            }
            val ownership = PlaybackCallbackOwnership.forPositionDiscontinuity(reason)
            syncNowPlayingState(notifyStats = ownership.notifiesStats)
            if (ownership.persistsSession) saveSessionAsync()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[playbackStateChanged] state=$playbackState songId=${_nowPlayingState.value.song?.id} repeatMode=$repeatMode")
            }
            if (playbackState == Player.STATE_READY) {
                // A valid item successfully loaded: the current bad-media recovery episode (if any)
                // has succeeded, so end it. A later independent failure begins a fresh episode.
                resetBadMediaRecoveryEpisode()
            }
            if (playbackState == Player.STATE_ENDED &&
                _sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG
            ) {
                triggerSleepTimer()
                return
            }
            syncNowPlayingState()
        }

        override fun onPlayerError(error: PlaybackException) {
            // WB-01: a track that cannot be played (moved, deleted, or unsupported) latches the
            // pipeline in STATE_IDLE with this error. Without handling, playback stays stuck on
            // the bad item. Recover by bypassing the failing item using the existing skip/stop
            // queue logic, never corrupting queue/session state and never fabricating stats.
            Log.w(TAG, "[playerError] code=${error.errorCodeName} message=${error.message}")
            recoverFromCurrentPlaybackError()
        }

        override fun onRepeatModeChanged(newRepeatMode: Int) {
            // Repeat is mirrored to Media3 and does not mutate queue structure, so an external
            // surface (system UI, Android Auto, Bluetooth/AVRCP) that changes the player's repeat
            // mode directly is accepted and synchronised back into the logical source of truth.
            //
            // Loop safety: when Wavdrop itself sets controller.repeatMode (toggle, hydration, play
            // paths) the mapped value already equals `repeatMode`, so this early-returns without
            // re-touching the player or re-emitting.
            val mapped = repeatModeFromPlayerMode(newRepeatMode)
            val updated = externalRepeatModeUpdate(current = repeatMode, incoming = mapped) ?: return
            if (DEBUG_STATS) Log.d(TAG, "[repeatModeChanged] external -> $updated (was $repeatMode)")
            repeatMode = updated
            _nowPlayingState.update { it.copy(repeatMode = updated) }
            saveSessionAsync()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Wavdrop models shuffle logically via `playbackOrder` and deliberately keeps Media3
            // shuffle OFF (see the many `shuffleModeEnabled = false` assignments). Native shuffle
            // must never become a second source of truth, so if any external surface flips Media3
            // shuffle on, reassert false and leave the logical `shuffleEnabled` untouched.
            //
            // Loop safety: setting it back to false re-enters this callback with
            // shuffleModeEnabled=false, which falls through the guard and does nothing.
            if (shouldReassertMediaShuffleOff(shuffleModeEnabled)) {
                if (DEBUG_STATS) Log.d(TAG, "[shuffleModeChanged] external shuffle=true -> reasserting false")
                mediaController?.shuffleModeEnabled = false
            }
        }
    }

    /**
     * Recovers from a Media3 [PlaybackException] on the current occurrence (Phase 8, extends WB-01).
     *
     * Contract:
     *  - **Bounded & occurrence-safe:** delegates the decision to the pure [BadMediaRecoveryPlanner],
     *    which tracks attempted occurrences for this episode and inspects at most `queueSize`
     *    occurrences. There is no infinite skip/retry loop under repeat-all, repeat-one, shuffle,
     *    duplicate songs, or an entirely broken queue.
     *  - **Queue preserved:** a bad occurrence is SKIPPED, never removed — the queue (and playlists)
     *    stay intact so a temporarily unreadable file can play later.
     *  - **Intent honoured:** playback resumes only if the user was actually playing when the first
     *    failure of the episode occurred (captured via `playWhenReady`), never auto-starting a
     *    paused user.
     *  - **One concise message per episode:** a single "skipping" message on the first bypass and a
     *    single "nothing playable" message on exhaustion — never one per broken file.
     *  - **No fabricated stats:** the shared skip path credits nothing for a track that accumulated
     *    no listening time (see [StatsTracker]).
     *
     * The pipeline is latched in STATE_IDLE with the error, so [MediaController.prepare] is issued
     * before seeking to clear it — otherwise the next item would not start.
     */
    private fun recoverFromCurrentPlaybackError() {
        val controller = mediaController
        val failingIndex = currentPlaybackIndex()

        // Can't reason about the queue position — halt cleanly rather than risk a bad seek. The
        // queue is preserved (not cleared) so the user keeps their context.
        if (controller == null || failingIndex == null || failingIndex !in playbackQueue.indices) {
            Log.w(TAG, "[badMedia] UNCLASSIFIED_PLAYBACK_ERROR: unresolved failing index; halting (queueSize=${playbackQueue.size})")
            haltPlaybackAfterError(controller)
            notifyBadMediaExhaustedOnce()
            return
        }

        // Original play intent: playWhenReady survives the transition to STATE_IDLE on error, so it
        // is the truthful signal for "was the user playing" even though isPlaying is already false.
        val wasPlaying = controller.playWhenReady || controller.isPlaying

        val step = BadMediaRecoveryPlanner.onPlaybackError(
            previous = badMediaRecoveryEpisode,
            queueGeneration = queueGeneration,
            queueSize = playbackQueue.size,
            failingIndex = failingIndex,
            repeatMode = repeatMode,
            wasPlaying = wasPlaying,
        )
        badMediaRecoveryEpisode = step.state
        if (step.isNewEpisode) {
            // A fresh episode: reset per-episode notification dedup so this episode may notify once.
            badMediaRecoverySkipNotified = false
            badMediaRecoveryExhaustionNotified = false
        }

        when (step) {
            is BadMediaRecoveryStep.Advance -> {
                Log.w(
                    TAG,
                    "[badMedia] RECOVERED_BAD_MEDIA: queueSize=${playbackQueue.size} " +
                        "failedIndex=$failingIndex targetIndex=${step.targetIndex} " +
                        "attempted=${step.state.attemptedOccurrences.size} resume=${step.state.wasPlaying}",
                )
                // Clear the latched error, then advance WITHOUT forcing playback — honour the
                // captured intent. The failing occurrence is left in the queue (skipped, not removed).
                controller.prepare()
                seekToPlaybackIndex(controller, step.targetIndex, forcePlay = step.state.wasPlaying)
                notifyBadMediaSkippedOnce()
            }
            is BadMediaRecoveryStep.Exhausted -> {
                Log.w(
                    TAG,
                    "[badMedia] BAD_MEDIA_EXHAUSTED: queueSize=${playbackQueue.size} " +
                        "failedIndex=$failingIndex attempted=${step.state.attemptedOccurrences.size}",
                )
                haltPlaybackAfterError(controller)
                notifyBadMediaExhaustedOnce()
            }
        }
    }

    /**
     * Stops playback after an unrecoverable error while PRESERVING the queue (Phase 8). Unlike the
     * pre-Phase-8 behaviour, the library/playback queues and the current song are kept so the user
     * retains their context and can retry later; only the transient playing/position state is
     * cleared. The player's media items are left loaded (not cleared) so a manual retry works.
     */
    private fun haltPlaybackAfterError(controller: MediaController?) {
        controller?.pause()
        lastKnownPositionMs = -1L
        _nowPlayingState.update {
            it.copy(
                isPlaying  = false,
                positionMs = 0L,
            )
        }
        saveSessionAsync()
    }

    /** Emits the single "skipping past a bad track" message for the current recovery episode. */
    private fun notifyBadMediaSkippedOnce() {
        if (badMediaRecoverySkipNotified) return
        badMediaRecoverySkipNotified = true
        _userMessages.tryEmit(PlaybackUserMessage.BAD_TRACK_SKIPPED)
    }

    /** Emits the single "nothing else playable" message for the current recovery episode. */
    private fun notifyBadMediaExhaustedOnce() {
        if (badMediaRecoveryExhaustionNotified) return
        badMediaRecoveryExhaustionNotified = true
        _userMessages.tryEmit(PlaybackUserMessage.QUEUE_EXHAUSTED)
    }

    /**
     * Ends the current bad-media recovery episode (Phase 8). Called when a valid item starts playing
     * or when explicit user intent (new queue, jump, manual navigation) supersedes recovery, so a
     * later independent failure begins a fresh, unbiased episode.
     */
    private fun resetBadMediaRecoveryEpisode() {
        badMediaRecoveryEpisode = null
        badMediaRecoverySkipNotified = false
        badMediaRecoveryExhaustionNotified = false
    }

    /**
     * Marks the queue identity as changed (Phase 8): bumps [queueGeneration] and ends any recovery
     * episode. Called from every queue replacement / structural mutation so a stale episode's
     * occurrence indices can never be reused.
     */
    private fun bumpQueueGeneration() {
        queueGeneration++
        resetBadMediaRecoveryEpisode()
    }

    init {
        scope.launch {
            appSettingsRepository.previousButtonBehavior.collect { behavior ->
                previousButtonBehavior = behavior
            }
        }

        ensureControllerConnection()
    }

    /**
     * Demand-driven controller acquisition. Safe to call repeatedly and from any thread: the work
     * is posted to the main executor and, once there, the [ControllerConnectionDecision] guarantees
     * at most one in-flight `buildAsync()`. A previously failed or disconnected controller is
     * rebuilt here; a live or in-flight connection is left untouched.
     */
    private fun ensureControllerConnection() {
        ContextCompat.getMainExecutor(context).execute {
            when (ControllerConnectionDecision.onRequest(controllerConnectionState)) {
                ControllerConnectionAction.StartConnection -> startControllerConnection()
                ControllerConnectionAction.AwaitExisting,
                ControllerConnectionAction.UseExisting -> Unit
            }
        }
    }

    // Main thread only. Precondition: controllerConnectionState == Disconnected.
    private fun startControllerConnection() {
        controllerConnectionState = ControllerConnectionState.Connecting
        val generation = ++controllerConnectionGeneration
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token)
            .setListener(controllerLifecycleListener)
            .setConnectionHints(Bundle().apply { putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) })
            .buildAsync()
        future.addListener(
            {
                val result = runCatching { future.get() }
                when (
                    ControllerAttemptOwnership.onCompletion(
                        attemptGeneration = generation,
                        currentGeneration = controllerConnectionGeneration,
                    )
                ) {
                    ControllerAttemptOutcome.DiscardStale -> {
                        // A newer attempt started (or release() ran) while this build was in
                        // flight. Release any controller this stale attempt produced and touch no
                        // state — the authoritative attempt owns mediaController/state/pending.
                        result.getOrNull()?.release()
                        Log.w(TAG, "Ignoring stale MediaController connection completion (gen=$generation)")
                    }
                    ControllerAttemptOutcome.Apply -> result.fold(
                        onSuccess = { controller -> onControllerConnected(controller) },
                        onFailure = { error ->
                            // Observable, non-terminal: clear the connecting state so a later
                            // demand (awaitMediaController / a playback action) can start a fresh
                            // attempt. Pending requests are intentionally NOT cleared here — they
                            // must survive a transient connection failure and run once connected.
                            controllerConnectionState = ControllerConnectionDecision.onConnectionFailed()
                            Log.w(TAG, "MediaController connection failed; will retry on next demand", error)
                        },
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // Main thread only.
    private fun onControllerConnected(controller: MediaController) {
        mediaController = controller
        controllerConnectionState = ControllerConnectionDecision.onConnected()
        controller.addListener(playerListener)
        controller.repeatMode = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        drainPendingRequests(controller)
        _progressPlayer.value = controller
    }

    // Main thread only. Consumes any request captured while the controller was unavailable.
    private fun drainPendingRequests(controller: MediaController) {
        val playRequest = pendingPlaybackRequest
        val preserveSearchRequest = pendingPreserveSearchRequest
        val externalRequest = pendingExternalPlaybackRequest
        val jumpRequest = pendingQueueJumpRequest
        pendingPlaybackRequest = null
        pendingPreserveSearchRequest = null
        pendingExternalPlaybackRequest = null
        // A queue jump is subordinate to any queue-replacing request that also accumulated
        // while disconnected: the later replace supersedes the older jump (the queue it referred to
        // is gone). Cleared here so a superseded jump can never fire against a replacement queue.
        pendingQueueJumpRequest = null
        // Transport intents (Phase 7) are lowest precedence — captured and cleared here so a
        // queue-replacing/jump branch below supersedes them (a fresh queue invalidates a
        // stale play/pause/seek/navigation). They apply only in the no-queue-change (else) branch.
        val navigation = pendingNavigation
        val playWhenReady = pendingPlayWhenReady
        val seek = pendingSeek
        pendingNavigation = null
        pendingPlayWhenReady = null
        pendingSeek = null
        when {
            externalRequest != null -> playExternalUri(
                uri = externalRequest.uri,
                displayName = externalRequest.displayName,
            )
            preserveSearchRequest != null -> playPreservedSearchPlan(
                plan = preserveSearchRequest.plan,
                startSong = preserveSearchRequest.startSong,
            )
            playRequest != null -> playFromQueueInternal(
                queue = playRequest.queue,
                startIndex = playRequest.startIndex,
                preservePlaybackOrder = playRequest.preservePlaybackOrder,
                source = playRequest.source,
            )
            // Lowest precedence: only if no queue-replacing/restore request superseded it. Validated
            // against the (possibly mutated) queue so it never seeks a different song.
            jumpRequest != null -> executePendingQueueJump(controller, jumpRequest)
            else -> {
                // No queue-replacing/restore/jump request supersedes the dirty flag in this branch,
                // so a warm reconnect after a shuffle change made while disconnected must push the
                // app-owned order to the player once, BEFORE any pending transport command runs, so
                // navigation/seek resolve against the synchronized queue rather than a stale one.
                if (reconnectRequiresQueueSync(playerQueueNeedsSync, supersededByQueueRequest = false)) {
                    synchronizePlayerQueueOnReconnect(controller)
                }
                drainPendingTransportCommands(controller, navigation, seek, playWhenReady)
            }
        }
    }

    /**
     * Main thread only. Pushes the app-owned queue/order to the live player exactly once on a warm
     * reconnect after shuffle changed while the controller was unavailable.
     *
     * Preserves the current queue OCCURRENCE (`_nowPlayingState.currentIndex` is a position, so it is
     * duplicate-safe and never jumps to an older duplicate) and the live playback position (read
     * before the reload), so the current song is not restarted and playback is not reset to index 0.
     * Reuses [syncPlayerQueueAt], which clears [playerQueueNeedsSync] after the push. If the current
     * occurrence cannot be resolved, the queue is left dirty (the existing non-destructive fallback)
     * so a later queue operation still synchronizes it.
     */
    private fun synchronizePlayerQueueOnReconnect(controller: MediaController) {
        val occurrence = _nowPlayingState.value.currentIndex
            .takeIf { it in playbackQueue.indices }
            ?: currentPlaybackIndex()?.takeIf { it in playbackQueue.indices }
            ?: return
        val positionMs = controller.currentPosition.coerceAtLeast(0L)
        syncPlayerQueueAt(
            controller = controller,
            playbackIndex = occurrence,
            positionMs = positionMs,
            playWhenReady = controller.isPlaying,
        )
        syncNowPlayingState()
    }

    /**
     * Main thread only. Applies transport intents captured while disconnected, in an order that
     * keeps them internally consistent and stale-safe. Runs only when no queue-replacing/restore/
     * jump request superseded them.
     *
     *  1. Navigation — recomputed against the live queue (never a stale index).
     *  2. Seek — applied only if its bound track is still current AFTER any navigation (so a seek
     *     issued against the old track is dropped rather than applied to a different one).
     *  3. Play/Pause — the latest desired state.
     */
    private fun drainPendingTransportCommands(
        controller: MediaController,
        navigation: NavigationIntent?,
        seek: PendingSeek?,
        playWhenReady: Boolean?,
    ) {
        if (navigation == null && seek == null && playWhenReady == null) {
            syncNowPlayingState()
            return
        }
        navigation?.let { navigate(controller, it) }
        if (seek != null) {
            when (val resolution = PendingTransport.resolveSeek(
                seek = seek,
                currentQueueGeneration = queueGeneration,
                playbackQueue = playbackQueue,
                currentPlaybackIndex = currentPlaybackIndex(),
            )) {
                is PendingSeekResolution.Apply -> {
                    val duration = controller.duration.takeIf { it > 0L }
                    controller.seekTo(
                        if (duration != null) resolution.positionMs.coerceIn(0L, duration)
                        else resolution.positionMs,
                    )
                }
                PendingSeekResolution.Discard -> Log.w(
                    TAG,
                    "Discarding stale pending seek generation=${seek.queueGeneration} " +
                        "index=${seek.playbackIndex} songId=${seek.targetSongId}",
                )
            }
        }
        playWhenReady?.let { if (it) controller.play() else controller.pause() }
        syncNowPlayingState()
        saveSessionAsync()
    }

    /**
     * Reacts to the session controller disconnecting (service killed / session released). Clears
     * the dead controller and returns to a retry-eligible state so the next demand reconnects.
     * This is not a reconnect daemon — nothing reconnects until something actually needs it.
     */
    private val controllerLifecycleListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            // Identity check preserved: a disconnect from a stale controller (e.g. one released by
            // a superseded connection attempt) must never clear the current controller/state.
            if (ControllerAttemptOwnership.shouldApplyDisconnect(mediaController === controller)) {
                mediaController = null
                _progressPlayer.value = null
                controllerConnectionState = ControllerConnectionDecision.onDisconnected()
                Log.w(TAG, "MediaController disconnected; will reconnect on next demand")
            }
        }
    }

    fun playSong(song: Song) {
        playFromQueue(queue = listOf(song), startIndex = 0)
    }

    fun playSearchResultPreservingQueue(song: Song) {
        val controller = mediaController
        val currentMediaIndex = controller?.currentMediaItemIndex
        val currentMediaSongId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val effectiveQueueBefore = playbackQueue
        val resolvedCurrentIndex = currentPlaybackIndex()
        val resolvedPlan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = effectiveQueueBefore,
            currentPlaybackIndex = resolvedCurrentIndex,
            song = song,
        )
        // When the current index is unresolvable, fall back to a plan that keeps the whole existing
        // queue as up-next after the tapped song instead of silently ignoring the tap.
        val usedFallback = resolvedPlan == null
        val plan = resolvedPlan
            ?: SearchPlaybackPlanner.preserveQueueFallback(
                playbackQueue = effectiveQueueBefore,
                song = song,
            )
        if (usedFallback && BuildConfig.DEBUG) {
            Log.d(
                SEARCH_TAG,
                "preserve search tap fallback: unresolved current index " +
                    "mediaIndex=$currentMediaIndex mediaSongId=$currentMediaSongId " +
                    "stateSongId=${_nowPlayingState.value.song?.id} " +
                    "before=${effectiveQueueBefore.queueLogSummary()} tap=${song.id} " +
                    "after=${plan.queue.queueLogSummary(currentSongId = song.id)}",
            )
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                SEARCH_TAG,
                "preserve search tap mediaIndex=$currentMediaIndex mediaSongId=$currentMediaSongId " +
                    "resolvedIndex=$resolvedCurrentIndex " +
                    "before=${effectiveQueueBefore.queueLogSummary(currentSongId = currentMediaSongId)} " +
                    "tap=${song.id} after=${plan.queue.queueLogSummary(currentSongId = song.id)} " +
                    "newIndex=${plan.currentIndex}",
            )
        }
        playPreservedSearchPlan(plan = plan, startSong = song)
    }

    private fun playPreservedSearchPlan(
        plan: SearchPlaybackPlan,
        startSong: Song,
    ) {
        isExternalPlayback = false
        // Fresh queue supersedes any in-progress bad-media recovery episode.
        bumpQueueGeneration()
        // Explicit user play supersedes any pending automatic Bluetooth resume.
        automaticResumeAuthority.supersedeByUser()
        // Shuffle-truthfulness invariant: `plan.queue` is derived from the current visible
        // `playbackQueue` (see playSearchResultPreservingQueue), so when shuffle is ON it already
        // carries the active shuffled order. We adopt it verbatim as the new libraryQueue with an
        // identity playbackOrder — this preserves the visible/played order exactly (history + X +
        // shuffled future) and keeps `shuffleEnabled` truthful. Do NOT rebuild from the original
        // library order here: that would flatten the shuffled continuation to sequential while the
        // shuffle button still reads ON.
        libraryQueue = plan.queue.ifEmpty { listOf(startSong) }
        playbackOrder = libraryQueue.indices.toList()
        playbackQueue = libraryQueue
        playerQueueNeedsSync = false

        val playbackStartIndex = plan.currentIndex.takeIf { it in playbackQueue.indices }
            ?: playbackQueue.indexOfFirst { it.id == startSong.id }.takeIf { it >= 0 }
            ?: 0
        pendingPreserveSearchPlan = SearchPlaybackPlan(
            queue = playbackQueue,
            currentIndex = playbackStartIndex,
        )

        lastKnownPositionMs = -1L
        statsTracker.onSongSelected(startSong)
        _nowPlayingState.update {
            it.copy(
                song = startSong,
                queue = playbackQueue,
                queueSource = PlaybackQueueSource.Other,
                currentIndex = playbackStartIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                positionMs = 0L,
                durationMs = startSong.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }

        val controller = mediaController
        if (controller == null) {
            pendingPreserveSearchRequest = PreserveSearchRequest(
                plan = SearchPlaybackPlan(
                    queue = playbackQueue,
                    currentIndex = playbackStartIndex,
                ),
                startSong = startSong,
            )
            return
        }

        controller.repeatMode = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        controller.setMeasuredMediaItems(
            operation = "preserve_search",
            songs = playbackQueue,
            startIndex = playbackStartIndex,
            positionMs = 0L,
        )
        controller.prepare()
        controller.play()
        saveSessionAsync()
    }

    fun playExternalUri(uri: Uri, displayName: String? = null) {
        val song = uri.toExternalSong(displayName)

        isExternalPlayback = true
        // Fresh queue supersedes any in-progress bad-media recovery episode.
        bumpQueueGeneration()
        // Explicit user play supersedes any pending automatic Bluetooth resume.
        automaticResumeAuthority.supersedeByUser()
        libraryQueue = listOf(song)
        playbackOrder = listOf(0)
        playbackQueue = libraryQueue
        playerQueueNeedsSync = false
        lastKnownPositionMs = -1L

        _nowPlayingState.update {
            it.copy(
                song = song,
                queue = playbackQueue,
                queueSource = PlaybackQueueSource.Other,
                currentIndex = 0,
                shuffleEnabled = false,
                repeatMode = repeatMode,
                positionMs = 0L,
                durationMs = 0L,
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }

        val controller = mediaController
        if (controller == null) {
            pendingExternalPlaybackRequest = ExternalPlaybackRequest(uri, displayName)
            return
        }

        controller.repeatMode = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        controller.setMeasuredMediaItems(
            operation = "external_uri",
            songs = listOf(song),
            startIndex = 0,
            positionMs = 0L,
        )
        controller.prepare()
        syncNowPlayingState(notifyStats = false)
        controller.play()
    }

    fun playFromQueue(
        queue: List<Song>,
        startSong: Song,
        source: PlaybackQueueSource = PlaybackQueueSource.Other,
    ) {
        val start = resolveQueueStartBySong(queue, startSong)
        if (start == null) {
            Log.w(TAG, "playFromQueue: start song ${startSong.id} is absent or ambiguous in queue; ignoring")
            return
        }
        playFromQueueInternal(
            queue = start.queue,
            startIndex = start.startIndex,
            preservePlaybackOrder = false,
            source = source,
        )
    }

    fun playFromQueue(
        queue: List<Song>,
        startIndex: Int,
        source: PlaybackQueueSource = PlaybackQueueSource.Other,
    ) {
        playFromQueueInternal(
            queue = queue,
            startIndex = startIndex,
            preservePlaybackOrder = false,
            source = source,
        )
    }

    private fun playFromQueueInternal(
        queue: List<Song>,
        startIndex: Int,
        preservePlaybackOrder: Boolean,
        source: PlaybackQueueSource = PlaybackQueueSource.Other,
    ) {
        val start = resolveQueueStart(queue, startIndex) ?: return
        val normalizedQueue = start.queue
        val originalStartIndex = start.startIndex
        val startSong = start.startSong
        isExternalPlayback = false
        // Fresh queue supersedes any in-progress bad-media recovery episode.
        bumpQueueGeneration()
        // Explicit user play supersedes any pending automatic Bluetooth resume.
        automaticResumeAuthority.supersedeByUser()

        libraryQueue = normalizedQueue
        if (preservePlaybackOrder) {
            playbackOrder = normalizedQueue.indices.toList()
            playbackQueue = normalizedQueue
        } else {
            rebuildPlaybackQueue(currentQueueIndex = originalStartIndex)
        }
        playerQueueNeedsSync = false
        // When shuffle is OFF, playbackOrder is identity so playbackStartIndex == originalStartIndex.
        // When shuffle is ON, buildPlaybackOrder places the start song at position 0.
        val playbackStartIndex = playbackOrder.indexOf(originalStartIndex).takeIf { it >= 0 } ?: 0

        lastKnownPositionMs = -1L
        statsTracker.onSongSelected(startSong)
        _nowPlayingState.update {
            it.copy(
                song = startSong,
                queue = playbackQueue,
                queueSource = source,
                currentIndex = playbackStartIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                positionMs = 0L,
                durationMs = startSong.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }

        val controller = mediaController
        if (controller == null) {
            pendingPlaybackRequest = PlaybackRequest(
                queue = normalizedQueue,
                startIndex = originalStartIndex,
                preservePlaybackOrder = preservePlaybackOrder,
                source = source,
            )
            return
        }
        controller.repeatMode = repeatMode.toPlayerRepeatMode()
        controller.shuffleModeEnabled = false
        // ExoPlayer is loaded with playbackQueue so auto-transitions follow shuffle order.
        controller.setMeasuredMediaItems(
            operation = "play_from_queue",
            songs = playbackQueue,
            startIndex = playbackStartIndex,
            positionMs = 0L,
        )
        controller.prepare()
        syncNowPlayingState()
        controller.play()
        saveSessionAsync()
    }

    fun playFromQueueShuffled(
        queue: List<Song>,
        source: PlaybackQueueSource = PlaybackQueueSource.Other,
    ) {
        val normalizedQueue = queue.ifEmpty { return }
        shuffleEnabled = true
        playFromQueue(
            queue = normalizedQueue,
            startIndex = normalizedQueue.indices.random(),
            source = source,
        )
    }

    fun playNext(song: Song) {
        // CF-2H3A: explicit Play Next intent cancels an owned crossfade before any queue inspection or mutation, even if
        // this then falls back (empty queue -> playSong, unresolved index -> append). Internal paths never notify.
        explicitPlayNextMutationListeners.notifyExplicitPlayNextMutation()
        if (libraryQueue.isEmpty()) {
            playSong(song)
            return
        }
        val currentPlaybackIndex = currentPlaybackIndex()
        if (currentPlaybackIndex == null) {
            // Active queue but the current index is temporarily unresolvable. Do NOT fall back to
            // playSong here: that would replace the whole queue. Append instead so the item is
            // still queued and the existing queue is preserved.
            if (BuildConfig.DEBUG) {
                Log.d(SEARCH_TAG, "playNext: unresolved current index, appending song=${song.id}")
            }
            appendAllPreservingQueue(listOf(song))
            return
        }

        // Append the new song to the library queue and insert its index into playbackOrder
        // immediately after the current playback position.
        bumpQueueGeneration()
        val newLibraryIndex = libraryQueue.size
        libraryQueue = libraryQueue + song
        val insertPlaybackIndex = currentPlaybackIndex + 1
        val newOrder = playbackOrder.toMutableList()
        newOrder.add(insertPlaybackIndex, newLibraryIndex)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // addMediaItem at playback position — no setMediaItems/prepare/play needed.
        if (!playerQueueNeedsSync) {
            mediaController?.addMediaItem(insertPlaybackIndex, song.toCachedMediaItem())
        }

        _nowPlayingState.update {
            it.copy(queue = playbackQueue, currentIndex = currentPlaybackIndex)
        }
        saveSessionAsync()
    }

    /** Inserts [songs] immediately after the current item in their original order. */
    fun playAllNext(songs: List<Song>) {
        // CF-2H3A: one notification per command, before planning (NoOp, StartQueue and InsertAfterCurrent alike).
        // insertAllAfterCurrent / appendAllPreservingQueue / playFromQueue reached from here never notify again.
        explicitPlayNextMutationListeners.notifyExplicitPlayNextMutation()
        // A non-empty library queue counts as active even if the current index is momentarily
        // unresolvable: insertAllAfterCurrent then appends rather than dropping the batch. Only a
        // genuinely empty queue starts a new one.
        val hasActiveCurrentItem = libraryQueue.isNotEmpty()
        when (val plan = planPlayAllNext(songs, hasActiveCurrentItem)) {
            PlayAllNextPlan.NoOp -> Unit
            is PlayAllNextPlan.StartQueue ->
                playFromQueue(queue = plan.queue, startIndex = 0)
            is PlayAllNextPlan.InsertAfterCurrent -> {
                insertAllAfterCurrent(plan.songs)
            }
        }
    }

    /** Appends [songs] to the end of the queue in their original order. */
    fun addAllToQueue(songs: List<Song>) {
        // CF-2H3B: one notification per command, before planning (NoOp, StartNewQueue and AppendPreservingQueue alike).
        // appendAllPreservingQueue (shared with the Play Next fallbacks) and playFromQueue never notify.
        explicitAddToQueueMutationListeners.notifyExplicitAddToQueueMutation()
        when (
            planQueueAdd(
                hasExistingQueue = libraryQueue.isNotEmpty(),
                isBatchEmpty = songs.isEmpty(),
                currentIndexResolvable = currentPlaybackIndex() != null,
            )
        ) {
            QueueAddPlan.NoOp -> Unit
            // Only a genuinely empty queue starts anew.
            QueueAddPlan.StartNewQueue -> playFromQueue(queue = songs, startIndex = 0)
            // Existing queue: append to tail, preserving the queue even when the current index is
            // temporarily unresolvable (previously this destructively replaced the queue).
            QueueAddPlan.AppendPreservingQueue -> appendAllPreservingQueue(songs)
        }
    }

    fun addToQueue(song: Song) {
        // CF-2H3B: notified before planning; see addAllToQueue.
        explicitAddToQueueMutationListeners.notifyExplicitAddToQueueMutation()
        when (
            planQueueAdd(
                hasExistingQueue = libraryQueue.isNotEmpty(),
                isBatchEmpty = false,
                currentIndexResolvable = currentPlaybackIndex() != null,
            )
        ) {
            QueueAddPlan.NoOp -> Unit
            QueueAddPlan.StartNewQueue -> playSong(song)
            // Existing queue: append to tail via the proven preserving path, which keeps the queue
            // and current song intact even when the current index is temporarily unresolvable.
            QueueAddPlan.AppendPreservingQueue -> appendAllPreservingQueue(listOf(song))
        }
    }

    private fun insertAllAfterCurrent(songs: List<Song>) {
        if (songs.isEmpty()) return
        val oldPlaybackQueue = playbackQueue
        val controller = mediaController
        val controllerCurrentIndex = controller?.currentMediaItemIndex
        val controllerSongId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val wasPlayerQueueNeedsSync = playerQueueNeedsSync
        val currentPlaybackIndex = currentPlaybackIndex()
        val result = currentPlaybackIndex?.let {
            QueueMutation.insertAllAfterCurrent(
                libraryQueue = libraryQueue,
                playbackOrder = playbackOrder,
                currentPlaybackIndex = it,
                songs = songs,
            )
        }
        if (currentPlaybackIndex == null || result == null) {
            // Current index unresolved — append the batch instead of silently dropping it.
            if (BuildConfig.DEBUG) {
                Log.d(SEARCH_TAG, "playAllNext: unresolved current index, appending ${songs.size} songs")
            }
            appendAllPreservingQueue(songs)
            return
        }
        bumpQueueGeneration()
        libraryQueue = result.libraryQueue
        playbackOrder = result.playbackOrder
        playbackQueue = result.playbackQueue

        val syncPlan = planBatchQueuePlayerSync(
            oldPlaybackQueue = oldPlaybackQueue,
            newPlaybackQueue = playbackQueue,
            currentPlaybackIndex = currentPlaybackIndex,
            controllerAvailable = controller != null,
            playerQueueNeedsSync = wasPlayerQueueNeedsSync,
            controllerCurrentIndex = controllerCurrentIndex,
            controllerSongId = controllerSongId,
            controllerMediaItemCount = controller?.mediaItemCount,
        )
        when (syncPlan.action) {
            BatchQueuePlayerSyncAction.NoOp -> Unit
            BatchQueuePlayerSyncAction.ReplaceFutureSuffix -> {
                controller?.replaceMeasuredFutureMediaItems(
                    operation = "play_all_next_future_suffix",
                    fromIndex = syncPlan.futureStartIndex,
                    songs = syncPlan.suffix,
                ) ?: run {
                    playerQueueNeedsSync = true
                }
            }
            BatchQueuePlayerSyncAction.FullQueueSync -> {
                if (controller != null) {
                    syncPlayerQueueAt(
                        controller = controller,
                        playbackIndex = currentPlaybackIndex,
                        positionMs = controller.currentPosition.coerceAtLeast(0L),
                        playWhenReady = controller.isPlaying,
                    )
                } else {
                    playerQueueNeedsSync = true
                }
            }
            BatchQueuePlayerSyncAction.MarkDirty -> {
                playerQueueNeedsSync = true
            }
        }

        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    /**
     * Appends [songs] to the end of the queue without needing a resolvable current index.
     * Used as the deterministic fallback for Play Next / Play All Next when the current playback
     * index cannot be resolved: appending preserves the existing queue and the user's intent to
     * enqueue, instead of silently dropping the items or destroying the queue.
     */
    private fun appendAllPreservingQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        bumpQueueGeneration()
        val result = QueueMutation.appendAll(
            libraryQueue = libraryQueue,
            playbackOrder = playbackOrder,
            songs = songs,
        )
        libraryQueue = result.libraryQueue
        playbackOrder = result.playbackOrder
        playbackQueue = result.playbackQueue

        if (!playerQueueNeedsSync) {
            mediaController?.addMeasuredMediaItems(
                operation = "append_fallback",
                songs = songs,
            )
        }

        // Appending to the end does not move the current item, so keep the existing current index
        // (coerced defensively into the new bounds).
        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = it.currentIndex.takeIf { idx -> idx in playbackQueue.indices } ?: 0,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    fun jumpToQueueItem(playbackIndex: Int) {
        if (playbackIndex !in playbackQueue.indices) return
        // Explicit user selection supersedes automatic bad-media recovery.
        resetBadMediaRecoveryEpisode()
        val controller = mediaController
        if (controller == null) {
            // Defer through the same bounded, stale-safe machinery as jumpToSongById: bind to the
            // song at this occurrence so executePendingQueueJump re-resolves it on reconnect
            // (correcting a mutated index, never jumping to a different song). Latest jump wins.
            pendingQueueJumpRequest = QueueJumpRequest(
                songId = playbackQueue[playbackIndex].id,
                resolvedPlaybackIndex = playbackIndex,
            )
            ensureControllerConnection()
            return
        }
        // playbackIndex is directly the ExoPlayer media item index since ExoPlayer holds playbackQueue.
        seekToPlaybackIndex(controller, playbackIndex)
    }

    /**
     * Jumps to the occurrence of [songId] selected by [resolveQueueOccurrenceIndex] — the current
     * occurrence, else the nearest upcoming, else the nearest historical one — without rebuilding
     * the queue, session, shuffle order, or repeat mode.
     *
     * Returns **true** when the command has been genuinely handled: either seeked immediately, or —
     * when the controller is temporarily unavailable — accepted as a bounded pending jump with a
     * demand-driven reconnection requested (Phase 2), so the intended occurrence plays once the
     * controller reconnects. The active queue is preserved in both cases.
     *
     * Returns **false** only when the occurrence cannot be safely resolved (absent, or ambiguous
     * while the current index is unresolved); the caller should then use its fallback playback path.
     * A non-null controller that silently no-ops no longer reports a false success.
     */
    fun jumpToSongById(songId: Long): Boolean {
        val playbackIndex = resolveQueueOccurrenceIndex(
            queue = playbackQueue,
            currentPlaybackIndex = currentPlaybackIndex(),
            songId = songId,
        )
        return when (planQueueJump(resolvedIndex = playbackIndex, controllerAvailable = mediaController != null)) {
            QueueJumpAction.Reject -> false
            QueueJumpAction.ExecuteNow -> {
                // A newer immediate jump supersedes any older deferred one.
                pendingQueueJumpRequest = null
                jumpToQueueItem(playbackIndex)
                true
            }
            QueueJumpAction.DeferAndReconnect -> {
                // Latest explicit tap wins: overwrite any earlier pending jump (single slot).
                pendingQueueJumpRequest = QueueJumpRequest(
                    songId = songId,
                    resolvedPlaybackIndex = playbackIndex,
                )
                // Trigger Phase 2's demand-driven acquisition; the jump drains on reconnect. The
                // controller-null branches of the play* methods do NOT self-reconnect, so this call
                // is required for the deferred jump to ever execute.
                ensureControllerConnection()
                if (BuildConfig.DEBUG) {
                    Log.d(RESUME_TAG, "jumpToSongById deferred: songId=$songId index=$playbackIndex; reconnecting")
                }
                true
            }
        }
    }

    // Main thread only. Executes a deferred queue jump after the controller reconnects, correcting
    // a stale index if the queue mutated while disconnected (never seeks a different song).
    private fun executePendingQueueJump(controller: MediaController, request: QueueJumpRequest) {
        when (
            val resolution = resolvePendingQueueJump(
                queue = playbackQueue,
                currentPlaybackIndex = currentPlaybackIndex(),
                songId = request.songId,
                resolvedPlaybackIndex = request.resolvedPlaybackIndex,
            )
        ) {
            is PendingQueueJumpResolution.Seek -> seekToPlaybackIndex(controller, resolution.playbackIndex)
            PendingQueueJumpResolution.Discard -> {
                Log.w(TAG, "Discarding stale pending queue jump songId=${request.songId}")
                syncNowPlayingState()
            }
        }
    }

    fun removeFromQueue(playbackIndex: Int) {
        // CF-2H3D: explicit removal intent cancels an owned crossfade first, even if validation then no-ops (e.g. current).
        explicitQueueRemovalListeners.notifyExplicitQueueRemoval()
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex == currentPlaybackIndex) return
        if (playbackIndex !in playbackQueue.indices) return
        val removedLibraryIndex = playbackOrder.getOrNull(playbackIndex) ?: return
        if (removedLibraryIndex !in libraryQueue.indices) return

        bumpQueueGeneration()
        // Remove from library queue and decrement any playback order entries above the gap.
        libraryQueue = libraryQueue.toMutableList().also { it.removeAt(removedLibraryIndex) }
        playbackOrder = playbackOrder
            .filterIndexed { index, _ -> index != playbackIndex }
            .map { index -> if (index > removedLibraryIndex) index - 1 else index }
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // playbackIndex is the ExoPlayer media item index.
        if (!playerQueueNeedsSync) {
            mediaController?.removeMediaItem(playbackIndex)
        }

        val newCurrentPlaybackIndex = if (playbackIndex < currentPlaybackIndex) {
            currentPlaybackIndex - 1
        } else {
            currentPlaybackIndex
        }.coerceIn(playbackQueue.indices)
        val currentSong = playbackQueue.getOrNull(newCurrentPlaybackIndex)

        _nowPlayingState.update {
            it.copy(
                song = currentSong ?: it.song,
                queue = playbackQueue,
                currentIndex = newCurrentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    /**
     * Removes every queue occurrence strictly before the current one. The current occurrence keeps
     * playing untouched and becomes index 0. Returns true only when something was removed.
     */
    fun clearEarlierQueue(): Boolean {
        // CF-2H3D: one notification per public command, before planning; applyBulkClear never notifies.
        explicitQueueRemovalListeners.notifyExplicitQueueRemoval()
        return applyBulkClear { currentIndex ->
            QueueMutation.clearEarlier(libraryQueue, playbackOrder, currentIndex)
        }
    }

    /** Removes every queue occurrence strictly after the current one. Returns true only on a real clear. */
    fun clearUpNext(): Boolean {
        // CF-2H3D: one notification per public command, before planning; applyBulkClear never notifies.
        explicitQueueRemovalListeners.notifyExplicitQueueRemoval()
        return applyBulkClear { currentIndex ->
            QueueMutation.clearUpNext(libraryQueue, playbackOrder, currentIndex)
        }
    }

    private inline fun applyBulkClear(
        plan: (currentIndex: Int) -> QueueMutation.BulkClearPlan,
    ): Boolean {
        // Media3 can advance before NowPlayingState syncs, so the boundary comes from the
        // authoritative resolver (resolved once here, not from the possibly stale state index).
        val currentPlaybackIndex = currentPlaybackIndex() ?: return false
        val mutation = plan(currentPlaybackIndex) as? QueueMutation.BulkClearPlan.Mutation
            ?: return false

        bumpQueueGeneration()
        libraryQueue = mutation.libraryQueue
        playbackOrder = mutation.playbackOrder
        playbackQueue = mutation.playbackQueue

        // Media3's playlist mirrors playbackOrder, so one range removal keeps it aligned. Removing
        // items before/after the current one never interrupts the current item or its position.
        if (!playerQueueNeedsSync) {
            mediaController?.removeMediaItems(mutation.mediaRemoveFrom, mutation.mediaRemoveToExclusive)
        }

        _nowPlayingState.update {
            it.copy(
                song = playbackQueue.getOrNull(mutation.currentPlaybackIndex) ?: it.song,
                queue = playbackQueue,
                currentIndex = mutation.currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
        return true
    }

    fun handleSongDeleted(songId: Long) {
        // CF-2H3E: a deletion event ends crossfade ownership first (even when routing is Unresolved or the plan no-ops).
        // The private deletion helpers (applyNonCurrentSongDeletion, handleCurrentSongDeleted, ...) never notify.
        explicitLibraryDeletionListeners.notifyExplicitLibraryDeletion()
        // Resolve the current occurrence ONCE through the occurrence-safe resolver; Now Playing state
        // may lag Media3 at a transition and must not decide current vs non-current.
        val currentIndex = currentPlaybackIndex()
        when (routeSongDeletion(playbackQueue, currentIndex, songId)) {
            SongDeletionRoute.Unresolved -> return // fail closed
            SongDeletionRoute.Current -> handleCurrentSongDeleted(songId, currentIndex ?: return)
            SongDeletionRoute.NonCurrent -> currentIndex?.let { applyNonCurrentSongDeletion(songId, it) }
        }
    }


    /**
     * Library deletion of a song that is not the current occurrence: ONE logical mutation planned from the
     * already-resolved [currentIdx] (never from Now Playing state, which may lag Media3). Removes every
     * occurrence of [songId]; the surviving current item keeps playing untouched.
     */
    private fun applyNonCurrentSongDeletion(songId: Long, currentIdx: Int) {
        val plan = QueueMutation.planNonCurrentSongDeletion(
            libraryQueue = libraryQueue,
            playbackOrder = playbackOrder,
            currentPlaybackIndex = currentIdx,
            deletedSongId = songId,
        ) as? QueueMutation.NonCurrentDeletionPlan.Mutation ?: return

        bumpQueueGeneration()
        libraryQueue = plan.libraryQueue
        playbackOrder = plan.playbackOrder
        playbackQueue = plan.playbackQueue

        // Aligned Media3 playlist mirrors the old playback order: remove the old positions, descending.
        // A dirty playlist is not authoritative and is left for the existing resync path.
        if (!playerQueueNeedsSync) {
            val controller = mediaController
            if (controller != null) plan.removedPlaybackPositions.forEach { controller.removeMediaItem(it) }
        }

        // Only queue/index are refreshed: the current song itself did not change, so Now Playing's song is
        // left to the normal Media3 sync (touching it here could hide a pending stats transition).
        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = plan.currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    private fun handleCurrentSongDeleted(deletedSongId: Long, currentIdx: Int) {
        if (currentIdx !in playbackQueue.indices) return

        // One coherent mutation: plan the whole resulting queue + current occurrence up front, so
        // nothing depends on a seek becoming observable before the removal (the old seek->remove race).
        // Deletion uses "next survivor" semantics (Repeat ONE still advances; see planner).
        val plan = QueueMutation.planCurrentSongDeletion(
            libraryQueue = libraryQueue,
            playbackOrder = playbackOrder,
            currentPlaybackIndex = currentIdx,
            deletedSongId = deletedSongId,
            repeatMode = repeatMode,
        )
        val controller = mediaController
        when {
            plan is QueueMutation.CurrentDeletionPlan.NoOp -> return
            plan is QueueMutation.CurrentDeletionPlan.Continue && controller != null ->
                applyCurrentDeletionContinuation(controller, plan)
            else -> {
                // No survivor to continue with, or controller not yet connected - clear queue and stop.
                bumpQueueGeneration()
                mediaController?.pause()
                mediaController?.clearMediaItems()
                libraryQueue        = emptyList()
                playbackOrder       = emptyList()
                playbackQueue       = emptyList()
                lastKnownPositionMs = -1L
                _nowPlayingState.update {
                    it.copy(
                        song         = null,
                        isPlaying    = false,
                        queue        = emptyList(),
                        currentIndex = 0,
                        positionMs   = 0L,
                        durationMs   = 0L,
                    )
                }
                saveSessionAsync()
            }
        }
    }

    private fun applyCurrentDeletionContinuation(
        controller: MediaController,
        plan: QueueMutation.CurrentDeletionPlan.Continue,
    ) {
        // Original play intent (playWhenReady survives buffering); a paused user is never auto-started.
        val wasPlaying = controller.playWhenReady || controller.isPlaying
        bumpQueueGeneration()
        libraryQueue = plan.libraryQueue
        playbackOrder = plan.playbackOrder
        playbackQueue = plan.playbackQueue
        lastKnownPositionMs = -1L

        // Explicit destructive library mutation (not a natural G-1 boundary): push the resulting
        // queue at the planned occurrence. Now Playing is updated from the plan below, never from a
        // controller index that may not be observable yet.
        syncPlayerQueueAt(controller, plan.currentPlaybackIndex, positionMs = 0L, playWhenReady = wasPlaying)

        val newSong = plan.currentSong
        // StatsTracker song transition and session persistence are owned by the Media3 transition callback
        // (onMediaItemTransition -> syncNowPlayingState(fromTransition = true) + saveSessionAsync) that the
        // playlist replacement above triggers; doing either here too would double-own them.
        _nowPlayingState.update {
            it.copy(
                song = newSong,
                isPlaying = wasPlaying,
                queue = playbackQueue,
                currentIndex = plan.currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                positionMs = 0L,
                durationMs = newSong.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }
    }

    fun moveQueueItemUp(playbackIndex: Int) {
        // CF-2H3C: explicit reorder intent cancels an owned crossfade first, even if validation then no-ops.
        explicitQueueReorderListeners.notifyExplicitQueueReorder()
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex <= currentPlaybackIndex || playbackIndex <= 0) return
        swapPlaybackItems(playbackIndex, playbackIndex - 1, currentPlaybackIndex)
    }

    fun moveQueueItemDown(playbackIndex: Int) {
        // CF-2H3C: explicit reorder intent cancels an owned crossfade first, even if validation then no-ops.
        explicitQueueReorderListeners.notifyExplicitQueueReorder()
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (playbackIndex <= currentPlaybackIndex || playbackIndex >= playbackQueue.size - 1) return
        swapPlaybackItems(playbackIndex, playbackIndex + 1, currentPlaybackIndex)
    }

    fun moveQueueItemTo(fromPlaybackIndex: Int, toPlaybackIndex: Int) {
        // CF-2H3C: explicit reorder intent cancels an owned crossfade first, even if validation then no-ops.
        explicitQueueReorderListeners.notifyExplicitQueueReorder()
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        if (fromPlaybackIndex <= currentPlaybackIndex || toPlaybackIndex <= currentPlaybackIndex) return
        if (fromPlaybackIndex == toPlaybackIndex) return
        if (fromPlaybackIndex !in playbackOrder.indices || toPlaybackIndex !in playbackOrder.indices) return

        bumpQueueGeneration()
        val newOrder = playbackOrder.toMutableList()
        val item = newOrder.removeAt(fromPlaybackIndex)
        newOrder.add(toPlaybackIndex, item)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        if (!playerQueueNeedsSync) {
            mediaController?.moveMediaItem(fromPlaybackIndex, toPlaybackIndex)
        }

        _nowPlayingState.update {
            it.copy(
                queue          = playbackQueue,
                currentIndex   = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode     = repeatMode,
            )
        }
        saveSessionAsync()
    }

    fun moveToPlayNext(playbackIndex: Int) {
        // CF-2H3A: notified before index validation so an invalid or already-immediate move still ends ownership.
        explicitPlayNextMutationListeners.notifyExplicitPlayNextMutation()
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
        val immediateNextIndex = currentPlaybackIndex + 1
        if (playbackIndex <= currentPlaybackIndex) return
        if (playbackIndex == immediateNextIndex) return
        if (playbackIndex !in playbackOrder.indices) return

        bumpQueueGeneration()
        // Lift the entry out of playbackOrder and re-insert right after current.
        val newOrder = playbackOrder.toMutableList()
        val libraryIndex = newOrder.removeAt(playbackIndex)
        newOrder.add(immediateNextIndex, libraryIndex)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        // Move the item in ExoPlayer's playlist using playback indices.
        if (!playerQueueNeedsSync) {
            mediaController?.moveMediaItem(playbackIndex, immediateNextIndex)
        }

        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    fun togglePlayPause() {
        // Explicit user transport intent outranks any pending automatic Bluetooth resume.
        automaticResumeAuthority.supersedeByUser()
        val controller = mediaController
        if (controller == null) {
            // No controller: derive the DESIRED play/pause state now (STATE_INTENT) and defer it —
            // a raw toggle replayed later could be stale. Only meaningful when a song is loaded;
            // otherwise there is nothing to play, so this is a genuine no-op. Latest intent wins
            // (a later PAUSE overwrites an earlier PLAY). Superseded by any queue-replacing request.
            val current = _nowPlayingState.value
            if (current.song == null) return
            val desiredPlay = PendingTransport.desiredPlayWhenReady(current.isPlaying)
            pendingPlayWhenReady = desiredPlay
            _nowPlayingState.update { it.copy(isPlaying = desiredPlay) } // optimistic; corrected on sync
            ensureControllerConnection()
            return
        }
        if (DEBUG_STATS) Log.d(TAG, "[togglePlayPause] before: controller.isPlaying=${controller.isPlaying}")
        if (controller.isPlaying) controller.pause() else controller.play()
        syncNowPlayingState()
        if (DEBUG_STATS) Log.d(TAG, "[togglePlayPause] after syncNPS: nowPlaying.isPlaying=${_nowPlayingState.value.isPlaying}")
    }

    /**
     * Called by PlaybackService when a non-Wavdrop controller (notification, media key, lock
     * screen, widget, external) issues PLAY_PAUSE. Explicit external transport outranks any
     * pending automatic Bluetooth resume.
     */
    fun onExplicitExternalTransport() {
        automaticResumeAuthority.supersedeByUser()
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null

        if (option == SleepTimerOption.OFF) {
            _sleepTimerState.value = SleepTimerState()
            return
        }

        val nowMs = System.currentTimeMillis()
        val durationMs = option.durationMs
        _sleepTimerState.value = SleepTimerState(
            option = option,
            startedAtMs = nowMs,
            endsAtMs = durationMs?.let { nowMs + it },
        )

        if (durationMs != null) {
            sleepTimerJob = scope.launch {
                delay(durationMs)
                triggerSleepTimer()
            }
        }
    }

    fun setCustomSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        val nowMs = System.currentTimeMillis()
        _sleepTimerState.value = SleepTimerState(
            option = SleepTimerOption.OFF,
            startedAtMs = nowMs,
            endsAtMs = nowMs + durationMs,
            customDurationMs = durationMs,
        )
        sleepTimerJob = scope.launch {
            delay(durationMs)
            triggerSleepTimer()
        }
    }

    fun skipToNext() {
        // CF-2G3: user intent cancels an owned crossfade first, even if navigation is deferred or a no-op. The
        // deferred-drain path (navigate) never notifies, so a pending command cancels exactly once.
        explicitNavigationListeners.notifyExplicitNavigation()
        // Explicit user navigation is distinct from automatic bad-media recovery: end any episode.
        resetBadMediaRecoveryEpisode()
        val controller = mediaController
        if (controller == null) {
            // Defer a single latest-direction navigation intent (not an accumulated skip count).
            // Recomputed against the live queue on reconnect, so a mutated queue never replays a
            // stale skip. Superseded by any queue-replacing request.
            pendingNavigation = NavigationIntent.NEXT
            ensureControllerConnection()
            return
        }
        navigate(controller, NavigationIntent.NEXT)
    }

    fun skipToPrevious() {
        // CF-2G3: also covers PREVIOUS resolving to restart-current (internal seek, not the public seekTo).
        explicitNavigationListeners.notifyExplicitNavigation()
        // Explicit user navigation is distinct from automatic bad-media recovery: end any episode.
        resetBadMediaRecoveryEpisode()
        val controller = mediaController
        if (controller == null) {
            pendingNavigation = NavigationIntent.PREVIOUS
            ensureControllerConnection()
            return
        }
        navigate(controller, NavigationIntent.PREVIOUS)
    }

    // Main thread only. Executes a next/previous navigation against the CURRENT queue state.
    // Shared by the immediate path and the deferred-reconnect drain so both recompute identically
    // (never replaying a stale index).
    private fun navigate(controller: MediaController, intent: NavigationIntent) {
        val currentPlaybackIndex = currentPlaybackIndex() ?: controller.currentMediaItemIndex
        when (intent) {
            NavigationIntent.NEXT -> {
                val nextPlaybackIndex = QueueNavigator.nextIndex(
                    queueSize = playbackQueue.size,
                    currentIndex = currentPlaybackIndex,
                    repeatMode = repeatMode,
                ) ?: return
                seekToPlaybackIndex(controller, nextPlaybackIndex)
            }
            NavigationIntent.PREVIOUS -> {
                when (val action = QueueNavigator.previousAction(
                    queueSize = playbackQueue.size,
                    currentIndex = currentPlaybackIndex,
                    currentPositionMs = controller.currentPosition,
                    repeatMode = repeatMode,
                    restartThresholdMs = previousButtonBehavior.previousRestartThresholdMs(),
                )) {
                    is PreviousQueueAction.MoveTo -> seekToPlaybackIndex(controller, action.index)
                    PreviousQueueAction.RestartCurrent -> {
                        controller.seekTo(0L)
                        syncPosition()
                        saveSessionAsync()
                    }
                    null -> Unit
                }
            }
        }
    }

    fun toggleShuffle() {
        // CF-2H2: user intent cancels an owned crossfade BEFORE any shuffle planning or queue-generation bump, even if
        // the toggle then no-ops (unresolved index, null plan). Native Media3 shuffle is not routed here. The service
        // custom TOGGLE_SHUFFLE command calls this method, so one user command yields one notification.
        explicitShuffleChangeListeners.notifyExplicitShuffleChange()
        val controller = mediaController
        val currentIndex = currentPlaybackIndex() ?: return
        val positionMs = controller?.currentPosition?.coerceAtLeast(0L)
            ?: _nowPlayingState.value.positionMs.coerceAtLeast(0L)
        val isPlaying = controller?.isPlaying ?: _nowPlayingState.value.isPlaying

        val newShuffleEnabled = !shuffleEnabled
        val toggleModel = QueueMutation.shuffleToggleModel(
            libraryQueue = libraryQueue,
            currentPlaybackOrder = playbackOrder,
            currentPlaybackIndex = currentIndex,
            shuffleEnabled = newShuffleEnabled,
        ) ?: return

        // Captured while the player queue is still in its pre-toggle state, to decide whether the
        // physical playlist can be reordered live (G-1).
        val oldPlaybackQueue = playbackQueue
        val wasPlayerQueueNeedsSync = playerQueueNeedsSync
        val controllerIndexBeforeToggle = controller?.currentMediaItemIndex
        val controllerCountBeforeToggle = controller?.mediaItemCount
        val syncPlan = planShufflePhysicalSync(
            oldPlaybackQueue = oldPlaybackQueue,
            newPlaybackQueue = toggleModel.playbackQueue,
            oldCurrentPlaybackIndex = currentIndex,
            newCurrentPlaybackIndex = toggleModel.currentPlaybackIndex,
            controllerAvailable = controller != null,
            controllerCurrentIndex = controllerIndexBeforeToggle,
            controllerMediaItemCount = controllerCountBeforeToggle,
            playerQueueNeedsSync = wasPlayerQueueNeedsSync,
        )

        // Dirty BEFORE the logical queue changes, so there is never an observable moment where the
        // logical queue is the new order while the flag says the player is aligned. Live
        // replacement and every fallback start dirty; only a proven NoOp stays clean. Anything not
        // provably aligned keeps the deferred path: the dirty flag records the app/player
        // divergence independently of controller availability (a WARM reconnect never reloads the
        // player), and [handlePendingAutomaticTransition] / the reconnect drain resolve it later —
        // see [planShufflePhysicalSync] and [playerQueueNeedsSync].
        playerQueueNeedsSync = syncPlan.playerQueueNeedsSync(ShuffleLiveSyncProgress.BeforeMutation)

        // Reorder changes occurrence identities; invalidate any in-progress recovery episode.
        bumpQueueGeneration()
        shuffleEnabled = newShuffleEnabled
        playbackOrder = toggleModel.playbackOrder
        playbackQueue = toggleModel.playbackQueue
        if (syncPlan is ShufflePhysicalSyncPlan.ReplaceAroundCurrent && controller != null) {
            // Aligned player + controller: reorder the physical playlist around the untouched
            // current item right now, so the next natural boundary is a pure Media3 AUTO
            // transition. The queue stays marked dirty during the live Media3 mutation because
            // playlist callbacks can observe intermediate physical state; it is cleared only after
            // BOTH replacements succeed, and stays dirty (deferred recovery) if either throws.
            val succeeded = applyShuffleAroundCurrent(controller, syncPlan)
            playerQueueNeedsSync = syncPlan.playerQueueNeedsSync(
                if (succeeded) ShuffleLiveSyncProgress.Succeeded else ShuffleLiveSyncProgress.Failed,
            )
        }
        if (controller == null) {
            // Demand-driven: bring the controller back so the reconnect drain can push the new order.
            ensureControllerConnection()
        }

        _nowPlayingState.update {
            it.copy(
                song = toggleModel.currentSong,
                isPlaying = isPlaying,
                queue = playbackQueue,
                currentIndex = toggleModel.currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                positionMs = positionMs,
            )
        }
        saveSessionAsync()
    }

    /**
     * Reorders the physical Media3 playlist around the current item using playlist mutations only:
     * no setMediaItems / prepare / play / pause / seek, and the current item is in neither replaced
     * range, so playback position and play state are untouched. Returns true only if both
     * mutations were issued; on any failure the caller leaves the queue marked dirty.
     */
    private fun applyShuffleAroundCurrent(
        controller: MediaController,
        plan: ShufflePhysicalSyncPlan.ReplaceAroundCurrent,
    ): Boolean = runCatching {
        val suffixStart = plan.oldCurrentPhysicalIndex + 1
        // Suffix first: its indexes are unaffected by the prefix edit that follows.
        if (plan.desiredSuffix.isNotEmpty() || suffixStart < controller.mediaItemCount) {
            controller.replaceMediaItems(
                suffixStart,
                controller.mediaItemCount,
                materializeMediaItems(plan.desiredSuffix).mediaItems,
            )
        }
        // Prefix strictly before the current item; afterwards the current item sits at
        // newCurrentPhysicalIndex.
        if (plan.desiredPrefix.isNotEmpty() || plan.oldCurrentPhysicalIndex > 0) {
            controller.replaceMediaItems(
                0,
                plan.oldCurrentPhysicalIndex,
                materializeMediaItems(plan.desiredPrefix).mediaItems,
            )
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                GAPLESS_TAG,
                "shuffle queue synchronized around current oldIndex=${plan.oldCurrentPhysicalIndex}" +
                    " newIndex=${plan.newCurrentPhysicalIndex} prefix=${plan.desiredPrefix.size}" +
                    " suffix=${plan.desiredSuffix.size}",
            )
        }
    }.onFailure { error ->
        Log.w(TAG, "[shuffleSync] live physical sync failed, deferring: ${error.message}")
    }.isSuccess

    fun cycleRepeatMode() {
        // CF-2H1: user intent cancels an owned crossfade BEFORE the repeat topology changes (every explicit command,
        // whether or not the new mode would invalidate the planned target). Also the path of the service's custom
        // CYCLE_REPEAT command, so one user command yields one notification.
        explicitRepeatChangeListeners.notifyExplicitRepeatChange()
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        mediaController?.repeatMode = repeatMode.toPlayerRepeatMode()
        _nowPlayingState.update { it.copy(repeatMode = repeatMode) }
        saveSessionAsync()
    }

    private val explicitSeekListeners = ExplicitSeekListenerRegistry()
    private val explicitNavigationListeners = ExplicitNavigationListenerRegistry()
    private val explicitRepeatChangeListeners = ExplicitRepeatChangeListenerRegistry()
    private val explicitShuffleChangeListeners = ExplicitShuffleChangeListenerRegistry()
    private val explicitPlayNextMutationListeners = ExplicitPlayNextMutationListenerRegistry()
    private val explicitAddToQueueMutationListeners = ExplicitAddToQueueMutationListenerRegistry()
    private val explicitQueueReorderListeners = ExplicitQueueReorderListenerRegistry()
    private val explicitQueueRemovalListeners = ExplicitQueueRemovalListenerRegistry()
    private val explicitLibraryDeletionListeners = ExplicitLibraryDeletionListenerRegistry()

    /**
     * CF-2H3E: registers (or clears with null) the lifecycle-scoped callback notified on every handleSongDeleted event.
     * Separate from the queue-removal seam. PlayerController knows nothing of crossfade; the owner must clear it on
     * teardown.
     */
    internal fun setExplicitLibraryDeletionListener(listener: (() -> Unit)?) {
        explicitLibraryDeletionListeners.set(listener)
    }

    /**
     * CF-2H3D: registers (or clears with null) the lifecycle-scoped callback notified on every explicit
     * removeFromQueue, clearEarlierQueue and clearUpNext command (not library deletion). PlayerController knows
     * nothing of crossfade; the owner must clear it on teardown.
     */
    internal fun setExplicitQueueRemovalListener(listener: (() -> Unit)?) {
        explicitQueueRemovalListeners.set(listener)
    }

    /**
     * CF-2H3C: registers (or clears with null) the lifecycle-scoped callback notified on every explicit
     * moveQueueItemUp/Down/To command (not moveToPlayNext, which belongs to the Play Next seam). PlayerController
     * knows nothing of crossfade; the owner must clear it on teardown.
     */
    internal fun setExplicitQueueReorderListener(listener: (() -> Unit)?) {
        explicitQueueReorderListeners.set(listener)
    }

    /**
     * CF-2H3B: registers (or clears with null) the lifecycle-scoped callback notified on every explicit addToQueue and
     * addAllToQueue command. PlayerController knows nothing of crossfade; the owner must clear it on teardown.
     */
    internal fun setExplicitAddToQueueMutationListener(listener: (() -> Unit)?) {
        explicitAddToQueueMutationListeners.set(listener)
    }

    /**
     * CF-2H3A: registers (or clears with null) the lifecycle-scoped callback notified on every explicit playNext,
     * playAllNext and moveToPlayNext command. PlayerController knows nothing of crossfade; the owner must clear it on
     * teardown.
     */
    internal fun setExplicitPlayNextMutationListener(listener: (() -> Unit)?) {
        explicitPlayNextMutationListeners.set(listener)
    }

    /**
     * CF-2H2: registers (or clears with null) the lifecycle-scoped callback notified on every explicit logical
     * toggleShuffle. PlayerController knows nothing of crossfade; the owner must clear it on teardown.
     */
    internal fun setExplicitShuffleChangeListener(listener: (() -> Unit)?) {
        explicitShuffleChangeListeners.set(listener)
    }

    /**
     * CF-2H1: registers (or clears with null) the lifecycle-scoped callback notified on every explicit app
     * cycleRepeatMode. PlayerController knows nothing of crossfade; the owner must clear it on teardown.
     */
    internal fun setExplicitRepeatChangeListener(listener: (() -> Unit)?) {
        explicitRepeatChangeListeners.set(listener)
    }

    /**
     * CF-2G3: registers (or clears with null) the lifecycle-scoped callback notified on every explicit app
     * skipToNext/skipToPrevious. PlayerController knows nothing of crossfade; the owner must clear it on teardown.
     */
    internal fun setExplicitNavigationListener(listener: (() -> Unit)?) {
        explicitNavigationListeners.set(listener)
    }

    /**
     * CF-2G2: registers (or clears with null) the lifecycle-scoped callback notified on every explicit app position
     * seek. PlayerController knows nothing of crossfade; the owner must clear it on teardown.
     */
    internal fun setExplicitSeekListener(listener: (() -> Unit)?) {
        explicitSeekListeners.set(listener)
    }

    fun seekTo(positionMs: Long) {
        // CF-2G2: the user asked to seek, so any owned crossfade stops owning playback. Notified before the seek is
        // clamped, deferred or applied (a deferred PendingSeek must not cancel again when it later drains).
        explicitSeekListeners.notifyExplicitSeek()
        val duration = _nowPlayingState.value.durationMs
        val clamped = positionMs.coerceIn(0L, if (duration > 0) duration else positionMs)
        val controller = mediaController
        if (controller == null) {
            // Defer only when the exact app-owned occurrence is known. A song-ID fallback would
            // collapse duplicate occurrences and could seek the wrong copy after reconnect.
            pendingSeek = capturePendingSeek(
                queueGeneration = queueGeneration,
                playbackQueue = playbackQueue,
                currentPlaybackIndex = currentPlaybackIndex(),
                positionMs = clamped,
            ) ?: return // Conservative no-op when occurrence identity cannot be established.
            _nowPlayingState.update { it.copy(positionMs = clamped) } // optimistic; corrected on sync
            ensureControllerConnection()
            return
        }
        controller.seekTo(clamped)
        syncPosition(positionOverrideMs = clamped)
        saveSessionAsync()
    }

    /**
     * Startup entry point used by [PlaybackStartupCoordinator]. Returns the authoritative
     * [PlayerHydrationResult] so the coordinator can distinguish terminal from transient outcomes
     * and keep failures retryable. Runs on the main dispatcher because Media3 controller access
     * (inside [ensurePlayerHydratedFromSession]) must happen on the application thread; the caller
     * may invoke this from any dispatcher.
     */
    suspend fun restoreSessionIfNeeded(availableSongs: List<Song>): PlayerHydrationResult =
        withContext(Dispatchers.Main.immediate) {
            val result = ensurePlayerHydratedFromSession(
                availableSongs = availableSongs,
                operation = "startup_restore",
            )
            if (BuildConfig.DEBUG) Log.d(RESUME_TAG, "restoreSessionIfNeeded: result=$result")
            result
        }

    suspend fun ensurePlayerHydratedFromSession(
        availableSongs: List<Song>,
        operation: String = "session_hydration",
    ): PlayerHydrationResult {
        // Fast path: external playback, or a connected controller that physically holds media.
        // A missing controller proves nothing about Media3 state, so it falls through to the
        // locked path, which obtains one (ControllerUnavailable stays retryable).
        if (isExternalPlayback) {
            logHydrationDecision(operation, "pre_lock", mediaController, HydrationDecision.SKIP_EXTERNAL)
            return PlayerHydrationResult.AlreadyHydrated
        }
        mediaController?.let { connected ->
            val decision = hydrationDecisionFor(connected)
            if (decision != HydrationDecision.NEEDS_HYDRATION) {
                logHydrationDecision(operation, "pre_lock", connected, decision)
                return PlayerHydrationResult.AlreadyHydrated
            }
        }

        return sessionHydrationMutex.withLock {
            if (isExternalPlayback) {
                logHydrationDecision(operation, "in_lock", mediaController, HydrationDecision.SKIP_EXTERNAL)
                return@withLock PlayerHydrationResult.AlreadyHydrated
            }

            val controller = awaitMediaController()
                ?: return@withLock PlayerHydrationResult.ControllerUnavailable
            val lockedDecision = hydrationDecisionFor(controller)
            logHydrationDecision(operation, "in_lock", controller, lockedDecision)
            if (lockedDecision != HydrationDecision.NEEDS_HYDRATION) {
                return@withLock PlayerHydrationResult.AlreadyHydrated
            }
            val rawSnapshot = sessionRepository.load()
                ?: return@withLock PlayerHydrationResult.NoSavedSession
            val resumeSettings = resumeBehaviorRepository.settings.first()
            val snapshot = PlaybackSessionRules.applyResumeBehavior(rawSnapshot, resumeSettings)
                ?: return@withLock PlayerHydrationResult.FilteredBySettings

            val mappedQueue = PlaybackSessionRules.mapSavedQueue(
                queueSongIds = snapshot.queueSongIds,
                availableSongs = availableSongs,
            ) ?: return@withLock PlayerHydrationResult.NoResolvableSong
            val startLibraryIndex = PlaybackSessionRules.resolveStartLibraryIndex(
                sessionSongId = snapshot.currentSongId,
                sessionIndex = snapshot.currentIndex,
                mappedQueue = mappedQueue,
            ) ?: return@withLock PlayerHydrationResult.NoResolvableSong
            val startSong = mappedQueue[startLibraryIndex]

            // Re-check the PHYSICAL player after all suspending work: if playback started meanwhile
            // (or external playback took over) the persisted snapshot must not overwrite it.
            // One decision drives both the log and the branch, so the diagnostic cannot diverge.
            val preApplyDecision = hydrationDecisionFor(controller)
            logHydrationDecision(operation, "pre_apply", controller, preApplyDecision)
            if (preApplyDecision != HydrationDecision.NEEDS_HYDRATION) {
                return@withLock PlayerHydrationResult.SkippedActiveQueue
            }

            val previousLibraryQueue = libraryQueue
            val previousPlaybackOrder = playbackOrder
            val previousPlaybackQueue = playbackQueue
            val previousShuffleEnabled = shuffleEnabled
            val previousRepeatMode = repeatMode
            val previousPlayerQueueNeedsSync = playerQueueNeedsSync
            val previousLastKnownPositionMs = lastKnownPositionMs
            val previousState = _nowPlayingState.value

            // A restored session is a fresh queue identity; end any stale recovery episode.
            bumpQueueGeneration()
            libraryQueue = mappedQueue
            shuffleEnabled = snapshot.shuffleEnabled
            repeatMode = snapshot.repeatMode
            restorePlaybackQueue(
                currentQueueIndex = startLibraryIndex,
                savedPlaybackOrder = snapshot.playbackOrder,
            )
            playerQueueNeedsSync = false
            val startPlaybackIndex = playbackOrder.indexOf(startLibraryIndex).takeIf { it >= 0 } ?: 0
            lastKnownPositionMs = -1L

            _nowPlayingState.update {
                it.copy(
                    song = startSong,
                    queue = playbackQueue,
                    queueSource = PlaybackQueueSource.Other,
                    currentIndex = startPlaybackIndex,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    positionMs = snapshot.positionMs,
                    durationMs = startSong.duration.coerceAtLeast(0L),
                    bufferedPositionMs = 0L,
                    isSeekable = false,
                )
            }

            runCatching {
                controller.repeatMode = repeatMode.toPlayerRepeatMode()
                controller.shuffleModeEnabled = false
                controller.setMeasuredMediaItems(
                    operation = operation,
                    songs = playbackQueue,
                    startIndex = startPlaybackIndex,
                    positionMs = snapshot.positionMs,
                )
                controller.prepare()
                syncNowPlayingState()
            }.fold(
                onSuccess = { PlayerHydrationResult.Hydrated },
                onFailure = { error ->
                    Log.w(TAG, "Session hydration failed during $operation", error)
                    libraryQueue = previousLibraryQueue
                    playbackOrder = previousPlaybackOrder
                    playbackQueue = previousPlaybackQueue
                    shuffleEnabled = previousShuffleEnabled
                    repeatMode = previousRepeatMode
                    playerQueueNeedsSync = previousPlayerQueueNeedsSync
                    lastKnownPositionMs = previousLastKnownPositionMs
                    _nowPlayingState.value = previousState
                    PlayerHydrationResult.MediaSetupFailed
                },
            )
        }
    }

    /** Mirrors a service-owned Media3 resumption without connecting back to the same session. */
    internal fun adoptPlaybackResumption(plan: PlaybackResumptionPlan) {
        bumpQueueGeneration()
        isExternalPlayback = false
        libraryQueue = plan.libraryQueue
        playbackOrder = plan.playbackOrder
        playbackQueue = plan.playbackQueue
        shuffleEnabled = plan.shuffleEnabled
        repeatMode = plan.repeatMode
        playerQueueNeedsSync = false
        lastKnownPositionMs = -1L
        val startSong = playbackQueue[plan.startPlaybackIndex]
        _nowPlayingState.update {
            it.copy(
                song = startSong,
                isPlaying = false,
                queue = playbackQueue,
                queueSource = PlaybackQueueSource.Other,
                currentIndex = plan.startPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                positionMs = plan.startPositionMs,
                durationMs = startSong.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }
    }

    /**
     * Called by PlaybackService when a Bluetooth audio device is removed.
     * Records whether playback was active at that moment so [resumeForBluetooth] can
     * honour the RESUME_IF_INTERRUPTED mode.
     */
    fun onBluetoothDeviceRemoved() {
        automaticResumeAuthority.supersedeByRouteLoss()
        val wasPlaying = mediaController?.isPlaying == true
        wasInterruptedByBluetooth = wasPlaying
        logResume("onBluetoothDeviceRemoved: wasPlaying=$wasPlaying")
        if (wasPlaying) {
            scope.launch { resumeBehaviorRepository.setBluetoothInterruptedResumePending(true) }
        }
    }

    /**
     * Called by PlaybackService when a wired audio output is removed.
     * Records whether playback was active at that moment so [resumeForWiredHeadphones] can
     * honour the RESUME_IF_INTERRUPTED mode.
     */
    fun onWiredDeviceRemoved() {
        val wasPlaying = mediaController?.isPlaying == true
        wasInterruptedByWired = wasPlaying
        logResume("onWiredDeviceRemoved: wasPlaying=$wasPlaying")
        if (wasPlaying) {
            scope.launch { resumeBehaviorRepository.setWiredInterruptedResumePending(true) }
        }
    }

    /**
     * Resumes playback after a Bluetooth audio device connects.
     *
     * Debounces duplicate events (some devices fire A2DP + headset + hearing-aid
     * connection events within milliseconds of each other).
     */
    @Synchronized
    fun resumeForBluetooth(availableSongs: List<Song>) {
        val nowMs = System.currentTimeMillis()
        if (!BluetoothResumeDebounce.shouldAttempt(
                lastAttemptAtMs = lastBluetoothResumeAttemptAtMs,
                nowMs = nowMs,
                windowMs = BLUETOOTH_RESUME_DEBOUNCE_MS,
            )
        ) {
            logResume(
                "resumeForBluetooth: debounced " +
                    "(${nowMs - lastBluetoothResumeAttemptAtMs}ms < ${BLUETOOTH_RESUME_DEBOUNCE_MS}ms since last attempt)",
            )
            return
        }
        lastBluetoothResumeAttemptAtMs = nowMs
        // The debounce only thins bursts; this token is what actually owns play authority.
        val request = BluetoothResumeRequest(
            token = automaticResumeAuthority.begin(),
            queueGeneration = queueGeneration,
        )
        scope.launch {
            // Token was created above (outside the lock) so a newer request invalidates this one
            // immediately; attempts and their entitlement settlement then run one at a time.
            val result = bluetoothResumeMutex.withLock { attemptBluetoothResume(availableSongs, request) }
            logResume("resumeForBluetooth: token=${request.token} result=$result")
        }
    }

    /**
     * Resumes playback after wired headphones connect.
     *
     * Debounced by checking whether a resume job is already in flight.
     */
    fun resumeForWiredHeadphones(availableSongs: List<Song>) {
        if (wiredResumeJob?.isActive == true) {
            logResume("resumeForWiredHeadphones: debounced — wiredResumeJob already active")
            return
        }
        wiredResumeJob = scope.launch {
            val result = attemptWiredResume(availableSongs)
            logResume("resumeForWiredHeadphones: result=$result")
        }
    }

    /** Identity of one automatic Bluetooth resume request. See [AutomaticResumeAuthority]. */
    private data class BluetoothResumeRequest(val token: Long, val queueGeneration: Long)

    private fun isBluetoothOutputConnected(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching { BluetoothOutputQuery.isConnected(audioManager) }.getOrDefault(false)
    }

    /**
     * Final gate immediately before an automatic Bluetooth play: request still current, output
     * still connected (fresh query, not the earlier readiness result), queue not mutated.
     */
    private fun authorizeBluetoothPlay(
        request: BluetoothResumeRequest,
        queueGenerationAllowedBumps: Int = 0,
    ): PlayAuthorization {
        val authorization = automaticResumeAuthority.authorizePlay(
            token = request.token,
            routeConnected = isBluetoothOutputConnected(),
            capturedQueueGeneration = request.queueGeneration,
            currentQueueGeneration = queueGeneration,
            queueGenerationAllowedBumps = queueGenerationAllowedBumps,
        )
        if (authorization != PlayAuthorization.ALLOWED) {
            logResume("bluetooth play denied token=${request.token}: $authorization")
        }
        return authorization
    }

    private fun denialOutcome(request: BluetoothResumeRequest, denial: PlayAuthorization): AutomaticResumeOutcome =
        BluetoothEntitlementPolicy.outcomeFor(denial, automaticResumeAuthority.invalidationOf(request.token))

    /**
     * Clears the entitlement, then rechecks ownership after the suspending write. If the request
     * lost ownership to route loss or a newer request while the write was in flight, the
     * entitlement is restored (persisted + in-memory) so a fresh interruption is never erased.
     * The in-memory flag is only cleared once the request is known to still be allowed to clear.
     */
    private suspend fun consumeBluetoothEntitlement(request: BluetoothResumeRequest) {
        resumeBehaviorRepository.setBluetoothInterruptedResumePending(false)
        val invalidation = automaticResumeAuthority.invalidationOf(request.token)
        if (BluetoothEntitlementPolicy.shouldRestoreAfterClear(invalidation)) {
            wasInterruptedByBluetooth = true
            resumeBehaviorRepository.setBluetoothInterruptedResumePending(true)
            logResume("consumeBluetoothEntitlement: token=${request.token} lost ownership ($invalidation) during clear — restored")
        } else {
            wasInterruptedByBluetooth = false
        }
    }

    /**
     * Core Bluetooth resume logic. The interruption entitlement (in-memory + persisted) is read
     * but NOT consumed up front; it is consumed once, at the end, and only when
     * [BluetoothEntitlementPolicy] says the outcome is definitive. Transient failures (controller
     * unavailable, setup failure, route lost, queue changed, superseded by a newer request) keep
     * it so the next reconnect can still resume. Every suspension point rechecks the token.
     */
    private suspend fun attemptBluetoothResume(
        availableSongs: List<Song>,
        request: BluetoothResumeRequest,
    ): AutomaticResumeOutcome {
        val rawOutcome = runBluetoothResume(availableSongs, request)
        // Reconcile with the request's latest invalidation immediately before settling, so an
        // old request cannot clear an entitlement owned by a newer route-loss or request.
        val outcome = BluetoothEntitlementPolicy.settle(
            rawOutcome,
            automaticResumeAuthority.invalidationOf(request.token),
        )
        if (outcome != rawOutcome) logResume("attemptBluetoothResume: token=${request.token} settled $rawOutcome -> $outcome")
        if (BluetoothEntitlementPolicy.shouldConsume(outcome)) consumeBluetoothEntitlement(request)
        return outcome
    }

    private suspend fun runBluetoothResume(
        availableSongs: List<Song>,
        request: BluetoothResumeRequest,
    ): AutomaticResumeOutcome {
        fun supersededOutcome(): AutomaticResumeOutcome? =
            automaticResumeAuthority.invalidationOf(request.token)?.let(BluetoothEntitlementPolicy::outcomeFor)

        val settings = resumeBehaviorRepository.settings.first()
        val persistedInterrupted = resumeBehaviorRepository.hasBluetoothInterruptedResumePending()
        val interrupted = wasInterruptedByBluetooth || persistedInterrupted

        logResume(
            "attemptBluetoothResume: token=${request.token} mode=${settings.bluetoothResumeMode} " +
                "interrupted=$interrupted rememberLastTrack=${settings.rememberLastTrack}",
        )

        supersededOutcome()?.let { return it }

        if (!settings.rememberLastTrack) {
            logResume("attemptBluetoothResume: skipped — rememberLastTrack=false")
            return AutomaticResumeOutcome.SKIPPED_BY_SETTING
        }

        if (!settings.bluetoothResumeMode.shouldResume(interrupted)) {
            logResume("attemptBluetoothResume: skipped — mode=${settings.bluetoothResumeMode} does not resume for interrupted=$interrupted")
            return AutomaticResumeOutcome.SKIPPED_BY_SETTING
        }

        val controller = awaitMediaController()
        if (controller == null) {
            logResume("attemptBluetoothResume: giving up — mediaController not ready after timeout")
            return AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE
        }

        supersededOutcome()?.let { return it }

        if (controller.isPlaying) {
            logResume("attemptBluetoothResume: already playing, nothing to do")
            return AutomaticResumeOutcome.ALREADY_PLAYING
        }

        val song = _nowPlayingState.value.song
        logResume(
            "attemptBluetoothResume: queueSize=${libraryQueue.size} " +
                "mediaItemCount=${controller.mediaItemCount} " +
                "playbackState=${stateString(controller.playbackState)}",
        )
        if (song != null && libraryQueue.isNotEmpty()) {
            authorizeBluetoothPlay(request).let { if (it != PlayAuthorization.ALLOWED) return denialOutcome(request, it) }
            lastKnownPositionMs = -1L
            statsTracker.onSongSelected(song)
            return performHotResume(controller, availableSongs, settings, "bluetooth", request)
        }

        logResume("attemptBluetoothResume: no active queue — loading session cold")
        val cold = resumeSessionCold(availableSongs, request)
        cold.denied?.let { return denialOutcome(request, it) }
        return when (cold.result) {
            PlayerHydrationResult.Hydrated,
            PlayerHydrationResult.AlreadyHydrated -> AutomaticResumeOutcome.PLAY_ISSUED
            PlayerHydrationResult.ControllerUnavailable -> AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE
            // Empty/late library can make a saved song unresolvable now but resolvable later.
            PlayerHydrationResult.MediaSetupFailed,
            PlayerHydrationResult.NoResolvableSong -> AutomaticResumeOutcome.SETUP_FAILED
            PlayerHydrationResult.FilteredBySettings -> AutomaticResumeOutcome.SKIPPED_BY_SETTING
            PlayerHydrationResult.NoSavedSession,
            PlayerHydrationResult.SkippedActiveQueue -> AutomaticResumeOutcome.NO_SESSION
        }
    }

    /** Core wired-headphone resume logic — same structure as [attemptBluetoothResume]. */
    private suspend fun attemptWiredResume(availableSongs: List<Song>): ResumeAttemptResult {
        val settings = resumeBehaviorRepository.settings.first()

        val inMemoryInterrupted = wasInterruptedByWired
        wasInterruptedByWired = false
        val persistedInterrupted = resumeBehaviorRepository.hasWiredInterruptedResumePending()
        val interrupted = inMemoryInterrupted || persistedInterrupted

        logResume(
            "attemptWiredResume: mode=${settings.wiredResumeMode} " +
                "interrupted=$interrupted rememberLastTrack=${settings.rememberLastTrack}",
        )

        if (!settings.rememberLastTrack) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: skipped — rememberLastTrack=false")
            return ResumeAttemptResult.SKIPPED_BY_SETTING
        }

        if (!settings.wiredResumeMode.shouldResume(interrupted)) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: skipped — mode=${settings.wiredResumeMode} does not resume for interrupted=$interrupted")
            return ResumeAttemptResult.SKIPPED_BY_SETTING
        }

        val controller = awaitMediaController()
        if (controller == null) {
            logResume("attemptWiredResume: giving up — mediaController not ready after timeout")
            return ResumeAttemptResult.SKIPPED_CONTROLLER_NOT_READY
        }

        if (controller.isPlaying) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: already playing before delay, nothing to do")
            return ResumeAttemptResult.ALREADY_PLAYING
        }

        delay(1_000L)

        if (controller.isPlaying) {
            if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)
            logResume("attemptWiredResume: already playing after delay, nothing to do")
            return ResumeAttemptResult.ALREADY_PLAYING
        }

        if (persistedInterrupted) resumeBehaviorRepository.setWiredInterruptedResumePending(false)

        val song = _nowPlayingState.value.song
        logResume(
            "attemptWiredResume: queueSize=${libraryQueue.size} " +
                "mediaItemCount=${controller.mediaItemCount} " +
                "playbackState=${stateString(controller.playbackState)}",
        )
        return if (song != null && libraryQueue.isNotEmpty()) {
            lastKnownPositionMs = -1L
            statsTracker.onSongSelected(song)
            performHotResume(controller, availableSongs, settings, "wired")
            ResumeAttemptResult.STARTED
        } else {
            logResume("attemptWiredResume: no active queue — loading session cold")
            when (resumeSessionCold(availableSongs).result) {
                PlayerHydrationResult.Hydrated,
                PlayerHydrationResult.AlreadyHydrated -> ResumeAttemptResult.STARTED
                PlayerHydrationResult.ControllerUnavailable -> ResumeAttemptResult.SKIPPED_CONTROLLER_NOT_READY
                else -> ResumeAttemptResult.SKIPPED_NO_SESSION
            }
        }
    }

    private data class ColdResumeResult(
        val result: PlayerHydrationResult,
        /** Non-null when an automatic request was denied play authority; play was NOT issued. */
        val denied: PlayAuthorization? = null,
    )

    /**
     * Hydrates the persisted session if needed, then applies reconnect autoplay policy.
     * The hydration primitive itself never calls play and never consumes retry eligibility.
     * For an automatic Bluetooth [request], authority is rechecked after the (suspending)
     * hydration and immediately before play; hydration may bump the queue generation once.
     */
    private suspend fun resumeSessionCold(
        availableSongs: List<Song>,
        request: BluetoothResumeRequest? = null,
    ): ColdResumeResult {
        val result = ensurePlayerHydratedFromSession(
            availableSongs = availableSongs,
            operation = "cold_resume",
        )
        if (!playerHydrationAllowsPlay(result)) {
            logResume("resumeSessionCold: hydration result=$result")
            return ColdResumeResult(result)
        }
        val controller = mediaController
        if (controller == null) {
            logResume("resumeSessionCold: mediaController null at play step, aborting")
            return ColdResumeResult(PlayerHydrationResult.ControllerUnavailable)
        }
        if (request != null) {
            val bumps = if (result == PlayerHydrationResult.Hydrated) 1 else 0
            val authorization = authorizeBluetoothPlay(request, queueGenerationAllowedBumps = bumps)
            if (authorization != PlayAuthorization.ALLOWED) return ColdResumeResult(result, authorization)
        }
        _nowPlayingState.value.song?.let { song ->
            if (!isExternalPlayback) statsTracker.onSongSelected(song)
        }
        logResume("resumeSessionCold: hydration result=$result, calling play()")
        controller.play()
        return ColdResumeResult(result)
    }

    /**
     * Executes the hot-resume play step after eligibility checks have passed.
     *
     * - If the controller has no loaded media items, falls back to [resumeSessionCold].
     * - If ExoPlayer is in STATE_IDLE or STATE_ENDED, calls [prepare] first so [play] is not a no-op.
     * - Logs immediate post-play diagnostics, then waits 700 ms and logs again.
     * - If after the delay the player is still not playing (playWhenReady=true, isPlaying=false,
     *   no error), performs one controlled retry — this covers transient audio-focus or
     *   audio-route suppression that resolves shortly after reconnect.
     *
     * For an automatic Bluetooth [request], both the initial play and the delayed retry are gated
     * by [authorizeBluetoothPlay]; a stale request never plays. Wired passes null (unchanged).
     */
    private suspend fun performHotResume(
        controller: MediaController,
        availableSongs: List<Song>,
        settings: ResumeBehaviorSettings,
        source: String,
        request: BluetoothResumeRequest? = null,
    ): AutomaticResumeOutcome {
        if (controller.currentMediaItem == null || controller.mediaItemCount == 0) {
            logResume("$source: hot resume has no media item (mediaItemCount=${controller.mediaItemCount}) — falling back to cold resume")
            val cold = resumeSessionCold(availableSongs, request)
            if (request != null) {
                cold.denied?.let { return denialOutcome(request, it) }
                if (!playerHydrationAllowsPlay(cold.result)) return AutomaticResumeOutcome.SETUP_FAILED
            }
            return AutomaticResumeOutcome.PLAY_ISSUED
        }

        if (request != null) {
            authorizeBluetoothPlay(request).let { if (it != PlayAuthorization.ALLOWED) return denialOutcome(request, it) }
        }

        // Issue prepare() if the pipeline is not ready — play() is a no-op in STATE_IDLE/ENDED.
        when (controller.playbackState) {
            Player.STATE_IDLE, Player.STATE_ENDED -> {
                logResume("$source: hot resume prepared + play (state=${stateString(controller.playbackState)})")
                controller.prepare()
                controller.play()
            }
            else -> {
                logResume("$source: hot resume play (state=${stateString(controller.playbackState)})")
                controller.play()
            }
        }

        logResumePlayDiagnostics(controller, "$source: immediate post-play")

        // Give ExoPlayer time to settle audio focus and routing after reconnect, then verify.
        delay(700L)

        logResumePlayDiagnostics(controller, "$source: delayed post-play")

        val suppression = controller.playbackSuppressionReason
        if (suppression != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            logResume("$source: playback suppressed reason=$suppression — not retrying")
            return AutomaticResumeOutcome.PLAY_ISSUED
        }

        // One controlled retry if the player accepted the command but still hasn't started.
        if (controller.playWhenReady && !controller.isPlaying && controller.playerError == null
            && controller.mediaItemCount > 0
        ) {
            // The initial play already happened, so a denied retry does not undo the outcome.
            if (request != null && authorizeBluetoothPlay(request) != PlayAuthorization.ALLOWED) {
                logResume("$source: hot resume retry aborted — request no longer authorized")
                return AutomaticResumeOutcome.PLAY_ISSUED
            }
            logResume("$source: hot resume retry play (playWhenReady=true but isPlaying=false after delay)")
            if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                controller.prepare()
            }
            controller.play()
            logResumePlayDiagnostics(controller, "$source: post-retry")
        }
        return AutomaticResumeOutcome.PLAY_ISSUED
    }


    private fun logResumePlayDiagnostics(controller: MediaController, label: String) {
        val suppression = controller.playbackSuppressionReason
        logResume(
            "$label: state=${stateString(controller.playbackState)} " +
                "playWhenReady=${controller.playWhenReady} " +
                "isPlaying=${controller.isPlaying} " +
                "suppression=${suppressionString(suppression)} " +
                "hasMediaItem=${controller.currentMediaItem != null} " +
                "mediaItemCount=${controller.mediaItemCount} " +
                "currentIndex=${controller.currentMediaItemIndex} " +
                "canPlay=${controller.availableCommands.contains(Player.COMMAND_PLAY_PAUSE)} " +
                "error=${controller.playerError}",
        )
    }

    private fun suppressionString(reason: Int): String = when (reason) {
        Player.PLAYBACK_SUPPRESSION_REASON_NONE                       -> "NONE"
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "TRANSIENT_AUDIO_FOCUS_LOSS"
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_ROUTE     -> "UNSUITABLE_AUDIO_ROUTE"
        else                                                           -> "UNKNOWN($reason)"
    }

    private fun stateString(state: Int): String = when (state) {
        Player.STATE_IDLE      -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY     -> "READY"
        Player.STATE_ENDED     -> "ENDED"
        else                   -> "UNKNOWN($state)"
    }

    /**
     * Polls for the [MediaController] to become available, up to [timeoutMs].
     *
     * Required because [resumeForBluetooth]/[resumeForWiredHeadphones] can be called from
     * [PlaybackService.onStartCommand] before the async [MediaController] future completes,
     * especially on cold start (broadcast-receiver wakeup after process death).
     */
    private suspend fun awaitMediaController(timeoutMs: Long = 3_000L): MediaController? {
        mediaController?.let { return it }
        // Demand-driven retry: if a previous connection attempt failed (or the controller was
        // disconnected), this starts a fresh build instead of waiting forever on a dead attempt.
        ensureControllerConnection()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val c = mediaController
            if (c != null) return c
            delay(100L)
        }
        return null
    }

    private fun logResume(message: String) {
        if (BuildConfig.DEBUG) Log.d(RESUME_TAG, message)
    }

    /**
     * [exactPlaybackIndex] / [positionOverrideMs] let a caller that has just issued an exact seek (CF-2D2) persist
     * the intended occurrence and position instead of re-reading a MediaController that may not have updated yet.
     */
    private fun saveSessionAsync(exactPlaybackIndex: Int? = null, positionOverrideMs: Long? = null) {
        val action = playbackSessionPersistenceAction(
            isExternalPlayback = isExternalPlayback,
            queueIsEmpty = libraryQueue.isEmpty(),
        )
        if (action == PlaybackSessionPersistenceAction.NONE) return

        val revision = sessionPersistenceGate.nextRevision()
        if (action == PlaybackSessionPersistenceAction.CLEAR) {
            lastPositionCheckpointElapsedRealtimeMs = -1L
            lastPositionCheckpointMs = -1L
            scope.launch {
                sessionPersistenceGate.runIfLatest(revision) {
                    sessionRepository.clear()
                }
            }
            return
        }

        val state = _nowPlayingState.value
        val controller = mediaController
        val positionMs = positionOverrideMs ?: controller?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs
        lastPositionCheckpointElapsedRealtimeMs = SystemClock.elapsedRealtime()
        lastPositionCheckpointMs = positionMs
        val preservePlan = if (exactPlaybackIndex != null) null else pendingPreserveSearchPlan

        val currentLibraryIndex = resolveSessionCurrentLibraryIndex(
            libraryQueue = libraryQueue,
            playbackOrder = playbackOrder,
            currentPlaybackIndex = preservePlan?.currentIndex ?: exactPlaybackIndex ?: currentPlaybackIndex(),
            exactCurrentLibraryIndex = preservePlan?.currentIndex,
            currentSongId = preservePlan?.currentSongId ?: state.song?.id,
        ) ?: return
        val currentSongId = libraryQueue[currentLibraryIndex].id

        val snapshot = PlaybackSessionSnapshot(
            queueSongIds   = libraryQueue.map { it.id },
            playbackOrder  = playbackOrder.takeIf { it.size == libraryQueue.size },
            currentSongId  = currentSongId,
            currentIndex   = currentLibraryIndex,
            positionMs     = positionMs,
            repeatMode     = repeatMode,
            shuffleEnabled = shuffleEnabled,
            updatedAtMs    = System.currentTimeMillis(),
        )
        scope.launch {
            sessionPersistenceGate.runIfLatest(revision) {
                sessionRepository.save(snapshot)
            }
        }
    }

    private fun hydrationDecisionFor(controller: MediaController): HydrationDecision =
        HydrationAuthority.decide(
            isExternalPlayback = isExternalPlayback,
            mediaQueuePresent = HydrationAuthority.mediaQueuePresent(
                mediaItemCount = controller.mediaItemCount,
                hasCurrentMediaItem = controller.currentMediaItem != null,
            ),
        )

    private fun logHydrationDecision(
        operation: String,
        stage: String,
        controller: MediaController?,
        decision: HydrationDecision,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            RESUME_TAG,
            "hydration[$operation/$stage]: logicalQueue=${libraryQueue.isNotEmpty() || playbackQueue.isNotEmpty()} " +
                "mediaItemCount=${controller?.mediaItemCount} " +
                "hasCurrentMediaItem=${controller?.currentMediaItem != null} " +
                "externalPlayback=$isExternalPlayback decision=$decision",
        )
    }

    fun release() {
        stopPositionTicker()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        scope.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
        _progressPlayer.value = null
        // Invalidate any in-flight buildAsync so a late completion cannot install a stale
        // controller or mutate state after release.
        ++controllerConnectionGeneration
        controllerConnectionState = ControllerConnectionState.Disconnected
        mediaItemCache.clear()
    }

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope.launch {
            while (true) {
                tickAndCheckLoopBoundary()
                delay(500)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun triggerSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState()
        mediaController?.pause()
        syncNowPlayingState()
        syncPosition()
        saveSessionAsync()
    }

    /**
     * Called by the 500 ms position ticker while playing.
     */
    private fun tickAndCheckLoopBoundary() {
        val controller = mediaController ?: return
        val durationMs = controller.safeDurationMs(fallbackMs = _nowPlayingState.value.song?.duration ?: 0L)
        val currentPos = controller.safePositionMs(durationMs)

        val prev = lastKnownPositionMs
        lastKnownPositionMs = currentPos

        if (DEBUG_STATS) {
            Log.d(TAG, "[ticker] prev=$prev cur=$currentPos dur=$durationMs repeat=$repeatMode isPlaying=${controller.isPlaying}")
        }

        if (LoopBoundaryDetector.isLoopBoundary(prev, currentPos, repeatMode)) {
            // Occurrence authority: the same resolver as everywhere else; unresolved => skip, never guess.
            val song = songAtResolvedPlaybackIndex(playbackQueue, currentPlaybackIndex())
            if (song != null) {
                if (DEBUG_STATS) Log.d(TAG, "[ticker] LOOP BOUNDARY detected prev=$prev cur=$currentPos songId=${song.id}")
                if (_sleepTimerState.value.option == SleepTimerOption.END_OF_CURRENT_SONG) {
                    triggerSleepTimer()
                    return
                }
                if (!isExternalPlayback) {
                    statsTracker.onSongSelected(song)
                    statsTracker.onPlaybackStarted()
                }
            }
        }

        _nowPlayingState.update {
            it.copy(
                positionMs         = currentPos,
                durationMs         = durationMs,
                bufferedPositionMs = controller.safeBufferedPositionMs(durationMs),
                isSeekable         = controller.isCurrentMediaItemSeekable,
            )
        }

        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        if (shouldCheckpointPlaybackPosition(
                isPlaying = controller.isPlaying,
                isExternalPlayback = isExternalPlayback,
                queueIsEmpty = libraryQueue.isEmpty(),
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                lastCheckpointElapsedRealtimeMs = lastPositionCheckpointElapsedRealtimeMs,
                currentPositionMs = currentPos,
                lastCheckpointPositionMs = lastPositionCheckpointMs,
            )
        ) {
            saveSessionAsync()
        }
    }

    private fun syncPosition(positionOverrideMs: Long? = null) {
        val controller = mediaController ?: return
        _nowPlayingState.update {
            val durationMs = controller.safeDurationMs(fallbackMs = it.song?.duration ?: 0L)
            val positionMs = positionOverrideMs ?: controller.safePositionMs(durationMs)
            it.copy(
                positionMs         = positionMs.coerceForDuration(durationMs),
                durationMs         = durationMs,
                bufferedPositionMs = controller.safeBufferedPositionMs(durationMs),
                isSeekable         = controller.isCurrentMediaItemSeekable,
            )
        }
    }

    // Seek to a playback index (position in playbackQueue / ExoPlayer playlist).
    //
    // [forcePlay] overrides the derived play intent (default null = keep whatever the controller is
    // doing, i.e. the pre-Phase-8 behaviour). Bad-media recovery passes the episode's captured
    // intent explicitly because at error time controller.isPlaying is already false in STATE_IDLE:
    // true resumes the recovered track, false leaves it paused (never auto-starting a paused user).
    private fun seekToPlaybackIndex(
        controller: MediaController,
        playbackIndex: Int,
        forcePlay: Boolean? = null,
    ) {
        lastKnownPositionMs = -1L
        val shouldPlay = forcePlay ?: controller.isPlaying
        if (playerQueueNeedsSync) {
            syncPlayerQueueAt(controller, playbackIndex, positionMs = 0L, playWhenReady = shouldPlay)
        } else {
            controller.seekTo(playbackIndex, 0L)
        }
        if (shouldPlay) {
            controller.play()
        } else if (forcePlay == false) {
            // Explicit paused recovery intent: ensure the player does not auto-resume the new item.
            controller.pause()
        }
        syncNowPlayingState()
        saveSessionAsync()
    }

    /**
     * The only place the app touches the player at a natural track boundary. Media3 has already
     * advanced natively; when the player playlist is in step with the logical queue (the normal
     * case) this returns false and WavDrop merely follows state, preserving native gapless
     * playback (see [MediaItemTransitionKind]). It intervenes — a full re-push of the queue, which
     * is NOT gapless — only while [playerQueueNeedsSync] says the player's order is stale (e.g. a
     * deferred shuffle reorder, or a queue op that could not reach the player).
     */
    private fun handlePendingAutomaticTransition(): Boolean {
        if (!naturalTransitionRequiresQueueResync(MediaItemTransitionKind.Auto, playerQueueNeedsSync)) {
            return false
        }
        val controller = mediaController ?: return false
        if (BuildConfig.DEBUG) {
            Log.d(GAPLESS_TAG, "natural boundary requires queue resync (playerQueueNeedsSync): full queue re-push, not native gapless")
        }
        val currentPlaybackIndex = _nowPlayingState.value.currentIndex
            .takeIf { it in playbackQueue.indices }
            ?: return false
        val nextPlaybackIndex = QueueNavigator.automaticNextIndex(
            queueSize = playbackQueue.size,
            currentIndex = currentPlaybackIndex,
            repeatMode = repeatMode,
        )
        val wasPlaying = controller.isPlaying
        if (nextPlaybackIndex == null) {
            syncPlayerQueueAt(
                controller = controller,
                playbackIndex = currentPlaybackIndex,
                positionMs = 0L,
                playWhenReady = false,
            )
            controller.pause()
            syncNowPlayingState(fromTransition = true)
            saveSessionAsync()
            return true
        }

        syncPlayerQueueAt(
            controller = controller,
            playbackIndex = nextPlaybackIndex,
            positionMs = 0L,
            playWhenReady = wasPlaying,
        )
        syncNowPlayingState(fromTransition = true)
        saveSessionAsync()
        return true
    }

    private fun syncPlayerQueueAt(
        controller: MediaController,
        playbackIndex: Int,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        playerQueueNeedsSync = false
        controller.setMeasuredMediaItems(
            operation = "sync_player_queue",
            songs = playbackQueue,
            startIndex = playbackIndex,
            positionMs = positionMs,
        )
        controller.prepare()
        if (playWhenReady) controller.play()
    }

    private fun rebuildPlaybackQueue(currentQueueIndex: Int) {
        playbackOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = libraryQueue.size,
            currentIndex = currentQueueIndex,
            shuffleEnabled = shuffleEnabled,
        )
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
    }

    private fun restorePlaybackQueue(currentQueueIndex: Int, savedPlaybackOrder: List<Int>?) {
        playbackOrder = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = savedPlaybackOrder,
            queueSize = libraryQueue.size,
            currentQueueIndex = currentQueueIndex,
            shuffleEnabled = shuffleEnabled,
        )
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
    }

    // Swap two items in playbackOrder (both must be strictly after current).
    // Calls moveMediaItem on the controller using playback indices.
    private fun swapPlaybackItems(fromIndex: Int, toIndex: Int, currentPlaybackIndex: Int) {
        if (fromIndex <= currentPlaybackIndex || toIndex <= currentPlaybackIndex) return
        if (fromIndex !in playbackOrder.indices || toIndex !in playbackOrder.indices) return

        bumpQueueGeneration()
        val newOrder = playbackOrder.toMutableList()
        val tmp = newOrder[fromIndex]
        newOrder[fromIndex] = newOrder[toIndex]
        newOrder[toIndex] = tmp
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }

        if (!playerQueueNeedsSync) {
            mediaController?.moveMediaItem(fromIndex, toIndex)
        }

        _nowPlayingState.update {
            it.copy(
                queue = playbackQueue,
                currentIndex = currentPlaybackIndex,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
            )
        }
        saveSessionAsync()
    }

    private fun syncNowPlayingState(fromTransition: Boolean = false, notifyStats: Boolean = true) {
        val controller = mediaController
        val preservePlan = pendingPreserveSearchPlanForSync(controller, fromTransition)
        val currentPlaybackIndex = preservePlan?.currentIndex?.takeIf { it in playbackQueue.indices }
            ?: currentPlaybackIndex()
        val currentSong = currentPlaybackIndex?.let(playbackQueue::getOrNull)

        val songChanged = currentSong != null && currentSong.id != _nowPlayingState.value.song?.id
        if (DEBUG_STATS && (fromTransition || songChanged)) {
            Log.d(TAG, "[syncNPS] fromTransition=$fromTransition songChanged=$songChanged currentSongId=${currentSong?.id} prevSongId=${_nowPlayingState.value.song?.id} isPlaying=${controller?.isPlaying}")
        }

        if (!isExternalPlayback && notifyStats && currentSong != null && (fromTransition || songChanged)) {
            statsTracker.onSongSelected(currentSong)
            if (controller?.isPlaying == true) {
                statsTracker.onPlaybackStarted()
            }
        }
        _nowPlayingState.update {
            val durationMs = controller?.safeDurationMs(fallbackMs = currentSong?.duration ?: it.song?.duration ?: 0L)
                ?: it.durationMs
            val positionMs = controller?.safePositionMs(durationMs) ?: it.positionMs.coerceForDuration(durationMs)
            it.copy(
                song               = currentSong ?: it.song,
                isPlaying          = controller?.isPlaying ?: it.isPlaying,
                queue              = playbackQueue,
                currentIndex       = currentPlaybackIndex ?: -1,
                shuffleEnabled     = shuffleEnabled,
                repeatMode         = repeatMode,
                positionMs         = positionMs,
                durationMs         = durationMs,
                bufferedPositionMs = controller?.safeBufferedPositionMs(durationMs) ?: it.bufferedPositionMs.coerceForDuration(durationMs),
                isSeekable         = controller?.isCurrentMediaItemSeekable ?: it.isSeekable,
            )
        }
    }

    private fun pendingPreserveSearchPlanForSync(
        controller: MediaController?,
        fromTransition: Boolean,
    ): SearchPlaybackPlan? {
        val mediaSongId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        val mediaIndex = controller?.currentMediaItemIndex
        val decision = SearchPlaybackPlanner.preserveSyncDecision(
            plan = pendingPreserveSearchPlan,
            activeQueue = playbackQueue,
            mediaSongId = mediaSongId,
            mediaIndex = mediaIndex,
            fromTransition = fromTransition,
        )
        if (decision.action == PreserveSearchSyncAction.ClearPlan) {
            pendingPreserveSearchPlan = null
        }
        return decision.plan
    }

    private fun MediaController.safePositionMs(durationMs: Long): Long =
        currentPosition.coerceAtLeast(0L).coerceForDuration(durationMs)

    private fun MediaController.safeDurationMs(fallbackMs: Long): Long =
        duration.takeIf { it > 0L } ?: fallbackMs.coerceAtLeast(0L)

    private fun MediaController.safeBufferedPositionMs(durationMs: Long): Long =
        bufferedPosition.coerceAtLeast(0L).coerceForDuration(durationMs)

    private fun Long.coerceForDuration(durationMs: Long): Long =
        if (durationMs > 0L) coerceIn(0L, durationMs) else coerceAtLeast(0L)

    private fun List<Song>.queueLogSummary(currentSongId: Long? = null): String {
        val sampleSize = 3
        fun List<Song>.ids(): String =
            joinToString(prefix = "[", postfix = "]") { it.id.toString() }

        val samples = if (size <= sampleSize * 2) {
            "ids=${ids()}"
        } else {
            "head=${take(sampleSize).ids()} tail=${takeLast(sampleSize).ids()}"
        }
        val current = currentSongId?.let { " current=$it" }.orEmpty()
        return "queue(size=$size $samples$current)"
    }

    /**
     * Read-only snapshot of the logical playback state for the future crossfade engine (CF-2B1).
     * No side effects: reads in-memory state and the existing occurrence resolution only.
     */
    internal fun captureCrossfadeRuntimeSnapshot(): CrossfadeRuntimeSnapshot {
        val controller = mediaController
        return CrossfadeRuntimeSnapshot(
            queueGeneration = queueGeneration,
            playbackQueue = playbackQueue.toList(),
            currentPlaybackIndex = currentPlaybackIndex(),
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            isPlaying = controller?.isPlaying ?: _nowPlayingState.value.isPlaying,
            isExternalPlayback = isExternalPlayback,
            playerQueueNeedsSync = playerQueueNeedsSync,
            controllerConnected = controller != null &&
                controllerConnectionState == ControllerConnectionState.Connected,
        )
    }

    /**
     * CF-2D2: repositions the authoritative primary to [CrossfadeTransitionKey.toPlaybackIndex] at the secondary's
     * physical [snapshot] position, or rejects with no seek (see [planCrossfadePrimaryReconciliation]). Exact
     * generation/occurrence only: no queue rebuild, no song-id fallback, no deferred request, no queue-generation
     * bump, no change to playback intent or gain. Local state follows the seek only after it was issued.
     */
    internal fun reconcileCrossfadePrimary(
        key: CrossfadeTransitionKey,
        snapshot: SecondaryHandoffSnapshot,
    ): CrossfadePrimaryReconciliationResult {
        val controller = mediaController
        val connected = controller != null && controllerConnectionState == ControllerConnectionState.Connected
        val plan = planCrossfadePrimaryReconciliation(
            key = key,
            snapshot = snapshot,
            queueGeneration = queueGeneration,
            playbackQueueSize = playbackQueue.size,
            currentPlaybackIndex = currentPlaybackIndex(),
            physicalCurrentIndex = controller?.currentMediaItemIndex,
            repeatMode = repeatMode,
            playerQueueNeedsSync = playerQueueNeedsSync,
            controllerAvailable = connected,
        )
        if (controller == null) return executeCrossfadePrimaryReconciliation(plan) { _, _ -> }
        return executeCrossfadePrimaryReconciliation(
            plan = plan,
            seekTo = { index, positionMs -> controller.seekTo(index, positionMs) },
            // The seek was issued: install the already-validated target locally. Nothing here re-reads the
            // controller, whose index/position may not have propagated yet.
            onSeekSucceeded = { targetIndex ->
                lastKnownPositionMs = -1L
                _nowPlayingState.update {
                    reconcileCrossfadeNowPlayingState(it, playbackQueue, targetIndex, snapshot, shuffleEnabled, repeatMode)
                }
                saveSessionAsync(exactPlaybackIndex = targetIndex, positionOverrideMs = snapshot.positionMs)
            },
        )
    }

    private fun currentPlaybackIndex(): Int? {
        val controller = mediaController
        val controllerIndex = controller?.currentMediaItemIndex
        val controllerSongId = controller?.currentMediaItem?.mediaId?.toLongOrNull()
        if (
            BuildConfig.DEBUG &&
            !playerQueueNeedsSync &&
            controllerIndex != null &&
            controllerIndex in playbackQueue.indices &&
            controllerSongId != null &&
            playbackQueue[controllerIndex].id != controllerSongId
        ) {
            Log.d(
                SEARCH_TAG,
                "currentPlaybackIndex: controller index/mediaId mismatch at index $controllerIndex",
            )
        }
        val state = _nowPlayingState.value
        return resolveCurrentPlaybackIndex(
            playbackQueue = playbackQueue,
            controllerIndex = controllerIndex,
            controllerSongId = controllerSongId,
            stateIndex = state.currentIndex,
            stateSongId = state.song?.id,
            playerQueueNeedsSync = playerQueueNeedsSync,
        )
    }

    private fun MediaController.setMeasuredMediaItems(
        operation: String,
        songs: List<Song>,
        startIndex: Int,
        positionMs: Long,
    ) {
        if (!BuildConfig.DEBUG) {
            setMediaItems(materializeMediaItems(songs).mediaItems, startIndex, positionMs)
            return
        }
        val totalStartedAtMs = SystemClock.elapsedRealtime()
        val materializationStartedAtMs = SystemClock.elapsedRealtime()
        val materialization = materializeMediaItems(songs)
        val materializationElapsedMs = SystemClock.elapsedRealtime() - materializationStartedAtMs
        val mutationStartedAtMs = SystemClock.elapsedRealtime()
        setMediaItems(materialization.mediaItems, startIndex, positionMs)
        val mutationElapsedMs = SystemClock.elapsedRealtime() - mutationStartedAtMs
        logQueuePerf(
            operation = operation,
            inputQueueSize = songs.size,
            mediaItemCount = materialization.mediaItems.size,
            currentIndex = startIndex,
            materializationElapsedMs = materializationElapsedMs,
            mutationElapsedMs = mutationElapsedMs,
            totalElapsedMs = SystemClock.elapsedRealtime() - totalStartedAtMs,
            cacheHits = materialization.cacheHits,
            cacheMisses = materialization.cacheMisses,
            cacheSize = mediaItemCache.size,
        )
    }

    private fun MediaController.addMeasuredMediaItems(
        operation: String,
        songs: List<Song>,
    ) {
        if (!BuildConfig.DEBUG) {
            addMediaItems(materializeMediaItems(songs).mediaItems)
            return
        }
        val totalStartedAtMs = SystemClock.elapsedRealtime()
        val materializationStartedAtMs = SystemClock.elapsedRealtime()
        val materialization = materializeMediaItems(songs)
        val materializationElapsedMs = SystemClock.elapsedRealtime() - materializationStartedAtMs
        val mutationStartedAtMs = SystemClock.elapsedRealtime()
        addMediaItems(materialization.mediaItems)
        val mutationElapsedMs = SystemClock.elapsedRealtime() - mutationStartedAtMs
        logQueuePerf(
            operation = operation,
            inputQueueSize = songs.size,
            mediaItemCount = materialization.mediaItems.size,
            currentIndex = currentMediaItemIndex,
            materializationElapsedMs = materializationElapsedMs,
            mutationElapsedMs = mutationElapsedMs,
            totalElapsedMs = SystemClock.elapsedRealtime() - totalStartedAtMs,
            cacheHits = materialization.cacheHits,
            cacheMisses = materialization.cacheMisses,
            cacheSize = mediaItemCache.size,
        )
    }

    private fun MediaController.addMeasuredMediaItems(
        operation: String,
        index: Int,
        songs: List<Song>,
    ) {
        if (!BuildConfig.DEBUG) {
            addMediaItems(index, materializeMediaItems(songs).mediaItems)
            return
        }
        val totalStartedAtMs = SystemClock.elapsedRealtime()
        val materializationStartedAtMs = SystemClock.elapsedRealtime()
        val materialization = materializeMediaItems(songs)
        val materializationElapsedMs = SystemClock.elapsedRealtime() - materializationStartedAtMs
        val mutationStartedAtMs = SystemClock.elapsedRealtime()
        addMediaItems(index, materialization.mediaItems)
        val mutationElapsedMs = SystemClock.elapsedRealtime() - mutationStartedAtMs
        logQueuePerf(
            operation = operation,
            inputQueueSize = songs.size,
            mediaItemCount = materialization.mediaItems.size,
            currentIndex = index,
            materializationElapsedMs = materializationElapsedMs,
            mutationElapsedMs = mutationElapsedMs,
            totalElapsedMs = SystemClock.elapsedRealtime() - totalStartedAtMs,
            cacheHits = materialization.cacheHits,
            cacheMisses = materialization.cacheMisses,
            cacheSize = mediaItemCache.size,
        )
    }

    private fun MediaController.replaceMeasuredFutureMediaItems(
        operation: String,
        fromIndex: Int,
        songs: List<Song>,
    ) {
        if (!BuildConfig.DEBUG) {
            replaceMediaItems(fromIndex, mediaItemCount, materializeMediaItems(songs).mediaItems)
            return
        }
        val totalStartedAtMs = SystemClock.elapsedRealtime()
        val materializationStartedAtMs = SystemClock.elapsedRealtime()
        val materialization = materializeMediaItems(songs)
        val materializationElapsedMs = SystemClock.elapsedRealtime() - materializationStartedAtMs
        val mutationStartedAtMs = SystemClock.elapsedRealtime()
        replaceMediaItems(fromIndex, mediaItemCount, materialization.mediaItems)
        val mutationElapsedMs = SystemClock.elapsedRealtime() - mutationStartedAtMs
        logQueuePerf(
            operation = operation,
            inputQueueSize = songs.size,
            mediaItemCount = materialization.mediaItems.size,
            currentIndex = fromIndex,
            materializationElapsedMs = materializationElapsedMs,
            mutationElapsedMs = mutationElapsedMs,
            totalElapsedMs = SystemClock.elapsedRealtime() - totalStartedAtMs,
            cacheHits = materialization.cacheHits,
            cacheMisses = materialization.cacheMisses,
            cacheSize = mediaItemCache.size,
        )
    }

    private fun logQueuePerf(
        operation: String,
        inputQueueSize: Int,
        mediaItemCount: Int,
        currentIndex: Int,
        materializationElapsedMs: Long,
        mutationElapsedMs: Long,
        totalElapsedMs: Long,
        cacheHits: Int,
        cacheMisses: Int,
        cacheSize: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            QUEUE_PERF_TAG,
            "operation=$operation inputQueueSize=$inputQueueSize " +
                "mediaItemCount=$mediaItemCount currentIndex=$currentIndex " +
                "materializationMs=$materializationElapsedMs " +
                "media3MutationMs=$mutationElapsedMs totalMs=$totalElapsedMs " +
                "cacheHits=$cacheHits cacheMisses=$cacheMisses cacheSize=$cacheSize",
        )
    }

    private data class MediaItemCacheKey(
        val id: Long,
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val albumId: Long,
    )

    private data class MediaItemMaterialization(
        val mediaItems: List<MediaItem>,
        val cacheHits: Int,
        val cacheMisses: Int,
    )

    private fun materializeMediaItems(songs: List<Song>): MediaItemMaterialization {
        if (songs.isEmpty()) {
            return MediaItemMaterialization(
                mediaItems = emptyList(),
                cacheHits = 0,
                cacheMisses = 0,
            )
        }

        var cacheHits = 0
        var cacheMisses = 0
        val mediaItems = ArrayList<MediaItem>(songs.size)
        val pendingCacheWrites = LinkedHashMap<MediaItemCacheKey, MediaItem>()
        songs.forEach { song ->
            val key = song.mediaItemCacheKey()
            val cached = mediaItemCache[key] ?: pendingCacheWrites[key]
            if (cached != null) {
                cacheHits += 1
                mediaItems += cached
            } else {
                cacheMisses += 1
                val mediaItem = song.toPlaybackMediaItem()
                pendingCacheWrites[key] = mediaItem
                mediaItems += mediaItem
            }
        }
        pendingCacheWrites.forEach { (key, mediaItem) ->
            mediaItemCache[key] = mediaItem
        }
        return MediaItemMaterialization(
            mediaItems = mediaItems,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
        )
    }

    private fun Song.toCachedMediaItem(): MediaItem =
        materializeMediaItems(listOf(this)).mediaItems.first()

    private fun Song.mediaItemCacheKey(): MediaItemCacheKey =
        MediaItemCacheKey(
            id = id,
            uri = uri,
            title = displayTitle,
            artist = displayArtist,
            album = album,
            duration = duration,
            albumId = albumId,
        )

    private fun Uri.toExternalSong(displayName: String?): Song {
        val title = displayName
            ?.substringBeforeLast('.', missingDelimiterValue = displayName)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: lastPathSegment
                ?.substringBeforeLast('.', missingDelimiterValue = lastPathSegment.orEmpty())
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: "External audio"
        return Song(
            id = EXTERNAL_AUDIO_SONG_ID,
            title = title,
            artist = "Unknown Artist",
            album = "External audio",
            albumId = 0L,
            duration = 0L,
            uri = toString(),
            dateAdded = System.currentTimeMillis() / 1_000L,
            trackNumber = 0,
            year = 0,
            folderPath = null,
            folderName = null,
        )
    }

    private data class PreserveSearchRequest(
        val plan: SearchPlaybackPlan,
        val startSong: Song,
    )

    private data class ExternalPlaybackRequest(
        val uri: Uri,
        val displayName: String?,
    )

    /**
     * A Recently-Played queue jump accepted while the controller was unavailable. Carries both the
     * resolved playback index and the [songId] so [resolvePendingQueueJump] can detect and correct
     * a stale index if the queue mutated before reconnection — never seeking a different song.
     */
    private data class QueueJumpRequest(
        val songId: Long,
        val resolvedPlaybackIndex: Int,
    )

}

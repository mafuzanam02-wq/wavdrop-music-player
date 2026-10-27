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
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
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
    verifiedLogicalIndices: Set<Int> = emptySet(),
    physicalIndexOffset: Int = 0,
): Int? {
    if (playbackQueue.isEmpty()) return null

    // While dirty the physical timeline may be offset from the logical one (physical = logical + offset), and the
    // controller index is trusted ONLY at logical indices the physical-queue reconciler verified (physical id == logical id).
    val logicalControllerIndex = controllerIndex?.let { if (playerQueueNeedsSync) it - physicalIndexOffset else it }

    val controllerIndexMatchesSong = logicalControllerIndex != null &&
        logicalControllerIndex in playbackQueue.indices &&
        controllerSongId != null &&
        playbackQueue[logicalControllerIndex].id != controllerSongId

    val controllerIndexTrusted = !playerQueueNeedsSync ||
        (logicalControllerIndex != null && logicalControllerIndex in verifiedLogicalIndices)
    if (controllerIndexTrusted && logicalControllerIndex != null && logicalControllerIndex in playbackQueue.indices) {
        if (controllerIndexMatchesSong) return null
        return logicalControllerIndex
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

/**
 * Why the physical Media3 timeline may differ from the logical playbackQueue. The physical queue reconciler repairs every
 * one of these incrementally (bounded work around the current item first); a full re-push is only a last resort.
 */
internal enum class QueueDirtyReason {
    ControllerUnavailable,
    AlreadyDirty,
    PhysicalCurrentMismatch,
    PhysicalCountMismatch,
    PhysicalPrefixChanged,
    LargeBatchSpan,
    ShuffleReorderDeferred,
    ShuffleLiveSyncFailed,
    BatchMutationFailed,
    Unknown,
}

/**
 * The only situations that still justify a synchronous FULL physical re-push (`syncPlayerQueueAt`). Each is rare and is
 * logged with its queue size; routine transport, queue mutation, shuffle and reconnect repair incrementally instead.
 */
internal enum class FullQueueSyncReason {
    /** The reconciler could not prove the current occurrence or the requested target (never navigate to a guess). */
    TransportTargetUnprovable,

    /** A natural boundary landed on an index the reconciler had not verified (stale suffix was audible). */
    NaturalBoundaryUnverified,

    /** Reconnect found a current occurrence it could not trust. */
    ReconnectCurrentUnprovable,

    /** Library deletion of the CURRENT song: an explicit destructive change with its own transition semantics. */
    CurrentSongDeleted,
}

internal enum class BatchQueuePlayerSyncAction {
    NoOp,

    /** One bounded Media3 `replaceMediaItems(replaceFromIndex, replaceToIndexExclusive, songs)`; an insertion is just `from == to`. */
    ReplaceFutureSpan,

    /** Logical queue already updated; the physical timeline is repaired later by the reconciler (never a full re-push here). */
    MarkDirty,
}

internal data class BatchQueuePlayerSyncPlan(
    val action: BatchQueuePlayerSyncAction,
    val futureStartIndex: Int,
    val replaceFromIndex: Int = futureStartIndex,
    val replaceToIndexExclusive: Int = futureStartIndex,
    val songs: List<Song> = emptyList(),
    val dirtyReason: QueueDirtyReason? = null,
)

/** A single replace larger than this is repaired in chunks by the reconciler instead of one main-thread burst. */
internal const val MAX_SINGLE_SPAN_REPLACE_ITEMS = 2_048

/**
 * Plans how a batch queue mutation reaches the physical player. The cost scales with the CHANGED span, not the queue:
 * the old and new future suffixes are trimmed of their common head and tail and only the middle is replaced (a pure
 * Play-All-Next insertion becomes an insert of the batch). Anything the planner cannot prove aligned is marked dirty for
 * the reconciler; it is never turned into a synchronous full queue re-push.
 */
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
    fun dirty(reason: QueueDirtyReason) = BatchQueuePlayerSyncPlan(
        action = BatchQueuePlayerSyncAction.MarkDirty,
        futureStartIndex = futureStartIndex,
        dirtyReason = reason,
    )
    if (!controllerAvailable) return dirty(QueueDirtyReason.ControllerUnavailable)
    if (playerQueueNeedsSync) return dirty(QueueDirtyReason.AlreadyDirty)
    if (currentPlaybackIndex !in oldPlaybackQueue.indices ||
        currentPlaybackIndex !in newPlaybackQueue.indices ||
        controllerCurrentIndex != currentPlaybackIndex ||
        (controllerSongId != null && newPlaybackQueue[currentPlaybackIndex].id != controllerSongId)
    ) {
        return dirty(QueueDirtyReason.PhysicalCurrentMismatch)
    }
    if (controllerMediaItemCount != oldPlaybackQueue.size) return dirty(QueueDirtyReason.PhysicalCountMismatch)
    for (i in 0 until futureStartIndex) {
        if (oldPlaybackQueue[i] != newPlaybackQueue[i]) return dirty(QueueDirtyReason.PhysicalPrefixChanged)
    }

    val oldSize = oldPlaybackQueue.size
    val newSize = newPlaybackQueue.size
    var head = 0
    while (futureStartIndex + head < oldSize && futureStartIndex + head < newSize &&
        oldPlaybackQueue[futureStartIndex + head] == newPlaybackQueue[futureStartIndex + head]
    ) head++
    var tail = 0
    while (tail < oldSize - futureStartIndex - head && tail < newSize - futureStartIndex - head &&
        oldPlaybackQueue[oldSize - 1 - tail] == newPlaybackQueue[newSize - 1 - tail]
    ) tail++
    val replaceFrom = futureStartIndex + head
    val replaceTo = oldSize - tail
    val songs = newPlaybackQueue.subList(replaceFrom, newSize - tail)
    if (replaceFrom == replaceTo && songs.isEmpty()) {
        return BatchQueuePlayerSyncPlan(action = BatchQueuePlayerSyncAction.NoOp, futureStartIndex = futureStartIndex)
    }
    if (songs.size > MAX_SINGLE_SPAN_REPLACE_ITEMS || replaceTo - replaceFrom > MAX_SINGLE_SPAN_REPLACE_ITEMS) {
        return dirty(QueueDirtyReason.LargeBatchSpan)
    }
    return BatchQueuePlayerSyncPlan(
        action = BatchQueuePlayerSyncAction.ReplaceFutureSpan,
        futureStartIndex = futureStartIndex,
        replaceFromIndex = replaceFrom,
        replaceToIndexExclusive = replaceTo,
        songs = songs.toList(),
    )
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
        const val NO_ARMED_PLAY = -1L
        const val SEARCH_TAG = "WavdropSearchPlayback"
        const val GAPLESS_TAG = "WavdropGapless"
        const val RESUME_TAG = "WavdropResume"
        const val QUEUE_PERF_TAG = "WavdropQueuePerf"
        const val EXTERNAL_AUDIO_SONG_ID = com.launchpoint.wavdrop.data.model.ExternalAudioIdentity.SONG_ID
        const val BLUETOOTH_RESUME_DEBOUNCE_MS = 1_500L
        const val MEDIA_ITEM_CACHE_MAX_SIZE = 12_288
        const val QUEUE_REPAIR_CHUNK_ITEMS = 256
        /** ST-1: a stop within this distance of the item end counts as its natural end (a pause-at-end reports position = duration). */
        const val SLEEP_NATURAL_END_TOLERANCE_MS = 750L
        /** ST-1: the second AUTO callback of one escaped boundary arrives within this window and is swallowed. */
        const val SLEEP_ESCAPE_ECHO_WINDOW_MS = 2_000L

        /** Wired plug events precede a usable output; the first automatic play waits this long (re-checking authority). */
        const val WIRED_RESUME_SETTLE_MS = 1_000L
        /** Granularity at which waiting automatic resumes re-check their authority. */
        const val RESUME_WAIT_SLICE_MS = 100L

        const val DEBUG_STATS = false
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

    private var pendingPreserveSearchPlan: SearchPlaybackPlan? = null

    // THE bounded, latest-wins authority for everything accepted while no usable MediaController exists: one deferred start
    // (library / search-preserve / external), one held queue jump, and the single-slot transport intents (play-pause, seek,
    // navigation). The newest explicit user intent wins regardless of command type; see [DeferredPlaybackCommands] for the policy
    // and [PendingTransport] for the seek stale rules. Main-thread confined, drained exactly once per authoritative connection.
    private val deferred = DeferredPlaybackCommands()
    private val sessionHydrationMutex = Mutex()
    private val sessionPersistenceGate = PlaybackSessionPersistenceGate()
    private var isExternalPlayback = false

    // libraryQueue: source order. playbackOrder: indices into libraryQueue (identity or shuffled).
    // playbackQueue: songs in playback order = playbackOrder.map { libraryQueue[it] }.
    // IMMUTABLE-BY-REPLACEMENT: libraryQueue / playbackOrder / playbackQueue are never mutated in place; every change assigns a
    // freshly built list. Readers (NowPlayingState.queue, CrossfadeRuntimeSnapshot.playbackQueue) rely on this to share the reference.
    // ExoPlayer is ALWAYS loaded with playbackQueue, so currentMediaItemIndex == playback index.
    private var libraryQueue: List<Song> = emptyList()
    private var playbackOrder: List<Int> = emptyList()
    private var playbackQueue: List<Song> = emptyList()
    // Physical-queue sync state. `playerQueueNeedsSync` (the long-standing flag) is true while the physical Media3 timeline
    // may differ from playbackQueue ("dirty"). Only two things clear it: a COMPLETED reconciliation
    // ([PhysicalQueueReconciler], the single repair authority) and a full re-push ([syncPlayerQueueAt], a last resort).
    // The reason, the logical indices the reconciler verified and the physical offset qualify the dirty state so transport
    // can stay targeted instead of rebuilding the whole timeline.
    private var queueDirty: Boolean = false
    private var queueDirtyReason: QueueDirtyReason? = null
    private var verifiedLogicalIndices: Set<Int> = emptySet()
    private var physicalIndexOffset: Int = 0
    private var queueRepairCursor: ReconcileCursor? = null
    private var queueRepairJob: Job? = null
    private val physicalQueueReconciler = PhysicalQueueReconciler()
    private var playerQueueNeedsSync: Boolean
        get() = queueDirty
        set(value) {
            queueDirty = value
            if (value) {
                if (queueDirtyReason == null) queueDirtyReason = QueueDirtyReason.Unknown
            } else {
                queueDirtyReason = null
                verifiedLogicalIndices = emptySet()
                physicalIndexOffset = 0
                queueRepairCursor = null
                queueRepairJob?.cancel()
                queueRepairJob = null
            }
        }
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

    // Same primitive, separate instance for the wired route: a wired removal must not void a Bluetooth request (and vice
    // versa), while explicit user transport voids both.
    private val wiredResumeAuthority = AutomaticResumeAuthority()

    // Serializes automatic Bluetooth resume attempts + entitlement settlement only (not user
    // transport, wired resume, or queue work), so the newest request mutates entitlement last.
    private val bluetoothResumeMutex = Mutex()

    // Same, for wired attempts.
    private val wiredResumeMutex = Mutex()

    // Session hydrations (saved-queue loads, by whoever ran them) among the queue-generation bumps: lets a cold PLAY tell
    // "the saved session got loaded" apart from "the queue was edited or replaced".
    private var hydrationCount = 0L

    // Request ownership for an explicit PLAY that hydrates the saved session asynchronously. Shared with the session
    // player (PlaybackService) so PAUSE, a newer PLAY and every selection invalidate an older suspended PLAY.
    internal val coldPlayAuthority = ColdPlayAuthority(
        queueGeneration = { queueGeneration },
        hydrations = { hydrationCount },
    )

    // Current-route queries for the final pre-play check. Injectable so tests can script route loss deterministically.
    internal var bluetoothOutputConnectedQuery: () -> Boolean = { queryAudioRoute(BluetoothOutputQuery::isConnected) }
    internal var wiredOutputConnectedQuery: () -> Boolean = { queryAudioRoute(WiredOutputQuery::isConnected) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionTickerJob: Job? = null
    private var sleepTimerJob: Job? = null

    // ST-1: the lifecycle-scoped owner (PlaybackService) is told when the terminal boundary arms/releases so it can end any
    // owned crossfade and toggle the physical pause-at-end. The deferred rebind re-validates an armed boundary after a queue
    // mutation (one main-thread turn later, once the mutation has finished). The echo deadline makes the second of the two
    // AUTO callbacks of one escaped boundary harmless (no fabricated stats for the track that must not have started).
    private val sleepBoundaryListeners = SleepBoundaryListenerRegistry()
    private var sleepRebindJob: Job? = null
    private var sleepEscapeEchoDeadlineElapsedMs = 0L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) PlaybackStartupTrace.onIsPlaying("ctl")
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
                handleSleepPlaybackStopped()
                stopPositionTicker()
                syncPosition()
                saveSessionAsync()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (DEBUG_STATS) {
                Log.d(TAG, "[mediaItemTransition] reason=$reason mediaId=${mediaItem?.mediaId} repeatMode=$repeatMode isPlaying=${mediaController?.isPlaying}")
            }
            // P5-C: window upkeep must not depend on the Full-mode echo/ownership logic below (another callback may already have
            // synced the state). It is idempotent: NoChange when nothing is due, Busy while an operation is in flight.
            if (isBounded()) onBoundedAutomaticTransition()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && isStaleAutomaticEcho()) {
                // Echo of an earlier discontinuity after a playlist mutation (e.g. a physical repair): the current
                // occurrence did not change, so no stats, sleep-timer or session ownership; just refresh state.
                syncNowPlayingState(fromTransition = false)
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && handlePendingAutomaticTransition()) {
                return
            }
            if (handleSleepTransition(sleepTransitionKind(reason), isAutomaticCallback = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) {
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
            if (isBounded()) onBoundedAutomaticTransition() // see onMediaItemTransition
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                if (isStaleAutomaticEcho()) {
                    syncNowPlayingState(notifyStats = false)
                    return
                }
                if (handlePendingAutomaticTransition()) {
                    return
                }
                if (handleSleepTransition(SleepTransitionKind.AUTOMATIC, isAutomaticCallback = true)) {
                    return
                }
                // Reset ticker tracking so it doesn't double-detect this same boundary.
                lastKnownPositionMs = -1L
                // The resolved LOGICAL occurrence (newPosition.mediaItemIndex is physical: equal to it when aligned, offset
                // from it while a queue repair is in flight).
                // newPosition.mediaItemIndex is PHYSICAL: only the Full-mode alignment lets it stand in for a logical index.
                val song = currentPlaybackIndex()?.let(playbackQueue::getOrNull)
                    ?: (if (isBounded()) null else playbackQueue.getOrNull(newPosition.mediaItemIndex)) ?: return
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

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // P5-D: a physical playlist change nobody in the adapter asked for (an external index-bearing edit) is noticed here, not
            // only at the next transition. The adapter's own operations hold the pending slot / leave the Active state, so they never
            // reach the recovery branch; an unprovable current while Active fails closed with one bounded re-anchor.
            if (isBounded() && !physicalWindowAdapter.isBusy) onBoundedAutomaticTransition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            PlaybackStartupTrace.onPlayerState("ctl", playbackState)
            if (DEBUG_STATS) {
                Log.d(TAG, "[playbackStateChanged] state=$playbackState songId=${_nowPlayingState.value.song?.id} repeatMode=$repeatMode")
            }
            if (playbackState == Player.STATE_READY) {
                // A valid item successfully loaded: the current bad-media recovery episode (if any)
                // has succeeded, so end it. A later independent failure begins a fresh episode.
                resetBadMediaRecoveryEpisode()
            }
            if (playbackState == Player.STATE_ENDED) {
                handleSleepPlaybackStopped()
                if (isBounded()) continueAfterPhysicalWindowEnd()
            }
            syncNowPlayingState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // ST-1: a pause (natural pause-at-end of the armed occurrence, or a user/system pause) while a terminal boundary
            // is armed. Idempotent with onIsPlayingChanged / STATE_ENDED: the first one consumes, the rest find nothing armed.
            if (!playWhenReady) handleSleepPlaybackStopped()
        }

        override fun onPlayerError(error: PlaybackException) {
            // WB-01: a track that cannot be played (moved, deleted, or unsupported) latches the
            // pipeline in STATE_IDLE with this error. Without handling, playback stays stuck on
            // the bad item. Recover by bypassing the failing item using the existing skip/stop
            // queue logic, never corrupting queue/session state and never fabricating stats.
            Log.w(TAG, "[playerError] code=${error.errorCodeName} message=${error.message}")
            PlaybackStartupTrace.onPlayerError("ctl", error)
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
            if (isBounded()) {
                // P5-E: in bounded mode this controller observes the LOGICAL repeat the façade presents. An external change is routed
                // to the logical command port instead, and the physical mirror is managed through the physical port only.
                return
            }
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
            // P5-E: bounded mode presents the logical shuffle state to this controller; it is never a native-shuffle intrusion.
            if (isBounded()) return
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
            PlaybackStartupTrace.abort("bad_media_unclassified")
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
                PlaybackStartupTrace.mark("bad_media_recovery_advance", step.state.attemptedOccurrences.size)
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
                PlaybackStartupTrace.abort("bad_media_exhausted")
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
     * Explicit user intent (selection, transport, external transport) outranks every pending automatic resume (Bluetooth
     * and wired) and every suspended cold PLAY.
     */
    private fun supersedeAutomaticResumeByUser(includingColdPlay: Boolean = true) {
        automaticResumeAuthority.supersedeByUser()
        wiredResumeAuthority.supersedeByUser()
        if (includingColdPlay) coldPlayAuthority.supersede()
    }

    /**
     * Marks the queue identity as changed (Phase 8): bumps [queueGeneration] and ends any recovery
     * episode. Called from every queue replacement / structural mutation so a stale episode's
     * occurrence indices can never be reused.
     */
    private fun bumpQueueGeneration() {
        queueGeneration++
        notifyLogicalPresentationChanged()
        pendingDeletionContinuation = null // a bounded deleted-current continuation belongs to the generation that made it
        resetBadMediaRecoveryEpisode()
        // The logical queue is about to change: whatever was verified, and any in-flight repair, no longer applies. A dirty
        // queue is repaired again from the new facts (the repair starts after this main-thread turn finishes).
        verifiedLogicalIndices = emptySet()
        queueRepairCursor = null
        queueRepairJob?.cancel()
        queueRepairJob = null
        kickQueueRepair()
        scheduleSleepBoundaryRebind()
    }

    /** Marks the physical queue dirty for [reason] and starts bounded repair. The logical queue is already the truth. */
    private fun markQueueDirty(reason: QueueDirtyReason) {
        playerQueueNeedsSync = true
        queueDirtyReason = reason
        verifiedLogicalIndices = emptySet()
        queueRepairCursor = null
        kickQueueRepair()
    }

    /**
     * THE rule for a logical queue edit that has a one-command physical equivalent: the edit either provably reached Media3 or
     * leaves an explicit repair obligation (the dirty flag) that survives disconnection and reconnection. It is dirty, never
     * silently skipped, when:
     *  - the queue is already dirty (the edit is part of what the reconciler will converge on);
     *  - no controller exists (the controller is demanded so the reconnect drain reconciles);
     *  - the controller cannot change media items right now;
     *  - the command throws.
     * A command that returns without throwing proves only that it was issued, not that the asynchronous timeline matches, so the
     * reconciler's physical-vs-logical verification remains the final authority.
     */
    private fun applyPhysicalEdit(edit: (MediaController) -> Unit) {
        val controller = mediaController
        if (controller == null) {
            // Demanded even when already dirty: a failed earlier attempt must be retried by a later edit.
            if (!playerQueueNeedsSync) markQueueDirty(QueueDirtyReason.ControllerUnavailable)
            ensureControllerConnection()
            return
        }
        if (playerQueueNeedsSync) return
        if (!controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            markQueueDirty(QueueDirtyReason.BatchMutationFailed)
            return
        }
        runCatching { edit(controller) }.onFailure { error ->
            Log.w(TAG, "[queueEdit] physical edit failed, queue marked dirty: ${error.message}")
            markQueueDirty(QueueDirtyReason.BatchMutationFailed)
        }
    }

    /**
     * Keeps a pending deferred start on the SAME queue entry across a structural edit whose exact index transformation is known
     * ([map], see [OccurrenceMap]); an edit that removed the selected entry cancels the start. Call right after the edit bumped
     * the generation, passing the generation from BEFORE the bump. Edits without a known transformation never call this and
     * the start is rejected by [resolveDeferredStart].
     */
    private fun rebaseDeferredStart(generationBeforeEdit: Long, map: (Int) -> Int?) {
        val pending = deferred.start ?: return
        val mapped = map(pending.startPlaybackIndex)?.takeIf { playbackQueue.getOrNull(it)?.id == pending.startSongId }
        if (deferred.rebaseStart(generationBeforeEdit, queueGeneration, mapped) && mapped == null) {
            Log.w(TAG, "Cancelling deferred start selection=${pending.selectionId}: its occurrence was removed")
            PlaybackStartupTrace.abort("deferred_start_removed")
        }
    }

    /**
     * Starts (or restarts) the background physical-queue repair: bounded chunks on separate looper turns, bound to the queue
     * generation and the controller instance it started against, so a stale pass can never mutate a newer queue or a
     * replaced controller. Explicit transport never waits for it (see [repairForTransportTarget]).
     */
    private fun kickQueueRepair() {
        if (!queueDirty || isBounded()) return // P5-C: the Full-queue reconciler never runs against a bounded window
        queueRepairJob?.cancel()
        val generation = queueGeneration
        val startedController = mediaController
        queueRepairJob = scope.launch {
            yield() // let the command that dirtied the queue finish its main-thread turn first
            while (isActive) {
                val controller = mediaController ?: return@launch
                if (startedController != null && controller !== startedController) return@launch
                if (generation != queueGeneration || !queueDirty) return@launch
                val outcome = reconcilePhysicalQueue(controller, budget = QUEUE_REPAIR_CHUNK_ITEMS)
                if (outcome !is QueueReconcileOutcome.InProgress) return@launch
                yield()
            }
        }
    }

    /**
     * One reconciliation call against the live controller (main thread). Updates the dirty state from the outcome:
     * Completed clears the flag, InProgress records what is verified, Unsafe leaves the queue dirty for the caller's
     * conservative fallback. Never touches the current item.
     */
    private fun reconcilePhysicalQueue(
        controller: MediaController,
        budget: Int,
        logicalCurrent: Int? = null,
        priorityIndices: Collection<Int> = emptyList(),
    ): QueueReconcileOutcome {
        if (isBounded()) return QueueReconcileOutcome.Unsafe("bounded_mode") // never reconcile a window as if it were the full queue
        val current = logicalCurrent ?: currentPlaybackIndex()
            ?: return QueueReconcileOutcome.Unsafe("current_unresolved")
        val automaticNext = QueueNavigator.automaticNextIndex(playbackQueue.size, current, repeatMode)
        val outcome = runCatching {
            physicalQueueReconciler.reconcile(
                timeline = queueTimelineFor(controller),
                queue = playbackQueue,
                logicalCurrent = current,
                priorityIndices = if (automaticNext != null) priorityIndices + automaticNext else priorityIndices,
                budget = budget,
                cursor = queueRepairCursor,
            )
        }.getOrElse { error ->
            Log.w(TAG, "[queueRepair] reconcile failed: ${error.message}")
            QueueReconcileOutcome.Unsafe("exception")
        }
        when (outcome) {
            QueueReconcileOutcome.Completed -> {
                if (BuildConfig.DEBUG) {
                    Log.d(QUEUE_PERF_TAG, "queue_repair completed reason=$queueDirtyReason queueSize=${playbackQueue.size} current=$current")
                }
                playerQueueNeedsSync = false
            }
            is QueueReconcileOutcome.InProgress -> {
                verifiedLogicalIndices = outcome.verified
                physicalIndexOffset = outcome.physicalOffset
                queueRepairCursor = outcome.cursor
            }
            is QueueReconcileOutcome.Unsafe -> {
                verifiedLogicalIndices = emptySet()
                physicalIndexOffset = 0
                queueRepairCursor = null
                if (BuildConfig.DEBUG) {
                    Log.d(QUEUE_PERF_TAG, "queue_repair unsafe reason=${outcome.reason} dirty=$queueDirtyReason queueSize=${playbackQueue.size} current=$current")
                }
            }
        }
        return outcome
    }

    /**
     * Explicit transport (Next / Previous / queue jump) on a dirty queue: synchronously establish just the target window
     * (bounded, independent of queue size) so the command is a plain seek. Returns false when the current occurrence or
     * the target cannot be proven; the caller then falls back to a labelled full re-push. Never navigates to a guess.
     */
    private fun repairForTransportTarget(controller: MediaController, targetLogicalIndex: Int): Boolean {
        val outcome = reconcilePhysicalQueue(controller, budget = 0, priorityIndices = listOf(targetLogicalIndex))
        return when (outcome) {
            QueueReconcileOutcome.Completed -> true
            is QueueReconcileOutcome.InProgress -> targetLogicalIndex in outcome.verified
            is QueueReconcileOutcome.Unsafe -> false
        }
    }

    /** True when an automatic callback cannot be a real advance (see [isStaleAutomaticAdvanceEcho]). Main thread only. */
    private fun isStaleAutomaticEcho(): Boolean {
        val resolved = currentPlaybackIndex() ?: return false
        return isStaleAutomaticAdvanceEcho(
            resolvedIndex = resolved,
            syncedIndex = _nowPlayingState.value.currentIndex,
            queueSize = playbackQueue.size,
            repeatMode = repeatMode,
        )
    }

    /** A natural advance is pure Media3 gapless only if it landed on an index the reconciler verified (id equal there). */
    private fun naturalAdvanceLandedOnVerifiedIndex(controller: MediaController): Boolean =
        isVerifiedPhysicalLanding(
            queue = playbackQueue,
            controllerIndex = controller.currentMediaItemIndex,
            controllerSongId = controller.currentMediaItem?.mediaId?.toLongOrNull(),
            physicalOffset = physicalIndexOffset,
            verifiedLogicalIndices = verifiedLogicalIndices,
        )

    private fun queueTimelineFor(controller: MediaController): PhysicalQueueTimeline =
        PlayerQueueTimeline(controller) { from, to, songs ->
            controller.replaceMeasuredMediaItems("queue_repair", from, to, songs)
        }

    /**
     * Builds the future that completes with the connected [MediaController]. Production: the real session token for
     * [PlaybackService]. Internal and replaceable only so a test can drive a connection attempt (start, fail, complete, complete
     * late) deterministically; production never replaces it.
     */
    internal var controllerFutureFactory: () -> ListenableFuture<MediaController> = {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        MediaController.Builder(context, token)
            .setListener(controllerLifecycleListener)
            .setConnectionHints(Bundle().apply { putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) })
            .buildAsync()
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
        val future = try {
            controllerFutureFactory()
        } catch (e: Exception) {
            // The attempt could not even be started (e.g. the session token cannot be resolved). Leave the state retry-eligible
            // instead of stranding it in Connecting forever; a pending deferred command survives for the next demand.
            controllerConnectionState = ControllerConnectionDecision.onConnectionFailed()
            Log.w(TAG, "MediaController connection could not be started; will retry on next demand", e)
            return
        }
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
        if (isBounded()) {
            // P5-E: the physical window lives in the physical player and is observed in-process through the port, so a new controller
            // says nothing about it and a plain reconnect needs no re-anchor. Only a window that is not verified (or work owed from
            // edits accepted while disconnected) is repaired, by the drain that follows.
            pendingDeletionContinuation = null
            val verified = physicalWindowAdapter.active != null && !physicalWindowAdapter.isBusy
            if (!verified || playerQueueNeedsSync) {
                physicalWindowAdapter.onPlayerReplaced()
                playerQueueNeedsSync = true
            }
        }
        applyPhysicalTransportModes(controller)
        drainPendingRequests(controller)
        _progressPlayer.value = controller
    }

    /**
     * Main thread only. Executes what the user asked for while no usable controller existed, exactly once, in this order:
     *  1. the deferred START (the latest accepted selection; older ones were replaced when the newer was accepted). Its physical
     *     install uses the CURRENT logical queue, so it does not re-derive order, bump the generation or notify again;
     *  2. with no deferred start: the dirty-queue reconnect sync, then the held queue jump;
     *  3. navigation, then seek (each stale-validated against its anchor occurrence), then the latest play/pause intent.
     * Transport issued after a start is therefore honoured against the installed queue, not discarded because a start exists.
     */
    private fun drainPendingRequests(controller: MediaController) {
        val drained = deferred.take()
        // Debug tracer drain bracket: the end runs in a `finally`, so its drain flag clears even if a drained command throws.
        // The exception still propagates exactly as before.
        PlaybackStartupTrace.withPendingDrain {
            var startInstalled = false
            var jumpSelected = false
            var seek = drained.seek
            // The recorded occurrence is the authority and is installed only while no structural edit has happened since it was
            // recorded (see resolveDeferredStart); otherwise the start is rejected, never re-aimed by song id.
            val start = drained.start
            val resolvedStart = start?.let {
                resolveDeferredStart(it, drained.startQueueGeneration, queueGeneration, playbackQueue)
            }
            if (start != null && resolvedStart is DeferredStartResolution.Reject) {
                Log.w(TAG, "Dropping deferred start selection=${start.selectionId}: ${resolvedStart.reason}")
                PlaybackStartupTrace.abort("deferred_start_unprovable")
                markQueueDirty(QueueDirtyReason.Unknown)
            }
            if (start != null && resolvedStart is DeferredStartResolution.Install) {
                // A seek issued after the start targets the started occurrence. Seeking "in current item" right after a fresh
                // install is not available until the player reports the item seekable (Media3 drops the command), so a seek that
                // is still valid for the started occurrence is folded into the install position instead. With a Next/Previous
                // also pending the occurrence is about to change, so the seek keeps its post-navigation stale validation.
                var startPositionMs = start.startPositionMs
                if (seek != null && drained.navigation == null) {
                    val resolution = PendingTransport.resolveSeek(
                        seek = seek,
                        currentQueueGeneration = queueGeneration,
                        playbackQueue = playbackQueue,
                        currentPlaybackIndex = resolvedStart.playbackIndex,
                    )
                    if (resolution is PendingSeekResolution.Apply) {
                        startPositionMs = resolution.positionMs
                        seek = null
                    }
                }
                // A Pause older than the newest jump/navigation never overrides that selection's Play.
                val startPlay = if (drained.playPredatesSelection) true else drained.playWhenReady ?: true
                installDeferredStart(controller, start, resolvedStart.playbackIndex, play = startPlay, positionMs = startPositionMs)
                startInstalled = true
            } else {
                // A warm reconnect after a shuffle change made while disconnected must push the app-owned order to the player once,
                // BEFORE any pending transport command runs, so navigation/seek resolve against the synchronized queue.
                if (reconnectRequiresQueueSync(playerQueueNeedsSync, supersededByQueueRequest = false)) {
                    synchronizePlayerQueueOnReconnect(controller)
                }
                jumpSelected = drained.queueJump?.let { executePendingQueueJump(controller, it) } ?: false
            }
            // A start's own install already applied its play/pause intent; otherwise the drain applies it last. An older Pause
            // is dropped when the newer selection it predates actually started.
            drainPendingTransportCommands(
                controller = controller,
                navigation = drained.navigation,
                seek = seek,
                playWhenReady = if (startInstalled) null else drained.playWhenReady,
                playPredatesSelection = drained.playPredatesSelection,
                selectionAlreadyStarted = jumpSelected,
            )
        }
    }

    /**
     * Installs the accepted selection of [command] into the freshly connected [controller]. The queue/order/occurrence/source are
     * already the logical truth, so this only performs the physical install for the already-proven recorded occurrence [index].
     */
    private fun installDeferredStart(controller: MediaController, command: DeferredStart, index: Int, play: Boolean, positionMs: Long) {
        // [index] is the recorded occurrence, proven unchanged by resolveDeferredStart (no structural edit since it was recorded).
        // A whole-queue install of the logical queue leaves nothing for the reconciler to repair.
        playerQueueNeedsSync = false
        when (command) {
            is DeferredStart.LibraryQueue -> installLibraryStart(controller, index, play, positionMs)
            is DeferredStart.PreserveSearch -> installPreservedSearchStart(controller, index, play, positionMs)
            is DeferredStart.ExternalUri -> installExternalStart(controller, play, positionMs)
        }
    }

    /**
     * An accepted fresh start while no usable controller exists. The caller has already installed the logical queue/order/state.
     * This records that the PHYSICAL install is owed (latest accepted selection wins; older start and transport payloads are
     * dropped by [DeferredPlaybackCommands.acceptStart]) and demands the controller through the shared connection machinery,
     * which never starts a second concurrent attempt.
     */
    private fun deferStart(create: (selectionId: Long) -> DeferredStart) {
        deferred.acceptStart(queueGeneration, create)
        // A start carries an implicit play intent; show it so a following toggle derives the right desired state.
        _nowPlayingState.update { it.copy(isPlaying = true) }
        PlaybackStartupTrace.onControllerParked()
        ensureControllerConnection()
    }

    /** A queue jump made while no controller exists: retargets a deferred start (newer than it) or is held as a jump. */
    private fun deferQueueJump(songId: Long, playbackIndex: Int, positionMs: Long = 0L) {
        when (deferred.captureQueueJump(songId, playbackIndex, queueGeneration, positionMs)) {
            JumpCapture.RetargetedStart -> retargetAcceptedStart(playbackIndex, positionMs)
            JumpCapture.DeferredJump -> Unit
        }
        ensureControllerConnection()
    }

    /** The deferred start now targets another occurrence of the same logical queue; mirror what accepting a start there does. */
    private fun retargetAcceptedStart(playbackIndex: Int, positionMs: Long = 0L) {
        val song = playbackQueue.getOrNull(playbackIndex) ?: return
        lastKnownPositionMs = -1L
        pendingPreserveSearchPlan = null // its recorded index belongs to the previous target
        statsTracker.onSongSelected(song)
        PlaybackStartupTrace.expectStart(song.id)
        _nowPlayingState.update {
            it.copy(
                song = song,
                currentIndex = playbackIndex,
                positionMs = positionMs,
                durationMs = song.duration.coerceAtLeast(0L),
                bufferedPositionMs = 0L,
                isSeekable = false,
            )
        }
    }

    /**
     * Main thread only. Reconciles the app-owned queue with the live player once on a warm reconnect after the queue (or
     * shuffle) changed while the controller was unavailable.
     *
     * Large-queue hardening: this no longer re-pushes the whole queue. The reconciler validates the physical timeline
     * first, repairs a bounded window around the current occurrence synchronously (so a pending Next/seek that follows
     * resolves against a trusted window) and continues the rest in bounded background chunks. The current occurrence
     * (`_nowPlayingState.currentIndex` is a position, so it is duplicate-safe and never jumps to an older duplicate) is
     * never touched, so the song is not restarted and the play/pause state and position are preserved. Only when the current
     * occurrence cannot be trusted does it fall back to the labelled full re-push, which preserves the live position as
     * before. If the occurrence cannot be resolved at all the queue stays dirty (the non-destructive fallback).
     */
    private fun synchronizePlayerQueueOnReconnect(controller: MediaController) {
        if (isBounded()) {
            // P5-C: the old controller's acknowledgements say nothing about this player. Re-anchor a bounded window around the
            // exact logical current (never the full queue); the position is kept.
            reanchorBoundedAfterReconnect(controller)
            return
        }
        val occurrence = _nowPlayingState.value.currentIndex
            .takeIf { it in playbackQueue.indices }
            ?: currentPlaybackIndex()?.takeIf { it in playbackQueue.indices }
            ?: return
        when (reconcilePhysicalQueue(controller, budget = 0, logicalCurrent = occurrence)) {
            QueueReconcileOutcome.Completed -> Unit
            is QueueReconcileOutcome.InProgress -> kickQueueRepair()
            is QueueReconcileOutcome.Unsafe -> {
                val positionMs = controller.currentPosition.coerceAtLeast(0L)
                syncPlayerQueueAt(
                    controller = controller,
                    playbackIndex = occurrence,
                    positionMs = positionMs,
                    playWhenReady = controller.isPlaying,
                    reason = FullQueueSyncReason.ReconnectCurrentUnprovable,
                )
            }
        }
        syncNowPlayingState()
    }

    /**
     * Main thread only. Applies transport intents captured while disconnected, in an order that
     * keeps them internally consistent and stale-safe. They survive a deferred start (they were issued after it and the
     * start's install has already run); anything issued BEFORE a newer start was dropped when that start was accepted.
     *
     *  1. Navigation — executed only if the occurrence it was issued from is still the resolved current one
     *     ([resolvePendingNavigation]); otherwise it is a stale skip and is discarded, never re-aimed by song id.
     *  2. Seek — applied only if its bound track is still current AFTER any navigation (so a seek
     *     issued against the old track is dropped rather than applied to a different one).
     *  3. Play/Pause — the latest desired state.
     */
    private fun drainPendingTransportCommands(
        controller: MediaController,
        navigation: PendingNavigation?,
        seek: PendingSeek?,
        playWhenReady: Boolean?,
        playPredatesSelection: Boolean = false,
        selectionAlreadyStarted: Boolean = false,
    ) {
        if (navigation == null && seek == null && playWhenReady == null) {
            syncNowPlayingState()
            return
        }
        var selectionStarted = selectionAlreadyStarted
        if (navigation != null) {
            val intent = resolvePendingNavigation(navigation, playbackQueue, currentPlaybackIndex())
            if (intent != null) {
                if (navigate(controller, intent)) selectionStarted = true
            } else {
                Log.w(
                    TAG,
                    "Discarding stale pending ${navigation.intent} from index=${navigation.anchorPlaybackIndex} " +
                        "songId=${navigation.anchorSongId}",
                )
            }
        }
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
        // An older intent never overrides the explicit selection that was captured after it and actually started.
        if (!(playPredatesSelection && selectionStarted)) {
            playWhenReady?.let { if (it) controller.play() else controller.pause() }
        }
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
                // CF-2F4: only an authoritative disconnect notifies, and BEFORE the controller reference is cleared so an
                // audible crossfade cleanup can still reach the live primary. Stale disconnects never get here.
                controllerDisconnectedListeners.notifyControllerDisconnected()
                pendingDeletionContinuation = null // its acknowledgement can only have been requested through this controller
                mediaController = null
                _progressPlayer.value = null
                controllerConnectionState = ControllerConnectionDecision.onDisconnected()
                Log.w(TAG, "MediaController disconnected; will reconnect on next demand")
            }
        }
    }

    fun playSong(song: Song) {
        // CF-2H3F: explicit start intent cancels an owned crossfade first, then the non-notifying start primitive runs.
        notifyQueueReplacement()
        startQueueWithoutNotification(queue = listOf(song), startIndex = 0)
    }

    /**
     * Non-notifying whole-queue start. Used by the public play commands (after they notify) and by the Play Next /
     * Add to Queue fallbacks that own their own cancellation seam, so one user command never cancels twice.
     */
    private fun startQueueWithoutNotification(
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

    fun playSearchResultPreservingQueue(song: Song) {
        // CF-2H3F: notified first; playPreservedSearchPlan (also used by the pending drain) never notifies.
        notifyQueueReplacement()
        val controller = mediaController
        PlaybackStartupTrace.begin("search_preserve", playbackQueue.size, controllerConnected = controller != null)
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
        supersedeAutomaticResumeByUser()
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
        PlaybackStartupTrace.mark("logical_queue_built", playbackQueue.size)
        PlaybackStartupTrace.expectStart(startSong.id)

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
        PlaybackStartupTrace.mark("ui_state_published")

        val controller = mediaController
        if (controller == null) {
            deferStart { id -> DeferredStart.PreserveSearch(id, playbackStartIndex, startSong.id) }
            return
        }
        deferred.clear() // a connected accept supersedes anything stale (nothing should exist while connected)
        installPreservedSearchStart(controller, playbackStartIndex, play = true)
    }

    /** Physical install of an accepted preserve-search selection (the connected path and the deferred drain share it). */
    private fun installPreservedSearchStart(controller: MediaController, playbackStartIndex: Int, play: Boolean, positionMs: Long = 0L) {
        applyPhysicalTransportModes(controller)
        controller.installPlaybackQueue(
            operation = "preserve_search",
            startIndex = playbackStartIndex,
            positionMs = positionMs,
        )
        controller.prepare()
        PlaybackStartupTrace.onPrepareIssued()
        if (play) {
            controller.play()
            PlaybackStartupTrace.mark("play_issued")
        } else {
            controller.pause()
        }
        saveSessionAsync()
    }

    fun playExternalUri(uri: Uri, displayName: String? = null) {
        // CF-2H3F: notified first, before any state change; the pending drain calls the non-notifying variant.
        notifyQueueReplacement()
        playExternalUriWithoutNotification(uri, displayName)
    }

    private fun playExternalUriWithoutNotification(uri: Uri, displayName: String? = null) {
        val song = uri.toExternalSong(displayName)

        isExternalPlayback = true
        // Fresh queue supersedes any in-progress bad-media recovery episode.
        bumpQueueGeneration()
        // Explicit user play supersedes any pending automatic Bluetooth resume.
        supersedeAutomaticResumeByUser()
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
            deferStart { id -> DeferredStart.ExternalUri(id, song.id, uri.toString(), displayName) }
            return
        }
        deferred.clear() // a connected accept supersedes anything stale (nothing should exist while connected)
        installExternalStart(controller, play = true)
    }

    /** Physical install of an accepted external-URI selection (the connected path and the deferred drain share it). */
    private fun installExternalStart(controller: MediaController, play: Boolean, positionMs: Long = 0L) {
        applyPhysicalTransportModes(controller)
        controller.setMeasuredMediaItems(
            operation = "external_uri",
            songs = playbackQueue,
            startIndex = 0,
            positionMs = positionMs,
        )
        controller.prepare()
        syncNowPlayingState(notifyStats = false)
        if (play) controller.play() else controller.pause()
    }

    fun playFromQueue(
        queue: List<Song>,
        startSong: Song,
        source: PlaybackQueueSource = PlaybackQueueSource.Other,
    ) {
        // CF-2H3F: notified before resolution so an absent or ambiguous start song still cancels.
        notifyQueueReplacement()
        val start = resolveQueueStartBySong(queue, startSong)
        if (start == null) {
            PlaybackStartupTrace.rejected("play_from_queue", queue.size, "absent_or_ambiguous_start")
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
        // CF-2H3F: notified before any resolution or replacement.
        notifyQueueReplacement()
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
        PlaybackStartupTrace.begin("play_from_queue", queue.size, controllerConnected = mediaController != null)
        val start = resolveQueueStart(queue, startIndex) ?: run {
            PlaybackStartupTrace.abort("invalid_start_index")
            return
        }
        val normalizedQueue = start.queue
        val originalStartIndex = start.startIndex
        val startSong = start.startSong
        PlaybackStartupTrace.expectStart(startSong.id)
        isExternalPlayback = false
        // Fresh queue supersedes any in-progress bad-media recovery episode.
        bumpQueueGeneration()
        // Explicit user play supersedes any pending automatic Bluetooth resume.
        supersedeAutomaticResumeByUser()

        libraryQueue = normalizedQueue
        if (preservePlaybackOrder) {
            playbackOrder = normalizedQueue.indices.toList()
            playbackQueue = normalizedQueue
        } else {
            rebuildPlaybackQueue(currentQueueIndex = originalStartIndex)
        }
        playerQueueNeedsSync = false
        PlaybackStartupTrace.mark("logical_queue_built")
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
        PlaybackStartupTrace.mark("ui_state_published")

        val controller = mediaController
        if (controller == null) {
            // The queue, the (possibly shuffled) order, the occurrence and the source are now the logical truth; only the
            // physical install is owed. Latest accepted selection wins, and the controller is demanded.
            deferStart { id -> DeferredStart.LibraryQueue(id, playbackStartIndex, startSong.id) }
            return
        }
        deferred.clear() // a connected accept supersedes anything stale (nothing should exist while connected)
        installLibraryStart(controller, playbackStartIndex, play = true)
    }

    /** Physical install of an accepted library-queue selection (the connected path and the deferred drain share it). */
    private fun installLibraryStart(controller: MediaController, playbackStartIndex: Int, play: Boolean, positionMs: Long = 0L) {
        applyPhysicalTransportModes(controller)
        // ExoPlayer is loaded with playbackQueue so auto-transitions follow shuffle order.
        controller.installPlaybackQueue(
            operation = "play_from_queue",
            startIndex = playbackStartIndex,
            positionMs = positionMs,
        )
        controller.prepare()
        PlaybackStartupTrace.onPrepareIssued()
        syncNowPlayingState()
        if (play) {
            controller.play()
            PlaybackStartupTrace.mark("play_issued")
        } else {
            controller.pause()
        }
        saveSessionAsync()
    }

    fun playFromQueueShuffled(
        queue: List<Song>,
        source: PlaybackQueueSource = PlaybackQueueSource.Other,
    ) {
        // CF-2H3F: notified BEFORE any logical mutation (shuffleEnabled) and without double-notifying via playFromQueue.
        notifyQueueReplacement()
        val normalizedQueue = queue.ifEmpty { return }
        shuffleEnabled = true
        startQueueWithoutNotification(
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
            startQueueWithoutNotification(queue = listOf(song), startIndex = 0)
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
        val generationBefore = queueGeneration
        bumpQueueGeneration()
        val newLibraryIndex = libraryQueue.size
        libraryQueue = libraryQueue + song
        val insertPlaybackIndex = currentPlaybackIndex + 1
        val newOrder = playbackOrder.toMutableList()
        newOrder.add(insertPlaybackIndex, newLibraryIndex)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
        rebaseDeferredStart(generationBefore, OccurrenceMap.insert(insertPlaybackIndex, 1))

        // addMediaItem at playback position — no setMediaItems/prepare/play needed; otherwise a dirty repair obligation.
        if (isBounded()) {
            applyBoundedQueueMutation("play_next", currentPlaybackIndex, OccurrenceMap.insert(insertPlaybackIndex, 1))
        } else {
            applyPhysicalEdit { it.addMediaItem(insertPlaybackIndex, song.toCachedMediaItem()) }
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
                startQueueWithoutNotification(queue = plan.queue, startIndex = 0)
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
            QueueAddPlan.StartNewQueue -> startQueueWithoutNotification(queue = songs, startIndex = 0)
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
            QueueAddPlan.StartNewQueue -> startQueueWithoutNotification(queue = listOf(song), startIndex = 0)
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
        val generationBefore = queueGeneration
        bumpQueueGeneration()
        libraryQueue = result.libraryQueue
        playbackOrder = result.playbackOrder
        playbackQueue = result.playbackQueue
        rebaseDeferredStart(generationBefore, OccurrenceMap.insert(currentPlaybackIndex + 1, songs.size))

        if (isBounded()) {
            // The whole batch enters the logical queue; the adapter submits only the physically relevant span after the current.
            applyBoundedQueueMutation("play_all_next", currentPlaybackIndex, OccurrenceMap.insert(currentPlaybackIndex + 1, songs.size))
            _nowPlayingState.update {
                it.copy(queue = playbackQueue, currentIndex = currentPlaybackIndex, shuffleEnabled = shuffleEnabled, repeatMode = repeatMode)
            }
            saveSessionAsync()
            return
        }
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
            BatchQueuePlayerSyncAction.ReplaceFutureSpan -> {
                // Cost scales with the changed span (an insertion is just the batch), never with the queue size.
                val applied = controller != null && runCatching {
                    controller.replaceMeasuredMediaItems(
                        operation = "play_all_next_span",
                        fromIndex = syncPlan.replaceFromIndex,
                        toIndexExclusive = syncPlan.replaceToIndexExclusive,
                        songs = syncPlan.songs,
                    )
                }.isSuccess
                if (!applied) markQueueDirty(QueueDirtyReason.BatchMutationFailed)
            }
            BatchQueuePlayerSyncAction.MarkDirty -> {
                markQueueDirty(syncPlan.dirtyReason ?: QueueDirtyReason.Unknown)
                if (controller == null) ensureControllerConnection() // the obligation is repaired on reconnect
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
        val oldCurrentForBounded = if (isBounded()) currentPlaybackIndex() else null // resolved BEFORE the generation moves
        val generationBefore = queueGeneration
        bumpQueueGeneration()
        val result = QueueMutation.appendAll(
            libraryQueue = libraryQueue,
            playbackOrder = playbackOrder,
            songs = songs,
        )
        libraryQueue = result.libraryQueue
        playbackOrder = result.playbackOrder
        playbackQueue = result.playbackQueue
        rebaseDeferredStart(generationBefore, OccurrenceMap.identity()) // an append moves no existing entry

        if (isBounded()) {
            // An append moves no existing entry. Outside the window it is logical-only; at the genuine end (or a repeat-all ring)
            // the adapter re-derives the window, so appended entries never land before the current item.
            applyBoundedQueueMutation("append", oldCurrentForBounded, OccurrenceMap.identity())
        } else {
            applyPhysicalEdit {
                it.addMeasuredMediaItems(
                    operation = "append_fallback",
                    songs = songs,
                )
            }
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

    fun jumpToQueueItem(playbackIndex: Int) = seekToQueueItem(playbackIndex, 0L)

    /**
     * The one queue-selection entry for an index AND a position (an external index-bearing seek reaches it through the logical
     * command port). [positionMs] is where to land in the target item; the default position is 0. In a bounded window an in-window
     * target is a mapped physical seek, an out-of-window target a bounded re-anchor, both at [positionMs]; the same latest-wins
     * navigation authority as every other selection applies.
     */
    fun seekToQueueItem(playbackIndex: Int, positionMs: Long) {
        if (playbackIndex !in playbackQueue.indices) return
        // Explicit user selection supersedes automatic bad-media recovery.
        resetBadMediaRecoveryEpisode()
        // ...and any pending automatic resume / suspended cold PLAY aimed at the previously selected occurrence.
        supersedeAutomaticResumeByUser()
        val controller = mediaController
        if (controller == null) {
            // Defer through the same bounded, stale-safe machinery as jumpToSongById: bind to the
            // song at this occurrence so executePendingQueueJump re-resolves it on reconnect
            // (correcting a mutated index, never jumping to a different song). Latest jump wins.
            deferQueueJump(songId = playbackQueue[playbackIndex].id, playbackIndex = playbackIndex, positionMs = positionMs)
            return
        }
        deferred.clear()
        // playbackIndex is directly the ExoPlayer media item index since ExoPlayer holds playbackQueue.
        selectOccurrence(controller, playbackIndex, positionMs)
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
                // jumpToQueueItem clears any older deferred payload: a newer immediate jump supersedes it.
                jumpToQueueItem(playbackIndex)
                true
            }
            QueueJumpAction.DeferAndReconnect -> {
                // Latest explicit tap wins: it retargets a deferred start, else replaces the single held jump, and
                // demands the controller (the drain runs on reconnect).
                deferQueueJump(songId = songId, playbackIndex = playbackIndex)
                if (BuildConfig.DEBUG) {
                    Log.d(RESUME_TAG, "jumpToSongById deferred: songId=$songId index=$playbackIndex; reconnecting")
                }
                true
            }
        }
    }

    // Main thread only. Executes a deferred queue jump after the controller reconnects, correcting
    // a stale index if the queue mutated while disconnected (never seeks a different song).
    // Returns true when a different occurrence was selected (so its Play intent outranks an older deferred Pause).
    private fun executePendingQueueJump(controller: MediaController, request: PendingQueueJump): Boolean {
        return when (
            val resolution = resolvePendingQueueJump(
                queue = playbackQueue,
                currentPlaybackIndex = currentPlaybackIndex(),
                songId = request.songId,
                resolvedPlaybackIndex = request.resolvedPlaybackIndex,
            )
        ) {
            is PendingQueueJumpResolution.Seek -> selectOccurrence(controller, resolution.playbackIndex, request.positionMs)
            PendingQueueJumpResolution.Discard -> {
                Log.w(TAG, "Discarding stale pending queue jump songId=${request.songId}")
                syncNowPlayingState()
                false
            }
        }
    }

    /**
     * THE explicit-selection entry of the shared navigation path (Next, Previous-to-another, queue row, deferred drain, external
     * index seek). Selecting an occurrence OTHER than the current one means Play, even from a paused state; the same occurrence is a
     * restart/seek and keeps whatever play state it had. Automatic callers (recovery, window-end continuation) pass their own
     * explicit [forcePlay] straight to [seekToPlaybackIndex] and never come through here. Returns true when a different occurrence
     * was selected.
     */
    private fun selectOccurrence(controller: MediaController, target: Int, positionMs: Long = 0L): Boolean {
        val current = currentPlaybackIndex() ?: controller.currentMediaItemIndex
        val differs = target != current
        seekToPlaybackIndex(controller, target, forcePlay = if (differs) true else null, positionMs = positionMs)
        return differs
    }

    fun removeFromQueue(playbackIndex: Int) {
        // CF-2H3D: explicit removal intent cancels an owned crossfade first, even if validation then no-ops (e.g. current).
        explicitQueueRemovalListeners.notifyExplicitQueueRemoval()
        // The boundary is the authoritative current occurrence (Media3 can advance before Now Playing syncs). Unprovable current:
        // nothing that might be the playing entry is removed.
        val currentPlaybackIndex = currentPlaybackIndex() ?: return
        if (playbackIndex == currentPlaybackIndex) return
        if (playbackIndex !in playbackQueue.indices) return
        val removedLibraryIndex = playbackOrder.getOrNull(playbackIndex) ?: return
        if (removedLibraryIndex !in libraryQueue.indices) return

        val generationBefore = queueGeneration
        bumpQueueGeneration()
        // Remove from library queue and decrement any playback order entries above the gap.
        libraryQueue = libraryQueue.toMutableList().also { it.removeAt(removedLibraryIndex) }
        playbackOrder = playbackOrder
            .filterIndexed { index, _ -> index != playbackIndex }
            .map { index -> if (index > removedLibraryIndex) index - 1 else index }
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
        rebaseDeferredStart(generationBefore, OccurrenceMap.remove(playbackIndex))

        // playbackIndex is the ExoPlayer media item index.
        if (isBounded()) {
            applyBoundedQueueMutation("remove_from_queue", currentPlaybackIndex, OccurrenceMap.remove(playbackIndex))
        } else {
            applyPhysicalEdit { it.removeMediaItem(playbackIndex) }
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

        val generationBefore = queueGeneration
        bumpQueueGeneration()
        libraryQueue = mutation.libraryQueue
        playbackOrder = mutation.playbackOrder
        playbackQueue = mutation.playbackQueue
        // Everything before (clearEarlier) or after (clearUpNext) the current entry is gone; the current entry itself survives.
        val clearMap: (Int) -> Int? = { old ->
            when {
                mutation.mediaRemoveFrom == 0 -> if (old < mutation.mediaRemoveToExclusive) null else old - mutation.mediaRemoveToExclusive
                else -> if (old >= mutation.mediaRemoveFrom) null else old
            }
        }
        rebaseDeferredStart(generationBefore, clearMap)

        // Media3's playlist mirrors playbackOrder, so one range removal keeps it aligned. Removing
        // items before/after the current one never interrupts the current item or its position.
        if (isBounded()) {
            applyBoundedQueueMutation("bulk_clear", currentPlaybackIndex, clearMap)
        } else {
            applyPhysicalEdit { it.removeMediaItems(mutation.mediaRemoveFrom, mutation.mediaRemoveToExclusive) }
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

        val generationBefore = queueGeneration
        bumpQueueGeneration()
        libraryQueue = plan.libraryQueue
        playbackOrder = plan.playbackOrder
        playbackQueue = plan.playbackQueue
        rebaseDeferredStart(generationBefore, OccurrenceMap.removeAll(plan.removedPlaybackPositions))

        // Aligned Media3 playlist mirrors the old playback order: remove the old positions, descending.
        // A dirty playlist is not authoritative and is left for the reconciler; no controller is a repair obligation.
        if (isBounded()) {
            applyBoundedQueueMutation("song_deleted", currentIdx, OccurrenceMap.removeAll(plan.removedPlaybackPositions))
        } else {
            applyPhysicalEdit { controller -> plan.removedPlaybackPositions.forEach { controller.removeMediaItem(it) } }
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
        // Bounded: the exact occurrences that disappear, in the OLD queue's coordinates (every occurrence of the deleted song).
        val boundedDeletion = if (isBounded()) BoundedDeletion(currentIdx, playbackQueue.indices.filter { playbackQueue[it].id == deletedSongId }) else null
        val controller = mediaController
        when {
            plan is QueueMutation.CurrentDeletionPlan.NoOp -> return
            plan is QueueMutation.CurrentDeletionPlan.Continue && controller != null ->
                applyCurrentDeletionContinuation(controller, plan, boundedDeletion)
            plan is QueueMutation.CurrentDeletionPlan.Continue -> applyCurrentDeletionWithoutController(plan, boundedDeletion)
            else -> {
                // No survivor to continue with - clear queue and stop. Anything deferred referred to the removed queue.
                bumpQueueGeneration()
                deferred.clear()
                // An empty queue has no window. The adapter is reset and the mode left BEFORE the physical clear: the clear fires its
                // listener callbacks synchronously, and bounded upkeep must not see the old queue and re-install it.
                if (isBounded()) { physicalWindowAdapter.reset(); installedQueueMode = QueueMode.Full; notifyLogicalPresentationChanged() }
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

    /**
     * The current song was deleted while no controller exists but survivors remain: the surviving queue is NOT cleared. The
     * planned survivor becomes the selected occurrence as a deferred start that keeps the user's play/pause intent, so the
     * reconnect installs the surviving queue at that exact occurrence from position 0 (never a stale physical position).
     */
    private class BoundedDeletion(val oldCurrent: Int, val removedPositions: List<Int>)

    /**
     * P5-D: the continuation chosen by a bounded current-song deletion, bound to the logical generation it was made in and to the
     * exact occurrence (position, not song id) it must land on. Any newer queue change bumps the generation, a second deletion
     * replaces it, and a controller disconnect clears it, so a late acknowledgement can never act for a superseded continuation.
     */
    private class DeletionContinuation(val generation: Long, val occurrence: Int, val songId: Long)

    private var pendingDeletionContinuation: DeletionContinuation? = null

    /**
     * Runs when the replacement window of [binding] was physically verified. It issues no Play/Pause: the physical playWhenReady
     * already is the latest authorized intent (a Pause during verification stays a Pause). The only command is a prepare when the
     * player was idle, and only if this is still the current continuation, generation and occurrence.
     */
    private fun completeDeletionContinuation(binding: DeletionContinuation) {
        if (pendingDeletionContinuation !== binding) return // superseded (another deletion) or cleared (disconnect)
        pendingDeletionContinuation = null
        if (binding.generation != queueGeneration) return // a newer queue change owns the queue now
        if (physicalWindowAdapter.trackedLogical != binding.occurrence) return // a newer navigation owns the current occurrence
        val controller = mediaController ?: return
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
    }

    private fun applyCurrentDeletionWithoutController(plan: QueueMutation.CurrentDeletionPlan.Continue, bounded: BoundedDeletion? = null) {
        val wasPlaying = _nowPlayingState.value.isPlaying
        bumpQueueGeneration()
        libraryQueue = plan.libraryQueue
        playbackOrder = plan.playbackOrder
        playbackQueue = plan.playbackQueue
        lastKnownPositionMs = -1L
        val newSong = plan.currentSong
        deferred.acceptStart(queueGeneration) { id -> DeferredStart.LibraryQueue(id, plan.currentPlaybackIndex, newSong.id) }
        deferred.capturePlayWhenReady(wasPlaying)
        markQueueDirty(QueueDirtyReason.ControllerUnavailable)
        if (bounded != null) {
            // The window is unprovable without a player: drop it and owe a bounded re-install around the continuation.
            applyBoundedQueueMutation(
                "current_song_deleted_offline", bounded.oldCurrent, OccurrenceMap.removeAll(bounded.removedPositions),
                continuation = plan.currentPlaybackIndex,
            )
        }
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
        saveSessionAsync()
        ensureControllerConnection()
    }

    private fun applyCurrentDeletionContinuation(
        controller: MediaController,
        plan: QueueMutation.CurrentDeletionPlan.Continue,
        bounded: BoundedDeletion? = null,
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
        if (bounded != null) {
            // Bounded: every occurrence of the deleted song leaves the logical queue; the continuation chosen by the deletion
            // policy is installed as a bounded window (never the Full-queue push). The install happens only after the physical
            // baseline answers, so NO playback command is issued here: the physical player still holds the OLD window, and a
            // prepare()/play() now would act on it. The continuation is bound to its generation and exact occurrence; when (and only
            // when) the replacement window is verified and the binding is still current, [completeDeletionContinuation] runs.
            // The latest intent is the physical playWhenReady itself - every pause/play, from any controller, writes it - so a
            // Pause issued while verification is pending is simply kept: nothing replays a captured state.
            val binding = DeletionContinuation(queueGeneration, plan.currentPlaybackIndex, plan.currentSong.id)
            pendingDeletionContinuation = binding
            applyBoundedQueueMutation(
                "current_song_deleted", bounded.oldCurrent, OccurrenceMap.removeAll(bounded.removedPositions),
                continuation = plan.currentPlaybackIndex,
                onVerified = { completeDeletionContinuation(binding) },
            )
        } else {
            syncPlayerQueueAt(
                controller, plan.currentPlaybackIndex, positionMs = 0L, playWhenReady = wasPlaying,
                reason = FullQueueSyncReason.CurrentSongDeleted,
            )
        }

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
        val currentPlaybackIndex = currentPlaybackIndex() ?: return // unprovable current: reorder nothing
        if (playbackIndex <= currentPlaybackIndex || playbackIndex <= 0) return
        swapPlaybackItems(playbackIndex, playbackIndex - 1, currentPlaybackIndex)
    }

    fun moveQueueItemDown(playbackIndex: Int) {
        // CF-2H3C: explicit reorder intent cancels an owned crossfade first, even if validation then no-ops.
        explicitQueueReorderListeners.notifyExplicitQueueReorder()
        val currentPlaybackIndex = currentPlaybackIndex() ?: return // unprovable current: reorder nothing
        if (playbackIndex <= currentPlaybackIndex || playbackIndex >= playbackQueue.size - 1) return
        swapPlaybackItems(playbackIndex, playbackIndex + 1, currentPlaybackIndex)
    }

    fun moveQueueItemTo(fromPlaybackIndex: Int, toPlaybackIndex: Int) {
        // CF-2H3C: explicit reorder intent cancels an owned crossfade first, even if validation then no-ops.
        explicitQueueReorderListeners.notifyExplicitQueueReorder()
        val currentPlaybackIndex = currentPlaybackIndex() ?: return // unprovable current: reorder nothing
        if (fromPlaybackIndex <= currentPlaybackIndex || toPlaybackIndex <= currentPlaybackIndex) return
        if (fromPlaybackIndex == toPlaybackIndex) return
        if (fromPlaybackIndex !in playbackOrder.indices || toPlaybackIndex !in playbackOrder.indices) return

        val generationBefore = queueGeneration
        bumpQueueGeneration()
        val newOrder = playbackOrder.toMutableList()
        val item = newOrder.removeAt(fromPlaybackIndex)
        newOrder.add(toPlaybackIndex, item)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
        rebaseDeferredStart(generationBefore, OccurrenceMap.move(fromPlaybackIndex, toPlaybackIndex))

        if (isBounded()) {
            applyBoundedQueueMutation("move_queue_item", currentPlaybackIndex, OccurrenceMap.move(fromPlaybackIndex, toPlaybackIndex))
        } else {
            applyPhysicalEdit { it.moveMediaItem(fromPlaybackIndex, toPlaybackIndex) }
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
        val currentPlaybackIndex = currentPlaybackIndex() ?: return // unprovable current: reorder nothing
        val immediateNextIndex = currentPlaybackIndex + 1
        if (playbackIndex <= currentPlaybackIndex) return
        if (playbackIndex == immediateNextIndex) return
        if (playbackIndex !in playbackOrder.indices) return

        val generationBefore = queueGeneration
        bumpQueueGeneration()
        // Lift the entry out of playbackOrder and re-insert right after current.
        val newOrder = playbackOrder.toMutableList()
        val libraryIndex = newOrder.removeAt(playbackIndex)
        newOrder.add(immediateNextIndex, libraryIndex)
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
        rebaseDeferredStart(generationBefore, OccurrenceMap.move(playbackIndex, immediateNextIndex))

        // Move the item in ExoPlayer's playlist using playback indices.
        if (isBounded()) {
            applyBoundedQueueMutation("move_to_play_next", currentPlaybackIndex, OccurrenceMap.move(playbackIndex, immediateNextIndex))
        } else {
            applyPhysicalEdit { it.moveMediaItem(playbackIndex, immediateNextIndex) }
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

    // Bounded explicit selection from a paused state: the epoch of the selection whose Play waits for its re-anchored window to be
    // verified (NO_ARMED_PLAY = none). Every newer selection and every explicit Play/Pause advances/clears it, so latest wins.
    private var selectionPlayEpoch = 0L
    private var armedPlayEpoch = NO_ARMED_PLAY

    /** True while an explicit selection's Play waits for its bounded window to be verified (the session wrapper must not pre-empt it). */
    internal fun isSelectionPlayArmed(): Boolean = armedPlayEpoch != NO_ARMED_PLAY

    private fun retireArmedSelectionPlay() {
        selectionPlayEpoch++
        armedPlayEpoch = NO_ARMED_PLAY
    }

    fun togglePlayPause() {
        // Explicit user transport intent outranks any pending automatic Bluetooth resume.
        supersedeAutomaticResumeByUser()
        val controller = mediaController
        if (controller == null) {
            // No controller: derive the DESIRED play/pause state now (STATE_INTENT) and defer it —
            // a raw toggle replayed later could be stale. Only meaningful when a song is loaded;
            // otherwise there is nothing to play, so this is a genuine no-op. Latest intent wins
            // (a later PAUSE overwrites an earlier PLAY). Superseded by any queue-replacing request.
            val current = _nowPlayingState.value
            if (current.song == null) return
            val desiredPlay = !deferred.intendedPlayWhenReady(current.isPlaying)
            deferred.capturePlayWhenReady(desiredPlay)
            _nowPlayingState.update { it.copy(isPlaying = desiredPlay) } // optimistic; corrected on sync
            ensureControllerConnection()
            return
        }
        if (DEBUG_STATS) Log.d(TAG, "[togglePlayPause] before: controller.isPlaying=${controller.isPlaying}")
        // A Play armed behind an unverified bounded window is the state the user sees as "about to play": a tap is a Pause.
        val armed = armedPlayEpoch != NO_ARMED_PLAY
        retireArmedSelectionPlay()
        if (controller.isPlaying || armed) controller.pause() else controller.play()
        syncNowPlayingState()
        if (DEBUG_STATS) Log.d(TAG, "[togglePlayPause] after syncNPS: nowPlaying.isPlaying=${_nowPlayingState.value.isPlaying}")
    }

    /**
     * Called by PlaybackService when a non-Wavdrop controller (notification, media key, lock
     * screen, widget, external) issues PLAY_PAUSE. Explicit external transport outranks any
     * pending automatic Bluetooth resume.
     */
    fun onExplicitExternalTransport() {
        retireArmedSelectionPlay() // an external Play/Pause is newer than a Play still armed behind a window verification
        // Automatic resumes only: the session player itself decides the cold-PLAY authority per command (a PAUSE ends the
        // lineage; a PLAY begins/joins one), so a repeated PLAY must not end the lineage it is continuing.
        supersedeAutomaticResumeByUser(includingColdPlay = false)
    }

    /**
     * An external PLAY ([play] = true) or PAUSE (false) reached the session player. With no usable controller, a deferred
     * start / play-pause intent would otherwise replay on reconnect and undo this newer command, so the newest desired state
     * overwrites it (same latest-wins slot the UI toggle uses). Nothing is created when nothing is deferred.
     */
    fun onExplicitExternalPlayIntent(play: Boolean) {
        if (mediaController != null) return
        if (deferred.start == null && deferred.playWhenReady == null) return
        deferred.capturePlayWhenReady(play)
    }

    /**
     * Sets, replaces or clears the sleep timer. A duration option starts a countdown ([finishCurrentTrack] is the independent
     * modifier: at expiry it arms the exact then-current occurrence as the terminal one instead of pausing immediately).
     * [SleepTimerOption.END_OF_CURRENT_SONG] arms the exact current occurrence now. The state is in-memory only.
     */
    fun setSleepTimer(option: SleepTimerOption, finishCurrentTrack: Boolean = false) {
        cancelSleepCountdown()
        val previous = _sleepTimerState.value
        when (option) {
            SleepTimerOption.OFF -> applySleepTransition(SleepTimerPolicy.off(previous))
            SleepTimerOption.END_OF_CURRENT_SONG ->
                applySleepTransition(SleepTimerPolicy.startStandalone(System.currentTimeMillis(), currentSleepOccurrence()))
            else -> startSleepCountdown(previous, option, customDurationMs = null, finishCurrentTrack = finishCurrentTrack)
        }
    }

    fun setCustomSleepTimer(durationMs: Long, finishCurrentTrack: Boolean = false) {
        cancelSleepCountdown()
        startSleepCountdown(_sleepTimerState.value, SleepTimerOption.OFF, customDurationMs = durationMs, finishCurrentTrack = finishCurrentTrack)
    }

    private fun cancelSleepCountdown() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    private fun startSleepCountdown(
        previous: SleepTimerState,
        option: SleepTimerOption,
        customDurationMs: Long?,
        finishCurrentTrack: Boolean,
    ) {
        // A new configuration replaces an armed boundary of the previous timer (its physical hold is released).
        if (previous.isFinishingCurrentTrack) sleepBoundaryListeners.notifyArmed(false)
        val next = SleepTimerPolicy.startDuration(System.currentTimeMillis(), option, customDurationMs, finishCurrentTrack)
        _sleepTimerState.value = next
        val durationMs = next.endsAtMs?.let { it - (next.startedAtMs ?: 0L) } ?: return
        sleepTimerJob = scope.launch {
            delay(durationMs)
            sleepTimerJob = null
            onSleepCountdownExpired()
        }
    }

    fun skipToNext() {
        // CF-2G3: user intent cancels an owned crossfade first, even if navigation is deferred or a no-op. The
        // deferred-drain path (navigate) never notifies, so a pending command cancels exactly once.
        explicitNavigationListeners.notifyExplicitNavigation()
        // ST-1: explicit navigation supersedes an armed sleep boundary (never carried onto the newly selected track).
        applySleepTransition(SleepTimerPolicy.onExplicitNavigation(_sleepTimerState.value))
        // Explicit user navigation is distinct from automatic bad-media recovery: end any episode.
        resetBadMediaRecoveryEpisode()
        supersedeAutomaticResumeByUser() // a different selection than the one an automatic resume was begun for
        val controller = mediaController
        if (controller == null) {
            // Defer a single latest-direction navigation intent (not an accumulated skip count).
            // Recomputed against the live queue on reconnect, so a mutated queue never replays a
            // stale skip. Superseded by any queue-replacing request.
            deferNavigation(NavigationIntent.NEXT)
            return
        }
        navigate(controller, NavigationIntent.NEXT)
    }

    fun skipToPrevious() {
        // CF-2G3: also covers PREVIOUS resolving to restart-current (internal seek, not the public seekTo).
        explicitNavigationListeners.notifyExplicitNavigation()
        applySleepTransition(SleepTimerPolicy.onExplicitNavigation(_sleepTimerState.value))
        // Explicit user navigation is distinct from automatic bad-media recovery: end any episode.
        resetBadMediaRecoveryEpisode()
        supersedeAutomaticResumeByUser()
        val controller = mediaController
        if (controller == null) {
            deferNavigation(NavigationIntent.PREVIOUS)
            return
        }
        navigate(controller, NavigationIntent.PREVIOUS)
    }

    /**
     * Next/Previous while no controller exists: bound to the exact occurrence it was issued from (latest direction wins). An
     * occurrence that cannot be proven is not guessed - the skip is dropped, but the controller is still demanded.
     */
    private fun deferNavigation(intent: NavigationIntent) {
        capturePendingNavigation(intent, playbackQueue, currentPlaybackIndex())?.let(deferred::captureNavigation)
        ensureControllerConnection()
    }

    // Main thread only. Executes a next/previous navigation against the CURRENT queue state.
    // Shared by the immediate path and the deferred-reconnect drain so both recompute identically
    // (never replaying a stale index).
    // Returns true when a different occurrence was selected (and therefore started).
    private fun navigate(controller: MediaController, intent: NavigationIntent): Boolean {
        val currentPlaybackIndex = currentPlaybackIndex() ?: controller.currentMediaItemIndex
        return when (intent) {
            NavigationIntent.NEXT -> {
                val nextPlaybackIndex = QueueNavigator.nextIndex(
                    queueSize = playbackQueue.size,
                    currentIndex = currentPlaybackIndex,
                    repeatMode = repeatMode,
                ) ?: return false
                selectOccurrence(controller, nextPlaybackIndex)
            }
            NavigationIntent.PREVIOUS -> {
                when (val action = QueueNavigator.previousAction(
                    queueSize = playbackQueue.size,
                    currentIndex = currentPlaybackIndex,
                    currentPositionMs = controller.currentPosition,
                    repeatMode = repeatMode,
                    restartThresholdMs = previousButtonBehavior.previousRestartThresholdMs(),
                )) {
                    is PreviousQueueAction.MoveTo -> selectOccurrence(controller, action.index)
                    PreviousQueueAction.RestartCurrent -> {
                        controller.seekTo(0L)
                        syncPosition()
                        saveSessionAsync()
                        false
                    }
                    null -> false
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
        val oldPlaybackOrderForBounded = playbackOrder
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
        val generationBeforeShuffle = queueGeneration
        bumpQueueGeneration()
        shuffleEnabled = newShuffleEnabled
        playbackOrder = toggleModel.playbackOrder
        playbackQueue = toggleModel.playbackQueue
        // The toggle keeps the current ENTRY playing and moves it to toggleModel.currentPlaybackIndex. A pending start selects the
        // current entry, so it follows; any other index is not known here and stays rejected.
        rebaseDeferredStart(generationBeforeShuffle) { old -> if (old == currentIndex) toggleModel.currentPlaybackIndex else null }
        if (isBounded()) {
            // Bounded: the toggle is a logical reorder. The current occurrence keeps playing (the adapter replaces only the spans
            // before/after it inside the window, never restarting it); the exact old->new map comes from the two orders.
            applyBoundedQueueMutation(
                "toggle_shuffle", currentIndex,
                occurrenceMapBetweenOrders(oldPlaybackOrderForBounded, toggleModel.playbackOrder),
                continuation = toggleModel.currentPlaybackIndex,
            )
        } else if (syncPlan is ShufflePhysicalSyncPlan.ReplaceAroundCurrent && controller != null) {
            // Aligned player + controller: reorder the physical playlist around the untouched
            // current item right now, so the next natural boundary is a pure Media3 AUTO
            // transition. The queue stays marked dirty during the live Media3 mutation because
            // playlist callbacks can observe intermediate physical state; it is cleared only after
            // BOTH replacements succeed, and stays dirty (deferred recovery) if either throws.
            if (syncPlan.desiredPrefix.size + syncPlan.desiredSuffix.size > MAX_SINGLE_SPAN_REPLACE_ITEMS) {
                // Large queue: one replace of thousands of items would monopolize a main-thread turn and one IPC message.
                // The reconciler reorders around the untouched current item in bounded chunks, window ahead of it first.
                markQueueDirty(QueueDirtyReason.ShuffleReorderDeferred)
            } else {
                val succeeded = applyShuffleAroundCurrent(controller, syncPlan)
                playerQueueNeedsSync = syncPlan.playerQueueNeedsSync(
                    if (succeeded) ShuffleLiveSyncProgress.Succeeded else ShuffleLiveSyncProgress.Failed,
                )
                if (!succeeded) markQueueDirty(QueueDirtyReason.ShuffleLiveSyncFailed)
            }
        } else if (playerQueueNeedsSync) {
            markQueueDirty(QueueDirtyReason.ShuffleReorderDeferred)
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
        setRepeatModeExplicit(
            when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            },
        )
    }

    /** One explicit repeat change (the cycle, or an external controller's setRepeatMode through the logical command port). */
    private fun setRepeatModeExplicit(target: RepeatMode) {
        // CF-2H1: user intent cancels an owned crossfade BEFORE the repeat topology changes (every explicit command,
        // whether or not the new mode would invalidate the planned target). Also the path of the service's custom
        // CYCLE_REPEAT command, so one user command yields one notification.
        explicitRepeatChangeListeners.notifyExplicitRepeatChange()
        repeatMode = target
        // P5-E: in bounded mode the physical mirror is set through the physical port; the controller's repeat is the logical one.
        if (isBounded()) physicalPort?.repeatMode = physicalRepeatModeFor(repeatMode)
        else mediaController?.repeatMode = physicalRepeatModeFor(repeatMode)
        _nowPlayingState.update { it.copy(repeatMode = repeatMode) }
        saveSessionAsync()
        // Bounded: leaving/entering ALL changes the window geometry (ring wrap); the policy re-plans it from the verified window.
        if (isBounded()) {
            maintainBoundedWindow()
            notifyLogicalPresentationChanged()
        }
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
    private val explicitQueueReplacementListeners = ExplicitQueueReplacementListenerRegistry()
    private val controllerDisconnectedListeners = ControllerDisconnectedListenerRegistry()

    /**
     * CF-2F4: registers (or clears with null) the lifecycle-scoped callback notified when the current authoritative
     * MediaController disconnects. PlaybackService registers it and MUST clear it on teardown; PlayerController knows
     * nothing of crossfade.
     */
    internal fun setControllerDisconnectedListener(listener: (() -> Unit)?) {
        controllerDisconnectedListeners.set(listener)
    }

    /**
     * CF-2H3F: registers (or clears with null) the lifecycle-scoped callback notified on every explicit user playback
     * start that replaces the queue. Not used by session resumption (adoptPlaybackResumption), the pending-request
     * drain, or the Play Next / Add to Queue fallbacks. PlayerController knows nothing of crossfade; the owner must
     * clear it on teardown.
     */
    internal fun setExplicitQueueReplacementListener(listener: (() -> Unit)?) {
        explicitQueueReplacementListeners.set(listener)
    }

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
            val pendingSeek = capturePendingSeek(
                queueGeneration = queueGeneration,
                playbackQueue = playbackQueue,
                currentPlaybackIndex = currentPlaybackIndex(),
                positionMs = clamped,
            ) ?: return // Conservative no-op when occurrence identity cannot be established.
            deferred.captureSeek(pendingSeek)
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

            // A cold PLAY's hydration must not mutate a logical queue that something OTHER than a session hydration moved since that PLAY began (a newer
            // selection or edit): revalidate immediately before the first mutation.
            val owned = kotlin.coroutines.coroutineContext[OwnedHydration.Key]
            if (owned != null && !owned.isQueueUndisturbed()) {
                logHydrationDecision(operation, "owner_stale", controller, HydrationDecision.NEEDS_HYDRATION)
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
            hydrationCount++
            bumpQueueGeneration()
            libraryQueue = mappedQueue
            shuffleEnabled = snapshot.shuffleEnabled
            repeatMode = snapshot.repeatMode
            restorePlaybackQueue(
                currentQueueIndex = startLibraryIndex,
                savedPlaybackOrder = snapshot.playbackOrder,
            )
            playerQueueNeedsSync = false
            PlaybackStartupTrace.begin(operation, playbackQueue.size, controllerConnected = true, playIntended = false)
            PlaybackStartupTrace.expectStart(startSong.id)
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
            PlaybackStartupTrace.mark("ui_state_published")

            runCatching {
                applyPhysicalTransportModes(controller)
                controller.installPlaybackQueue(
                    operation = operation,
                    startIndex = startPlaybackIndex,
                    positionMs = snapshot.positionMs,
                )
                controller.prepare()
                PlaybackStartupTrace.onPrepareIssued()
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

    /**
     * Mirrors a service-owned Media3 resumption without connecting back to the same session, and returns what Media3 must install.
     * Full: the whole queue, exactly as before. Bounded (P5-E): the restored LOGICAL queue is adopted, ONE bounded window around the
     * exact saved occurrence is installed through the physical port at the saved position and verified in-process, and Media3 is
     * handed that window with the PHYSICAL start index. Once the façade presents the logical queue Media3's own install is skipped
     * (the change-items commands are unavailable), so there is no second install and no whole-queue install ever.
     */
    internal fun adoptPlaybackResumption(plan: PlaybackResumptionPlan): androidx.media3.session.MediaSession.MediaItemsWithStartPosition {
        hydrationCount++ // a service-owned resumption is a session load, not a queue edit
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
        if (!plan.bounded) return plan.toMedia3Resumption()
        return installBoundedResumptionWindow(plan)
    }

    /** The queue authority a resumption request captured when it arrived; a newer selection/queue change moves it. */
    internal fun resumptionAuthority(): Long = queueGeneration

    internal fun isResumptionAuthorityCurrent(token: Long): Boolean = queueGeneration == token

    /** Whether a restored queue of [queueSize] songs is installed as a bounded window (the service passes this to the resumption mapper). */
    internal fun boundedModeForResumption(queueSize: Int): Boolean =
        logicalPresentationAvailable &&
            PhysicalQueuePolicy.modeFor(queueSize, boundedEnabled = PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED || PhysicalQueuePolicy.VALIDATION_BOUNDED || boundedInstallEnabledForTesting) == QueueMode.Bounded

    /** The bounded half of a resumption: see [adoptPlaybackResumption]. Nothing here touches a MediaController. */
    private fun installBoundedResumptionWindow(plan: PlaybackResumptionPlan): androidx.media3.session.MediaSession.MediaItemsWithStartPosition {
        val port = requireBoundedPort()
        installedQueueMode = QueueMode.Bounded
        playerQueueNeedsSync = true // owed until the physical player acknowledges the window
        port.repeatMode = if (repeatMode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        port.shuffleModeEnabled = false
        val outcome = physicalWindowAdapter.install(
            player = port,
            queue = playbackQueue,
            logicalGeneration = queueGeneration,
            startIndex = plan.startPlaybackIndex,
            positionMs = plan.startPositionMs,
            repeatMode = repeatMode,
            verifier = boundedVerifier(port),
            onOutcome = boundedOutcomeHandler("resumption"),
        )
        if (outcome !is BoundedInstallOutcome.Submitted) {
            installedQueueMode = QueueMode.Full
            error("bounded resumption install was not issued: $outcome")
        }
        notifyLogicalPresentationChanged()
        val items = List(port.mediaItemCount) { port.mediaItemAt(it) }
        return androidx.media3.session.MediaSession.MediaItemsWithStartPosition(items, port.currentMediaItemIndex, plan.startPositionMs)
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
     * honour the RESUME_IF_INTERRUPTED mode. The removal also voids every pending wired resume request.
     */
    fun onWiredDeviceRemoved() {
        wiredResumeAuthority.supersedeByRouteLoss()
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
        launchAutomaticResume(bluetoothRoute, availableSongs)
    }

    /**
     * Resumes playback after wired headphones connect.
     *
     * There is no job-based debounce: every connect event begins a new request that voids the older one (a duplicate event
     * simply supersedes its predecessor, which ends without playing). A job being active is not authority; the request
     * token is.
     */
    @Synchronized
    fun resumeForWiredHeadphones(availableSongs: List<Song>) {
        launchAutomaticResume(wiredRoute, availableSongs)
    }

    private fun launchAutomaticResume(route: ResumeRoute, availableSongs: List<Song>) {
        // The token is created here (outside the route's lock) so a newer request, a user command or a route loss
        // invalidates this one immediately; attempts and their entitlement settlement then run one at a time.
        val request = AutomaticResumeRequest(
            route = route,
            token = route.authority.begin(),
            queueGeneration = queueGeneration,
        )
        scope.launch {
            val result = route.mutex.withLock { attemptAutomaticResume(availableSongs, request) }
            logResume("resumeFor${route.label}: token=${request.token} result=$result")
        }
    }

    /**
     * Everything that differs between the Bluetooth and the wired automatic-resume request. All decisions (authority,
     * final authorization, entitlement settlement) are otherwise one shared implementation.
     */
    private class ResumeRoute(
        val label: String,
        val authority: AutomaticResumeAuthority,
        val mutex: Mutex,
        /** Time allowed for the output to become usable before the first play (a wired plug event precedes a usable route). */
        val settleMs: Long,
        val isOutputConnected: () -> Boolean,
        val mode: (ResumeBehaviorSettings) -> HeadphoneResumeMode,
        val inMemoryInterrupted: () -> Boolean,
        val setInMemoryInterrupted: (Boolean) -> Unit,
        val persistedInterrupted: suspend () -> Boolean,
        val setPersistedInterrupted: suspend (Boolean) -> Unit,
    )

    private val bluetoothRoute = ResumeRoute(
        label = "Bluetooth",
        authority = automaticResumeAuthority,
        mutex = bluetoothResumeMutex,
        settleMs = 0L,
        isOutputConnected = { bluetoothOutputConnectedQuery() },
        mode = { it.bluetoothResumeMode },
        inMemoryInterrupted = { wasInterruptedByBluetooth },
        setInMemoryInterrupted = { wasInterruptedByBluetooth = it },
        persistedInterrupted = { resumeBehaviorRepository.hasBluetoothInterruptedResumePending() },
        setPersistedInterrupted = { resumeBehaviorRepository.setBluetoothInterruptedResumePending(it) },
    )

    private val wiredRoute = ResumeRoute(
        label = "Wired",
        authority = wiredResumeAuthority,
        mutex = wiredResumeMutex,
        settleMs = WIRED_RESUME_SETTLE_MS,
        isOutputConnected = { wiredOutputConnectedQuery() },
        mode = { it.wiredResumeMode },
        inMemoryInterrupted = { wasInterruptedByWired },
        setInMemoryInterrupted = { wasInterruptedByWired = it },
        persistedInterrupted = { resumeBehaviorRepository.hasWiredInterruptedResumePending() },
        setPersistedInterrupted = { resumeBehaviorRepository.setWiredInterruptedResumePending(it) },
    )

    /** Identity of one automatic resume request. See [AutomaticResumeAuthority]. */
    private data class AutomaticResumeRequest(val route: ResumeRoute, val token: Long, val queueGeneration: Long)

    private fun queryAudioRoute(query: (AudioManager) -> Boolean): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching { query(audioManager) }.getOrDefault(false)
    }

    /**
     * Final gate immediately before an automatic play: request still current, output still connected (a fresh route
     * query, never an earlier readiness result or connect event), queue not mutated, and the [controller] the caller is
     * about to act on is still the live one. [controller] null means the caller reads the live controller itself.
     */
    private fun authorizeAutomaticPlay(
        request: AutomaticResumeRequest,
        controller: MediaController?,
        queueGenerationAllowedBumps: Int = 0,
    ): PlayAuthorization {
        val route = request.route
        val authorization = route.authority.authorizePlay(
            token = request.token,
            routeConnected = route.isOutputConnected(),
            capturedQueueGeneration = request.queueGeneration,
            currentQueueGeneration = queueGeneration,
            queueGenerationAllowedBumps = queueGenerationAllowedBumps,
            controllerCurrent = controller == null || mediaController === controller,
        )
        if (authorization != PlayAuthorization.ALLOWED) {
            logResume("${route.label} play denied token=${request.token}: $authorization")
        }
        return authorization
    }

    private fun denialOutcome(request: AutomaticResumeRequest, denial: PlayAuthorization): AutomaticResumeOutcome =
        BluetoothEntitlementPolicy.outcomeFor(denial, request.route.authority.invalidationOf(request.token))

    /**
     * Waits [totalMs] in short slices and stops early as soon as the request loses authority, so a superseded attempt
     * neither holds its route's lock nor lingers. Returns whether the request is still current.
     */
    private suspend fun delayWhileCurrent(request: AutomaticResumeRequest, totalMs: Long): Boolean {
        var remaining = totalMs
        while (remaining > 0L) {
            val slice = minOf(remaining, RESUME_WAIT_SLICE_MS)
            delay(slice)
            remaining -= slice
            if (!request.route.authority.isCurrent(request.token)) return false
        }
        return request.route.authority.isCurrent(request.token)
    }

    /**
     * Clears the entitlement, then rechecks ownership after the suspending write. If the request
     * lost ownership to route loss or a newer request while the write was in flight, the
     * entitlement is restored (persisted + in-memory) so a fresh interruption is never erased.
     * The in-memory flag is only cleared once the request is known to still be allowed to clear.
     */
    private suspend fun consumeEntitlement(request: AutomaticResumeRequest) {
        val route = request.route
        route.setPersistedInterrupted(false)
        val invalidation = route.authority.invalidationOf(request.token)
        if (BluetoothEntitlementPolicy.shouldRestoreAfterClear(invalidation)) {
            route.setInMemoryInterrupted(true)
            route.setPersistedInterrupted(true)
            logResume("consumeEntitlement(${route.label}): token=${request.token} lost ownership ($invalidation) during clear — restored")
        } else {
            route.setInMemoryInterrupted(false)
        }
    }

    /**
     * Core automatic-resume logic for Bluetooth and wired. The interruption entitlement (in-memory + persisted) is read
     * but NOT consumed up front; it is consumed once, at the end, and only when
     * [BluetoothEntitlementPolicy] says the outcome is definitive. Transient failures (controller
     * unavailable, setup failure, route lost, queue changed, superseded by a newer request) keep
     * it so the next reconnect can still resume. Every suspension point rechecks the token.
     */
    private suspend fun attemptAutomaticResume(
        availableSongs: List<Song>,
        request: AutomaticResumeRequest,
    ): AutomaticResumeOutcome {
        val rawOutcome = runAutomaticResume(availableSongs, request)
        // Reconcile with the request's latest invalidation immediately before settling, so an
        // old request cannot clear an entitlement owned by a newer route-loss or request.
        val outcome = BluetoothEntitlementPolicy.settle(
            rawOutcome,
            request.route.authority.invalidationOf(request.token),
        )
        if (outcome != rawOutcome) {
            logResume("attemptAutomaticResume(${request.route.label}): token=${request.token} settled $rawOutcome -> $outcome")
        }
        if (BluetoothEntitlementPolicy.shouldConsume(outcome)) consumeEntitlement(request)
        return outcome
    }

    private suspend fun runAutomaticResume(
        availableSongs: List<Song>,
        request: AutomaticResumeRequest,
    ): AutomaticResumeOutcome {
        val route = request.route
        fun supersededOutcome(): AutomaticResumeOutcome? =
            route.authority.invalidationOf(request.token)?.let(BluetoothEntitlementPolicy::outcomeFor)

        val settings = resumeBehaviorRepository.settings.first()
        val persistedInterrupted = route.persistedInterrupted()
        val interrupted = route.inMemoryInterrupted() || persistedInterrupted
        val mode = route.mode(settings)

        logResume(
            "attemptResume(${route.label}): token=${request.token} mode=$mode " +
                "interrupted=$interrupted rememberLastTrack=${settings.rememberLastTrack}",
        )

        supersededOutcome()?.let { return it }

        if (!settings.rememberLastTrack) {
            logResume("attemptResume(${route.label}): skipped — rememberLastTrack=false")
            return AutomaticResumeOutcome.SKIPPED_BY_SETTING
        }

        if (!mode.shouldResume(interrupted)) {
            logResume("attemptResume(${route.label}): skipped — mode=$mode does not resume for interrupted=$interrupted")
            return AutomaticResumeOutcome.SKIPPED_BY_SETTING
        }

        val controller = awaitMediaController { route.authority.isCurrent(request.token) }
        if (controller == null) {
            logResume("attemptResume(${route.label}): giving up — mediaController not ready")
            return supersededOutcome() ?: AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE
        }

        supersededOutcome()?.let { return it }

        if (controller.isPlaying) {
            logResume("attemptResume(${route.label}): already playing, nothing to do")
            return AutomaticResumeOutcome.ALREADY_PLAYING
        }

        if (route.settleMs > 0L && !delayWhileCurrent(request, route.settleMs)) {
            return supersededOutcome() ?: AutomaticResumeOutcome.SUPERSEDED_BY_NEWER_REQUEST
        }
        if (mediaController !== controller) {
            logResume("attemptResume(${route.label}): controller replaced during settle")
            return AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE
        }
        if (controller.isPlaying) {
            logResume("attemptResume(${route.label}): already playing after settle, nothing to do")
            return AutomaticResumeOutcome.ALREADY_PLAYING
        }

        val song = _nowPlayingState.value.song
        logResume(
            "attemptResume(${route.label}): queueSize=${libraryQueue.size} " +
                "mediaItemCount=${controller.mediaItemCount} " +
                "playbackState=${stateString(controller.playbackState)}",
        )
        if (song != null && libraryQueue.isNotEmpty()) {
            authorizeAutomaticPlay(request, controller).let { if (it != PlayAuthorization.ALLOWED) return denialOutcome(request, it) }
            lastKnownPositionMs = -1L
            statsTracker.onSongSelected(song)
            return performHotResume(controller, availableSongs, request)
        }

        logResume("attemptResume(${route.label}): no active queue — loading session cold")
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

    private data class ColdResumeResult(
        val result: PlayerHydrationResult,
        /** Non-null when an automatic request was denied play authority; play was NOT issued. */
        val denied: PlayAuthorization? = null,
    )

    /**
     * Hydrates the persisted session if needed, then applies reconnect autoplay policy.
     * The hydration primitive itself never calls play and never consumes retry eligibility.
     * Authority is checked BEFORE hydration (an invalidated request must not replace the physical queue) and
     * rechecked after the (suspending) hydration, immediately before play; hydration may bump the queue generation once.
     */
    private suspend fun resumeSessionCold(
        availableSongs: List<Song>,
        request: AutomaticResumeRequest,
    ): ColdResumeResult {
        // An already-invalid request (superseded, route gone, queue changed, controller replaced) must not replace the
        // physical queue either, so the full gate runs before hydration as well as before play.
        authorizeAutomaticPlay(request, null).let { denial ->
            if (denial != PlayAuthorization.ALLOWED) {
                logResume("resumeSessionCold: token=${request.token} denied before hydration: $denial")
                // Hydration was never attempted; the result is a placeholder because `denied` takes precedence.
                return ColdResumeResult(PlayerHydrationResult.SkippedActiveQueue, denial)
            }
        }
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
        val bumps = if (result == PlayerHydrationResult.Hydrated) 1 else 0
        val authorization = authorizeAutomaticPlay(request, null, queueGenerationAllowedBumps = bumps)
        if (authorization != PlayAuthorization.ALLOWED) return ColdResumeResult(result, authorization)
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
     * The request keeps its authority through every step: the cold fallback, the prepare/play sequence, the 700 ms wait
     * and the delayed retry are each gated by [authorizeAutomaticPlay]; a stale request never plays. `playWhenReady`
     * remaining true is never authority.
     */
    private suspend fun performHotResume(
        controller: MediaController,
        availableSongs: List<Song>,
        request: AutomaticResumeRequest,
    ): AutomaticResumeOutcome {
        val source = request.route.label
        if (controller.currentMediaItem == null || controller.mediaItemCount == 0) {
            logResume("$source: hot resume has no media item (mediaItemCount=${controller.mediaItemCount}) — falling back to cold resume")
            val cold = resumeSessionCold(availableSongs, request)
            cold.denied?.let { return denialOutcome(request, it) }
            if (!playerHydrationAllowsPlay(cold.result)) return AutomaticResumeOutcome.SETUP_FAILED
            return AutomaticResumeOutcome.PLAY_ISSUED
        }

        authorizeAutomaticPlay(request, controller).let { if (it != PlayAuthorization.ALLOWED) return denialOutcome(request, it) }

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

        // Give ExoPlayer time to settle audio focus and routing after reconnect, then verify. The wait ends early
        // if the request loses authority (user pause, route loss, newer request).
        val stillCurrent = delayWhileCurrent(request, 700L)

        logResumePlayDiagnostics(controller, "$source: delayed post-play")

        if (!stillCurrent) {
            logResume("$source: request lost authority during settle wait — no retry")
            return AutomaticResumeOutcome.PLAY_ISSUED
        }

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
            if (authorizeAutomaticPlay(request, controller) != PlayAuthorization.ALLOWED) {
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
    private suspend fun awaitMediaController(
        timeoutMs: Long = 3_000L,
        // An automatic request that lost authority stops waiting instead of holding its route lock for the full timeout.
        keepWaiting: () -> Boolean = { true },
    ): MediaController? {
        mediaController?.let { return it }
        // Demand-driven retry: if a previous connection attempt failed (or the controller was
        // disconnected), this starts a fresh build instead of waiting forever on a dead attempt.
        ensureControllerConnection()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && keepWaiting()) {
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
        // Lifecycle teardown voids every pending automatic resume and suspended cold PLAY (their coroutines are cancelled
        // below; this also closes the window before they next reach a suspension point).
        supersedeAutomaticResumeByUser()
        wiredResumeAuthority.supersedeByRouteLoss()
        automaticResumeAuthority.supersedeByRouteLoss()
        stopPositionTicker()
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepRebindJob?.cancel()
        sleepRebindJob = null
        _sleepTimerState.value = SleepTimerState()
        sleepBoundaryListeners.notifyArmed(false)
        scope.cancel()
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
        _progressPlayer.value = null
        // Invalidate any in-flight buildAsync so a late completion cannot install a stale
        // controller or mutate state after release.
        ++controllerConnectionGeneration
        controllerConnectionState = ControllerConnectionState.Disconnected
        // Teardown must not resurrect a command into a later controller.
        deferred.clear()
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

    // ── ST-1: sleep timer / terminal boundary (decisions live in SleepTimerPolicy; this only executes them) ───────────────

    /** The exact occurrence playing now (generation + playback position), or null when it cannot be resolved safely. */
    private fun currentSleepOccurrence(): SleepTerminalOccurrence? {
        val index = currentPlaybackIndex() ?: return null
        val song = playbackQueue.getOrNull(index) ?: return null
        return SleepTerminalOccurrence(queueGeneration, index, song.id)
    }

    /** Publishes the policy's state and performs its action. The ONLY place the timer state changes after configuration. */
    private fun applySleepTransition(transition: SleepTimerTransition) {
        val previous = _sleepTimerState.value
        _sleepTimerState.value = transition.state
        if (previous.phase == SleepTimerPhase.COUNTDOWN && transition.state.phase != SleepTimerPhase.COUNTDOWN) cancelSleepCountdown()
        when (transition.action) {
            SleepTimerAction.None -> Unit
            is SleepTimerAction.ArmBoundary -> sleepBoundaryListeners.notifyArmed(true)
            SleepTimerAction.PauseNow -> {
                sleepBoundaryListeners.notifyArmed(false)
                mediaController?.pause()
                syncNowPlayingState()
                syncPosition()
                saveSessionAsync()
            }
            SleepTimerAction.StopAtBoundary -> {
                // The player already stopped at the natural end of the armed occurrence (no transition happened): record the
                // terminal occurrence and its final position. The timer itself is never persisted.
                sleepBoundaryListeners.notifyArmed(false)
                syncNowPlayingState()
                syncPosition()
                saveSessionAsync()
            }
            SleepTimerAction.DisarmBoundary -> sleepBoundaryListeners.notifyArmed(false)
        }
    }

    private fun onSleepCountdownExpired() {
        val controller = mediaController
        val playbackActive = controller != null && controller.playWhenReady &&
            controller.playbackState != Player.STATE_ENDED && controller.playbackState != Player.STATE_IDLE
        val current = if (playbackActive && controller?.currentMediaItem != null) currentSleepOccurrence() else null
        applySleepTransition(SleepTimerPolicy.onCountdownExpired(_sleepTimerState.value, current, playbackActive))
    }

    /** The player stopped while a boundary may be armed: natural end of the armed occurrence, or an earlier pause. Idempotent. */
    private fun handleSleepPlaybackStopped() {
        val state = _sleepTimerState.value
        if (!state.isFinishingCurrentTrack) return
        val controller = mediaController ?: return
        val stopped = !controller.playWhenReady || controller.playbackState == Player.STATE_ENDED
        if (!stopped) return
        applySleepTransition(SleepTimerPolicy.onPlaybackStopped(state, currentSleepOccurrence(), atNaturalEnd = isAtNaturalEnd(controller)))
    }

    private fun isAtNaturalEnd(controller: MediaController): Boolean {
        if (controller.playbackState == Player.STATE_ENDED) return true
        val duration = controller.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return false
        return controller.currentPosition >= duration - SLEEP_NATURAL_END_TOLERANCE_MS
    }

    private fun sleepTransitionKind(reason: Int): SleepTransitionKind = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> SleepTransitionKind.AUTOMATIC
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> SleepTransitionKind.REPEAT_WRAP
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> SleepTransitionKind.NAVIGATION
        else -> SleepTransitionKind.PLAYLIST_CHANGED
    }

    /**
     * A Media3 transition observation while a boundary may be armed. Returns true when the boundary had escaped (the player
     * advanced or wrapped although it should have paused): playback is stopped and the caller must NOT run its normal
     * selection/stats handling for a track that must not have started. The second callback of the same escape is swallowed.
     */
    private fun handleSleepTransition(kind: SleepTransitionKind, isAutomaticCallback: Boolean): Boolean {
        val state = _sleepTimerState.value
        if (!state.isFinishingCurrentTrack) {
            return isAutomaticCallback && SystemClock.elapsedRealtime() < sleepEscapeEchoDeadlineElapsedMs
        }
        val transition = SleepTimerPolicy.onTransition(state, kind, currentSleepOccurrence())
        applySleepTransition(transition)
        val escaped = transition.action == SleepTimerAction.PauseNow
        if (escaped) sleepEscapeEchoDeadlineElapsedMs = SystemClock.elapsedRealtime() + SLEEP_ESCAPE_ECHO_WINDOW_MS
        return escaped
    }

    /**
     * Called after every queue-identity bump. A structural mutation or shuffle toggle keeps the same physical item playing, so
     * the armed boundary is re-bound to that exact occurrence once the mutation has finished (next main-thread turn). A queue
     * that now plays a DIFFERENT song means the bound occurrence is gone: the boundary is cleared. Never moves it to another one.
     */
    private fun scheduleSleepBoundaryRebind() {
        if (!_sleepTimerState.value.isFinishingCurrentTrack) return
        sleepRebindJob?.cancel()
        sleepRebindJob = scope.launch {
            yield()
            applySleepTransition(SleepTimerPolicy.onQueueIdentityChanged(_sleepTimerState.value, currentSleepOccurrence()))
        }
    }

    /** An explicit whole-queue playback start / song selection: crossfade cleanup (existing) plus clearing an armed boundary. */
    private fun notifyQueueReplacement() {
        explicitQueueReplacementListeners.notifyExplicitQueueReplacement()
        applySleepTransition(SleepTimerPolicy.onQueueReplaced(_sleepTimerState.value))
    }

    /** ST-1: registers (or clears with null) the lifecycle-scoped owner of the physical boundary; replays the current state once. */
    internal fun setSleepBoundaryListener(listener: ((Boolean) -> Unit)?) {
        sleepBoundaryListeners.set(listener, _sleepTimerState.value.isFinishingCurrentTrack)
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
                if (handleSleepTransition(SleepTransitionKind.REPEAT_WRAP, isAutomaticCallback = false)) {
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
    //
    // Large-queue hardening: aligned -> a plain seekTo. Dirty -> the reconciler establishes only the target window
    // (bounded, independent of queue size) and the command is then a plain seekTo at the physical index of the target;
    // the rest of the timeline is repaired in the background. A full re-push happens only when the current occurrence
    // or the target cannot be proven (never a guessed duplicate).
    private fun seekToPlaybackIndex(
        controller: MediaController,
        playbackIndex: Int,
        forcePlay: Boolean? = null,
        positionMs: Long = 0L,
    ) {
        lastKnownPositionMs = -1L
        val shouldPlay = forcePlay ?: controller.isPlaying
        if (isBounded()) {
            seekBoundedToLogical(controller, playbackIndex, shouldPlay, forcePlay, positionMs)
            return
        }
        if (playerQueueNeedsSync) {
            if (repairForTransportTarget(controller, playbackIndex)) {
                // Completed clears the flag (offset 0); otherwise the physical index is the logical one plus the offset.
                controller.seekTo(playbackIndex + physicalIndexOffset, positionMs.coerceAtLeast(0L))
                kickQueueRepair()
            } else {
                syncPlayerQueueAt(
                    controller, playbackIndex, positionMs = positionMs.coerceAtLeast(0L), playWhenReady = shouldPlay,
                    reason = FullQueueSyncReason.TransportTargetUnprovable,
                )
            }
        } else {
            controller.seekTo(playbackIndex, positionMs.coerceAtLeast(0L))
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
     * advanced natively. When the player playlist is in step with the logical queue (the normal
     * case) this returns false and WavDrop merely follows state, preserving native gapless
     * playback (see [MediaItemTransitionKind]). While the queue is still dirty (a deferred shuffle reorder, or
     * a queue op that could not reach the player) the reconciler has already verified the window ahead of the current
     * item, so a boundary that landed on a verified index is ALSO pure native gapless (no re-push). Only a boundary
     * that landed on an unverified index intervenes with a full re-push (NOT gapless) as the labelled last resort.
     */
    private fun handlePendingAutomaticTransition(): Boolean {
        if (isBounded()) {
            // P5-C: Media3 advanced natively inside the verified window. Follow the state (return false) and keep the window
            // topped up; never the Full-queue re-push below. An unprovable landing is recovered by a bounded re-anchor.
            onBoundedAutomaticTransition()
            return false
        }
        if (!naturalTransitionRequiresQueueResync(MediaItemTransitionKind.Auto, playerQueueNeedsSync)) {
            return false
        }
        val controller = mediaController ?: return false
        if (naturalAdvanceLandedOnVerifiedIndex(controller)) {
            // Follow state exactly like an aligned boundary and keep repairing outward from the new current item.
            kickQueueRepair()
            return false
        }
        if (BuildConfig.DEBUG) {
            Log.d(GAPLESS_TAG, "natural boundary on an unverified index: full queue re-push, not native gapless")
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
                reason = FullQueueSyncReason.NaturalBoundaryUnverified,
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
            reason = FullQueueSyncReason.NaturalBoundaryUnverified,
        )
        syncNowPlayingState(fromTransition = true)
        saveSessionAsync()
        return true
    }

    /**
     * FULL physical re-push (`setMediaItems` of the whole playbackQueue + prepare). This is a LAST-RESORT recovery, not
     * routine transport: it is reached only when the physical timeline cannot be proven or repaired incrementally (see
     * [FullQueueSyncReason]) or for a fresh/destructive queue change. It is the one place that clears the dirty state besides a
     * completed reconciliation, and every use is logged with its reason, queue size and current index.
     */
    private fun syncPlayerQueueAt(
        controller: MediaController,
        playbackIndex: Int,
        positionMs: Long,
        playWhenReady: Boolean,
        reason: FullQueueSyncReason,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(
                QUEUE_PERF_TAG,
                "full_queue_sync reason=$reason queueSize=${playbackQueue.size} currentIndex=$playbackIndex dirtyReason=$queueDirtyReason",
            )
        }
        // Bounded (inactive): the queue is clean only once the installed window is VERIFIED (see installPlaybackQueue).
        if (!isBoundedInstall()) playerQueueNeedsSync = false
        controller.installPlaybackQueue(
            operation = "sync_player_queue:$reason",
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

        val generationBefore = queueGeneration
        bumpQueueGeneration()
        val newOrder = playbackOrder.toMutableList()
        val tmp = newOrder[fromIndex]
        newOrder[fromIndex] = newOrder[toIndex]
        newOrder[toIndex] = tmp
        playbackOrder = newOrder
        playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
        rebaseDeferredStart(generationBefore, OccurrenceMap.swap(fromIndex, toIndex))

        if (isBounded()) {
            applyBoundedQueueMutation("swap_queue_items", currentPlaybackIndex, OccurrenceMap.swap(fromIndex, toIndex))
        } else {
            applyPhysicalEdit { it.moveMediaItem(fromIndex, toIndex) }
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
     * CF-2M4: read-only Song -> MediaItem materialization for the engine NEXT slot. Same representation and cache as CURRENT
     * (identical mediaId, URI and metadata). Mutates no queue, bumps no generation, writes no stats or session state and
     * sends no controller command; it only reuses (and may populate) the existing media-item cache.
     */
    internal fun materializePlaybackMediaItemsForCrossfade(songs: List<Song>): List<MediaItem> =
        materializeMediaItems(songs).mediaItems

    /**
     * P5-E Checkpoint C: the crossfade view of a BOUNDED queue. NEXT mirrors CURRENT's verified physical window, so the unchanged
     * crossfade stack is handed the WINDOW as its queue, in PHYSICAL coordinates (queue = the window's songs in physical order,
     * current = the physical index, keys = physical from/to). Logical indices never reach it.
     *
     * The explicit readiness contract: a NEXT may be prepared or promoted only when the window is VERIFIED (Active), nothing is in
     * flight (no pending install/adjust/mutation, no held navigation, no deleted-current continuation), the physical CURRENT proves
     * its occurrence, and enough of the window lies AHEAD of the target (or the window genuinely ends the logical queue).
     * Otherwise the snapshot reports `playerQueueNeedsSync`, the unchanged planner answers a transient Unavailable, and an existing
     * NEXT loses ownership and is invalidated: CURRENT is authoritative and a NEXT built on a window that changed is never promoted.
     *
     * `queueGeneration` is an ownership EPOCH: the logical generation joined with the window revision, so any window change
     * (refill, trim, re-anchor, mutation) is a new epoch and the existing ownership rules cancel the NEXT built on the old one.
     */
    private fun boundedCrossfadeSnapshot(controller: MediaController?): CrossfadeRuntimeSnapshot {
        val port = physicalPort
        val adapter = physicalWindowAdapter
        val active = adapter.active
        val resolution = if (port != null && active != null) adapter.resolveCurrent(port) else null
        val proven = resolution as? CurrentResolution.Proven
        val physicalCurrent = proven?.physical
        val aheadOfTarget = if (physicalCurrent != null && active != null) active.installedCount - 2 - physicalCurrent else -1
        val enoughAhead = active != null && physicalCurrent != null &&
            (aheadOfTarget >= PhysicalWindowPolicy.AHEAD_FLOOR || active.window.endsAtLogicalEnd)
        val ready = controller != null && active != null && proven != null && !adapter.isBusy && !adapter.hasHeldNavigation &&
            pendingDeletionContinuation == null && !playerQueueNeedsSync && enoughAhead
        val slice = if (active != null) boundedWindowSongs(active) else emptyList()
        val epoch = if (active != null) (queueGeneration shl 24) or (active.window.windowGeneration and 0xFFFFFFL) else queueGeneration
        return CrossfadeRuntimeSnapshot(
            queueGeneration = epoch,
            playbackQueue = slice,
            currentPlaybackIndex = physicalCurrent,
            // The window already contains the wrapped successor of a ring, and ALL is never a physical loop: plan on the physical mirror.
            repeatMode = if (repeatMode == RepeatMode.ONE) RepeatMode.ONE else RepeatMode.OFF,
            shuffleEnabled = shuffleEnabled,
            isPlaying = controller?.isPlaying ?: _nowPlayingState.value.isPlaying,
            isExternalPlayback = isExternalPlayback,
            playerQueueNeedsSync = !ready,
            controllerConnected = controller != null && controllerConnectionState == ControllerConnectionState.Connected,
            sleepBoundaryArmed = _sleepTimerState.value.isFinishingCurrentTrack,
        )
    }

    private var windowSongsCache: Triple<InstalledWindow, List<Song>, List<Song>>? = null

    /** The songs of the verified window in physical order; cached per window revision and queue (O(window), never O(queue)). */
    private fun boundedWindowSongs(active: InstalledWindow): List<Song> {
        val queue = playbackQueue
        windowSongsCache?.let { (window, source, songs) -> if (window === active && source === queue) return songs }
        val songs = List(active.installedCount) { queue[active.physicalToLogical(it)!!] }
        windowSongsCache = Triple(active, queue, songs)
        return songs
    }

    /**
     * Read-only snapshot of the logical playback state for the future crossfade engine (CF-2B1).
     * No side effects: reads in-memory state and the existing occurrence resolution only.
     */
    internal fun captureCrossfadeRuntimeSnapshot(): CrossfadeRuntimeSnapshot {
        val controller = mediaController
        if (isBounded()) return boundedCrossfadeSnapshot(controller)
        return CrossfadeRuntimeSnapshot(
            queueGeneration = queueGeneration,
            // Shared reference, not a copy: `playbackQueue` is replaced wholesale on every structural change and never mutated in
            // place (every producer allocates a fresh list; see CrossfadeRuntimeSnapshot), so a snapshot keeps describing the
            // queue it was captured from while this runs every 250 ms (twice while a NEXT is Ready) without an O(N) copy.
            playbackQueue = playbackQueue,
            currentPlaybackIndex = currentPlaybackIndex(),
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            isPlaying = controller?.isPlaying ?: _nowPlayingState.value.isPlaying,
            isExternalPlayback = isExternalPlayback,
            // Full mode only (a bounded queue returns boundedCrossfadeSnapshot above).
            playerQueueNeedsSync = playerQueueNeedsSync,
            controllerConnected = controller != null &&
                controllerConnectionState == ControllerConnectionState.Connected,
            sleepBoundaryArmed = _sleepTimerState.value.isFinishingCurrentTrack,
        )
    }


    private fun currentPlaybackIndex(): Int? {
        if (isBounded()) {
            // P5-C: the physical index is only a position inside the window. The logical occurrence is PROVEN through the active
            // window (position + the id verified at it) or taken from the adapter's tracked occurrence while an operation is in
            // flight; nothing here searches the queue for a song id.
            val port = physicalPort
            val logical = if (mediaController != null && port != null) physicalWindowAdapter.currentLogical(port)
            else physicalWindowAdapter.trackedLogical
            return logical?.takeIf { it in playbackQueue.indices }
        }
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
            verifiedLogicalIndices = verifiedLogicalIndices,
            physicalIndexOffset = physicalIndexOffset,
        )
    }

    /**
     * P5-B: test-only switch that lets [PhysicalQueuePolicy.modeFor] answer Bounded for queues above the Full limit. It is never
     * set in production and is not a setting; with it false (always) every install below is the unchanged Full path.
     */
    internal var boundedInstallEnabledForTesting: Boolean = false

    private val physicalWindowAdapter = PhysicalWindowAdapter(
        currentLogicalGeneration = { queueGeneration },
        materialize = { songs -> materializeMediaItems(songs).mediaItems },
    ).also { adapter ->
        // P5-F validation trace only: reports each physical playlist operation the adapter actually issued; changes nothing.
        adapter.submissionObserver = { ValidationTrace.physicalSubmitted(it, playbackQueue.size) }
    }

    /**
     * Test seam: how the physical player's acknowledgement is requested. Production is always the session probe
     * (the port verifier); a test replaces it to control the ORDER of acknowledgements against other events.
     */
    internal var boundedVerifierFactory: (PhysicalWindowPort) -> PhysicalInstallVerifier? = { it.verifier }

    private fun boundedVerifier(port: PhysicalWindowPort): PhysicalInstallVerifier? = boundedVerifierFactory(port)

    /**
     * P5-E: the physical CURRENT player, as the service attached it. Every index-bearing or proving operation of a bounded window goes
     * through this port and NEVER through the logical `MediaController`, whose indices are logical once the session façade presents the
     * complete queue. Full mode never reads it.
     */
    private var physicalPort: PhysicalWindowPort? = null

    /** Attached by the service (production: [EnginePhysicalPort]) or a test; null detaches. Bounded mode refuses to run without it. */
    internal fun attachPhysicalWindowPort(port: PhysicalWindowPort?) { physicalPort = port }

    private fun requireBoundedPort(): PhysicalWindowPort =
        physicalPort ?: error("bounded mode requires an attached PhysicalWindowPort (the logical controller must not carry physical indices)")

    /** The mode of the queue install that is about to happen, decided once from the logical queue size. */
    private fun isBoundedInstall(): Boolean =
        logicalPresentationAvailable &&
            PhysicalQueuePolicy.modeFor(
                playbackQueue.size,
                boundedEnabled = PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED || PhysicalQueuePolicy.VALIDATION_BOUNDED || boundedInstallEnabledForTesting,
            ) == QueueMode.Bounded

    /**
     * THE queue-installation seam for a whole playbackQueue (library start, preserved search, hydration, queue repair).
     * Full: exactly the previous `setMeasuredMediaItems(playbackQueue)`. Bounded (unreachable while the gate is off): one
     * bounded window via [physicalWindowAdapter]; there is no full-queue fallback. [startIndex] is a LOGICAL playbackQueue index.
     * Not converted yet (P5-C..E): navigation, queue edits, crossfade and the session facade still assume Full.
     */
    private fun MediaController.installPlaybackQueue(operation: String, startIndex: Int, positionMs: Long) {
        ValidationTrace.installRequested(operation, playbackQueue.size, if (isBoundedInstall()) QueueMode.Bounded else QueueMode.Full, startIndex)
        if (!isBoundedInstall()) {
            if (installedQueueMode != QueueMode.Full) notifyLogicalPresentationChanged()
            installedQueueMode = QueueMode.Full
            if (physicalWindowAdapter.state != WindowInstallState.Idle) physicalWindowAdapter.reset()
            setMeasuredMediaItems(operation = operation, songs = playbackQueue, startIndex = startIndex, positionMs = positionMs)
            return
        }
        val modeBeforeInstall = installedQueueMode
        installedQueueMode = QueueMode.Bounded // decided once per install; later navigation/transition code reads this
        notifyLogicalPresentationChanged()
        val outcome = physicalWindowAdapter.install(
            player = requireBoundedPort(),
            queue = playbackQueue,
            logicalGeneration = queueGeneration,
            startIndex = startIndex,
            positionMs = positionMs,
            repeatMode = this@PlayerController.repeatMode,
            verifier = boundedVerifier(requireBoundedPort()), // Active only on the physical player's own acknowledgement
            onOutcome = boundedOutcomeHandler(operation),
        )
        when (outcome) {
            is BoundedInstallOutcome.Submitted -> playerQueueNeedsSync = true // dirty until the window is verified
            is BoundedInstallOutcome.Rejected -> { revertRefusedBoundedInstall(modeBeforeInstall); error("bounded install rejected: ${outcome.reason}") }
            is BoundedInstallOutcome.Failed -> { revertRefusedBoundedInstall(modeBeforeInstall); throw outcome.cause }
            else -> error("unexpected immediate bounded install outcome: $outcome")
        }
    }


    /** A bounded install that was refused or failed immediately changed nothing physical: the mode returns to what it was. */
    private fun revertRefusedBoundedInstall(modeBeforeInstall: QueueMode) {
        installedQueueMode = modeBeforeInstall
        if (modeBeforeInstall == QueueMode.Full) physicalWindowAdapter.reset()
        notifyLogicalPresentationChanged()
    }
    // ── P5-E: the logical presentation of a bounded queue ──────────────────────────────────────────────────────────────────

    /**
     * Whether a session façade exists that can present the LOGICAL queue to controllers. Bounded installs need it (without it a
     * controller would see the 100-item physical window as the whole queue), so the service clears it for a topology without the
     * façade. Defaults to true so a test harness decides for itself.
     */
    internal var logicalPresentationAvailable: Boolean = true

    private var logicalPresentationInvalidator: (() -> Unit)? = null

    /** The façade registers how to re-read the presentation; cleared with null on teardown. */
    internal fun setLogicalPresentationInvalidator(invalidator: (() -> Unit)?) { logicalPresentationInvalidator = invalidator }

    private var presentationNotifyPosted = false

    /**
     * Coalesced to the end of the current main-thread turn: every logical queue mutation touches several fields (list,
     * generation, window), and the façade must evaluate the finished state, never a half-applied one.
     */
    private fun notifyLogicalPresentationChanged() {
        if (logicalPresentationInvalidator == null || presentationNotifyPosted) return
        presentationNotifyPosted = true
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            presentationNotifyPosted = false
            logicalPresentationInvalidator?.invoke()
        }
    }

    /**
     * What the façade presents while the installed queue is bounded; null in Full mode (the façade is then a pure forwarder).
     * The current occurrence is read in-process from the physical CURRENT through the adapter at the instant of the read, so the
     * presented index can never lag a physical advance; while an operation is in flight it is the adapter's tracked occurrence.
     */
    internal val logicalQueuePresentation: LogicalQueuePresentation = object : LogicalQueuePresentation {
        override fun snapshot(): LogicalQueueSnapshot? {
            if (!isBounded()) return null
            val queue = playbackQueue
            if (queue.isEmpty()) return null
            val port = physicalPort
            val index = (port?.let { physicalWindowAdapter.currentLogical(it) }
                ?: physicalWindowAdapter.trackedLogical.takeIf { it >= 0 }
                ?: _nowPlayingState.value.currentIndex).coerceIn(queue.indices)
            return LogicalQueueSnapshot(queueGeneration, queue, index, repeatMode, shuffleEnabled)
        }
    }

    /** The explicit logical command port the façade routes external navigation/repeat/shuffle commands to in bounded mode. */
    internal val logicalQueueCommands: LogicalQueueCommands = object : LogicalQueueCommands {
        override fun skipToNext() = this@PlayerController.skipToNext()
        override fun skipToPrevious() = this@PlayerController.skipToPrevious()
        override fun seekToQueueItem(index: Int, positionMs: Long) =
            this@PlayerController.seekToQueueItem(index, if (positionMs == androidx.media3.common.C.TIME_UNSET) 0L else positionMs)
        override fun setRepeatMode(media3RepeatMode: Int) {
            val target = repeatModeFromPlayerMode(media3RepeatMode)
            if (target != repeatMode) setRepeatModeExplicit(target)
        }
        override fun setShuffleModeEnabled(enabled: Boolean) {
            if (enabled != shuffleEnabled) toggleShuffle()
        }
    }

    /**
     * Repeat and native-shuffle OFF for the PHYSICAL CURRENT player. While the installed queue is (or is about to become) bounded
     * these go through the physical port: the controller's repeat/shuffle are the LOGICAL ones the façade presents, and a command
     * sent through it would be interpreted as an external logical change. Full to Full keeps the unchanged controller routing.
     */
    private fun applyPhysicalTransportModes(controller: MediaController) {
        val targetBounded = isBoundedInstall()
        val port = physicalPort
        if ((isBounded() || targetBounded) && port != null) {
            port.repeatMode = if (targetBounded) {
                if (repeatMode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            } else {
                repeatMode.toPlayerRepeatMode()
            }
            port.shuffleModeEnabled = false
        } else {
            controller.repeatMode = repeatMode.toPlayerRepeatMode()
            controller.shuffleModeEnabled = false
        }
    }

    // ── P5-C: bounded-window integration (all of it unreachable while the rollout gate is off) ─────────────────────────────

    /** How the CURRENT physical queue was installed. Full unless a bounded install was accepted (never changes mid-install). */
    private var installedQueueMode: QueueMode = QueueMode.Full

    private fun isBounded(): Boolean = installedQueueMode == QueueMode.Bounded

    /** Bounded physical repeat is ONE or OFF: ALL is window geometry, never a physical loop inside a 100-item window. */
    private fun physicalRepeatModeFor(mode: RepeatMode): Int =
        if (isBounded()) {
            if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        } else {
            mode.toPlayerRepeatMode()
        }

    /**
     * P5-D: the physical effect of an already-applied LOGICAL queue mutation in a bounded window. The caller has finished the
     * logical state (queue, order, generation bump) and passes the exact occurrence it was on ([oldCurrent], in the OLD queue's
     * coordinates), the exact old->new occurrence map ([occurrenceMap], the same maps the deferred commands use) and, if the current
     * occurrence was removed, the continuation chosen by the deletion policy. The adapter owns everything physical: it classifies
     * (logical-only / bounded edit / re-anchor / recover), verifies through the physical acknowledgement and publishes only a proven
     * window. With no controller the edit stays logical and a bounded re-install around the exact current is owed on reconnect.
     * Never a Full-queue push.
     */
    private fun applyBoundedQueueMutation(
        operation: String,
        oldCurrent: Int?,
        occurrenceMap: (Int) -> Int?,
        continuation: Int? = null,
        onVerified: (() -> Unit)? = null,
    ) {
        val controller = mediaController
        // With no controller the edit stays logical (offline); with one, the physical window is reached through the port only.
        val port = if (controller != null) requireBoundedPort() else null
        playerQueueNeedsSync = true // owed until the adapter publishes a verified window (or reconnect re-anchors)
        val result = physicalWindowAdapter.applyMutation(
            QueueMutationRequest(
                player = port,
                queue = playbackQueue,
                repeatMode = repeatMode,
                oldCurrent = oldCurrent,
                occurrenceMap = occurrenceMap,
                continuation = continuation,
                verifier = port?.let { boundedVerifier(it) },
                onOutcome = boundedOutcomeHandler(operation, onVerified),
            ),
        )
        ValidationTrace.baselineObservation(operation, playbackQueue.size, result)
        when (result) {
            is MutationSubmission.Submitted -> {
                if (controller == null) ensureControllerConnection() // the repair obligation is discharged on reconnect
                if (playbackQueue.isEmpty()) { installedQueueMode = QueueMode.Full; notifyLogicalPresentationChanged() }
            }
            is MutationSubmission.Rejected -> Log.w(TAG, "[bounded] $operation: physical effect rejected (${result.reason}); the logical edit stands, a bounded re-install is owed")
            is MutationSubmission.Failed -> Log.w(TAG, "[bounded] $operation: physical effect failed (${result.cause.message}); the logical edit stands, a bounded re-install is owed")
        }
    }

    /** Exact old->new occurrence map for a reorder that is described only by the two playback orders (shuffle toggle). */
    private fun occurrenceMapBetweenOrders(oldOrder: List<Int>, newOrder: List<Int>): (Int) -> Int? {
        val newPositionByLibraryIndex = HashMap<Int, Int>(newOrder.size)
        newOrder.forEachIndexed { position, libraryIndex -> newPositionByLibraryIndex[libraryIndex] = position }
        return { old -> oldOrder.getOrNull(old)?.let { newPositionByLibraryIndex[it] } }
    }

    /** Terminal handling of ONE bounded install or adjustment; never publishes anything the adapter did not verify. */
    private fun boundedOutcomeHandler(operation: String, onVerified: (() -> Unit)? = null): (BoundedInstallOutcome) -> Unit {
        val submittedAtNanos = ValidationTrace.nowNanos()
        return TracedOutcome(operation) { result -> handleBoundedOutcome(operation, onVerified, submittedAtNanos, result) }
    }

    private fun handleBoundedOutcome(operation: String, onVerified: (() -> Unit)?, submittedAtNanos: Long, result: BoundedInstallOutcome) {
        ValidationTrace.windowTerminal(operation, submittedAtNanos, result, playbackQueue.size, physicalPort?.mediaItemCount)
        notifyLogicalPresentationChanged()
        when (result) {
            is BoundedInstallOutcome.Active -> {
                playerQueueNeedsSync = false
                syncNowPlayingState(notifyStats = false)
                onVerified?.invoke() // an operation-bound follow-up runs only now, and only if it is still the current intent
                maintainBoundedWindow() // chain: ends at NoChange, so no polling and no unbounded repeat
            }
            is BoundedInstallOutcome.Stale -> Unit // newer intent won; it owns the queue state
            else -> {
                playerQueueNeedsSync = true // explicit recoverable state: nothing was published
                Log.w(TAG, "bounded window operation ($operation) not verified: $result")
            }
        }
    }

    /** One policy-driven window adjustment around the PROVEN current occurrence, if none is in flight. */
    private fun maintainBoundedWindow() {
        if (mediaController == null) return
        val port = physicalPort ?: return
        val result = physicalWindowAdapter.maintain(
            player = port,
            queue = playbackQueue,
            repeatMode = repeatMode,
            verifier = boundedVerifier(port),
            onOutcome = boundedOutcomeHandler("maintain"),
        )
        ValidationTrace.baselineObservation("maintain", playbackQueue.size, result)
        when (result) {
            is MaintenanceResult.Unprovable -> {
                if (result.reason == "id_mismatch" || result.reason == "outside_window") recoverBoundedFromUnprovable(port)
            }
            is MaintenanceResult.Failed -> Log.w(TAG, "bounded window maintenance failed: ${result.cause.message}")
            else -> Unit
        }
    }

    private fun recoverBoundedFromUnprovable(port: PhysicalWindowPort) {
        Log.w(TAG, "[bounded] current occurrence unprovable; bounded re-anchor around ${physicalWindowAdapter.trackedLogical}")
        val result = physicalWindowAdapter.recoverUnprovable(
            player = port,
            queue = playbackQueue,
            repeatMode = repeatMode,
            verifier = boundedVerifier(port),
            onOutcome = boundedOutcomeHandler("recover_unprovable"),
        )
    }

    /** Media3 advanced natively inside the verified window: translate it, keep the window topped up, never re-push. */
    private fun onBoundedAutomaticTransition() {
        if (mediaController == null) return
        val port = physicalPort ?: return
        when (val resolution = physicalWindowAdapter.resolveCurrent(port)) {
            is CurrentResolution.Proven -> maintainBoundedWindow()
            is CurrentResolution.Unproven ->
                if (physicalWindowAdapter.state is WindowInstallState.Active) recoverBoundedFromUnprovable(port)
                else Log.d(TAG, "[bounded] automatic transition while the window is unverified (${resolution.reason}); state follows the tracked occurrence")
        }
    }

    /**
     * Exact logical navigation in a bounded window: a seek at the mapped physical index when the VERIFIED window holds the target,
     * otherwise a bounded re-anchor around the exact target. Chosen behavior for an unverified mapping: re-anchor around the exact
     * logical target (never an estimated index, never the full queue). Play intent is applied exactly like the Full branch.
     */
    private fun seekBoundedToLogical(controller: MediaController, logicalIndex: Int, shouldPlay: Boolean, forcePlay: Boolean?, positionMs: Long = 0L) {
        val port = requireBoundedPort()
        // A newer selection retires any Play that an older one still has armed behind its window verification.
        val epoch = ++selectionPlayEpoch
        armedPlayEpoch = NO_ARMED_PLAY
        val startIfStillCurrent = {
            if (armedPlayEpoch == epoch && selectionPlayEpoch == epoch && mediaController === controller) {
                armedPlayEpoch = NO_ARMED_PLAY
                controller.play()
                syncNowPlayingState()
            }
        }
        val result = physicalWindowAdapter.navigateTo(
            player = port,
            queue = playbackQueue,
            target = logicalIndex,
            positionMs = positionMs.coerceAtLeast(0L),
            repeatMode = repeatMode,
            verifier = boundedVerifier(port),
            onSeekApplied = startIfStillCurrent,
            onOutcome = boundedOutcomeHandler("navigate", onVerified = startIfStillCurrent),
        )
        var armPlay = false
        when (result) {
            is BoundedNavigationResult.SeekIssued -> Unit
            // Held: the seek is applied when the in-flight adjustment settles. Playing now would play the stale occurrence.
            is BoundedNavigationResult.Held -> armPlay = shouldPlay && !controller.isPlaying
            is BoundedNavigationResult.Reanchoring -> {
                playerQueueNeedsSync = true // dirty until the re-anchored window is verified
                controller.prepare()
                // A paused (or idle) player must not start an unverified window: Play is armed and fires once it is verified.
                armPlay = shouldPlay && !controller.isPlaying
            }
            is BoundedNavigationResult.Rejected, is BoundedNavigationResult.Failed -> {
                Log.w(TAG, "[bounded] navigation to $logicalIndex not performed: $result")
                return // the current track is preserved; nothing was changed
            }
        }
        if (armPlay) {
            armedPlayEpoch = epoch
        } else if (shouldPlay) {
            controller.play()
        } else if (forcePlay == false) {
            controller.pause()
        }
        syncNowPlayingState()
        saveSessionAsync()
        if (result is BoundedNavigationResult.SeekIssued) maintainBoundedWindow()
    }

    /**
     * Media3 reached the end of the PHYSICAL window. If that is the genuine logical end (repeat OFF) playback correctly stops;
     * otherwise the window was outrun (very short tracks) and playback continues with a bounded re-anchor at the next logical
     * occurrence per the logical navigation rules (repeat ALL wraps through the complete queue).
     */
    private fun continueAfterPhysicalWindowEnd() {
        val controller = mediaController ?: return
        val port = physicalPort ?: return
        // The physical player is observed directly: an ENDED that arrives while it is not on its last item is stale, never a window end.
        if (port.currentMediaItemIndex < port.mediaItemCount - 1) return
        val current = physicalWindowAdapter.currentLogical(port) ?: return
        val next = QueueNavigator.automaticNextIndex(playbackQueue.size, current, repeatMode) ?: return
        Log.w(TAG, "[bounded] physical window ended before the logical end; continuing at $next")
        seekToPlaybackIndex(controller, next, forcePlay = true)
    }

    private fun reanchorBoundedAfterReconnect(controller: MediaController) {
        val occurrence = _nowPlayingState.value.currentIndex.takeIf { it in playbackQueue.indices }
            ?: physicalWindowAdapter.trackedLogical.takeIf { it in playbackQueue.indices }
            ?: return
        val positionMs = _nowPlayingState.value.positionMs.coerceAtLeast(0L)
        val port = requireBoundedPort()
        val result = physicalWindowAdapter.navigateTo(
            player = port,
            queue = playbackQueue,
            target = occurrence,
            positionMs = positionMs,
            repeatMode = repeatMode,
            verifier = boundedVerifier(port),
            onOutcome = boundedOutcomeHandler("reconnect"),
        )
        if (result is BoundedNavigationResult.Reanchoring) controller.prepare()
        syncNowPlayingState(notifyStats = false)
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
        // Tracer: fingerprint the list BEFORE the timing window opens, so its cost is not charged to materialization or install.
        PlaybackStartupTrace.onInstallStarting(operation, songs)
        val totalStartedAtMs = SystemClock.elapsedRealtime()
        val materializationStartedAtMs = SystemClock.elapsedRealtime()
        val materialization = materializeMediaItems(songs)
        val materializationElapsedMs = SystemClock.elapsedRealtime() - materializationStartedAtMs
        PlaybackStartupTrace.onMaterialized(operation, materialization.mediaItems.size, materialization.cacheHits, materialization.cacheMisses)
        val mutationStartedAtMs = SystemClock.elapsedRealtime()
        setMediaItems(materialization.mediaItems, startIndex, positionMs)
        val mutationElapsedMs = SystemClock.elapsedRealtime() - mutationStartedAtMs
        PlaybackStartupTrace.onPlaylistInstalled(operation, materialization.mediaItems.size, mutationElapsedMs)
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

    /**
     * One bounded Media3 `replaceMediaItems(from, toExclusive, songs)` (empty [songs] removes, `from == to` inserts) with the
     * same debug accounting as the other measured mutations: materialization is timed separately from the Media3 mutation.
     */
    private fun MediaController.replaceMeasuredMediaItems(
        operation: String,
        fromIndex: Int,
        toIndexExclusive: Int,
        songs: List<Song>,
    ) {
        if (songs.isEmpty()) {
            if (toIndexExclusive > fromIndex) removeMediaItems(fromIndex, toIndexExclusive)
            return
        }
        if (!BuildConfig.DEBUG) {
            replaceMediaItems(fromIndex, toIndexExclusive, materializeMediaItems(songs).mediaItems)
            return
        }
        val totalStartedAtMs = SystemClock.elapsedRealtime()
        val materializationStartedAtMs = SystemClock.elapsedRealtime()
        val materialization = materializeMediaItems(songs)
        val materializationElapsedMs = SystemClock.elapsedRealtime() - materializationStartedAtMs
        val mutationStartedAtMs = SystemClock.elapsedRealtime()
        replaceMediaItems(fromIndex, toIndexExclusive, materialization.mediaItems)
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

}

package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/**
 * A fresh playback start that the user ACCEPTED while no usable [androidx.media3.session.MediaController] existed.
 *
 * The accepted selection's queue, playback order (including an already-drawn shuffle order), occurrence, source and Now Playing
 * state were installed into [PlayerController]'s logical fields at accept time. A deferred start therefore carries no queue copy:
 * it records only that the PHYSICAL install is still owed, which occurrence it targets, and how to install it. At reconnect the
 * controller installs the CURRENT logical queue, so nothing is re-derived (no re-shuffle, no second generation bump) and queue
 * changes made while disconnected are included rather than overwritten by a stale snapshot.
 *
 * [startSongId] is an identity check, never a lookup key: the occurrence is positional ([startPlaybackIndex]); when the indexed
 * song no longer matches, the command is discarded rather than re-resolved by id (duplicates make an id ambiguous).
 */
internal sealed interface DeferredStart {
    /** Monotonic id of the user selection that created this command. Never reused, never reset. */
    val selectionId: Long

    /** The occurrence (index into the logical playback queue) the selection starts at. */
    val startPlaybackIndex: Int

    /** Identity check for [startPlaybackIndex] at install time. */
    val startSongId: Long

    /** The same command aimed at another occurrence of the same logical queue (a queue jump made while still disconnected). */
    fun retargeted(playbackIndex: Int, songId: Long): DeferredStart

    /** Album / artist / folder / playlist / collection / library / search-replace start (the common queue path). */
    data class LibraryQueue(
        override val selectionId: Long,
        override val startPlaybackIndex: Int,
        override val startSongId: Long,
    ) : DeferredStart {
        override fun retargeted(playbackIndex: Int, songId: Long) = copy(startPlaybackIndex = playbackIndex, startSongId = songId)
    }

    /** Search tap that preserves the existing queue (history + tapped song + continuation). */
    data class PreserveSearch(
        override val selectionId: Long,
        override val startPlaybackIndex: Int,
        override val startSongId: Long,
    ) : DeferredStart {
        override fun retargeted(playbackIndex: Int, songId: Long) = copy(startPlaybackIndex = playbackIndex, startSongId = songId)
    }

    /** ACTION_VIEW audio: a one-item external queue, never persisted as a library session. */
    data class ExternalUri(
        override val selectionId: Long,
        override val startSongId: Long,
        val uri: String,
        val displayName: String?,
    ) : DeferredStart {
        override val startPlaybackIndex: Int get() = 0
        override fun retargeted(playbackIndex: Int, songId: Long) = this // a one-item queue has exactly one occurrence
    }
}

/** A Recently-Played style queue jump captured while disconnected with no deferred start to retarget. */
internal data class PendingQueueJump(
    val songId: Long,
    val resolvedPlaybackIndex: Int,
)

/**
 * A deferred Next/Previous, anchored to the exact occurrence it was issued from. It executes only if that occurrence is still the
 * resolved current one when the controller reconnects; otherwise it is a stale skip and is discarded (never re-aimed by song id).
 */
internal data class PendingNavigation(
    val intent: NavigationIntent,
    val anchorPlaybackIndex: Int,
    val anchorSongId: Long,
)

/** Captures a navigation intent only when the current occurrence is provable; an unresolvable occurrence is never guessed. */
internal fun capturePendingNavigation(
    intent: NavigationIntent,
    playbackQueue: List<Song>,
    currentPlaybackIndex: Int?,
): PendingNavigation? {
    val index = currentPlaybackIndex?.takeIf { it in playbackQueue.indices } ?: return null
    return PendingNavigation(intent, anchorPlaybackIndex = index, anchorSongId = playbackQueue[index].id)
}

/** The intent to execute, or null when the anchor occurrence is no longer the resolved current one (a stale skip). */
internal fun resolvePendingNavigation(
    navigation: PendingNavigation,
    playbackQueue: List<Song>,
    currentPlaybackIndex: Int?,
): NavigationIntent? {
    val index = currentPlaybackIndex ?: return null
    if (index != navigation.anchorPlaybackIndex) return null
    if (playbackQueue.getOrNull(index)?.id != navigation.anchorSongId) return null
    return navigation.intent
}

/** The occurrence a deferred start installs at, or why it cannot be proven. */
internal sealed interface DeferredStartResolution {
    data class Install(val playbackIndex: Int) : DeferredStartResolution
    data class Reject(val reason: String) : DeferredStartResolution
}

/**
 * Establishes the exact occurrence a deferred start installs at. The recorded [DeferredStart.startPlaybackIndex] is the authority;
 * [recordedQueueGeneration] is the queue generation in force when that index was recorded (at acceptance, or when a queue jump
 * retargeted the start), and every structural queue mutation bumps the generation.
 *
 *  - Generation unchanged: no structural mutation happened since the index was recorded, so the recorded index IS the occurrence.
 *    It must still hold the selected song; otherwise the command is rejected.
 *  - Generation changed: a structural edit happened and nothing tracks the selected OCCURRENCE across it (a song id that still
 *    appears once does not prove it is the same entry: the selected copy may have been removed and the same id reinserted, or a
 *    sibling copy may remain). The start is therefore rejected conservatively, whatever the queue now holds. Per-occurrence
 *    tracking across offline edits belongs with the offline-mutation reconciliation work.
 */
internal fun resolveDeferredStart(
    command: DeferredStart,
    recordedQueueGeneration: Long,
    currentQueueGeneration: Long,
    playbackQueue: List<Song>,
): DeferredStartResolution {
    if (recordedQueueGeneration != currentQueueGeneration) {
        return DeferredStartResolution.Reject("queue structure changed after the occurrence was recorded")
    }
    val index = command.startPlaybackIndex
    return if (playbackQueue.getOrNull(index)?.id == command.startSongId) {
        DeferredStartResolution.Install(index)
    } else {
        DeferredStartResolution.Reject("recorded occurrence $index no longer holds the selected song")
    }
}
/** What [DeferredPlaybackCommands.captureQueueJump] did with a queue jump. */
internal enum class JumpCapture {
    /** A start was already deferred: it now targets the jumped-to occurrence (the jump is newer than the start). */
    RetargetedStart,

    /** No start was deferred: the jump itself is held (single slot) and executes after reconnect. */
    DeferredJump,
}

/** Everything taken from [DeferredPlaybackCommands] in one consume-once drain. */
internal data class DeferredDrain(
    val start: DeferredStart?,
    val startQueueGeneration: Long,
    val queueJump: PendingQueueJump?,
    val seek: PendingSeek?,
    val navigation: PendingNavigation?,
    val playWhenReady: Boolean?,
)

/**
 * THE bounded, latest-wins authority for every playback command accepted while the controller is unavailable. It replaces the
 * former independent slots (ordinary / search-preserve / external request, queue jump, transport intents) whose fixed drain
 * precedence let an older request of a "stronger" type beat a newer selection and silently dropped every transport command
 * issued after a start.
 *
 * Exactly five O(1) slots exist; nothing is ever appended to a list, so memory is bounded however many commands are issued.
 *
 * Policy (the user's LATEST explicit intent always wins; precedence is never by command type):
 *  - [acceptStart]: replaces any earlier start and DISCARDS every older queue jump / seek / navigation / play-pause intent: they
 *    referred to a queue that this selection replaced, and must never replay against it. The new start plays by default.
 *  - [captureQueueJump] after a deferred start: the jump is newer than the start, so it RETARGETS the start's occurrence (same
 *    logical queue). With no deferred start it is held as a jump. Either way older seek/navigation are discarded (they were relative
 *    to the previous occurrence). Play/pause intent survives.
 *  - [capturePlayWhenReady]: latest wins; applied after the start is installed, so a later Pause leaves the installed queue paused
 *    and a later Play re-asserts play.
 *  - [captureSeek]: latest wins, bound to its occurrence (stale-validated at execution, see [PendingTransport.resolveSeek]).
 *  - [captureNavigation]: latest direction wins, bound to its anchor occurrence (see [resolvePendingNavigation]).
 *  - [take]: consume-once. It hands out every slot and empties them, so a command can never execute twice.
 *
 * Drain order applied by the owner: install the start (else dirty-queue sync and the held jump), then navigation, then seek, then
 * play/pause. Not thread-safe by design: main-thread confined like the rest of the controller state.
 */
internal class DeferredPlaybackCommands {
    private var lastSelectionId = 0L

    var start: DeferredStart? = null
        private set

    /** Queue generation in force when [start]'s occurrence index was last recorded (acceptance or retarget). */
    var startQueueGeneration: Long = 0L
        private set
    var queueJump: PendingQueueJump? = null
        private set
    var seek: PendingSeek? = null
        private set
    var navigation: PendingNavigation? = null
        private set
    var playWhenReady: Boolean? = null
        private set

    val isEmpty: Boolean
        get() = start == null && queueJump == null && seek == null && navigation == null && playWhenReady == null

    /** Records a newly accepted selection. [create] receives the new monotonic selection id. */
    fun acceptStart(create: (selectionId: Long) -> DeferredStart): DeferredStart = acceptStart(0L, create)

    /** [queueGeneration] is the generation in force when the start's occurrence index is recorded (see [resolveDeferredStart]). */
    fun acceptStart(queueGeneration: Long, create: (selectionId: Long) -> DeferredStart): DeferredStart {
        val command = create(++lastSelectionId)
        start = command
        startQueueGeneration = queueGeneration
        queueJump = null
        seek = null
        navigation = null
        playWhenReady = null
        return command
    }

    fun captureQueueJump(songId: Long, playbackIndex: Int, queueGeneration: Long? = null): JumpCapture {
        seek = null
        navigation = null
        val pending = start
        return if (pending != null) {
            start = pending.retargeted(playbackIndex, songId)
            queueGeneration?.let { startQueueGeneration = it }
            queueJump = null
            JumpCapture.RetargetedStart
        } else {
            queueJump = PendingQueueJump(songId = songId, resolvedPlaybackIndex = playbackIndex)
            JumpCapture.DeferredJump
        }
    }

    fun captureSeek(pending: PendingSeek) { seek = pending }

    fun captureNavigation(pending: PendingNavigation) { navigation = pending }

    fun capturePlayWhenReady(play: Boolean) { playWhenReady = play }

    /**
     * The play/pause state the user currently intends, for deriving a toggle: an explicit deferred intent wins, then a deferred
     * start (which implies play), then the last known real state.
     */
    fun intendedPlayWhenReady(currentlyPlaying: Boolean): Boolean =
        playWhenReady ?: if (start != null) true else currentlyPlaying

    /** Consume-once: returns every slot and empties them. */
    fun take(): DeferredDrain {
        val drained = DeferredDrain(start, startQueueGeneration, queueJump, seek, navigation, playWhenReady)
        clear()
        return drained
    }

    /**
     * Positional continuity of the pending start across ONE structural queue edit whose exact index transformation is known.
     * [generationBeforeEdit] is the queue generation before the edit bumped it and [generationAfterEdit] the one after. The start
     * follows the edit only if its recorded index was valid at [generationBeforeEdit] (an earlier opaque edit already made it
     * unprovable and it stays rejected by [resolveDeferredStart]). [newIndex] is where the selected occurrence went, or null
     * when the edit removed it, which cancels the start. Returns true when a pending start was rebased or cancelled.
     */
    fun rebaseStart(generationBeforeEdit: Long, generationAfterEdit: Long, newIndex: Int?): Boolean {
        val pending = start ?: return false
        if (startQueueGeneration != generationBeforeEdit) return false
        if (newIndex == null) {
            start = null
            startQueueGeneration = 0L
            return true
        }
        start = pending.retargeted(newIndex, pending.startSongId)
        startQueueGeneration = generationAfterEdit
        return true
    }

    /** Teardown: nothing may be resurrected after this. Selection ids keep increasing. */
    fun clear() {
        start = null
        startQueueGeneration = 0L
        queueJump = null
        seek = null
        navigation = null
        playWhenReady = null
    }
}

/**
 * Exact playback-index transformations of the structural queue edits, as pure functions: for an old playback index, where the same
 * ENTRY (not merely the same song id) sits after the edit, or null when the edit removed it. Only edits whose transformation is
 * fully known by construction appear here; any other edit leaves the deferred start to the conservative generation rejection.
 */
internal object OccurrenceMap {
    fun identity(): (Int) -> Int? = { it }

    /** [count] entries inserted so the first of them lands at [at]. */
    fun insert(at: Int, count: Int): (Int) -> Int? = { if (it < at) it else it + count }

    fun remove(position: Int): (Int) -> Int? = {
        when {
            it < position -> it
            it == position -> null
            else -> it - 1
        }
    }

    /** Every entry whose position is in [removedPositions] (any order) is gone; the rest close up. */
    fun removeAll(removedPositions: Collection<Int>): (Int) -> Int? {
        val sorted = removedPositions.toSortedSet()
        return { index -> if (index in sorted) null else index - sorted.count { it < index } }
    }

    /** The entry at [from] is lifted out and re-inserted at [to]. */
    fun move(from: Int, to: Int): (Int) -> Int? = { index ->
        when {
            index == from -> to
            from < to && index in (from + 1)..to -> index - 1
            to < from && index in to until from -> index + 1
            else -> index
        }
    }

    fun swap(a: Int, b: Int): (Int) -> Int? = { index -> if (index == a) b else if (index == b) a else index }

    /** Everything strictly before [current] removed. */
    fun clearEarlier(current: Int): (Int) -> Int? = { if (it < current) null else it - current }

    /** Everything strictly after [current] removed. */
    fun clearUpNext(current: Int): (Int) -> Int? = { if (it > current) null else it }
}
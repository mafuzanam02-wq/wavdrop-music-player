package com.launchpoint.wavdrop.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * CF-2M3: the owner of the logical play-when-ready state behind a [SessionFacade]. Implemented only by [PlayerEngine].
 * Reads must be cheap and side-effect free (they run inside `getState`).
 */
internal interface LogicalPlayWhenReadyOwner {
    val logicalPlayWhenReady: Boolean
    val logicalPlayWhenReadyChangeReason: Int
    val logicalPlaybackSuppressionReason: Int

    /** A logical user/session request. The owner decides what the physical player must do. */
    fun requestPlayWhenReady(playWhenReady: Boolean)
}

/**
 * CF-2M2: the stable session-facing [Player] identity. It forwards every command to, and derives its whole state (real
 * Timeline, real live position, current item, playWhenReady/isPlaying, repeat, parameters, commands, metadata, tracks,
 * audio attributes, device info, audio session id, error) from, ONE wrapped physical player. It synthesizes nothing.
 *
 * Today exactly one physical player exists and the delegate never changes in production. The single narrow future seam is
 * [replaceDelegate] (internally `ForwardingSimpleBasePlayer.setPlayer`); it exists so tests can exercise a delegate swap
 * in isolation. Nothing in production calls it (CF-2M4+ own promotion).
 *
 * Looper contract: the façade runs on the wrapped player's application looper and a replacement must share it. A mismatch
 * is rejected with an explicit [IllegalArgumentException] before any state changes; there is no cross-thread workaround.
 *
 * Wrapper order (see PlaybackService): physical ExoPlayer -> SessionFacade -> [PreviousBehaviorPlayer] -> MediaLibrarySession.
 * The WavDrop policy wrapper stays OUTSIDE the façade, so its decisions (`controllerForCurrentRequest`, previous semantics)
 * run synchronously on the session request exactly as before, and are not duplicated inside `handle*`.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SessionFacade private constructor(
    delegate: Player,
    private val occurrenceIds: OccurrenceIdSource,
) : ForwardingSimpleBasePlayer(OccurrenceIdentityPlayer(delegate, occurrenceIds)) {

    constructor(delegate: Player) : this(delegate, OccurrenceIdSource())

    // CF-2M5: stable timeline identity across a delegate swap (see getState). Declared BEFORE the init block below, which reads state.
    private var lastPresentedPlaylist: List<SimpleBasePlayer.MediaItemData>? = null
    private var aliasPending = false
    private var windowAlias: Map<Any, Any> = emptyMap()
    private var periodAlias: Map<Any, Any> = emptyMap()

    // SimpleBasePlayer snapshots its state lazily on first access. Take the snapshot at construction so every later physical
    // change (even before the first session read) is a real diff against a known state, never silently folded into the first read.
    init { playbackState }

    // P5-E: bounded-queue presentation. Null (the default) is the unchanged forwarder. See LogicalQueueTimeline.kt.
    private var logicalPresentation: LogicalQueuePresentation? = null
    private var logicalCommands: LogicalQueueCommands? = null

    /**
     * Binds the logical queue source and the logical command port. While [LogicalQueuePresentation.snapshot] is non-null the
     * façade presents the complete LOGICAL queue (lazy timeline, logical current index, logical repeat/shuffle) instead of the
     * bounded physical window, derives position/duration/play state from the physical CURRENT, and routes every
     * index-bearing external command to [commands] instead of the physical player. Structural playlist edits are unavailable
     * and rejected in that mode: the physical window is never edited as if it were the whole queue.
     */
    fun bindLogicalQueue(presentation: LogicalQueuePresentation?, commands: LogicalQueueCommands?) {
        logicalPresentation = presentation
        logicalCommands = commands
        invalidateState()
    }

    private fun boundedSnapshot(): LogicalQueueSnapshot? = logicalPresentation?.snapshot()

    // Set only for the duration of one replaceDelegate(..., presentAsAutoTransition = true) call (see getState).
    private var pinnedAutoTransitionPositionMs: Long? = null

    // CF-2M3: optional logical play-when-ready owner (the PlayerEngine). Null (CF-2M2 shape) means a pure forwarder.
    private var playWhenReadyOwner: LogicalPlayWhenReadyOwner? = null

    /**
     * CF-2M3: binds the single owner of the LOGICAL play-when-ready / suppression state. With physical players that do not
     * handle audio focus themselves, only the engine knows "playWhenReady is true but suppressed by a transient focus loss",
     * so the façade presents the owner's state for exactly those three fields and routes `setPlayWhenReady` to it. Every
     * other field still comes from the physical delegate. Pass null to unbind.
     */
    fun bindPlayWhenReadyOwner(owner: LogicalPlayWhenReadyOwner?) {
        playWhenReadyOwner = owner
        invalidateState()
    }

    /** Re-reads the bound owner's state and notifies listeners of the real diff (called by the owner after it changes). */
    fun invalidateLogicalState() = invalidateState()

    /** The physical player this façade currently forwards to. Read-only; for diagnostics and tests. */
    val delegatePlayer: Player get() = (getPlayer() as ForwardingPlayer).wrappedPlayer

    /**
     * Future promotion seam (unused by production in CF-2M2). Makes [newDelegate] the only physical player this façade
     * observes and commands. Replacing with the same instance is a no-op; a looper mismatch is rejected loudly.
     *
     * By default Media3 reports the swap as the state diff it really is: `DISCONTINUITY_REASON_INTERNAL` plus
     * `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED` when the current item differs. With [presentAsAutoTransition] the same
     * single diff is instead reported as `DISCONTINUITY_REASON_AUTO_TRANSITION` (+ `MEDIA_ITEM_TRANSITION_REASON_AUTO`), which
     * is how a future logical promotion must look to controllers. This pins the REASON of the one diff that Media3 itself
     * derives; it fabricates no event.
     */
    fun replaceDelegate(newDelegate: Player, presentAsAutoTransition: Boolean = false) {
        val current = delegatePlayer
        if (current === newDelegate) return
        require(newDelegate.applicationLooper === current.applicationLooper) {
            "SessionFacade delegate must share the application looper (current=${current.applicationLooper}, " +
                "replacement=${newDelegate.applicationLooper})"
        }
        pinnedAutoTransitionPositionMs = if (presentAsAutoTransition) newDelegate.currentPosition else null
        aliasPending = true // the next state evaluation (setPlayer invalidates synchronously) establishes the uid aliases
        try {
            setPlayer(OccurrenceIdentityPlayer(newDelegate, occurrenceIds))
        } finally {
            // setPlayer invalidates synchronously, so the pin has been consumed by exactly that one state evaluation.
            pinnedAutoTransitionPositionMs = null
        }
    }

    /**
     * CF-2M6: invoked synchronously BEFORE a command that changes the current player's transport or queue is forwarded to the
     * delegate. The engine uses it to cut a retiring player first, so no command can ever race a still-audible A (pause is
     * covered at the engine's own play-state boundary). Null (the CF-2M2 shape) means a pure forwarder.
     */
    var commandBoundary: ((PromotionInterruption) -> Unit)? = null

    private fun boundary(interruption: PromotionInterruption) { commandBoundary?.invoke(interruption) }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (boundedSnapshot() != null) return handleBoundedSeek(mediaItemIndex, positionMs, seekCommand)
        boundary(
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> PromotionInterruption.Navigation
                else -> PromotionInterruption.Seek
            },
        )
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    /** A structural or otherwise unsupported command while the queue is presented logically: refused loudly, never a silent success. */
    private fun rejectBounded(what: String): ListenableFuture<*> =
        Futures.immediateFailedFuture<Any>(UnsupportedOperationException("$what is not available while the queue is bounded"))

    /**
     * External seeks while the queue is presented logically. The index a controller sends is LOGICAL: navigation goes to the
     * logical command port (which owns the queue order, repeat/shuffle and the physical window); only a seek INSIDE the current
     * item reaches the physical player, at the physical index it really has.
     */
    private fun handleBoundedSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val commands = logicalCommands ?: return rejectBounded("seek")
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                boundary(PromotionInterruption.Navigation)
                commands.skipToNext()
                Futures.immediateVoidFuture()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                boundary(PromotionInterruption.Navigation)
                commands.skipToPrevious()
                Futures.immediateVoidFuture()
            }
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                val explicitPositionMs = if (seekCommand == Player.COMMAND_SEEK_TO_DEFAULT_POSITION) androidx.media3.common.C.TIME_UNSET else positionMs
                val currentLogical = boundedSnapshot()?.currentIndex
                if (mediaItemIndex == currentLogical) {
                    // The current item at a position (or its default position): not a navigation, so no queue authority is involved.
                    boundary(PromotionInterruption.Seek)
                    val physical = delegatePlayer
                    physical.seekTo(
                        physical.currentMediaItemIndex,
                        if (explicitPositionMs == androidx.media3.common.C.TIME_UNSET) 0L else explicitPositionMs,
                    )
                } else {
                    boundary(PromotionInterruption.Navigation)
                    // The LOGICAL index and the requested position travel together (TIME_UNSET = the item's default position).
                    commands.seekToQueueItem(mediaItemIndex, explicitPositionMs)
                }
                Futures.immediateVoidFuture()
            }
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD -> {
                boundary(PromotionInterruption.Seek)
                val physical = delegatePlayer
                physical.seekTo(physical.currentMediaItemIndex, positionMs)
                Futures.immediateVoidFuture()
            }
            else -> rejectBounded("seek($seekCommand)")
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        if (boundedSnapshot() != null) {
            boundary(PromotionInterruption.RepeatChanged)
            logicalCommands?.setRepeatMode(repeatMode) ?: return rejectBounded("setRepeatMode")
            return Futures.immediateVoidFuture()
        }
        boundary(PromotionInterruption.RepeatChanged)
        return super.handleSetRepeatMode(repeatMode)
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (boundedSnapshot() != null) {
            boundary(PromotionInterruption.ShuffleChanged)
            logicalCommands?.setShuffleModeEnabled(shuffleModeEnabled) ?: return rejectBounded("setShuffleModeEnabled")
            return Futures.immediateVoidFuture()
        }
        boundary(PromotionInterruption.ShuffleChanged)
        return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
    }

    override fun handleSetMediaItems(mediaItems: MutableList<androidx.media3.common.MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (boundedSnapshot() != null) return rejectBounded("handleSetMediaItems")
        boundary(PromotionInterruption.QueueMutated)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<androidx.media3.common.MediaItem>): ListenableFuture<*> {
        if (boundedSnapshot() != null) return rejectBounded("handleAddMediaItems")
        boundary(PromotionInterruption.QueueMutated)
        return super.handleAddMediaItems(index, mediaItems)
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        if (boundedSnapshot() != null) return rejectBounded("handleMoveMediaItems")
        boundary(PromotionInterruption.QueueMutated)
        return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<androidx.media3.common.MediaItem>): ListenableFuture<*> {
        if (boundedSnapshot() != null) return rejectBounded("handleReplaceMediaItems")
        boundary(PromotionInterruption.QueueMutated)
        return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (boundedSnapshot() != null) return rejectBounded("handleRemoveMediaItems")
        boundary(PromotionInterruption.QueueMutated)
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }

    override fun handleStop(): ListenableFuture<*> {
        boundary(PromotionInterruption.FacadeCommand)
        return super.handleStop()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val owner = playWhenReadyOwner ?: return super.handleSetPlayWhenReady(playWhenReady)
        owner.requestPlayWhenReady(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun getState(): SimpleBasePlayer.State {
        val physical = super.getState()
        val owner = playWhenReadyOwner
        val withOwner = if (owner == null) physical else physical.buildUpon()
            .setPlayWhenReady(owner.logicalPlayWhenReady, owner.logicalPlayWhenReadyChangeReason)
            .setPlaybackSuppressionReason(owner.logicalPlaybackSuppressionReason)
            .build()
        val bounded = boundedSnapshot()
        val base = if (bounded != null) {
            // The uid aliasing below describes physical mirrors of ONE physical queue; the logical timeline has its own stable
            // identity and does not depend on which physical player is wrapped, so there is nothing to alias (and no list to build).
            lastPresentedPlaylist = null
            aliasPending = false
            clearAliases()
            withLogicalQueue(withOwner, bounded)
        } else {
            withStableTimelineIdentity(withOwner)
        }
        val pinnedPositionMs = pinnedAutoTransitionPositionMs ?: return base
        return base.buildUpon()
            .setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, pinnedPositionMs)
            .build()
    }


    /**
     * P5-E: replaces the (bounded) physical playlist of [physical] by the lazy LOGICAL timeline. Everything that describes the
     * playing item (position, buffering, play state, tracks, metadata) stays the physical CURRENT's; what is rewritten is only
     * what has a queue meaning: the timeline, the current index, repeat/shuffle and the commands that depend on them.
     */
    private fun withLogicalQueue(physical: SimpleBasePlayer.State, snapshot: LogicalQueueSnapshot): SimpleBasePlayer.State {
        val physicalTimeline = physical.timeline
        val physicalIndex = physical.currentMediaItemIndex
        val currentDurationUs = if (!physicalTimeline.isEmpty && physicalIndex in 0 until physicalTimeline.windowCount) {
            physicalTimeline.getWindow(physicalIndex, androidx.media3.common.Timeline.Window()).durationUs
        } else {
            androidx.media3.common.C.TIME_UNSET
        }
        val timeline = LogicalQueueTimeline(snapshot.songs, snapshot.generation, snapshot.currentIndex, currentDurationUs)
        val repeat = when (snapshot.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        val hasNext = timeline.getNextWindowIndex(snapshot.currentIndex, repeat, false) != androidx.media3.common.C.INDEX_UNSET
        val hasPrevious = timeline.getPreviousWindowIndex(snapshot.currentIndex, repeat, false) != androidx.media3.common.C.INDEX_UNSET
        val commands = physical.availableCommands.buildUpon()
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .remove(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrevious)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
        return physical.buildUpon()
            .setPlaylist(timeline, physical.currentTracks, physical.currentMetadata)
            .setCurrentMediaItemIndex(snapshot.currentIndex)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(snapshot.shuffleEnabled)
            .setAvailableCommands(commands)
            .build()
    }
    /**
     * CF-2M5: two physical players describe the SAME logical queue with different internal Timeline uids (every ExoPlayer owns
     * its own media-source holders), so a delegate swap would otherwise surface a `timeline(PLAYLIST_CHANGED)` that a native
     * gapless transition never produces. When the new delegate mirrors the previously presented queue (same size and an equal
     * MediaItem at every index) its window/period uids are aliased to the uids already presented, so the Timeline the controllers
     * hold is unchanged and only the real AUTO transition remains. A queue that does not match is NOT aliased: Media3 then
     * reports the genuine timeline change. Uids of items inserted afterwards are never aliased.
     */
    private fun withStableTimelineIdentity(state: SimpleBasePlayer.State): SimpleBasePlayer.State {
        val playlist = state.playlist
        if (aliasPending) {
            aliasPending = false
            val previous = lastPresentedPlaylist
            if (previous != null) establishAliases(previous, playlist) else clearAliases()
        }
        if (windowAlias.isEmpty() && periodAlias.isEmpty()) {
            lastPresentedPlaylist = playlist
            return state
        }
        // Re-label uids through a Timeline VIEW (O(1) per lookup, O(N) only when something reads every window), never by
        // rebuilding a MediaItemData list: that list-built PlaylistTimeline is the structure whose Pair(window, period) hash keys
        // used to cancel (see OccurrenceTimelineIdentity.kt). The pre-promotion state is timeline-backed too, so the two compare equal.
        val aliased = state.buildUpon()
            .setPlaylist(AliasedTimeline(state.timeline, windowAlias, periodAlias), state.currentTracks, state.currentMetadata)
            .build()
        lastPresentedPlaylist = aliased.playlist
        return aliased
    }

    private fun clearAliases() {
        windowAlias = emptyMap()
        periodAlias = emptyMap()
    }

    private fun establishAliases(previous: List<SimpleBasePlayer.MediaItemData>, next: List<SimpleBasePlayer.MediaItemData>) {
        if (previous.size != next.size || next.indices.any { previous[it].mediaItem != next[it].mediaItem }) {
            clearAliases()
            return
        }
        val windows = HashMap<Any, Any>()
        val periods = HashMap<Any, Any>()
        for (i in next.indices) {
            val old = previous[i]
            val new = next[i]
            if (old.uid != new.uid) windows[new.uid] = old.uid
            if (old.periods.size == new.periods.size) {
                for (j in new.periods.indices) {
                    if (old.periods[j].uid != new.periods[j].uid) periods[new.periods[j].uid] = old.periods[j].uid
                }
            }
        }
        windowAlias = windows
        periodAlias = periods
    }
}

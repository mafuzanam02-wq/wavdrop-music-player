package com.launchpoint.wavdrop.playback

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
internal class SessionFacade(delegate: Player) : ForwardingSimpleBasePlayer(delegate) {

    // CF-2M5: stable timeline identity across a delegate swap (see getState). Declared BEFORE the init block below, which reads state.
    private var lastPresentedPlaylist: List<SimpleBasePlayer.MediaItemData>? = null
    private var aliasPending = false
    private var windowAlias: Map<Any, Any> = emptyMap()
    private var periodAlias: Map<Any, Any> = emptyMap()

    // SimpleBasePlayer snapshots its state lazily on first access. Take the snapshot at construction so every later physical
    // change (even before the first session read) is a real diff against a known state, never silently folded into the first read.
    init { playbackState }

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
    val delegatePlayer: Player get() = getPlayer()

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
        val current = getPlayer()
        if (current === newDelegate) return
        require(newDelegate.applicationLooper === current.applicationLooper) {
            "SessionFacade delegate must share the application looper (current=${current.applicationLooper}, " +
                "replacement=${newDelegate.applicationLooper})"
        }
        pinnedAutoTransitionPositionMs = if (presentAsAutoTransition) newDelegate.currentPosition else null
        aliasPending = true // the next state evaluation (setPlayer invalidates synchronously) establishes the uid aliases
        try {
            setPlayer(newDelegate)
        } finally {
            // setPlayer invalidates synchronously, so the pin has been consumed by exactly that one state evaluation.
            pinnedAutoTransitionPositionMs = null
        }
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
        val base = withStableTimelineIdentity(withOwner)
        val pinnedPositionMs = pinnedAutoTransitionPositionMs ?: return base
        return base.buildUpon()
            .setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, pinnedPositionMs)
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
        val presented = playlist.map { item ->
            val window = windowAlias[item.uid]
            val periodsNeedAlias = item.periods.any { periodAlias.containsKey(it.uid) }
            if (window == null && !periodsNeedAlias) {
                item
            } else {
                val builder = item.buildUpon()
                if (window != null) builder.setUid(window)
                if (periodsNeedAlias) {
                    builder.setPeriods(item.periods.map { p -> periodAlias[p.uid]?.let { p.buildUpon().setUid(it).build() } ?: p })
                }
                builder.build()
            }
        }
        lastPresentedPlaylist = presented
        return state.buildUpon().setPlaylist(presented).build()
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

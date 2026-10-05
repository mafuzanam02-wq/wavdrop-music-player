package com.launchpoint.wavdrop.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusManager
import androidx.media3.common.util.UnstableApi

/**
 * CF-2M3: OWNERSHIP FOUNDATION for promotable dual-player playback (docs/architecture/CROSSFADE-PROMOTION-FEASIBILITY.md).
 *
 * One engine owns, for the gated two-slot path only:
 *  - two physical players in explicit [PlayerSlotRole]s (CURRENT / NEXT);
 *  - the stable session-facing [SessionFacade] (delegate = CURRENT);
 *  - ONE logical audio-focus owner (a single Media3 [AudioFocusManager]) whose state follows the logical CURRENT player;
 *  - ONE logical noisy-audio owner (a single broadcast receiver);
 *  - the shared audio-session id both physicals were configured with;
 *  - lifecycle: [release] is the only place either physical is released.
 *
 * Physical players here are built with `handleAudioFocus = false` and `handleAudioBecomingNoisy = false`, so neither can
 * compete for focus or pause itself. Because a physical player then cannot report `AUDIO_FOCUS_LOSS` /
 * `TRANSIENT_AUDIO_FOCUS_LOSS`, the engine owns the LOGICAL play-when-ready / suppression state (a port of the mapping in
 * Media3 1.11.1 `ExoPlayerImplInternal.updatePlayWhenReadyWithAudioFocus`), applies it to the physical CURRENT player, and the
 * façade presents it ([LogicalPlayWhenReadyOwner]).
 *
 * What this class deliberately does NOT do in CF-2M3: load or prepare NEXT, mirror or graft a queue, promote, overlap, ramp
 * gains, strip tails, own stats or plan queues. NEXT stays empty, idle, paused and neutral for the whole session. Production
 * never calls [swapRolesForTest].
 *
 * Threading: everything runs on the application looper shared by both physicals (the main thread in production).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlayerEngine<P : Player>(
    context: Context,
    first: P,
    second: P,
    val audioSessionId: Int,
    audioAttributes: AudioAttributes,
    handleAudioBecomingNoisy: Boolean = true,
    private val releasePlayer: (P) -> Unit = { it.release() },
    nextSlotPerfLog: ((String) -> Unit)? = null,
    nextSlotGraftScheduler: NextSlotGraftScheduler? = null,
    /** Test seam: invoked before each synchronous promotion/retirement step; a test throws from it to inject a failure. */
    private val promotionStepHook: ((PromotionStep) -> Unit)? = null,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val table: PlayerSlotTable<P>

    init {
        require(first.applicationLooper === second.applicationLooper) {
            "both physical players must share one application looper"
        }
        require(first.audioSessionId == audioSessionId && second.audioSessionId == audioSessionId) {
            "both physical players must be configured with the shared audio session id $audioSessionId " +
                "before the engine is built (first=${first.audioSessionId}, second=${second.audioSessionId})"
        }
        // CF-2M3 NEXT contract: empty, idle, paused. Anything else means something already used the slot.
        require(second.mediaItemCount == 0 && second.playbackState == Player.STATE_IDLE && !second.playWhenReady) {
            "the NEXT physical player must start empty, idle and paused"
        }
        table = PlayerSlotTable(PlayerSlot(0, first), PlayerSlot(1, second))
    }

    val currentSlot: PlayerSlot<P> get() = table.current
    val nextSlot: PlayerSlot<P> get() = table.next
    val currentPlayer: P get() = table.current.player
    val nextPlayer: P get() = table.next.player

    fun roleOf(slot: PlayerSlot<P>): PlayerSlotRole? = table.roleOf(slot)

    /** The ONE stable session-facing player. Its delegate is CURRENT. */
    val facade: SessionFacade = SessionFacade(first)

    /**
     * CF-2M4: the ONLY owner of NEXT's media lifecycle (prepare B alone, graft, invalidate). It drives the NEXT physical
     * player only; it never starts it, promotes it, requests focus or touches the façade.
     */
    val nextPreparation: NextSlotPreparation<P> = NextSlotPreparation(
        { nextPlayer },
        nextSlotPerfLog,
        nextSlotGraftScheduler,
        canAccept = { !released && !promotionActive },
    )

    // ── logical play-when-ready state (the façade presents it) ────────────────────────────────────────────────────────────

    private var logicalPlayWhenReady: Boolean = first.playWhenReady
    private var logicalChangeReason: Int = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var logicalSuppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private val owner = object : LogicalPlayWhenReadyOwner {
        override val logicalPlayWhenReady: Boolean get() = this@PlayerEngine.logicalPlayWhenReady
        override val logicalPlayWhenReadyChangeReason: Int get() = logicalChangeReason
        override val logicalPlaybackSuppressionReason: Int get() = logicalSuppressionReason
        override fun requestPlayWhenReady(playWhenReady: Boolean) =
            applyRequest(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
    }

    // ── ONE logical audio-focus owner ─────────────────────────────────────────────────────────────────────────────────────

    private val duckControl = object : AudioFocusManager.PlayerControl {
        override fun setVolumeMultiplier(volumeMultiplier: Float) {
            duckMultiplier = volumeMultiplier
            applyVolumes()
        }

        override fun executePlayerCommand(playerCommand: Int) = onFocusCallbackCommand(playerCommand)
    }

    private val focus = AudioFocusManager(appContext, first.applicationLooper, duckControl).also {
        it.setAudioAttributes(audioAttributes)
    }
    private var duckMultiplier: Float = 1f

    /** Diagnostic: how many times the engine released its single focus owner (must end at exactly 1). */
    var focusReleaseCount: Int = 0
        private set

    // ── physical observer (CURRENT only; minimal) ─────────────────────────────────────────────────────────────────────────

    // True only while the engine itself writes the physical play state, so its own echo is not treated as an external command.
    private var writingPhysicalPlayState = false

    private val currentObserver = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = reevaluateFocus()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (writingPhysicalPlayState || released) return
            // Someone wrote the physical CURRENT directly. Route it through the logical owner instead of letting the
            // physical state silently disagree with the logical one.
            if (playWhenReady != physicalPlayTarget()) {
                applyRequest(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
        }
    }

    // ── ONE logical noisy-audio owner ─────────────────────────────────────────────────────────────────────────────────────

    private var noisyReceiver: BroadcastReceiver? = null

    var released: Boolean = false
        private set

    init {
        facade.bindPlayWhenReadyOwner(owner)
        first.addListener(currentObserver)
        setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
    }

    /** The user's "pause when audio output disconnects" preference. One receiver for the whole engine. */
    fun setHandleAudioBecomingNoisy(enabled: Boolean) {
        if (released) return
        if (enabled == (noisyReceiver != null)) return
        if (enabled) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onAudioBecomingNoisy()
                }
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiver = receiver
        } else {
            unregisterNoisyReceiver()
        }
    }

    private fun unregisterNoisyReceiver() {
        val receiver = noisyReceiver ?: return
        noisyReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
    }

    private fun onAudioBecomingNoisy() {
        if (released) return
        applyRequest(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
    }

    // ── focus / play-state mapping (port of ExoPlayerImplInternal.updatePlayWhenReadyWithAudioFocus) ─────────────────────

    /** A play-when-ready request from the user/session/noisy owner. */
    private fun applyRequest(playWhenReady: Boolean, reason: Int) {
        if (released) return
        // ExoPlayerImpl.computePlaybackSuppressionReason: a pause during a transient loss keeps TRANSIENT until focus resolves.
        val suppressionIn =
            if (logicalSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS && !playWhenReady) {
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            } else {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }
        val command = focus.updateAudioFocus(playWhenReady, currentPlayer.playbackState)
        commit(playWhenReady, command, suppressionIn, reason)
    }

    /** The logical CURRENT player's state changed (e.g. IDLE -> BUFFERING): focus must be re-evaluated for it. */
    private fun reevaluateFocus() {
        if (released) return
        val command = focus.updateAudioFocus(logicalPlayWhenReady, currentPlayer.playbackState)
        commit(logicalPlayWhenReady, command, logicalSuppressionReason, logicalChangeReason)
    }

    /** The platform focus callback (loss / regain), delivered by the single [AudioFocusManager]. */
    private fun onFocusCallbackCommand(command: Int) {
        if (released) return
        commit(logicalPlayWhenReady, command, logicalSuppressionReason, logicalChangeReason)
    }

    private fun commit(requestedPlayWhenReady: Boolean, command: Int, suppressionIn: Int, reasonIn: Int) {
        val newPlayWhenReady = requestedPlayWhenReady && command != AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY
        var newReason = when {
            command == AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY -> Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            reasonIn == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            else -> reasonIn
        }
        val newSuppression = when (command) {
            AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK -> Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
            else -> suppressionIn
        }
        // The change reason is only meaningful with a play-when-ready change; never emit a reason-only diff.
        if (newPlayWhenReady == logicalPlayWhenReady) newReason = logicalChangeReason
        logicalPlayWhenReady = newPlayWhenReady
        logicalChangeReason = newReason
        logicalSuppressionReason = newSuppression
        pushPhysicalPlayState()
        facade.invalidateLogicalState()
    }

    private fun physicalPlayTarget(): Boolean =
        logicalPlayWhenReady && logicalSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    /** Only the logical CURRENT physical is ever driven; NEXT is never written. */
    private fun pushPhysicalPlayState() {
        val target = physicalPlayTarget()
        val physical = currentPlayer
        if (physical.playWhenReady == target) return
        writingPhysicalPlayState = true
        try {
            physical.playWhenReady = target
        } finally {
            writingPhysicalPlayState = false
        }
    }

    // ── ONE gain composer (CF-2M5) ────────────────────────────────────────────────────────────────────────────────────────

    // Per-slot fade component (1 = neutral). The ONLY writer of a physical `.volume` is [applyVolumes].
    private val fade = HashMap<Int, Float>()
    private fun fadeOf(slot: PlayerSlot<P>): Float = fade[slot.id] ?: 1f

    /**
     * physicalVolume = baseGain(1) x focusDuckMultiplier x fadeGain(slot). The duck applies to every AUDIBLE slot: the logical
     * CURRENT and (during an overlap) the retiring slot; an idle NEXT stays neutral. Focus callbacks only change the duck and
     * fade ticks only change the fade component; both recompute from the other's current value, so neither can overwrite
     * the other.
     */
    private fun applyVolumes() {
        if (released) return
        for (slot in listOf(currentSlot, nextSlot)) {
            val audible = slot === currentSlot || slot === retiringSlot
            val volume = (if (audible) duckMultiplier else 1f) * fadeOf(slot)
            if (slot.player.volume != volume) slot.player.volume = volume
        }
    }

    private fun setFade(slot: PlayerSlot<P>, gain: Float) {
        require(isValidCrossfadeGain(gain)) { "fade gain must be finite within 0..1" }
        fade[slot.id] = gain
        applyVolumes()
    }

    // ── shared role-swap bookkeeping + CURRENT listener ownership ────────────────────────────────────────────────────────

    private val currentListeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()
    private var observedCurrent: P = first

    /**
     * Adds a PHYSICAL observer that always follows the logical CURRENT player (errors, READY/terminal state, interruption
     * signals). It moves with the role at promotion, so callers never hold a permanent reference to the initial player and a
     * retiring player's events are never delivered to it.
     */
    fun addCurrentPlayerListener(listener: Player.Listener) {
        if (currentListeners.addIfAbsent(listener)) observedCurrent.addListener(listener)
    }

    fun removeCurrentPlayerListener(listener: Player.Listener) {
        if (currentListeners.remove(listener)) observedCurrent.removeListener(listener)
    }

    private fun observeCurrent(target: P) {
        if (observedCurrent === target) return
        val previous = observedCurrent
        previous.removeListener(currentObserver)
        currentListeners.forEach { previous.removeListener(it) }
        observedCurrent = target
        target.addListener(currentObserver)
        currentListeners.forEach { target.addListener(it) }
    }

    // ── production promotion (CF-2M5) ────────────────────────────────────────────────────────────────────────────────────

    /** The slot being faded out after a promotion; null when no overlap exists. */
    var retiringSlot: PlayerSlot<P>? = null
        private set
    val retiringPlayer: P? get() = retiringSlot?.player

    // A retiring player whose cleanup failed: silent (fade 0) and never reused until engine release.
    private var quarantinedSlot: PlayerSlot<P>? = null

    /** True while a retiring (or quarantined) player occupies the NEXT role. */
    val promotionActive: Boolean get() = retiringSlot != null || quarantinedSlot != null

    /** Invoked (physical-only) when the retiring player ends or errors before the overlap completes. */
    var retiringTerminalListener: (() -> Unit)? = null

    private val retiringObserver = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) retiringTerminalListener?.invoke()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            retiringTerminalListener?.invoke()
        }
    }

    private fun step(step: PromotionStep) { promotionStepHook?.invoke(step) }

    /**
     * Promotes the READY, fully grafted NEXT player for the EXACT live [key]. Order (nothing is sought, re-prepared or
     * re-loaded; B is started exactly once):
     *  1. verify every precondition (fail closed, no mutation);
     *  2. consume the Ready preparation (detach, keep contents);
     *  3. incoming fade gain = 0;
     *  4. start incoming B (`playWhenReady = true`, paused -> playing, no seek);
     *  5. swap roles, move CURRENT observers, façade `replaceDelegate(.., presentAsAutoTransition = true)`;
     *  6. the old CURRENT becomes the retiring slot.
     * The caller then strips the retiring tail ([stripRetiringTail]), runs the fade ([setFadeGains]) and calls [finishRetirement].
     */
    fun promoteReadyNext(key: CrossfadeTransitionKey): PromotionStartResult {
        if (released) return PromotionStartResult.Rejected(PromotionRejection.Released)
        if (promotionActive) return PromotionStartResult.Rejected(PromotionRejection.OverlapActive)
        val prep = nextPreparation.state as? NextSlotState.Ready
            ?: return PromotionStartResult.Rejected(PromotionRejection.PreparationNotReady)
        if (prep.key != key) return PromotionStartResult.Rejected(PromotionRejection.KeyMismatch)
        val outgoing = currentSlot
        val incoming = nextSlot
        val out = outgoing.player
        val inc = incoming.player
        if (out.currentMediaItemIndex != key.fromPlaybackIndex) {
            return PromotionStartResult.Rejected(PromotionRejection.CurrentIndexMismatch)
        }
        if (inc.currentMediaItemIndex != key.toPlaybackIndex) {
            return PromotionStartResult.Rejected(PromotionRejection.NextIndexMismatch)
        }
        // Full order was verified by the graft; re-check the size and both anchors (cheap) at the promotion instant.
        if (out.mediaItemCount != inc.mediaItemCount ||
            out.getMediaItemAt(key.fromPlaybackIndex).mediaId != inc.getMediaItemAt(key.fromPlaybackIndex).mediaId ||
            out.getMediaItemAt(key.toPlaybackIndex).mediaId != inc.getMediaItemAt(key.toPlaybackIndex).mediaId
        ) {
            return PromotionStartResult.Rejected(PromotionRejection.TimelineMismatch)
        }
        if (inc.playbackState != Player.STATE_READY) return PromotionStartResult.Rejected(PromotionRejection.NextNotReady)
        if (inc.playWhenReady) return PromotionStartResult.Rejected(PromotionRejection.NextAlreadyStarted)
        if (!physicalPlayTarget() || out.playbackState != Player.STATE_READY) {
            return PromotionStartResult.Rejected(PromotionRejection.CurrentNotAdvancing)
        }

        var failedStep = PromotionStep.ConsumePreparation
        try {
            step(PromotionStep.ConsumePreparation)
            val consumed = nextPreparation.consumeReadyForPromotion(key)
                ?: return PromotionStartResult.Rejected(PromotionRejection.PreparationNotReady)
            check(consumed === inc) { "consumed player is not the NEXT slot" }
            failedStep = PromotionStep.IncomingGainZero
            step(PromotionStep.IncomingGainZero)
            setFade(incoming, 0f)
            failedStep = PromotionStep.StartIncoming
            step(PromotionStep.StartIncoming)
            inc.playWhenReady = true
        } catch (e: Exception) {
            // A failure before the consume boundary leaves a Ready preparation pointing at a player we are about to empty.
            nextPreparation.invalidate(null)
            recycleIncomingAfterFailure(incoming)
            return PromotionStartResult.Failed(failedStep, survivorSlotId = outgoing.id)
        }

        var swapped = false
        try {
            step(PromotionStep.SwapRoles)
            table.swapRoles()
            swapped = true
            observeCurrent(currentPlayer)
            step(PromotionStep.ReplaceDelegate)
            facade.replaceDelegate(currentPlayer, presentAsAutoTransition = true)
            retiringSlot = outgoing
            out.addListener(retiringObserver)
            applyVolumes()
        } catch (e: Exception) {
            return settleAfterSwapFailure(outgoing, incoming, swapped)
        }
        return PromotionStartResult.Promoted(outgoing.id, incoming.id)
    }

    /** B never became authoritative: stop and empty it, restore its neutral fade; A stays the one authoritative player. */
    private fun recycleIncomingAfterFailure(incoming: PlayerSlot<P>) {
        val p = incoming.player
        try {
            if (p.playWhenReady) p.playWhenReady = false
            if (p.playbackState != Player.STATE_IDLE) p.stop()
            if (p.mediaItemCount > 0) p.clearMediaItems()
            if (p.repeatMode != Player.REPEAT_MODE_OFF) p.repeatMode = Player.REPEAT_MODE_OFF
        } catch (_: Exception) {
            // Best effort: silence is verified below.
        }
        fade[incoming.id] = 0f
        try { applyVolumes() } catch (_: Exception) { }
        val clean = try { !p.playWhenReady && p.mediaItemCount == 0 } catch (_: Exception) { false }
        if (clean) {
            fade[incoming.id] = 1f
            try { applyVolumes() } catch (_: Exception) { }
        } else {
            quarantinedSlot = incoming // silent (fade 0) and never reused: no further crossfade until engine release
        }
    }

    private fun settleAfterSwapFailure(outgoing: PlayerSlot<P>, incoming: PlayerSlot<P>, swapped: Boolean): PromotionStartResult {
        return if (facade.delegatePlayer === incoming.player) {
            // The façade already follows B: B is the survivor. Complete the bookkeeping and cut A immediately.
            if (table.current !== incoming) table.swapRoles()
            observeCurrent(incoming.player)
            retiringSlot = outgoing
            fade[incoming.id] = 1f
            finishRetirement()
            PromotionStartResult.Failed(PromotionStep.ReplaceDelegate, survivorSlotId = incoming.id)
        } else {
            // The façade still follows A: A stays authoritative; undo the swap and silence/empty B.
            if (swapped && table.current !== outgoing) table.swapRoles()
            observeCurrent(outgoing.player)
            retiringSlot = null
            recycleIncomingAfterFailure(incoming)
            PromotionStartResult.Failed(PromotionStep.SwapRoles, survivorSlotId = outgoing.id)
        }
    }

    /**
     * Strips the retiring player's FUTURE tail so it can never auto-advance into its own B, and turns its repeat OFF (so
     * repeat-all cannot wrap into it either). Touches only the retiring player: no seek, no façade event (the façade follows
     * the new CURRENT).
     */
    fun stripRetiringTail() {
        val r = retiringPlayer ?: return
        step(PromotionStep.StripTail)
        if (r.repeatMode != Player.REPEAT_MODE_OFF) r.repeatMode = Player.REPEAT_MODE_OFF
        val from = r.currentMediaItemIndex
        val count = r.mediaItemCount
        if (from + 1 < count) r.removeMediaItems(from + 1, count)
    }

    /** Writes the two fade components for the overlap (outgoing = retiring, incoming = new CURRENT). */
    fun setFadeGains(outgoing: Float, incoming: Float) {
        val retiring = retiringSlot ?: return
        step(PromotionStep.FadeGainWrite)
        require(isValidCrossfadeGain(outgoing) && isValidCrossfadeGain(incoming)) { "fade gains must be finite within 0..1" }
        fade[retiring.id] = outgoing
        fade[currentSlot.id] = incoming
        applyVolumes()
    }

    /**
     * Ends the overlap: B = 1, A = 0, then stop/clear the retiring player and return it to the empty, paused, neutral NEXT
     * shape. It is NOT released (the next preparation reuses it). If its cleanup fails it stays silent and quarantined.
     */
    fun finishRetirement(): Boolean {
        val slot = retiringSlot ?: return true
        val p = slot.player
        retiringSlot = null
        retiringTerminalListener = null
        try { p.removeListener(retiringObserver) } catch (_: Exception) { }
        fade[currentSlot.id] = 1f
        fade[slot.id] = 0f
        try { applyVolumes() } catch (_: Exception) { }
        try {
            step(PromotionStep.RetireClear)
            if (p.playWhenReady) p.playWhenReady = false
            if (p.playbackState != Player.STATE_IDLE) p.stop()
            if (p.mediaItemCount > 0) p.clearMediaItems()
            if (p.repeatMode != Player.REPEAT_MODE_OFF) p.repeatMode = Player.REPEAT_MODE_OFF
        } catch (e: Exception) {
            quarantinedSlot = slot
            return false
        }
        fade[slot.id] = 1f
        try { applyVolumes() } catch (_: Exception) { }
        return true
    }

    // ── test-only role swap ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Test-only bare ownership swap (no start, no fade, no retirement): exchanges the roles, moves the CURRENT observers and
     * points the façade at the new CURRENT. NEVER called from production; promotion uses [promoteReadyNext].
     */
    @VisibleForTesting
    internal fun swapRolesForTest() {
        check(!released) { "engine released" }
        // NEXT's preparation belongs to the NEXT physical of the OLD roles: end it before the roles move.
        nextPreparation.invalidate(null)
        table.swapRoles()
        observeCurrent(currentPlayer)
        facade.replaceDelegate(currentPlayer)
        applyVolumes()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The ONLY release path for the physical players, the focus owner and the noisy receiver. A second call is a no-op. */
    fun release() {
        if (released) return
        released = true
        retiringTerminalListener = null
        unregisterNoisyReceiver()
        nextPreparation.release()
        retiringSlot?.player?.removeListener(retiringObserver)
        observedCurrent.removeListener(currentObserver)
        currentListeners.forEach { observedCurrent.removeListener(it) }
        focus.release()
        focusReleaseCount++
        releasePlayer(currentPlayer)
        releasePlayer(nextPlayer)
    }
}

/** Synchronous steps of promotion/retirement, so a test can inject a failure at each (see [PlayerEngine] `promotionStepHook`). */
internal enum class PromotionStep { ConsumePreparation, IncomingGainZero, StartIncoming, SwapRoles, ReplaceDelegate, StripTail, FadeGainWrite, RetireClear }

internal enum class PromotionRejection {
    Released, OverlapActive, PreparationNotReady, KeyMismatch, CurrentIndexMismatch, NextIndexMismatch, TimelineMismatch,
    NextNotReady, NextAlreadyStarted, CurrentNotAdvancing,
}

internal sealed interface PromotionStartResult {
    /** B is the logical CURRENT; the old CURRENT is retiring. */
    data class Promoted(val outgoingSlotId: Int, val incomingSlotId: Int) : PromotionStartResult

    /** Nothing was changed. */
    data class Rejected(val reason: PromotionRejection) : PromotionStartResult

    /** A synchronous step failed; the engine settled to exactly one authoritative player ([survivorSlotId]). */
    data class Failed(val step: PromotionStep, val survivorSlotId: Int) : PromotionStartResult
}

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
    val nextPreparation: NextSlotPreparation<P> = NextSlotPreparation({ nextPlayer }, nextSlotPerfLog, nextSlotGraftScheduler)

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
            applyDuck()
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

    /** The logical duck multiplier applies to the logical CURRENT; NEXT stays neutral. Independent of which slot is CURRENT. */
    private fun applyDuck() {
        if (released) return
        if (currentPlayer.volume != duckMultiplier) currentPlayer.volume = duckMultiplier
        if (nextPlayer.volume != 1f) nextPlayer.volume = 1f
    }

    // ── test-only role swap ───────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Test-only ownership swap: exchanges the CURRENT/NEXT roles, points the façade at the new CURRENT and moves the CURRENT
     * observer and the duck multiplier with the role. It does not release, prepare, seek, mutate any queue, request focus
     * again or change the audio-session id. NEVER called from production playback (CF-2M5 owns real promotion).
     */
    @VisibleForTesting
    internal fun swapRolesForTest() {
        check(!released) { "engine released" }
        // NEXT's preparation belongs to the NEXT physical of the OLD roles: end it before the roles move.
        nextPreparation.invalidate(null)
        val oldCurrent = currentPlayer
        table.swapRoles()
        oldCurrent.removeListener(currentObserver)
        currentPlayer.addListener(currentObserver)
        facade.replaceDelegate(currentPlayer)
        applyDuck()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The ONLY release path for the physical players, the focus owner and the noisy receiver. A second call is a no-op. */
    fun release() {
        if (released) return
        released = true
        unregisterNoisyReceiver()
        nextPreparation.release()
        currentPlayer.removeListener(currentObserver)
        focus.release()
        focusReleaseCount++
        releasePlayer(currentPlayer)
        releasePlayer(nextPlayer)
    }
}

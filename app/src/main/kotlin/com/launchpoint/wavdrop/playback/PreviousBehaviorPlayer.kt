package com.launchpoint.wavdrop.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The WavDrop behaviour wrapper at the session boundary (previous-button semantics, explicit external transport
 * detection, crossfade cleanup hooks, lazy hydration on a bare PLAY). Moved verbatim out of `PlaybackService` in CF-2M2 so
 * it is constructible in tests; the only change is that the two [PlayerController] calls it made are now injected
 * functions ([onExternalTransport], [hydrateForPlay]) so the class no longer needs a concrete PlayerController.
 *
 * It is a synchronous [ForwardingPlayer]: every policy decision (including `MediaSession.controllerForCurrentRequest`)
 * runs on the calling session request, before the call is forwarded to its wrapped player.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PreviousBehaviorPlayer(
    player: Player,
    private val thresholdProvider: () -> Long,
    private val scope: CoroutineScope,
    // Explicit external transport supersedes any automatic resume (was PlayerController.onExplicitExternalTransport).
    private val onExternalTransport: () -> Unit,
    // Hydrates an empty player from the persisted session for an explicit PLAY (was PlayerController.ensurePlayerHydratedFromSession).
    private val hydrateForPlay: suspend (List<Song>) -> PlayerHydrationResult,
    private val songsProvider: suspend () -> List<Song>,
    private val logResume: (String) -> Unit,
    private val sessionProvider: () -> MediaSession?,
    // CF-2G1: invoked on every explicit pause BEFORE the primary pause is forwarded (crossfade cleanup needs the
    // primary still controllable to restore its gain). Kept as a callback so this player knows nothing of crossfade.
    private val onExplicitPause: () -> Unit,
    // CF-2G2: invoked for an EXTERNAL user controller's same-track position seek only, before the seek is
    // forwarded. Never for the app-marked controller (its user seeks are handled in PlayerController.seekTo, and its
    // internal seeks, e.g. CF-2D2 handoff reconciliation, must stay untouched).
    private val onExplicitSeek: () -> Unit,
    // CF-2G3: invoked ONCE per EXTERNAL user NEXT/PREVIOUS command, before navigation. App-marked controller
    // requests never reach it (PlayerController already notified). Internal delegation uses super.* to bypass it.
    private val onExplicitNavigation: () -> Unit,
    // CF-2H1: invoked ONCE per repeat-mode change from an EXTERNAL user controller (system UI, Android Auto,
    // AVRCP) before it is forwarded. The app-marked controller is inert here: app commands (incl. the custom
    // CYCLE_REPEAT command, which calls PlayerController.cycleRepeatMode) already notified in PlayerController.
    private val onExplicitRepeatChange: () -> Unit,
    // DEBUG-only transport diagnostics (null in release). Logging never issues or alters transport.
    private val transportLog: ((String) -> Unit)? = null,
) : ForwardingPlayer(player) {

    private fun logTransport(name: String) {
        val log = transportLog ?: return
        try {
            log(
                "$name t=${android.os.SystemClock.elapsedRealtime()} external=${isExternalUserTransportRequest()} " +
                    "hasMedia=${currentMediaItem != null || mediaItemCount > 0} index=$currentMediaItemIndex " +
                    "playbackState=$playbackState playWhenReady=$playWhenReady isPlaying=$isPlaying",
            )
        } catch (_: Exception) {
            // diagnostics must never affect transport
        }
    }

    override fun getMaxSeekToPreviousPosition(): Long = thresholdProvider()

    // Explicit external transport (notification, lock screen, media keys, widget, system
    // controllers) reaches the player here. Tied to the actual play()/pause() call.
    private fun isExternalUserTransportRequest(): Boolean {
        val controller = sessionProvider()?.controllerForCurrentRequest
        return ExternalTransportPolicy.isExternalUserController(
            hasController = controller != null,
            isAppController = controller?.connectionHints
                ?.getBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, false) == true,
        )
    }

    private fun noteExternalTransport() {
        if (isExternalUserTransportRequest()) {
            onExternalTransport()
        }
    }

    // CF-2G2: same-track position seek (Player.seekTo(positionMs) only). Only an external user controller is an
    // explicit user seek here; the app-marked controller is never cancelled at this layer.
    override fun seekTo(positionMs: Long) {
        if (isExternalUserTransportRequest()) onExplicitSeek()
        super.seekTo(positionMs)
    }

    override fun pause() {
        logTransport("TRANSPORT_PAUSE")
        noteExternalTransport()
        onExplicitPause() // cancel any owned crossfade first, then forward the pause
        super.pause()
    }

    override fun play() {
        logTransport("TRANSPORT_PLAY")
        noteExternalTransport()
        if (currentMediaItem != null || mediaItemCount > 0) {
            playForwarded()
            return
        }
        scope.launch {
            val result = runCatching {
                hydrateForPlay(songsProvider())
            }.getOrElse { error ->
                logResume("explicit PLAY hydration failed: ${error::class.simpleName} ${error.message}")
                PlayerHydrationResult.MediaSetupFailed
            }
            logResume("explicit PLAY hydration result=$result")
            if (playerHydrationAllowsPlay(result)) {
                playForwarded()
            }
        }
    }

    // CF-2G3: each explicit NEXT/PREVIOUS Media3 command is a distinct top-level seam (ForwardingPlayer does not
    // route one through another). Each cancels once for external user controllers only.
    override fun setRepeatMode(repeatMode: Int) {
        if (isExternalUserTransportRequest()) onExplicitRepeatChange()
        super.setRepeatMode(repeatMode)
    }

    override fun seekToNext() {
        if (isExternalUserTransportRequest()) onExplicitNavigation()
        super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (isExternalUserTransportRequest()) onExplicitNavigation()
        super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        if (isExternalUserTransportRequest()) onExplicitNavigation()
        super.seekToPreviousMediaItem()
    }

    override fun seekToPrevious() {
        // One cancel at the PREVIOUS command boundary, before threshold evaluation. Delegations below use super.*
        // so neither the CF-2G2 seek hook nor the media-item hook fires a second time.
        if (isExternalUserTransportRequest()) onExplicitNavigation()
        val thresholdMs = thresholdProvider()
        if (thresholdMs > 0L && currentPosition > thresholdMs) {
            super.seekTo(0L)
        } else if (hasPreviousMediaItem()) {
            super.seekToPreviousMediaItem()
        } else {
            super.seekTo(0L)
        }
    }

    private fun playForwarded() {
        super.play()
    }
}

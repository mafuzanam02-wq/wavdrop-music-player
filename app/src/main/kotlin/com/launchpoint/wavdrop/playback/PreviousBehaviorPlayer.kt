package com.launchpoint.wavdrop.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // Request ownership for a PLAY that must hydrate first. Owned by PlayerController in production so selections share it.
    private val coldPlayAuthority: ColdPlayAuthority = ColdPlayAuthority(),
    // Invoked for an EXTERNAL user PLAY(true)/PAUSE(false), so a play/pause intent still deferred in PlayerController (no
    // controller yet) is overwritten by this newer command instead of replaying after reconnect.
    private val onExternalPlayIntent: (Boolean) -> Unit = {},
    // True while an explicit selection's Play is armed behind a bounded window verification (PlayerController owns it and starts it
    // itself once the window is proven); this wrapper must not start that unverified window early.
    private val selectionPlayArmed: () -> Boolean = { false },
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
        // A PAUSE from any controller is a newer decision than every suspended cold PLAY: invalidate synchronously, before
        // anything can suspend.
        coldPlayAuthority.supersede()
        noteExternalTransport()
        if (isExternalUserTransportRequest()) onExternalPlayIntent(false)
        onExplicitPause() // cancel any owned crossfade first, then forward the pause
        super.pause()
    }

    override fun play() {
        logTransport("TRANSPORT_PLAY")
        noteExternalTransport()
        if (isExternalUserTransportRequest()) onExternalPlayIntent(true)
        if (currentMediaItem != null || mediaItemCount > 0) {
            coldPlayAuthority.supersede() // a direct PLAY ends any older cold PLAY still pending
            playForwarded()
            return
        }
        // Cold PLAY: capture authority NOW (synchronously, with this command); the hydration below suspends.
        val ticket = coldPlayAuthority.begin()
        scope.launch {
            try {
                val songs = runCatching { songsProvider() }.getOrNull()
                if (songs == null || !coldPlayAuthority.isCurrent(ticket)) {
                    // Superseded (or songs unavailable) before hydration even started: nothing to hydrate or play.
                    logResume("explicit PLAY dropped before hydration: superseded=${!coldPlayAuthority.isCurrent(ticket)}")
                    return@launch
                }
                val result = runCatching {
                    // Only the hydration started for THIS PLAY carries its ownership element.
                    withContext(ticket.ownedHydration) { hydrateForPlay(songs) }
                }.getOrElse { error ->
                    logResume("explicit PLAY hydration failed: ${error::class.simpleName} ${error.message}")
                    PlayerHydrationResult.MediaSetupFailed
                }
                logResume("explicit PLAY hydration result=$result")
                // Hydration may have retained a valid saved queue, but audible playback needs fresh authority: no newer
                // PAUSE/PLAY/selection, and the queue generation moved only through hydration.
                val authorization = coldPlayAuthority.authorize(ticket)
                if (authorization != ColdPlayAuthorization.ALLOWED) {
                    logResume("explicit PLAY denied after hydration: $authorization")
                    return@launch
                }
                if (playerHydrationAllowsPlay(result)) {
                    playForwarded()
                }
            } finally {
                coldPlayAuthority.complete(ticket)
            }
        }
    }

    // Media3 can deliver a play/pause as a play-when-ready change; route an EXTERNAL one through the same policy as
    // play()/pause() so it cannot bypass cold-PLAY authority. Internal/app calls forward untouched.
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (isExternalUserTransportRequest()) {
            if (playWhenReady) play() else pause()
        } else {
            super.setPlayWhenReady(playWhenReady)
        }
    }

    // CF-2G3: each explicit NEXT/PREVIOUS Media3 command is a distinct top-level seam (ForwardingPlayer does not
    // route one through another). Each cancels once for external user controllers only.
    override fun setRepeatMode(repeatMode: Int) {
        if (isExternalUserTransportRequest()) onExplicitRepeatChange()
        super.setRepeatMode(repeatMode)
    }

    // An EXTERNAL explicit Next/Previous/seek-to-another-item is a deliberate selection: it starts the target even from a paused
    // state. Seeking within the current item (and a Previous that restarts it) never does.
    private fun startSelectedIfExternal(external: Boolean, moves: Boolean) {
        if (external && moves && !playWhenReady && !selectionPlayArmed()) play()
    }

    override fun seekToNext() {
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        val moves = hasNextMediaItem()
        super.seekToNext()
        startSelectedIfExternal(external, moves)
    }

    override fun seekToNextMediaItem() {
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        val moves = hasNextMediaItem()
        super.seekToNextMediaItem()
        startSelectedIfExternal(external, moves)
    }

    override fun seekToPreviousMediaItem() {
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        val moves = hasPreviousMediaItem()
        super.seekToPreviousMediaItem()
        startSelectedIfExternal(external, moves)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val external = isExternalUserTransportRequest()
        val moves = mediaItemIndex != currentMediaItemIndex
        super.seekTo(mediaItemIndex, positionMs)
        startSelectedIfExternal(external, moves)
    }

    override fun seekToPrevious() {
        // One cancel at the PREVIOUS command boundary, before threshold evaluation. Delegations below use super.*
        // so neither the CF-2G2 seek hook nor the media-item hook fires a second time.
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        val thresholdMs = thresholdProvider()
        if (thresholdMs > 0L && currentPosition > thresholdMs) {
            super.seekTo(0L)
        } else if (hasPreviousMediaItem()) {
            super.seekToPreviousMediaItem()
            startSelectedIfExternal(external, true)
        } else {
            super.seekTo(0L)
        }
    }

    private fun playForwarded() {
        super.play()
    }
}

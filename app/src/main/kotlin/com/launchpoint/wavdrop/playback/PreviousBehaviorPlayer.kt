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
    // Optional single-player transport fade (explicit Play/Pause/Next/Previous). Null or disabled = exactly the unfaded behaviour.
    private val transportFade: TransportFadeController? = null,
    // P5-F20J-R2: true while a newly accepted queue is NOT adopted by the physical player (PlayerController.isInstallUnverified). While
    // true, every route that would start or navigate the stale physical item is refused here, the common session transport boundary.
    private val transportBlocked: () -> Boolean = { false },
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
        if (refuseWhileUnverified("seekTo")) return
        if (isExternalUserTransportRequest()) onExplicitSeek()
        super.seekTo(positionMs)
    }

    override fun pause() {
        logTransport("TRANSPORT_PAUSE")
        val external = isExternalUserTransportRequest()
        // A PAUSE from any controller is a newer decision than every suspended cold PLAY: invalidate synchronously, before
        // anything can suspend.
        coldPlayAuthority.supersede()
        noteExternalTransport()
        if (isExternalUserTransportRequest()) onExternalPlayIntent(false)
        onExplicitPause() // cancel any owned crossfade first, then forward the pause
        val fade = transportFade
        // Only an EXTERNAL user's explicit Pause is faded. The app-marked controller carries PlayerController's INTERNAL commands (sleep expiry,
        // error halt, queue clear, restoration) which are system-required stops and must never wait for the user's fade; the in-app
        // Play/Pause button fades in PlayerController.togglePlayPause instead.
        if (fade == null || !external) super.pause() else fade.pause(isPlaying) { super.pause() }
    }

    override fun play() {
        if (refuseWhileUnverified("play")) return
        // Media3 issues one PLAY after every playback-resumption request (also after a failed or dropped one), blind to a PAUSE,
        // selection or newer PLAY that arrived while the saved session was being restored. The restoration itself stays; only that PLAY
        // is refused, and only when it is made by the completion callback of a request that was superseded (see
        // ColdPlayAuthority.admitResumptionPlay: explicit ownership). Any other PLAY is never affected.
        if (!coldPlayAuthority.admitResumptionPlay()) {
            logTransport("TRANSPORT_PLAY_SUPPRESSED")
            logResume("resumption PLAY suppressed: a newer command superseded the request that restored the session")
            return
        }
        logTransport("TRANSPORT_PLAY")
        val external = isExternalUserTransportRequest()
        noteExternalTransport()
        if (isExternalUserTransportRequest()) onExternalPlayIntent(true)
        if (currentMediaItem != null || mediaItemCount > 0) {
            coldPlayAuthority.supersede() // a direct PLAY ends any older cold PLAY still pending
            playForwarded(external)
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
                // A saved queue that is already active (adopted by a playback-resumption request whose result Media3 has not applied to this
                // player yet) is not a reason to drop the newest PLAY: authorization above proves nothing newer happened and the queue moved
                // only through session hydrations. Without this, the newest PLAY would depend on an older request's callback running first.
                if (playerHydrationAllowsPlay(result) || result == PlayerHydrationResult.SkippedActiveQueue) {
                    playForwarded(external)
                }
            } finally {
                coldPlayAuthority.complete(ticket)
            }
        }
    }

    // Media3 can deliver a play/pause as a play-when-ready change; route an EXTERNAL one through the same policy as
    // play()/pause() so it cannot bypass cold-PLAY authority. Internal/app calls forward untouched.
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && refuseWhileUnverified("setPlayWhenReady")) return
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
        if (refuseWhileUnverified("seekToNext")) return
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        fadedNavigation(external) {
            val moves = hasNextMediaItem()
            super.seekToNext()
            startSelectedIfExternal(external, moves)
            moves
        }
    }

    override fun seekToNextMediaItem() {
        if (refuseWhileUnverified("seekToNextMediaItem")) return
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        fadedNavigation(external) {
            val moves = hasNextMediaItem()
            super.seekToNextMediaItem()
            startSelectedIfExternal(external, moves)
            moves
        }
    }

    override fun seekToPreviousMediaItem() {
        if (refuseWhileUnverified("seekToPreviousMediaItem")) return
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        fadedNavigation(external) {
            val moves = hasPreviousMediaItem()
            super.seekToPreviousMediaItem()
            startSelectedIfExternal(external, moves)
            moves
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (refuseWhileUnverified("seekToIndex")) return
        val external = isExternalUserTransportRequest()
        val moves = mediaItemIndex != currentMediaItemIndex
        super.seekTo(mediaItemIndex, positionMs)
        startSelectedIfExternal(external, moves)
    }

    override fun seekToPrevious() {
        if (refuseWhileUnverified("seekToPrevious")) return
        // One cancel at the PREVIOUS command boundary, before threshold evaluation. Delegations below use super.*
        // so neither the CF-2G2 seek hook nor the media-item hook fires a second time.
        val external = isExternalUserTransportRequest()
        if (external) onExplicitNavigation()
        fadedNavigation(external) {
            val thresholdMs = thresholdProvider()
            if (thresholdMs > 0L && currentPosition > thresholdMs) {
                super.seekTo(0L); false
            } else if (hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
                startSelectedIfExternal(external, true)
                true
            } else {
                super.seekTo(0L); false
            }
        }
    }

    /**
     * P5-F20J-R2: the synchronous safety guard at the common session boundary. Every session controller (notification, lock screen,
     * media buttons, Bluetooth/AVRCP, Android Auto, the app's own controller) reaches the player through these overrides, so a
     * command that would START or MOVE the stale physical item is refused while the accepted queue is unverified. Pause and Stop are
     * never refused. The in-app retry installs and verifies BEFORE it plays, so it is not affected.
     */
    private fun refuseWhileUnverified(name: String): Boolean {
        if (!transportBlocked()) return false
        logTransport("TRANSPORT_REFUSED_UNVERIFIED_QUEUE $name")
        return true
    }

    override fun seekForward() { if (!refuseWhileUnverified("seekForward")) super.seekForward() }
    override fun seekBack() { if (!refuseWhileUnverified("seekBack")) super.seekBack() }
    override fun seekToDefaultPosition() { if (!refuseWhileUnverified("seekToDefaultPosition")) super.seekToDefaultPosition() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { if (!refuseWhileUnverified("seekToDefaultPosition")) super.seekToDefaultPosition(mediaItemIndex) }

    private fun playForwarded(external: Boolean) {
        val fade = transportFade
        if (fade == null || !external) super.play() else fade.play(isPlaying) { super.play() }
    }

    // An EXTERNAL explicit navigation goes through the transport fade; [body] is the unchanged navigation and returns whether it moved.
    private fun fadedNavigation(external: Boolean, body: () -> Boolean) {
        val fade = transportFade
        if (fade == null || !external) body() else fade.navigate(isPlaying) { body() }
    }
}

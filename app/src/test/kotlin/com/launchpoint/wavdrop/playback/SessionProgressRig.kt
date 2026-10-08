package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.media.session.MediaController as PlatformController
import android.media.session.PlaybackState as PlatformState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * LS-1 probe: a REAL [MediaLibraryService.MediaLibrarySession] over a given session-facing [Player], a real Media3
 * [MediaController] (what an external Media3 client consumes) and a real platform [MediaControllerCompat] (the
 * PlaybackStateCompat the lock screen / system media controls extrapolate from). Time advances ONLY through [advance].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SessionProbe(
    val sessionPlayer: Player,
    /** The physical player that is the logical CURRENT right now (re-read on every sample, so it follows promotion). */
    val physical: () -> Player,
    val context: Context = RuntimeEnvironment.getApplication(),
) {
    val session: MediaLibraryService.MediaLibrarySession =
        MediaLibraryService.MediaLibrarySession.Builder(context, sessionPlayer, object : MediaLibraryService.MediaLibrarySession.Callback {})
            .setId("ls1-${System.nanoTime()}")
            .build()
    val controller: MediaController
    val compat: PlatformController

    init {
        val hints = Bundle().apply { putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(context, session.token).setConnectionHints(hints).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 100) idle()
        controller = future.get(5, TimeUnit.SECONDS)
        compat = PlatformController(context, session.platformToken)
        idle()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Issues a controller command the way a device does: the IPC hop takes real time, so the session handles it at a LATER
     * elapsed-realtime than the controller recorded when it issued it. (With a frozen test clock both are the same millisecond and
     * Media3's controller deliberately keeps its cached position until the next session update; that is a clock artifact, not
     * player behaviour.)
     */
    fun command(block: MediaController.() -> Unit) {
        controller.block()
        ShadowSystemClock.advanceBy(Duration.ofMillis(IPC_MS))
        idle()
    }

    /**
     * Advances the shadowed system clock by [ms] in small steps. The real ExoPlayer playback thread derives position from that
     * clock, so progression is exactly [ms]; the short real sleep only yields so the playback thread's report reaches the main
     * looper before the next step (the same technique as the ST-1 physical-player tests).
     */
    fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(STEP_MS, left)
            ShadowSystemClock.advanceBy(Duration.ofMillis(step))
            Thread.sleep(YIELD_MS)
            idle()
            left -= step
        }
    }

    /** One row of evidence: what every layer reports at the same instant. */
    data class Sample(
        val physical: Long, val sessionPlayer: Long, val controller: Long, val platform: Long, val platformState: Int,
        val state: Int, val playWhenReady: Boolean, val isPlaying: Boolean, val index: Int, val controllerDuration: Long,
    ) {
        override fun toString() =
            "phys=$physical sessionPlayer=$sessionPlayer ctl=$controller platform=$platform(st=$platformState) " +
                "state=$state pwr=$playWhenReady playing=$isPlaying idx=$index dur=$controllerDuration"
    }

    /** What SystemUI does with a PlaybackState: position + speed * (now - lastPositionUpdateTime) while PLAYING. */
    private fun platformPosition(): Pair<Long, Int> {
        val s: PlatformState = compat.playbackState ?: return -1L to -1
        val extrapolated = if (s.state == PlatformState.STATE_PLAYING) {
            s.position + (s.playbackSpeed * (SystemClock.elapsedRealtime() - s.lastPositionUpdateTime)).toLong()
        } else {
            s.position
        }
        return extrapolated to s.state
    }

    /** Lets the real playback thread deliver any in-flight position report before the layers are read (CPU-load robustness). */
    private fun quiesce() { repeat(3) { Thread.sleep(15); idle() } }

    fun sample(): Sample {
        quiesce()
        val (platformPos, platformState) = platformPosition()
        return Sample(
            physical = physical().currentPosition,
            sessionPlayer = sessionPlayer.currentPosition,
            controller = controller.currentPosition,
            platform = platformPos,
            platformState = platformState,
            state = controller.playbackState,
            playWhenReady = controller.playWhenReady,
            isPlaying = controller.isPlaying,
            index = controller.currentMediaItemIndex,
            controllerDuration = controller.duration,
        )
    }

    fun sampleSeries(count: Int, stepMs: Long): List<Sample> =
        (0 until count).map { if (it > 0) advance(stepMs); sample() }

    fun release() {
        runCatching { controller.release() }
        runCatching { session.release() }
    }

    companion object {
        const val STEP_MS = 50L
        const val YIELD_MS = 6L
        const val IPC_MS = 2L
    }
}

/** The production chain: two real ExoPlayers -> [PlayerEngine] -> [SessionFacade] -> [PreviousBehaviorPlayer] -> session. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SessionProgressRig(
    val itemCount: Int = 5,
    startIndex: Int = 0,
    loadQueue: Boolean = true,
    val context: Context = RuntimeEnvironment.getApplication(),
) {
    val p1: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
    val p2: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
    val engine = PlayerEngine(context, p1, p2, SHARED_SESSION_ID, AudioAttributes.DEFAULT)
    val facade: SessionFacade get() = engine.facade
    /** The PRODUCTION media-item mapping (no duration metadata: duration comes from the player timeline only). */
    val queue: List<MediaItem> = (1L..itemCount.toLong()).map { RealMedia3QueueRig.song(it).toPlaybackMediaItem() }
    private var probeRef: SessionProbe? = null

    val policy = PreviousBehaviorPlayer(
        player = engine.facade,
        thresholdProvider = { 3_000L },
        scope = CoroutineScope(Dispatchers.Unconfined),
        onExternalTransport = {},
        hydrateForPlay = { PlayerHydrationResult.Hydrated },
        songsProvider = { emptyList() },
        logResume = {},
        sessionProvider = { probeRef?.session },
        onExplicitPause = {},
        onExplicitSeek = {},
        onExplicitNavigation = {},
        onExplicitRepeatChange = {},
    )

    init {
        if (loadQueue) {
            facade.setMediaItems(queue, startIndex, 0L)
            facade.prepare()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    val probe: SessionProbe = SessionProbe(policy, { engine.currentPlayer }).also { probeRef = it }
    val controller: MediaController get() = probe.controller
    val session get() = probe.session

    fun advance(ms: Long) = probe.advance(ms)
    fun command(block: MediaController.() -> Unit) = probe.command(block)
    fun idle() = probe.idle()
    fun sample() = probe.sample()
    fun sampleSeries(count: Int, stepMs: Long) = probe.sampleSeries(count, stepMs)

    // ── real two-slot promotion (the production CF-2M4/2M5 path) ───────────────────────────────────────────────────────────

    private var generation = 1L

    /** Prepares and grafts NEXT for (from -> from + 1) on the real second ExoPlayer and waits until it is Ready. */
    fun prepareNext(from: Int): CrossfadeTransitionKey {
        val key = CrossfadeTransitionKey(generation++, from, from + 1)
        engine.nextPreparation.request(NextSlotRequest(key, queue))
        TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
        TestMediaSource.settle(60)
        return key
    }

    fun promote(key: CrossfadeTransitionKey): PromotionStartResult = engine.promoteReadyNext(key).also { idle() }

    /** The end of an overlap: the retiring player is stripped, silenced and emptied (as the promotion runtime does). */
    fun finishOverlap() {
        engine.stripRetiringTail()
        engine.setFadeGains(0f, 1f)
        check(engine.finishRetirement()) { "retirement failed" }
        TestMediaSource.settle(60)
        idle()
    }

    fun release() {
        probe.release()
        engine.release()
    }
}

/**
 * The most conservative model of a system media surface (lock screen / media controls): it learns ONLY from session-player
 * events (no polling, no periodic refresh), anchors (position, isPlaying, speed, time) at each relevant event exactly as Media3
 * reads them when it pushes a playback state, and extrapolates between events. If this model stays on the physical position,
 * the session player publishes every state change a real surface needs.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SystemSurfaceModel(private val sessionPlayer: Player) : Player.Listener {
    private var anchorPositionMs = sessionPlayer.currentPosition
    private var anchorAtMs = android.os.SystemClock.elapsedRealtime()
    private var anchorPlaying = sessionPlayer.isPlaying
    private var anchorSpeed = sessionPlayer.playbackParameters.speed
    val anchors = mutableListOf<String>()

    init { sessionPlayer.addListener(this) }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
        ) return
        anchorPositionMs = player.currentPosition
        anchorAtMs = android.os.SystemClock.elapsedRealtime()
        anchorPlaying = player.isPlaying
        anchorSpeed = player.playbackParameters.speed
        anchors += "pos=$anchorPositionMs playing=$anchorPlaying"
    }

    fun expectedPositionMs(): Long =
        if (anchorPlaying) anchorPositionMs + (anchorSpeed * (android.os.SystemClock.elapsedRealtime() - anchorAtMs)).toLong() else anchorPositionMs

    fun release() = sessionPlayer.removeListener(this)
}

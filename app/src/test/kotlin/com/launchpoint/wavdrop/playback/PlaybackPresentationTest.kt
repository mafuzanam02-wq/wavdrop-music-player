package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Play/Pause control the listener SEES (Now Playing, mini-player) is driven by one field of [NowPlayingState]. Every state the
 * controller publishes is recorded (an unconfined collector sees each update, none are conflated) so a transient
 * Play -> Pause -> Play visual is caught, not just the end state. Real Media3 and the real physical acknowledgement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackPresentationTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private fun store(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
    )

    /** The one field the UI renders as Play/Pause. */
    private fun shown(state: NowPlayingState): Boolean = state.presentedAsPlaying

    private inner class Harness(val bounded: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pp", physicalAck = true)
        val pc = PlayerController(
            context,
            StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
            PlaybackSessionRepository(store("sessionstore")),
            ResumeBehaviorSettingsRepository(store("resumestore")),
            AppSettingsRepository(store("appstore")),
        )
        val controller: MediaController get() = rig.controller
        val scope = CoroutineScope(Dispatchers.Unconfined)
        /** Every published value of the shown field, consecutive duplicates collapsed. */
        val seen = mutableListOf<Boolean>()

        init {
            pc.boundedInstallEnabledForTesting = bounded
            pc.attachPhysicalWindowPort(rig.port)
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
                .apply { isAccessible = true }.invoke(pc, rig.controller)
            rig.settle(100)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        val adapter: PhysicalWindowAdapter get() = field("physicalWindowAdapter").get(pc) as PhysicalWindowAdapter
        fun settle() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper(); rig.settle(150) }

        fun start(queue: List<Song>, index: Int, playing: Boolean) {
            pc.playFromQueue(queue, index)
            settle()
            if (!playing) { controller.pause(); rig.settle(150) }
            assertEquals("precondition", playing, rig.player.playWhenReady)
        }

        /** Begin recording now (the current value is the first entry). */
        fun record() {
            seen.clear()
            scope.launch { pc.nowPlayingState.collect { s -> shown(s).let { if (seen.lastOrNull() != it) seen += it } } }
        }

        val current: Boolean get() = shown(pc.nowPlayingState.value)
        fun releaseAll() { scope.cancel(); runCatching { pc.release() }; rig.releaseAll() }
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness(bounded: Boolean) = Harness(bounded).also { harnesses += it }
    @After fun tearDown() { harnesses.forEach { it.releaseAll() }; harnesses.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private fun both(body: (Boolean) -> Unit) { for (bounded in listOf(false, true)) body(bounded) }

    /** The listener must never be shown Pause -> Play -> Pause-style dips: once playback is presented it stays presented. */
    private fun assertNoDip(seen: List<Boolean>, what: String) {
        val firstTrue = seen.indexOf(true)
        assertTrue("$what: never became playing: $seen", firstTrue >= 0)
        assertFalse("$what: playing was withdrawn after being shown: $seen", seen.drop(firstTrue).contains(false))
    }

    @Test fun pausedNextShowsNoFalsePauseWhilePreparing() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.record()
        h.pc.skipToNext(); h.settle()
        assertNoDip(h.seen, "Next (bounded=$bounded)")
        assertTrue(h.current); assertTrue(h.rig.player.playWhenReady)
    }

    @Test fun pausedSelectionOfAnotherOccurrenceShowsNoFalsePause() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.record()
        h.pc.jumpToQueueItem(12); h.settle()
        assertNoDip(h.seen, "row tap (bounded=$bounded)")
        assertEquals(12, h.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun pausedBoundedOutOfWindowSelectionIsPresentedAsPlayingThroughVerification() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.record()
        h.pc.jumpToQueueItem(3)
        assertTrue("the accepted intent is shown at once", h.current)
        assertFalse("but nothing audible starts before the window is verified", h.rig.player.playWhenReady)
        h.settle()
        assertNoDip(h.seen, "re-anchor")
        assertTrue(h.rig.player.playWhenReady)
    }

    @Test fun aNewerPauseDuringVerificationWinsAndIsShownAtOnce() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.record()
        h.pc.jumpToQueueItem(3)
        h.pc.togglePlayPause()
        assertFalse("the Pause is reflected immediately, not after verification", h.current)
        h.settle()
        assertFalse(h.current)
        assertFalse(h.rig.player.playWhenReady)
        assertEquals("only the transient intent then the pause: ${h.seen}", listOf(false, true, false), h.seen)
    }

    @Test fun aRejectedVerificationNeverLeavesTheUiClaimingPlayback() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.record()
        h.pc.jumpToQueueItem(3)
        h.adapter.expirePending()                              // verification fails (no acknowledgement)
        h.settle()
        assertFalse("UI must not claim playback that never started", h.current)
        assertFalse(h.rig.player.playWhenReady)
        assertFalse(h.rig.player.isPlaying)
    }

    @Test fun rapidSuccessiveSelectionsPresentOneContinuousPlay() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(25_000).take(if (bounded) 25_000 else 60), 20, playing = false)
        h.record()
        h.pc.jumpToQueueItem(3); h.pc.jumpToQueueItem(40); h.pc.jumpToQueueItem(7)
        h.settle()
        assertNoDip(h.seen, "rapid selections (bounded=$bounded)")
        assertEquals(7, h.pc.nowPlayingState.value.currentIndex)
        assertTrue(h.rig.player.playWhenReady)
    }

    @Test fun normalBufferingDuringPlaybackIsNeverShownAsPause() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = true)
        h.record()
        h.pc.seekTo(60_000L); h.settle()                         // a seek re-buffers while still intending to play
        assertFalse("buffering must not look like a user Pause: ${h.seen}", h.seen.contains(false))
    }

    @Test fun aGenuineManualPauseIsShownImmediately() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = true)
        assertTrue(h.current)
        h.pc.togglePlayPause()
        assertFalse("reflected in the same turn as the command", h.current)
        h.settle()
        assertFalse(h.current); assertFalse(h.rig.player.playWhenReady)
    }

    @Test fun nextAtTheTrueQueueEndWithRepeatOffStaysPausedAndShownPaused() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(5), 4, playing = false)
        h.record()
        h.pc.skipToNext(); h.settle()
        assertEquals(listOf(false), h.seen)
        assertFalse(h.rig.player.playWhenReady)
    }

    @Test fun aPausedSameSongSeekStaysPausedAndShownPaused() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.record()
        h.pc.seekTo(15_000L); h.settle()
        assertEquals(listOf(false), h.seen)
        assertFalse(h.rig.player.playWhenReady)
    }

    @Test fun reopeningAnIntentionallyPausedSessionDoesNotAutoplayOrShowPlaying() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.record()
        PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
            .apply { isAccessible = true }.invoke(h.pc, h.controller)
        h.settle()
        assertEquals(listOf(false), h.seen)
        assertFalse(h.rig.player.playWhenReady)
    }

    @Test fun theSessionAndTheNotificationStayTruthful() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        h.pc.skipToNext(); h.settle()
        // What the notification / MediaSession exposes is the physical player; the UI agrees with it once settled.
        assertEquals(h.rig.player.playWhenReady, h.controller.playWhenReady)
        assertEquals(h.rig.player.isPlaying || h.rig.player.playWhenReady, h.current)
        h.pc.togglePlayPause(); h.settle()
        assertFalse(h.rig.player.playWhenReady); assertFalse(h.current)
    }

    // ── the derivation itself: every situation the control must keep distinguishable ──────────────────────────────────────

    private fun derive(
        isPlaying: Boolean = false, playWhenReady: Boolean = false, state: Int = Player.STATE_READY,
        suppressed: Boolean = false, error: Boolean = false, armed: Boolean = false,
    ) = PlaybackPresentation.presentedAsPlaying(isPlaying, playWhenReady, state, suppressed, error, armed)

    @Test fun theDerivationKeepsEveryRealSituationDistinguishable() {
        assertTrue("playing", derive(isPlaying = true, playWhenReady = true))
        assertTrue("preparing after a selection (buffering)", derive(playWhenReady = true, state = Player.STATE_BUFFERING))
        assertTrue("preparing before the first prepare (idle, no error)", derive(playWhenReady = true, state = Player.STATE_IDLE))
        assertFalse("intentionally paused", derive(playWhenReady = false, state = Player.STATE_READY))
        assertFalse("paused while buffering", derive(playWhenReady = false, state = Player.STATE_BUFFERING))
        assertFalse("audio focus loss is not buffering", derive(playWhenReady = true, state = Player.STATE_READY, suppressed = true))
        assertFalse("suppressed while buffering", derive(playWhenReady = true, state = Player.STATE_BUFFERING, suppressed = true))
        assertFalse("a playback error is never hidden behind the intent", derive(playWhenReady = true, state = Player.STATE_IDLE, error = true))
        assertFalse("an error beats an armed intent", derive(error = true, armed = true))
        assertFalse("end of queue", derive(playWhenReady = true, state = Player.STATE_ENDED))
        assertTrue("armed intent behind a bounded verification", derive(playWhenReady = false, armed = true))
        assertFalse("a withdrawn intent", derive(playWhenReady = false, armed = false))
    }

    @Test fun theMiniPlayerAndNowPlayingRenderThePresentationFieldNotTheRawMedia3Flag() {
        var dir = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (!java.io.File(dir, "app/src/main").isDirectory) dir = dir.parentFile!!
        val ui = java.io.File(dir, "app/src/main/kotlin/com/launchpoint/wavdrop/ui")
        val mini = java.io.File(ui, "components/MiniPlayer.kt").readText()
        val full = java.io.File(ui, "screen/nowplaying/NowPlayingScreen.kt").readText()
        assertTrue(mini.contains("nowPlaying.presentedAsPlaying") && !mini.contains("nowPlaying.isPlaying"))
        assertTrue(full.contains("state.presentedAsPlaying") && !full.contains("targetState = state.isPlaying"))
    }
}

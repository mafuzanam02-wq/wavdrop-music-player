package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * The control must DO what it SHOWS. The in-app toggle decides from the same presentation the UI renders (the user's playback intent
 * and any pending selection), never from `isPlaying` alone: while an accepted request is still preparing, `isPlaying` is false but
 * the control shows Pause, and a tap on it must be a real Pause. Every case checks the rendered state AND the physical player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackToggleConsistencyTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private fun store(name: String) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + Job()),
        produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
    )

    private fun newPc(context: Context) = PlayerController(
        context,
        StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
        PlaybackSessionRepository(store("sessionstore")),
        ResumeBehaviorSettingsRepository(store("resumestore")),
        AppSettingsRepository(store("appstore")),
    )

    private inner class Harness(val bounded: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "tg", physicalAck = true)
        val pc = newPc(context)
        val controller: MediaController get() = rig.controller

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

        /** What the control currently displays: true = it shows Pause. */
        val showsPause: Boolean get() = pc.nowPlayingState.value.presentedAsPlaying
        val physicalIntent: Boolean get() = rig.player.playWhenReady

        /** A tap on the displayed control: it must do the OPPOSITE of what it shows, on the real player. */
        fun tapAndExpectOpposite(what: String) {
            val before = showsPause
            pc.togglePlayPause()
            settle()
            assertEquals("$what: a tap on '${if (before) "Pause" else "Play"}' must ${if (before) "pause" else "play"} the real player", !before, physicalIntent)
            assertEquals("$what: the control agrees with the player after the tap", !before, showsPause)
        }

        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }
    }

    private val harnesses = mutableListOf<Harness>()
    private val dharnesses = mutableListOf<DHarness>()
    private fun harness(bounded: Boolean) = Harness(bounded).also { harnesses += it }
    private fun dharness() = DHarness().also { dharnesses += it }
    @After fun tearDown() { harnesses.forEach { it.releaseAll() }; harnesses.clear(); dharnesses.forEach { it.releaseAll() }; dharnesses.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private fun both(body: (Boolean) -> Unit) { for (bounded in listOf(false, true)) body(bounded) }

    @Test fun playingAndReadyShowsPauseAndATapPauses() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = true)
        assertTrue(h.showsPause)
        h.tapAndExpectOpposite("playing/ready (bounded=$bounded)")
    }

    @Test fun anExplicitlyPausedSessionShowsPlayAndATapPlays() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(30), 5, playing = false)
        assertFalse(h.showsPause)
        h.tapAndExpectOpposite("paused (bounded=$bounded)")
    }

    @Test fun aPlayIntentThatIsStillBufferingShowsPauseAndATapPauses() = both { bounded ->
        val h = harness(bounded)
        h.pc.playFromQueue(queue(30), 5)                        // accepted, nothing has had time to become READY yet
        val c = h.controller
        assertTrue("precondition: intent to play but not (yet) really playing", c.playWhenReady && !c.isPlaying)
        assertTrue("the control shows Pause for the preparing request", h.showsPause)
        h.pc.togglePlayPause()
        assertFalse("the tap must be a Pause, not another Play", h.controller.playWhenReady)
        h.settle()
        assertFalse(h.physicalIntent); assertFalse(h.showsPause)
    }

    @Test fun aBoundedSelectionWithAnArmedPlayShowsPauseAndATapCancelsItForGood() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.jumpToQueueItem(3)                                 // out of the window: Play is armed, playWhenReady is still false
        assertFalse(h.physicalIntent)
        assertTrue(h.showsPause)
        h.pc.togglePlayPause()
        assertFalse(h.showsPause)
        h.settle()                                              // verification completes AFTER the newer Pause
        assertFalse("the older armed Play must not resume playback", h.physicalIntent)
        assertEquals(3, h.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun aPauseImmediatelyAfterADistantSelectionWins() = both { bounded ->
        val h = harness(bounded)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.jumpToQueueItem(3)
        h.pc.togglePlayPause()
        h.settle()
        assertFalse(h.physicalIntent); assertFalse(h.showsPause)
    }

    @Test fun rapidPlayPauseWhileBufferingLandsOnTheLastTap() = both { bounded ->
        val h = harness(bounded)
        h.pc.playFromQueue(queue(30), 5)                        // preparing, shown as Pause
        h.pc.togglePlayPause()                                  // Pause
        h.pc.togglePlayPause()                                  // Play
        h.pc.togglePlayPause()                                  // Pause
        h.settle()
        assertFalse("last tap was a Pause", h.physicalIntent); assertFalse(h.showsPause)
        h.pc.togglePlayPause(); h.settle()
        assertTrue("and a further tap plays", h.physicalIntent); assertTrue(h.showsPause)
    }

    @Test fun anOlderArmedPlayNeverResumesAfterANewerPlayPauseCycle() {
        val h = harness(true)
        h.start(queue(25_000), 12_500, playing = false)
        h.pc.jumpToQueueItem(3)
        h.pc.togglePlayPause()                                  // Pause (cancels the armed Play)
        h.pc.togglePlayPause()                                  // the user plays again, deliberately
        h.settle()
        assertTrue(h.physicalIntent)
        h.pc.togglePlayPause(); h.settle()                      // and pauses once more: no stale callback may flip it back
        assertFalse(h.physicalIntent); assertFalse(h.showsPause)
    }

    // ── disconnected ────────────────────────────────────────────────────────────────────────────────────────────────────

    private inner class DHarness {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "tgd")
        val pc = newPc(context)
        val attempts = mutableListOf<SettableFuture<MediaController>>()
        private val controllers = mutableListOf<MediaController>()

        init {
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            rig.settle(50)
            pc.controllerFutureFactory = { SettableFuture.create<MediaController>().also { attempts += it } }
            field("controllerConnectionState").set(pc, ControllerConnectionState.Disconnected)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun complete(): MediaController {
            rig.settle(50)
            val future = MediaController.Builder(context, rig.session.token).setConnectionHints(Bundle()).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 200) rig.settle(20)
            val c = future.get().also { controllers += it }
            attempts.last().set(c)
            rig.settle(400)
            return c
        }
        fun disconnect(c: MediaController) {
            (field("controllerLifecycleListener").get(pc) as MediaController.Listener).onDisconnected(c)
            rig.settle(50)
        }
        fun releaseAll() { runCatching { pc.release() }; controllers.forEach { runCatching { it.release() } }; rig.releaseAll() }
    }

    @Test fun aDeferredPauseThenPlayFollowTheDisplayedState() {
        val d = dharness()
        d.pc.playFromQueue(queue(8), startIndex = 2)
        val c = d.complete()
        d.disconnect(c)
        assertTrue(d.pc.nowPlayingState.value.presentedAsPlaying)
        d.pc.togglePlayPause()                                  // shown Pause -> a deferred Pause
        assertFalse(d.pc.nowPlayingState.value.presentedAsPlaying)
        d.complete()
        assertFalse("the deferred Pause is applied on reconnect", d.rig.player.playWhenReady)
        // and a deferred Play from the shown Play
        val c2 = d.complete().also { d.disconnect(it) }
        assertFalse(d.pc.nowPlayingState.value.presentedAsPlaying)
        d.pc.togglePlayPause()
        assertTrue(d.pc.nowPlayingState.value.presentedAsPlaying)
        d.complete()
        assertTrue("the deferred Play is applied on reconnect", d.rig.player.playWhenReady)
    }

    @Test fun anAcceptedStartThatIsStillParkedShowsPauseAndATapIsADeferredPause() {
        val d = dharness()
        d.pc.playFromQueue(queue(8), startIndex = 2)           // no controller yet: the start is deferred, shown as playing
        assertTrue(d.pc.nowPlayingState.value.presentedAsPlaying)
        d.pc.togglePlayPause()
        assertFalse(d.pc.nowPlayingState.value.presentedAsPlaying)
        d.complete()
        assertFalse("the newer Pause wins over the parked start's Play", d.rig.player.playWhenReady)
    }

    // ── one policy for every control ────────────────────────────────────────────────────────────────────────────────────

    @Test fun everyInAppControlRoutesThroughTheOneToggle() {
        var dir = File(System.getProperty("user.dir")!!).absoluteFile
        while (!File(dir, "app/src/main").isDirectory) dir = dir.parentFile!!
        val ui = File(dir, "app/src/main/kotlin/com/launchpoint/wavdrop/ui")
        for (path in listOf("components/MiniPlayer.kt", "screen/nowplaying/NowPlayingScreen.kt")) {
            val text = File(ui, path).readText()
            assertTrue("$path renders the presentation field", text.contains("presentedAsPlaying"))
            assertFalse("$path must not decide play/pause itself", Regex("""\.(play|pause)\(\)""").containsMatchIn(text))
        }
        val vms = listOf("viewmodel/PlaybackControlsViewModel.kt", "screen/nowplaying/NowPlayingViewModel.kt", "screen/home/HomeViewModel.kt")
        for (path in vms) assertTrue(path, File(ui, path).readText().contains("playerController.togglePlayPause()"))
    }

    // ── presentation precedence ─────────────────────────────────────────────────────────────────────────────────────────

    private fun derive(
        isPlaying: Boolean = false, playWhenReady: Boolean = false, state: Int = Player.STATE_READY,
        suppressed: Boolean = false, error: Boolean = false, armed: Boolean = false,
    ) = PlaybackPresentation.presentedAsPlaying(isPlaying, playWhenReady, state, suppressed, error, armed)

    @Test fun anArmedIntentNeverOverridesAnErrorOrSuppression() {
        assertFalse("error beats an armed intent", derive(error = true, armed = true))
        assertFalse("audio-focus suppression beats an armed intent", derive(suppressed = true, armed = true))
        assertFalse("suppression beats an armed intent even with playWhenReady", derive(playWhenReady = true, suppressed = true, armed = true, state = Player.STATE_BUFFERING))
        assertTrue("an armed intent alone is shown", derive(armed = true))
    }

    @Test fun theToggleActionFollowsTheSamePrecedenceAsTheDisplay() {
        // The action is "Pause iff the control shows Pause": exercised through the one shared function.
        assertTrue(PlaybackPresentation.toggleShouldPause(derive(playWhenReady = true, state = Player.STATE_BUFFERING)))
        assertTrue(PlaybackPresentation.toggleShouldPause(derive(armed = true)))
        assertFalse("a suppressed or failed request offers Play (retry)", PlaybackPresentation.toggleShouldPause(derive(playWhenReady = true, suppressed = true)))
        assertFalse(PlaybackPresentation.toggleShouldPause(derive(error = true, playWhenReady = true, state = Player.STATE_IDLE)))
    }
}

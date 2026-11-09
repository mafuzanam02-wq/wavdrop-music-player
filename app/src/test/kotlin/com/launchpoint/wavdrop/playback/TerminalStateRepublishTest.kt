package com.launchpoint.wavdrop.playback

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * F20E: the final queue item reaches its natural end through the REAL production topology (two-slot [PlayerEngine] -> [SessionFacade]
 * -> MediaSession -> in-process MediaController over real ExoPlayers), with pause-at-end-of-media-items (the sleep boundary's
 * physical enforcement) armed or not.
 *
 * Failure mode under test (seen on a Galaxy A15): once the armed item ended, the engine's own play-when-ready writes and
 * ExoPlayer's asynchronous echoes of them ping-ponged forever (physical true/false, logical true/false, one session publication
 * each), saturating the main thread until Android raised an ANR. Every layer is COUNTED with a hard bound; a guard releases the
 * engine when the bound is crossed so a regression FAILS the test instead of hanging the worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TerminalStateRepublishTest {
    private class Counts {
        var physicalState = 0
        var physicalPlayWhenReady = 0
        var physicalIsPlaying = 0
        var facadeEvents = 0
        var facadePlayWhenReady = 0
        var controllerEvents = 0
        var stormGuardFired = false
        override fun toString() =
            "physState=$physicalState physPWR=$physicalPlayWhenReady physPlaying=$physicalIsPlaying facadeEvents=$facadeEvents " +
                "facadePWR=$facadePlayWhenReady controllerEvents=$controllerEvents stormGuardFired=$stormGuardFired"
    }

    private class Run(val assembly: PlaybackAssembly, val counts: Counts, val controller: MediaController)

    private val context = RuntimeEnvironment.getApplication()
    private val toRelease = mutableListOf<() -> Unit>()
    private var mainTasks = 0
    private companion object {
        var counter = 0
        const val DRAIN_LIMIT = 300
        const val TOTAL_TASK_BUDGET = 6_000
        /** A healthy terminal transition produces a handful of play-when-ready callbacks; anything near this is a loop. */
        const val STORM_PHYSICAL_PWR_THRESHOLD = 300
        const val HEALTHY_PHYSICAL_PWR_MAX = 12
    }

    @After fun tearDown() { toRelease.reversed().forEach { runCatching { it() } } }

    /** Drains the main looper one task at a time with hard caps, so a self-reposting loop fails the test instead of hanging it. */
    private fun boundedIdle(counts: Counts? = null) {
        val looper = shadowOf(Looper.getMainLooper())
        var n = 0
        while (!looper.isIdle) {
            if (n++ >= DRAIN_LIMIT) throw AssertionError("main looper still busy after $DRAIN_LIMIT tasks in one drain: $counts")
            looper.runOneTask()
            if (++mainTasks > TOTAL_TASK_BUDGET) throw AssertionError("main looper task budget exhausted: $counts")
        }
    }

    private fun settleBounded(ms: Long, counts: Counts? = null) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) { boundedIdle(counts); Thread.sleep(5) }
        boundedIdle(counts)
    }

    private fun awaitBounded(counts: Counts?, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { boundedIdle(counts); if (condition()) return; Thread.sleep(5) }
        boundedIdle(counts); check(condition()) { "condition not reached within ${timeoutMs}ms" }
    }

    private fun advance(ms: Long, counts: Counts) {
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            ShadowSystemClock.advanceBy(Duration.ofMillis(step))
            Thread.sleep(8)
            boundedIdle(counts)
            left -= step
            if (counts.stormGuardFired) return
        }
    }

    private fun start(armHold: Boolean, lastItem: Boolean = true): Run {
        val counts = Counts()
        val assembly = assemblePlayback(
            context, rolloutEnabled = true, audioAttributes = AudioAttributes.DEFAULT,
            playerFactory = PhysicalPlayerFactory { _, _, _ -> TestMediaSource.newPlayer(context) },
            sessionIdProvider = AudioSessionIdProvider { 4243 },
        )
        val engine = checkNotNull(assembly.engine)
        toRelease += { engine.release() }
        engine.addCurrentPlayerListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { counts.physicalState++ }
            override fun onIsPlayingChanged(isPlaying: Boolean) { counts.physicalIsPlaying++ }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                counts.physicalPlayWhenReady++
                if (counts.physicalPlayWhenReady > STORM_PHYSICAL_PWR_THRESHOLD && !counts.stormGuardFired) {
                    counts.stormGuardFired = true
                    engine.release() // stops the storm so the test can FAIL with the counts instead of hanging the worker
                }
            }
        })
        assembly.logicalPlayer.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { counts.facadeEvents++ }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { counts.facadePlayWhenReady++ }
        })
        val session = MediaSession.Builder(context, assembly.logicalPlayer).setId("f20e-${counter++}").build()
        toRelease += { session.release() }
        val future = MediaController.Builder(context, session.token).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) boundedIdle(counts)
        val controller = future.get(5, TimeUnit.SECONDS)
        toRelease += { controller.release() }
        controller.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { counts.controllerEvents++ }
        })
        boundedIdle(counts)

        val items = listOf("a", "b", "c").map { MediaItem.Builder().setMediaId(it).setUri("content://x/$it").build() }
        controller.setMediaItems(items)
        controller.prepare()
        awaitBounded(counts) { engine.currentPlayer.playbackState == Player.STATE_READY }
        assembly.setPauseAtEndOfMediaItems(armHold)
        controller.seekTo(if (lastItem) 2 else 0, engine.currentPlayer.duration - 300L)
        settleBounded(100, counts)
        controller.play()
        boundedIdle(counts)
        return Run(assembly, counts, controller)
    }

    /** Plays the item to its natural end, then lets virtual time run on, with the counters sampled. */
    private fun playToEndAndSettle(r: Run) {
        advance(1_500, r.counts)
        advance(5_000, r.counts)
    }

    private fun assertNoStorm(label: String, r: Run) {
        assertFalse("$label: republish storm (guard fired): ${r.counts}", r.counts.stormGuardFired)
        assertTrue("$label: physical play-when-ready callbacks must stay bounded: ${r.counts}", r.counts.physicalPlayWhenReady <= HEALTHY_PHYSICAL_PWR_MAX)
        assertTrue("$label: facade events must stay bounded: ${r.counts}", r.counts.facadeEvents <= 60)
        assertTrue("$label: controller events must stay bounded: ${r.counts}", r.counts.controllerEvents <= 60)
        assertTrue("$label: the main looper must drain: ${r.counts}", shadowOf(Looper.getMainLooper()).isIdle)
    }

    @Test fun `final item with the boundary armed settles without a republish storm`() {
        val r = start(armHold = true)
        playToEndAndSettle(r)
        assertNoStorm("armed", r)
        val p = r.assembly.currentPlayer
        assertEquals("stays on the final occurrence", 2, p.currentMediaItemIndex)
        val diag = "physPwr=${p.playWhenReady} state=${p.playbackState} logicalPwr=${r.assembly.logicalPlayer.playWhenReady} " +
            "logicalState=${r.assembly.logicalPlayer.playbackState} counts=${r.counts}"
        assertFalse("the armed boundary stopped playback at the end: $diag", p.playWhenReady)
        assertFalse("logical state agrees: $diag", r.assembly.logicalPlayer.playWhenReady)
        assertTrue("terminal position", p.currentPosition >= p.duration - 50L)
    }

    @Test fun `final item with no boundary armed settles without a republish storm`() {
        val r = start(armHold = false)
        playToEndAndSettle(r)
        assertNoStorm("unarmed", r)
        assertEquals(Player.STATE_ENDED, r.assembly.currentPlayer.playbackState)
    }

    @Test fun `middle item with the boundary armed also settles without a republish storm`() {
        val r = start(armHold = true, lastItem = false)
        playToEndAndSettle(r)
        assertNoStorm("armed-middle", r)
        assertEquals("the hold keeps the player on the armed item", 0, r.assembly.currentPlayer.currentMediaItemIndex)
        assertFalse(r.assembly.currentPlayer.playWhenReady)
    }

    @Test fun `releasing the boundary after the terminal stop lets an ordinary Play resume`() {
        val r = start(armHold = true)
        playToEndAndSettle(r)
        assertNoStorm("armed", r)
        val before = r.counts.physicalPlayWhenReady
        r.assembly.setPauseAtEndOfMediaItems(false)
        r.controller.seekTo(2, 0L)
        r.controller.play()
        boundedIdle(r.counts)
        awaitBounded(r.counts) { r.assembly.currentPlayer.isPlaying }
        assertTrue("Play resumed", r.assembly.logicalPlayer.playWhenReady)
        assertFalse("no storm after resuming: ${r.counts}", r.counts.stormGuardFired)
        assertTrue("bounded callbacks for the resume: ${r.counts}", r.counts.physicalPlayWhenReady - before <= HEALTHY_PHYSICAL_PWR_MAX)
    }
}

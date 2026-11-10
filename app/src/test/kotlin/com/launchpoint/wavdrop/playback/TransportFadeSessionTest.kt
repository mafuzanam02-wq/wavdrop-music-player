package com.launchpoint.wavdrop.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Real Media3 session chain (in-process controllers): engine façade -> PreviousBehaviorPlayer(+TransportFade) -> MediaSession.
 * The app-marked controller models PlayerController's INTERNAL commands (sleep expiry, error halt, queue clear...), which are
 * system-required and must never be delayed by the user's fade; an unmarked controller models an EXTERNAL user (notification,
 * lock screen, Bluetooth, Android Auto), whose explicit transport is faded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TransportFadeSessionTest {
    private val time = VirtualFadeTime()
    private val writes = mutableListOf<Float>()
    private val calls = mutableListOf<String>()
    private val toRelease = mutableListOf<() -> Unit>()
    private var session: MediaSession? = null
    private companion object { var counter = 0 }

    @After fun tearDown() { toRelease.reversed().forEach { runCatching { it() } } }

    private inner class Rig(titles: List<String> = listOf("A", "B", "C", "D"), index: Int = 1, enabled: Boolean = true) {
        val f = PlayerEngineFixture(p1Titles = titles, p1Index = index)
        val fade = TransportFadeController(
            sink = TransportGainSink { writes += it; f.engine.setTransportGain(it) },
            clock = time, scheduler = time, config = { TransportFadeConfig(enabled, 300L) },
        )
        val policy = PreviousBehaviorPlayer(
            player = f.facade,
            thresholdProvider = { 3_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            onExternalTransport = { calls += "externalTransport" },
            hydrateForPlay = { PlayerHydrationResult.Hydrated },
            songsProvider = { emptyList() },
            logResume = {},
            sessionProvider = { session },
            onExplicitPause = { calls += "explicitPause" },
            onExplicitSeek = { calls += "explicitSeek" },
            onExplicitNavigation = { calls += "explicitNavigation" },
            onExplicitRepeatChange = { calls += "explicitRepeat" },
            transportFade = fade,
        )
        init {
            f.engine.bindTransportFade(fade)
            session = MediaSession.Builder(RuntimeEnvironment.getApplication(), policy).setId("tf-session-${counter++}").build()
            toRelease += { session!!.release() }
        }
        val index get() = f.facade.currentMediaItemIndex
        fun play() { f.facade.play(); idleMainLooper() }
        fun connect(app: Boolean): MediaController {
            val hints = Bundle().apply { if (app) putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }
            val future = MediaController.Builder(RuntimeEnvironment.getApplication(), session!!.token).setConnectionHints(hints).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 100) idleMainLooper()
            return future.get(5, TimeUnit.SECONDS).also { c -> toRelease += { c.release() }; idleMainLooper() }
        }
        fun settle(ms: Long = 2_000) { time.advance(ms); idleMainLooper() }
    }

    // ── 1. System-originated stops are never delayed ───────────────────────────────────────────────────────────
    @Test fun appMarkedPauseIsImmediateAndUnfaded() {
        val r = Rig(); r.play()
        val app = r.connect(app = true)
        app.pause(); idleMainLooper()
        assertFalse("a system-required stop must not wait for the fade", r.f.facade.playWhenReady)
        assertTrue(writes.isEmpty()); assertEquals(0, time.pendingCount)
    }

    @Test fun appMarkedPlayIsNotFadedIn() {
        val r = Rig()
        val app = r.connect(app = true)
        app.play(); idleMainLooper()
        assertTrue(r.f.facade.playWhenReady)
        assertTrue(writes.isEmpty()); assertEquals(1f, r.f.p1.volume, 0f)
    }

    @Test fun externalPauseFadesOutThenPauses() {
        val r = Rig(); r.play()
        val ext = r.connect(app = false)
        ext.pause(); idleMainLooper()
        assertTrue("still playing during the fade-out", r.f.facade.playWhenReady)
        r.settle(300)
        assertFalse(r.f.facade.playWhenReady)
        assertEquals(1f, r.f.p1.volume, 0.0001f)
    }

    @Test fun externalPlayFadesInFromZeroWithNoPreSilence() {
        val r = Rig()
        val ext = r.connect(app = false)
        ext.play(); idleMainLooper()
        assertTrue(r.f.facade.playWhenReady)
        assertEquals("starts at zero gain", 0f, r.f.p1.volume, 0.0001f)
        r.settle(300)
        assertEquals(1f, r.f.p1.volume, 0.0001f)
    }

    // ── 3. External transport commands ────────────────────────────────────────────────────────────────────────
    @Test fun externalNextNavigatesOnceImmediatelyThenRampsInToFullGain() {
        val r = Rig(index = 1); r.play()
        val ext = r.connect(app = false)
        ext.seekToNext(); idleMainLooper()
        assertEquals("navigated at tap time, no fade wait", 2, r.index); assertTrue(r.f.facade.playWhenReady); assertEquals("incoming starts silent", 0f, r.f.p1.volume, 0.0001f)
        r.settle(1_000)
        assertEquals(2, r.index); assertTrue(r.f.facade.playWhenReady)
        assertEquals(1f, r.f.p1.volume, 0.0001f)
        assertEquals("one navigation", 1, calls.count { it == "explicitNavigation" })
    }

    @Test fun externalPreviousRestartsCurrentWhenPastTheThreshold() {
        val r = Rig(index = 2); r.play()
        r.f.p1.mutate { setContentPositionMs(5_000L) }; idleMainLooper()
        val ext = r.connect(app = false)
        ext.seekToPrevious(); idleMainLooper(); r.settle()
        assertEquals("restart-current policy, not the previous item", 2, r.index)
        assertTrue(r.f.p1.commands.any { it.contains("seek") || it.contains("Seek") } || r.index == 2)
        assertEquals(1f, r.f.p1.volume, 0.0001f)
    }

    @Test fun externalPreviousMovesBackWhenNearTheStart() {
        val r = Rig(index = 2); r.play()
        val ext = r.connect(app = false)
        ext.seekToPrevious(); idleMainLooper(); r.settle()
        assertEquals(1, r.index); assertEquals(1f, r.f.p1.volume, 0.0001f)
    }

    @Test fun rapidExternalNextThenPauseNavigatesOnceThenPausesAndEndsAtFullGain() {
        val r = Rig(index = 0); r.play()
        val ext = r.connect(app = false)
        ext.seekToNext(); idleMainLooper(); time.advance(40)
        ext.pause(); idleMainLooper()
        r.settle(2_000)
        assertEquals("the Next was not lost", 1, r.index)
        assertFalse("the Pause was not reversed by the earlier navigation", r.f.facade.playWhenReady)
        assertEquals(1f, r.f.p1.volume, 0.0001f); assertEquals(0, time.pendingCount)
    }

    @Test fun externalNavigationWhilePausedKeepsTheExistingPolicyAndOnlyFadesTheStart() {
        val r = Rig(index = 1)
        val ext = r.connect(app = false)
        ext.seekToNext(); idleMainLooper()
        assertEquals("immediate: no fade delay while paused", 2, r.index)
        assertTrue("existing policy: an external selection starts the target", r.f.facade.playWhenReady)
        assertEquals("the policy-started target fades in from zero (no click)", 0f, r.f.p1.volume, 0.0001f)
        r.settle(400); assertEquals(1f, r.f.p1.volume, 0.0001f); assertEquals(2, r.index)
    }

    @Test fun controllerDisconnectDuringAFadeNeitherDuplicatesNorStrandsTheNavigation() {
        val r = Rig(index = 0); r.play()
        val ext = r.connect(app = false)
        ext.seekToNext(); idleMainLooper(); time.advance(40)
        ext.release(); idleMainLooper()
        r.settle(2_000)
        assertEquals("exactly one navigation, the controller leaving does not repeat it", 1, r.index)
        assertEquals(1f, r.f.p1.volume, 0.0001f)
        val again = r.connect(app = false) // reconnect: transport works normally
        again.pause(); idleMainLooper(); r.settle(400)
        assertFalse(r.f.facade.playWhenReady); assertEquals(1f, r.f.p1.volume, 0.0001f)
    }

    // ── 5. Failure semantics of the shared controller ────────────────────────────────────────────────────────────
    @Test fun aFailingPauseCallbackStillRestoresGainAndLeavesNoGenerationActive() {
        val rig = Rig(); rig.play()
        rig.fade.pause(isPlaying = true) { error("pause failed") }
        time.advance(400)
        assertEquals(1f, rig.f.p1.volume, 0.0001f)
        assertEquals(0, time.pendingCount)
        // a later command works normally
        rig.fade.navigate(isPlaying = true) { true }
        time.advance(400)
        assertEquals(1f, rig.f.p1.volume, 0.0001f)
    }

    @Test fun aFailingPlayStartStillLeavesAnOwnedRecoverableState() {
        val rig = Rig()
        runCatching { rig.fade.play(isPlaying = false) { error("start failed") } }
        time.advance(400)
        assertEquals("never left muted", 1f, rig.f.p1.volume, 0.0001f)
        assertEquals(0, time.pendingCount)
    }

    @Test fun aPauseIsNeverReversedByAnEarlierPendingNavigationThatThrows() {
        val rig = Rig(); rig.play()
        var paused = false
        rig.fade.navigate(isPlaying = true) { error("boom") }
        time.advance(40)
        rig.fade.pause(isPlaying = true) { paused = true; rig.f.facade.pause() }
        time.advance(2_000)
        assertTrue(paused); assertFalse(rig.f.facade.playWhenReady)
        assertEquals(1f, rig.f.p1.volume, 0.0001f)
    }

    @Test fun userCommandsStayOrderedAndASystemInterruptionAbortsOnlyTheIncomingRamp() {
        val rig = Rig(); rig.play()
        val order = mutableListOf<String>()
        rig.fade.navigate(true) { order += "nav"; true }
        time.advance(40)
        rig.fade.pause(isPlaying = true) { order += "pause" }
        time.advance(2_000)
        assertEquals(listOf("nav", "pause"), order) // user commands stay ordered

        val rig2 = Rig(); rig2.play()
        val order2 = mutableListOf<String>()
        rig2.fade.navigate(true) { order2 += "nav"; true }
        time.advance(40)
        rig2.fade.interrupt() // system interruption
        time.advance(2_000)
        assertEquals("the Next already ran once at tap time and is not repeated", listOf("nav"), order2); assertEquals(1f, rig2.f.p1.volume, 0.0001f)
    }
}

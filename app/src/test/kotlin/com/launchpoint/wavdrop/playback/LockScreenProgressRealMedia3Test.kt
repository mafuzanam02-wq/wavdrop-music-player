package com.launchpoint.wavdrop.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * LS-1: does the position the SYSTEM sees keep moving? Everything here runs the PRODUCTION session chain on real Media3
 * (two real ExoPlayers -> PlayerEngine -> SessionFacade -> PreviousBehaviorPlayer -> a real MediaLibrarySession -> a real
 * MediaController) with a deterministic clock. No PlayerController position ticker, no Activity and no Compose is involved:
 * the only authority is the logical CURRENT physical player exposed through the session.
 *
 * Every sample records, at one instant: the physical CURRENT position, the session-facing player position (what the session
 * publishes), the controller position (what an external client reads), the conservative event-only system-surface model, and
 * the playback tuple. See [SessionProgressRig].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LockScreenProgressRealMedia3Test {

    private val rigs = mutableListOf<SessionProgressRig>()
    private val models = mutableListOf<SystemSurfaceModel>()
    @After fun tearDown() { models.forEach { runCatching { it.release() } }; rigs.forEach { runCatching { it.release() } } }

    private fun rig(items: Int = 6, start: Int = 0): SessionProgressRig = SessionProgressRig(itemCount = items, startIndex = start).also { rigs += it }
    private fun model(r: SessionProgressRig) = SystemSurfaceModel(r.policy).also { models += it }

    private fun SessionProgressRig.play() = command { play() }
    private fun SessionProgressRig.pause() = command { pause() }

    /** The controller (what an external client reads) stays on the physical CURRENT and the session player equals it. */
    private fun assertTracksPhysical(label: String, samples: List<SessionProbe.Sample>, tolerance: Long = TOL) {
        samples.forEachIndexed { i, s ->
            assertEquals("$label[$i] session player == physical: $s", s.physical, s.sessionPlayer)
            assertTrue("$label[$i] controller within ${tolerance}ms of physical: $s", abs(s.controller - s.physical) <= tolerance)
        }
    }

    private fun assertAdvancing(label: String, samples: List<SessionProbe.Sample>, stepMs: Long) {
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            assertTrue("$label: physical advanced $a -> $b", b.physical - a.physical >= stepMs - 150)
            assertTrue("$label: session player advanced $a -> $b", b.sessionPlayer - a.sessionPlayer >= stepMs - 150)
            assertTrue("$label: controller advanced $a -> $b", b.controller - a.controller >= stepMs - TOL)
        }
    }

    private fun assertFrozen(label: String, samples: List<SessionProbe.Sample>) {
        val first = samples.first()
        samples.forEach {
            assertEquals("$label physical frozen", first.physical, it.physical)
            assertEquals("$label session player frozen", first.sessionPlayer, it.sessionPlayer)
            assertEquals("$label controller frozen", first.controller, it.controller)
            assertFalse("$label not playing: $it", it.isPlaying)
        }
    }

    // ── 1. ordinary playback: every layer advances ──────────────────────────────────────────────────────────────────────

    @Test fun `ordinary playback advances at every layer and the system-surface model stays on the physical position`() {
        val r = rig(); val m = model(r)
        r.play()
        val samples = mutableListOf<SessionProbe.Sample>()
        val modelErrors = mutableListOf<Long>()
        repeat(10) {
            r.advance(1_000)
            samples += r.sample()
            modelErrors += abs(m.expectedPositionMs() - r.engine.currentPlayer.currentPosition)
        }
        assertTracksPhysical("play", samples)
        assertAdvancing("play", samples, 1_000)
        assertTrue("monotonic", samples.zipWithNext().all { (a, b) -> b.controller >= a.controller })
        samples.forEach {
            assertEquals(Player.STATE_READY, it.state); assertTrue(it.playWhenReady); assertTrue(it.isPlaying); assertEquals(0, it.index)
        }
        assertTrue("event-only surface model within tolerance: $modelErrors", modelErrors.all { it <= TOL })
        assertTrue("moved ~10s overall: ${samples.last()}", samples.last().controller >= 9_500)
    }

    // ── 2./3. pause freezes, resume restarts from the paused position ───────────────────────────────────────────────────

    @Test fun `pause freezes every layer and resume restarts advancement from the paused position`() {
        val r = rig(); val m = model(r)
        r.play(); r.advance(3_000)
        r.pause()
        val paused = r.sample()
        assertFalse(paused.isPlaying)
        val frozen = r.sampleSeries(5, 2_000)
        assertFrozen("pause", frozen)
        assertTrue("surface model also frozen", abs(m.expectedPositionMs() - paused.physical) <= TOL)

        r.play()
        val resumed = r.sampleSeries(6, 1_000)
        assertTracksPhysical("resume", resumed)
        assertAdvancing("resume", resumed, 1_000)
        assertTrue("restarted from the paused position, not from 0: $resumed", resumed.first().controller >= paused.controller)
        assertTrue(resumed.first().controller - paused.controller <= TOL + 100)
        assertTrue(resumed.all { it.isPlaying })
        assertTrue("surface model follows the resume", abs(m.expectedPositionMs() - r.engine.currentPlayer.currentPosition) <= TOL)
    }

    // ── 4. seek jumps and then keeps moving (external seek, previous-restart) ───────────────────────────────────────────

    @Test fun `an external seek jumps the controller then playback continues from the target`() {
        val r = rig(); val m = model(r)
        r.play(); r.advance(2_000)
        r.command { seekTo(90_000L) }
        val afterSeek = r.sampleSeries(5, 1_000)
        assertTracksPhysical("seek", afterSeek)
        assertTrue("jumped to the target: ${afterSeek.first()}", abs(afterSeek.first().controller - 90_000L) <= TOL + 100)
        assertAdvancing("seek", afterSeek, 1_000)
        r.command { seekTo(20_000L) } // backwards
        val back = r.sampleSeries(4, 1_000)
        assertTracksPhysical("seek-back", back)
        assertTrue(abs(back.first().controller - 20_000L) <= TOL + 100)
        assertAdvancing("seek-back", back, 1_000)
        assertTrue("surface model re-anchored by the discontinuity", abs(m.expectedPositionMs() - r.engine.currentPlayer.currentPosition) <= TOL)
    }

    @Test fun `previous past the restart threshold restarts the current item and it keeps advancing without changing the item`() {
        val r = rig(start = 2)
        r.play(); r.advance(8_000) // beyond the 3s previous-restart threshold
        r.command { seekToPrevious() }
        val s = r.sampleSeries(5, 1_000)
        assertTracksPhysical("restart", s)
        assertEquals("previous semantics unchanged: restart, not previous item", 2, s.first().index)
        assertTrue("restarted near 0: ${s.first()}", s.first().controller <= 400)
        assertAdvancing("restart", s, 1_000)
    }

    // ── 5. native gapless transition with crossfade OFF ─────────────────────────────────────────────────────────────────

    @Test fun `a native transition to the next item resets the position and then keeps advancing`() {
        val r = rig(); val m = model(r)
        val events = EventRecorder().also { r.controller.addListener(it) }
        r.play()
        r.command { seekTo(r.controller.duration - 400L) }
        r.advance(1_500) // A plays out; the single physical player advances to B natively
        val s = r.sampleSeries(6, 1_000)
        assertEquals("moved to the next item", 1, s.first().index)
        assertSame("no promotion happened: the single physical player did it", r.p1, r.engine.currentPlayer)
        assertTracksPhysical("native", s)
        assertTrue("position belongs to B (restarted): ${s.first()}", s.first().controller < 3_000)
        assertAdvancing("native", s, 1_000)
        assertEquals(events.events.toString(), 1, events.events.count { it.startsWith("transition(") && it.endsWith("AUTO)") })
        assertTrue(abs(m.expectedPositionMs() - r.engine.currentPlayer.currentPosition) <= TOL)
        assertTrue(s.all { it.controllerDuration > 0 })
    }

    // ── 6. one real promotion ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `after a real promotion the session position stays LIVE and is not pinned to the promoted position`() {
        val r = rig(); val m = model(r)
        val tokenBefore = r.session.token
        val events = EventRecorder().also { r.controller.addListener(it) }
        r.play(); r.advance(4_000)
        val key = r.prepareNext(0)
        events.events.clear()

        val result = r.promote(key)
        assertTrue(result.toString(), result is PromotionStartResult.Promoted)
        assertSame("B is the physical CURRENT and the session player follows it", r.p2, r.engine.currentPlayer)
        assertSame(r.p2, r.facade.delegatePlayer)

        val immediate = r.sample()
        assertEquals("promoted into B", 1, immediate.index)
        assertTrue("session position begins at B's live position (near 0): $immediate", immediate.sessionPlayer in 0L..1_500L)
        assertEquals(immediate.physical, immediate.sessionPlayer)

        val after = r.sampleSeries(8, 1_000)
        assertTracksPhysical("promoted", after)
        assertAdvancing("promoted", after, 1_000)
        assertTrue("the pin was consumed by exactly one evaluation: ${after.last().sessionPlayer} vs ${immediate.sessionPlayer}", after.last().sessionPlayer >= immediate.sessionPlayer + 6_500)
        assertTrue(abs(m.expectedPositionMs() - r.p2.currentPosition) <= TOL)

        r.finishOverlap()
        val settled = r.sampleSeries(5, 1_000)
        assertTracksPhysical("after overlap", settled)
        assertAdvancing("after overlap", settled, 1_000)

        assertEquals("exactly one logical AUTO transition", 1, events.events.count { it.startsWith("transition(") })
        assertTrue(events.events.toString(), events.events.any { it.endsWith("AUTO)") && it.startsWith("transition(") })
        assertFalse("no isPlaying flicker", events.events.any { it.startsWith("isPlaying") })
        assertEquals("one discontinuity", 1, events.events.count { it.startsWith("discontinuity(") })
        assertEquals("same session identity", tokenBefore, r.session.token)
        assertTrue(r.controller.isConnected)
        assertSame(r.policy, r.session.player)
    }

    // ── 7. repeated promotions A->B->C->D ────────────────────────────────────────────────────────────────────────────────

    @Test fun `repeated promotions never leave the session position frozen`() {
        val r = rig(items = 6); val m = model(r)
        val tokenBefore = r.session.token
        r.play()
        for (from in 0..3) {
            r.advance(3_000)
            val key = r.prepareNext(from)
            assertTrue("promotion $from->${from + 1}", r.promote(key) is PromotionStartResult.Promoted)
            val expectedPlayer = if (from % 2 == 0) r.p2 else r.p1
            assertSame("physical CURRENT alternates", expectedPlayer, r.engine.currentPlayer)
            assertSame(expectedPlayer, r.facade.delegatePlayer)

            val during = r.sampleSeries(4, 1_000)
            assertTracksPhysical("promo $from during overlap", during)
            assertAdvancing("promo $from during overlap", during, 1_000)
            assertEquals(from + 1, during.last().index)

            r.finishOverlap()
            val afterOverlap = r.sampleSeries(4, 1_000)
            assertTracksPhysical("promo $from after overlap", afterOverlap)
            assertAdvancing("promo $from after overlap", afterOverlap, 1_000)
            assertTrue(afterOverlap.last().controllerDuration > 0)
            assertTrue("surface model ok after promotion $from", abs(m.expectedPositionMs() - r.engine.currentPlayer.currentPosition) <= TOL)
        }
        // an ordinary external seek and pause/resume still work after four swaps
        r.command { seekTo(100_000L) }
        val seeked = r.sampleSeries(4, 1_000)
        assertTracksPhysical("seek after promotions", seeked)
        assertTrue(abs(seeked.first().controller - 100_000L) <= TOL + 100)
        assertAdvancing("seek after promotions", seeked, 1_000)
        r.pause()
        assertFrozen("pause after promotions", r.sampleSeries(3, 1_500))
        r.play()
        assertAdvancing("resume after promotions", r.sampleSeries(4, 1_000), 1_000)
        assertEquals("one session, one controller across every swap", tokenBefore, r.session.token)
        assertTrue(r.controller.isConnected)
        assertEquals("the queue the controller sees never lost identity: ${r.controller.mediaItemCount}", 6, r.controller.mediaItemCount)
    }

    // ── 8./9. logical play-state owner and suppression ────────────────────────────────────────────────────────────────

    @Test fun `the logical owner tuple is coherent and a transient focus loss freezes the position until focus returns`() {
        val r = rig(); val m = model(r)
        val context: Context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        r.play(); r.advance(3_000)

        val playing = r.sample()
        assertTrue(r.facade.playWhenReady && r.engine.currentPlayer.playWhenReady && playing.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, r.facade.playbackSuppressionReason)
        assertTrue("READY + logical playWhenReady + no suppression => playing", r.facade.isPlaying && playing.isPlaying)

        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        r.idle()
        r.advance(60)
        val suppressed = r.sample()
        assertTrue("logical intent is still to play", r.facade.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, r.facade.playbackSuppressionReason)
        assertFalse("physical is genuinely stopped", r.engine.currentPlayer.playWhenReady)
        assertFalse(r.facade.isPlaying); assertFalse(suppressed.isPlaying)
        assertEquals(Player.STATE_READY, suppressed.state)
        val frozen = r.sampleSeries(4, 1_500)
        assertFrozen("suppressed", frozen)
        assertTrue("surface model also stopped (no faked advancement)", abs(m.expectedPositionMs() - r.engine.currentPlayer.currentPosition) <= TOL)

        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        r.idle()
        val regained = r.sampleSeries(6, 1_000)
        assertTracksPhysical("focus regained", regained)
        assertAdvancing("focus regained", regained, 1_000)
        assertTrue(regained.all { it.isPlaying })
        assertTrue(regained.first().controller - frozen.last().controller <= TOL + 100)
        assertTrue(abs(m.expectedPositionMs() - r.engine.currentPlayer.currentPosition) <= TOL)
    }

    // ── 11. duration ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the controller always receives a valid duration for the current item across start, seek, transition and promotion`() {
        val r = rig()
        r.play()
        val all = mutableListOf<SessionProbe.Sample>()
        all += r.sampleSeries(3, 1_000)
        r.command { seekTo(50_000L) }
        all += r.sampleSeries(3, 1_000)
        r.advance(500)
        val key = r.prepareNext(0)
        r.promote(key)
        all += r.sampleSeries(3, 1_000)
        r.finishOverlap()
        all += r.sampleSeries(3, 1_000)
        r.command { seekTo(r.controller.duration - 400L) }
        r.advance(1_500)
        all += r.sampleSeries(3, 1_000)
        assertTrue("duration > 0 in every sample: ${all.map { it.controllerDuration }}", all.all { it.controllerDuration > 0 })
        assertTrue("duration is the timeline's real duration, not fabricated", all.all { it.controllerDuration == TestMediaSource.DURATION_US / 1_000 })
        assertTrue("position never exceeds duration", all.all { it.controller <= it.controllerDuration })
    }

    // ── 12. identity ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `session and controller identity are stable through play, pause, seek, transition and promotion`() {
        val r = rig()
        val token = r.session.token
        val controller = r.controller
        r.play(); r.advance(2_000); r.pause(); r.play(); r.command { seekTo(30_000L) }
        r.command { seekTo(r.controller.duration - 400L) }; r.advance(1_500)
        val key = r.prepareNext(1)
        assertTrue(r.promote(key) is PromotionStartResult.Promoted)
        r.finishOverlap(); r.advance(1_000)
        assertEquals(token, r.session.token)
        assertSame(controller, r.controller)
        assertTrue(r.controller.isConnected)
        assertSame(r.policy, r.session.player)
        assertEquals("exactly one session-facing facade", r.facade, r.policy.wrappedPlayer)
    }

    private companion object {
        /** Controller vs physical: the 2 ms simulated IPC hop plus extrapolation rounding; far below any visible progress jump. */
        const val TOL = 250L
    }
}

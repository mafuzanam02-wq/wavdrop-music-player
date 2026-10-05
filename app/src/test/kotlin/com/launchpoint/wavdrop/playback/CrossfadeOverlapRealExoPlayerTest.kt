package com.launchpoint.wavdrop.playback

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import java.util.concurrent.TimeUnit
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

/**
 * CF-2M6 structural proof on REAL ExoPlayers (codec-free source) and a real MediaSession/MediaController: an interruption during
 * an active overlap leaves ONE authoritative player (B) with A stopped and cleared, moves only B for seek/pause, keeps duck as a
 * pure volume composition without swapping roles, and never produces a second AUTO transition or timeline change. Robolectric
 * freezes the playback clock, so A's natural end/error cannot be produced on a real player; those paths are covered on the
 * scripted physicals. Not a claim about audible smoothness (CF-2M7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadeOverlapRealExoPlayerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val p1: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
    private val p2: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
    private val engine = PlayerEngine(context, p1, p2, SHARED_SESSION_ID, AudioAttributes.DEFAULT)
    private val queue = (0 until 6).map { ScriptedPlayer.mediaItem("m$it") }
    private val key = CrossfadeTransitionKey(1L, 2, 3)
    private val toRelease = mutableListOf<() -> Unit>()
    private val runtime = CrossfadePromotionRuntime(
        engine = engine,
        snapshotProvider = { error("not evaluated: the test promotes through the engine and settles through the runtime seam") },
        configuredDurationMsProvider = { 6_000L },
        scheduler = OverlapScheduler(),
        clock = { 0L },
    )

    @After fun tearDown() {
        toRelease.reversed().forEach { runCatching { it() } }
        runtime.close()
        engine.release()
    }

    private fun overlap() {
        engine.facade.setMediaItems(queue, 2, 0L)
        engine.facade.prepare()
        engine.facade.play()
        TestMediaSource.awaitCondition { p1.playbackState == Player.STATE_READY && p1.playWhenReady }
        engine.nextPreparation.request(NextSlotRequest(key, queue))
        TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
        TestMediaSource.settle(100)
        assertTrue(engine.promoteReadyNext(key) is PromotionStartResult.Promoted)
        engine.stripRetiringTail()
        TestMediaSource.settle(200)
    }

    private fun assertOneAuthority() {
        assertSame(p2, engine.currentPlayer)
        assertSame(p2, engine.facade.delegatePlayer)
        assertEquals("A is emptied", 0, p1.mediaItemCount)
        assertFalse(p1.playWhenReady)
        assertEquals(Player.STATE_IDLE, p1.playbackState)
        assertFalse("A is recycled, not released", p1.isReleased)
        assertEquals(1f, p1.volume, 0f)
        assertEquals("B keeps the whole mirrored queue at the exact index", queue.size, p2.mediaItemCount)
        assertEquals(3, p2.currentMediaItemIndex)
        assertFalse(engine.promotionActive)
    }

    @Test fun pauseDuringAnActiveOverlapLeavesBCurrentAndPausedAndAStoppedAndCleared() {
        overlap()
        val events = EventRecorder().also { engine.facade.addListener(it) }
        engine.facade.pause()
        TestMediaSource.settle(200)
        assertOneAuthority()
        assertFalse("B is paused", p2.playWhenReady)
        assertEquals(1, events.events.count { it.startsWith("playWhenReady(false") })
        assertTrue("no second AUTO, no timeline change, no discontinuity: ${events.events}", events.events.none { it.startsWith("transition(") || it.startsWith("timeline(") || it.startsWith("discontinuity(") })
        engine.facade.play(); TestMediaSource.settle(200)
        assertTrue("resume resumes B only", p2.playWhenReady)
        assertEquals(0, p1.mediaItemCount)
    }

    @Test fun seekDuringAnActiveOverlapMovesBOnlyAndAIsNeverSeeked() {
        overlap()
        val aRecorder = object : Player.Listener {
            val discontinuities = mutableListOf<Int>()
            override fun onPositionDiscontinuity(o: Player.PositionInfo, n: Player.PositionInfo, reason: Int) { discontinuities += reason }
        }.also { p1.addListener(it) }
        val events = EventRecorder().also { engine.facade.addListener(it) }
        engine.facade.seekTo(40_000L)
        TestMediaSource.settle(300)
        assertOneAuthority()
        assertTrue("B plays on at the sought position: ${p2.currentPosition}", p2.playWhenReady && p2.currentPosition >= 40_000L)
        assertTrue("A received no seek: ${aRecorder.discontinuities}", aRecorder.discontinuities.none { it == Player.DISCONTINUITY_REASON_SEEK })
        assertTrue("only the real player's normal seek discontinuities (never an AUTO one): ${events.events}", events.events.filter { it.startsWith("discontinuity(") }.let { d -> d.isNotEmpty() && d.all { it.endsWith(",SEEK)") } })
        assertTrue(events.events.none { it == "transition(m3,AUTO)" || it.startsWith("timeline(") })
    }

    @Test fun aDuckUpdatesBothVolumesWithoutSwappingRolesOrSettling() {
        overlap()
        val g = CrossfadeGainCurve.equalPower(0.4f)
        engine.setFadeGains(g.outgoing, g.incoming)
        val roleBefore = engine.currentPlayer
        val focus = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).lastAudioFocusRequest.listener
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        TestMediaSource.settle(100)
        assertEquals(g.outgoing * 0.2f, p1.volume, 1e-5f)
        assertEquals(g.incoming * 0.2f, p2.volume, 1e-5f)
        assertSame("a duck never swaps roles", roleBefore, engine.currentPlayer)
        assertSame(p1, engine.retiringPlayer)
        assertTrue("the overlap still exists", engine.promotionActive)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        TestMediaSource.settle(100)
        assertEquals(g.outgoing, p1.volume, 1e-5f)
        assertEquals(g.incoming, p2.volume, 1e-5f)
    }

    @Test fun focusLossDuringAnActiveOverlapLeavesOneAuthoritativePlayerAndRegainResumesItOnly() {
        overlap()
        val events = EventRecorder().also { engine.facade.addListener(it) }
        val focus = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).lastAudioFocusRequest.listener
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        TestMediaSource.settle(200)
        assertOneAuthority()
        assertFalse("B is suppressed", p2.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, engine.facade.playbackSuppressionReason)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        TestMediaSource.settle(200)
        assertTrue(p2.playWhenReady)
        assertEquals(0, p1.mediaItemCount)
        assertTrue("no second AUTO/timeline event: ${events.events}", events.events.none { it.startsWith("transition(") || it.startsWith("timeline(") })
    }

    @Test fun theRetiringPlayersOwnEventsNeverSurfaceThroughTheFacadeAndAnInterruptedSettlementIsSilent() {
        overlap()
        val events = EventRecorder().also { engine.facade.addListener(it) }
        p1.seekTo(2, 179_000L) // physical manipulation of the retiring player only
        TestMediaSource.settle(200)
        assertTrue("retiring state is invisible to the session: ${events.events}", events.events.isEmpty())
        runtime.settleOverlap(PromotionInterruption.Other)
        TestMediaSource.settle(200)
        assertOneAuthority()
        assertTrue("settlement alone emits no logical event: ${events.events}", events.events.isEmpty())
        assertTrue(p2.playWhenReady)
    }

    @Test fun aRealControllerSeesExactlyOnePauseAndNoSecondTransitionWhenPausedDuringTheOverlap() {
        val session = MediaSession.Builder(context, engine.facade).setId("cf2m6-real").build().also { s -> toRelease += { s.release() } }
        overlap()
        val future = MediaController.Builder(context, session.token).setConnectionHints(Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 50) idleMainLooper()
        val controller = future.get(5, TimeUnit.SECONDS).also { c -> toRelease += { c.release() } }
        idleMainLooper()
        val events = EventRecorder().also { controller.addListener(it) }
        controller.pause()
        TestMediaSource.settle(300)
        idleMainLooper()
        assertOneAuthority()
        assertEquals("one pause at the controller: ${events.events}", 1, events.events.count { it.startsWith("playWhenReady(false") })
        assertTrue(events.events.none { it.startsWith("transition(") || it.startsWith("timeline(") })
        assertFalse(controller.isPlaying)
        assertEquals("m3", controller.currentMediaItem?.mediaId)
    }
}

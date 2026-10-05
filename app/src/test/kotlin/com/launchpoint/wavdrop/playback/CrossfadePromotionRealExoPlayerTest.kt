package com.launchpoint.wavdrop.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CF-2M5 structural proof on REAL ExoPlayers (codec-free source, real playback thread, real Timelines): the prepared B starts
 * without seek/reset, the façade follows it with one AUTO event, the retiring A's tail strip changes nothing about A, A can never
 * advance into its own B, and the recycled player can be prepared again. Not a claim about audio smoothness (CF-2M7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadePromotionRealExoPlayerTest {

    private class Recorder : Player.Listener {
        val discontinuities = mutableListOf<Int>()
        val transitions = mutableListOf<Int>()
        val states = mutableListOf<Int>()
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { discontinuities += reason }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitions += reason }
        override fun onPlaybackStateChanged(playbackState: Int) { states += playbackState }
    }

    private val context = RuntimeEnvironment.getApplication()
    private val p1: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
    private val p2: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
    private val engine = PlayerEngine(context, p1, p2, SHARED_SESSION_ID, AudioAttributes.DEFAULT)
    private val queue = (0 until 6).map { ScriptedPlayer.mediaItem("m$it") }
    private val key = CrossfadeTransitionKey(1L, 2, 3)

    @After fun tearDown() = engine.release()

    /** A (index 2) plays on P1 through the façade; NEXT is prepared and grafted for 2 -> 3 and READY. */
    private fun prepared() {
        engine.facade.setMediaItems(queue, 2, 0L)
        engine.facade.prepare()
        engine.facade.play()
        TestMediaSource.awaitCondition { p1.playbackState == Player.STATE_READY && p1.playWhenReady }
        engine.nextPreparation.request(NextSlotRequest(key, queue))
        TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
        TestMediaSource.settle(100)
    }

    @Test fun preparedBStartsWithoutSeekResetOrTransitionAndStaysCurrent() {
        prepared()
        val uidBeforeStart = p2.currentTimeline.getWindow(3, androidx.media3.common.Timeline.Window()).uid
        val rec = Recorder().also { p2.addListener(it) }
        val facadeEvents = EventRecorder().also { engine.facade.addListener(it) }
        val result = engine.promoteReadyNext(key)
        assertTrue(result.toString(), result is PromotionStartResult.Promoted)
        TestMediaSource.settle(300)
        assertTrue(p2.playWhenReady)
        assertEquals("B is current at the exact index", 3, p2.currentMediaItemIndex)
        assertEquals(queue.size, p2.mediaItemCount)
        assertSame("the same physical B window (not re-prepared or replaced)", uidBeforeStart, p2.currentTimeline.getWindow(3, androidx.media3.common.Timeline.Window()).uid)
        assertTrue("no seek/discontinuity on B: ${rec.discontinuities}", rec.discontinuities.isEmpty())
        assertTrue("B did not generate a media transition: ${rec.transitions}", rec.transitions.isEmpty())
        assertFalse("B never left READY/BUFFERING for IDLE: ${rec.states}", rec.states.any { it == Player.STATE_IDLE })
        assertTrue("position valid and small: ${p2.currentPosition}", p2.currentPosition in 0L..2_000L)
        // the session-facing player followed B with exactly one AUTO transition and one AUTO_TRANSITION discontinuity
        val e = facadeEvents.events
        assertEquals(e.toString(), 1, e.count { it.startsWith("transition(") })
        assertTrue(e.toString(), e.any { it == "transition(m3,AUTO)" })
        assertEquals(e.toString(), 1, e.count { it.startsWith("discontinuity(") })
        assertTrue(e.single { it.startsWith("discontinuity(") }.endsWith("AUTO_TRANSITION)"))
        assertFalse(e.toString(), e.any { it.startsWith("isPlaying") || it.contains("PLAYLIST_CHANGED") })
        assertSame(p2, engine.facade.delegatePlayer)
    }

    @Test fun theTailStripKeepsAAtItsPositionAndItsPlayerEventsQuiet() {
        prepared()
        engine.promoteReadyNext(key)
        TestMediaSource.settle(100)
        val rec = Recorder().also { p1.addListener(it) }
        val before = p1.currentPosition
        engine.stripRetiringTail()
        TestMediaSource.settle(300)
        assertEquals("A is still current on P1", 2, p1.currentMediaItemIndex)
        assertEquals("only A and its past remain", 3, p1.mediaItemCount)
        assertFalse(p1.hasNextMediaItem())
        assertTrue("no seek/discontinuity on A: ${rec.discontinuities}", rec.discontinuities.isEmpty())
        assertTrue("no media transition on A: ${rec.transitions}", rec.transitions.isEmpty())
        assertTrue("A's position did not rewind", p1.currentPosition >= before)
        assertEquals("P2's mirrored queue is untouched", queue.size, p2.mediaItemCount)
        assertEquals(3, p2.currentMediaItemIndex)
    }

    @Test fun theRetiringPlayerHasNothingToAdvanceIntoAndItsStateIsInvisibleToTheSession() {
        prepared()
        engine.promoteReadyNext(key)
        engine.stripRetiringTail()
        TestMediaSource.settle(100)
        val facadeEvents = EventRecorder().also { engine.facade.addListener(it) }
        // Physical manipulation of the retiring player only (Robolectric freezes the playback clock, so its natural end cannot be
        // awaited here; the structural facts that make an AUTO into its own B impossible are asserted instead).
        p1.seekTo(2, 179_950L)
        TestMediaSource.settle(200)
        assertEquals("A never advanced into a B of its own", 2, p1.currentMediaItemIndex)
        assertEquals(3, p1.mediaItemCount)
        assertEquals("no next item exists on the retiring player", androidx.media3.common.C.INDEX_UNSET, p1.nextMediaItemIndex)
        assertFalse(p1.hasNextMediaItem())
        assertEquals(Player.REPEAT_MODE_OFF, p1.repeatMode)
        assertTrue("the retiring player's own state changes do not reach the session: ${facadeEvents.events}", facadeEvents.events.isEmpty())
        assertSame(p2, engine.facade.delegatePlayer)
        assertEquals(3, p2.currentMediaItemIndex)
    }

    @Test fun repeatAllOnTheRetiringPlayerCannotWrapIntoItsOwnB() {
        engine.facade.setMediaItems(queue, 5, 0L) // A is the LAST item: the next occurrence wraps to index 0 under repeat-all
        engine.facade.repeatMode = Player.REPEAT_MODE_ALL
        engine.facade.prepare(); engine.facade.play()
        TestMediaSource.awaitCondition { p1.playbackState == Player.STATE_READY && p1.playWhenReady }
        val wrapKey = CrossfadeTransitionKey(1L, 5, 0)
        engine.nextPreparation.request(NextSlotRequest(wrapKey, queue, Player.REPEAT_MODE_ALL))
        TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
        assertTrue(engine.promoteReadyNext(wrapKey) is PromotionStartResult.Promoted)
        engine.stripRetiringTail()
        assertEquals(Player.REPEAT_MODE_OFF, p1.repeatMode)
        assertEquals(0, p2.currentMediaItemIndex)
        assertEquals(Player.REPEAT_MODE_ALL, p2.repeatMode)
        assertFalse("A has nothing to wrap into", p1.hasNextMediaItem())
    }

    @Test fun retirementEmptiesP1KeepsItAliveAndItCanBePreparedAgain() {
        prepared()
        engine.promoteReadyNext(key)
        engine.stripRetiringTail()
        engine.setFadeGains(0f, 1f)
        assertTrue(engine.finishRetirement())
        TestMediaSource.settle(100)
        assertEquals(0, p1.mediaItemCount)
        assertEquals(Player.STATE_IDLE, p1.playbackState)
        assertFalse(p1.playWhenReady)
        assertFalse("P1 is not released", p1.isReleased)
        assertEquals(1f, p1.volume, 0f)
        assertEquals(1f, p2.volume, 0f)
        // the next transition (3 -> 4) is prepared on the recycled P1
        val next = CrossfadeTransitionKey(1L, 3, 4)
        engine.nextPreparation.request(NextSlotRequest(next, queue))
        TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
        assertEquals(4, p1.currentMediaItemIndex)
        assertEquals(queue.size, p1.mediaItemCount)
        assertFalse(p1.playWhenReady)
    }
}

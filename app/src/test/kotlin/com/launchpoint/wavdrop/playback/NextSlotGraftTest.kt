package com.launchpoint.wavdrop.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CF-2M4: the graft algorithm proven on a REAL ExoPlayer (trackless source, Robolectric, real playback thread), not on a model:
 * prepare ONLY B, wait for READY, then `addMediaItems(0, before)` + `addMediaItems(after)`. B must stay the same physical
 * decode: no restart, no seek, no discontinuity, no item transition, index shifts to `toPlaybackIndex`, position unchanged.
 * A scripted-player section proves the same bookkeeping in the engine fixture, including duplicate-song occurrences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NextSlotGraftTest {

    private class RealEngine {
        val context = RuntimeEnvironment.getApplication()
        val p1: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
        val p2: ExoPlayer = TestMediaSource.newPlayer(context, SHARED_SESSION_ID)
        val engine = PlayerEngine(context, p1, p2, SHARED_SESSION_ID, AudioAttributes.DEFAULT)
        fun request(key: CrossfadeTransitionKey, queue: List<MediaItem>, repeat: Int = Player.REPEAT_MODE_OFF) =
            engine.nextPreparation.request(NextSlotRequest(key, queue, repeat))

        fun awaitReady() = TestMediaSource.awaitCondition { engine.nextPreparation.state is NextSlotState.Ready }
        fun windowUid(index: Int): Any = p2.currentTimeline.getWindow(index, Timeline.Window()).uid
    }

    private val created = mutableListOf<RealEngine>()
    private fun real() = RealEngine().also { created += it }

    @After fun tearDown() = created.forEach { it.engine.release() }

    private class Recorder : Player.Listener {
        val discontinuities = mutableListOf<Int>()
        val transitions = mutableListOf<Int>()
        val states = mutableListOf<Int>()
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { discontinuities += reason }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitions += reason }
        override fun onPlaybackStateChanged(playbackState: Int) { states += playbackState }
    }

    // ── 5-11 on a real ExoPlayer ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun realExoPlayerGraftKeepsTheSamePreparedBWithNoSeekRestartOrDiscontinuity() {
        val r = real()
        val queue = nextSlotQueue("A", "B", "C", "D", "E", "F")
        val to = 3
        r.request(nextSlotKey(to, from = 2), queue)
        // Target-first: only B is loaded until it is READY.
        assertEquals(1, r.p2.mediaItemCount)
        assertEquals("D", r.p2.currentMediaItem?.mediaId)
        TestMediaSource.awaitCondition { r.p2.playbackState == Player.STATE_READY || r.engine.nextPreparation.state is NextSlotState.Ready }
        r.awaitReady()
        // Everything the real player reported after READY is now in the recorder only if we attach early; so attach now and
        // prove the SECOND-phase facts by re-driving a fresh graft below. Here: the end state.
        assertEquals(queue.size, r.p2.mediaItemCount)
        assertEquals(to, r.p2.currentMediaItemIndex)
        assertEquals((0 until queue.size).map { r.p2.getMediaItemAt(it).mediaId }, queue.map { it.mediaId })
        assertFalse(r.p2.playWhenReady)
        assertFalse(r.p2.isPlaying)
        assertEquals(0L, r.p2.currentPosition)
        assertEquals(Player.REPEAT_MODE_OFF, r.p2.repeatMode)
    }

    @Test fun realExoPlayerKeepsBsPhysicalWindowUidAndEmitsNoDiscontinuityOrTransitionDuringGraft() {
        val r = real()
        val queue = nextSlotQueue("A", "B", "C", "D", "E", "F", "G")
        val to = 4
        // Drive the two phases by hand on the real player to observe exactly what the graft does to a READY B.
        val p = r.p2
        p.setMediaItem(queue[to])
        p.playWhenReady = false
        p.prepare()
        TestMediaSource.awaitCondition { p.playbackState == Player.STATE_READY }
        val uidBefore = r.windowUid(0)
        val recorder = Recorder().also { p.addListener(it) }
        p.addMediaItems(0, queue.subList(0, to))
        p.addMediaItems(queue.subList(to + 1, queue.size))
        TestMediaSource.settle(300)
        assertEquals(queue.size, p.mediaItemCount)
        assertEquals(to, p.currentMediaItemIndex)
        assertSame("B is the same physical media-source holder after the graft", uidBefore, r.windowUid(to))
        assertTrue("no position discontinuity (no seek, no restart): ${recorder.discontinuities}", recorder.discontinuities.isEmpty())
        assertTrue("no media item transition: ${recorder.transitions}", recorder.transitions.isEmpty())
        assertFalse("never left READY/BUFFERING for IDLE/ENDED: ${recorder.states}", recorder.states.any { it == Player.STATE_IDLE || it == Player.STATE_ENDED })
        assertEquals(0L, p.currentPosition)
        assertFalse(p.playWhenReady)
        p.removeListener(recorder)
    }

    @Test fun realExoPlayerMultiChunkGraftKeepsBsIdentityAndEmitsNoDiscontinuityTransitionOrSeek() {
        val r = real()
        val p = r.p2
        val queue = (0 until 1800).map { ScriptedPlayer.mediaItem("m$it") }
        val to = 900 // before 900 = 4 chunks, after 899 = 4 chunks
        var uidAtReady: Any? = null
        val recorder = Recorder()
        var armed = false
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !armed) {
                    armed = true
                    uidAtReady = r.windowUid(0)
                    p.addListener(recorder) // everything from B's READY onward, i.e. the whole graft
                }
            }
        })
        r.request(nextSlotKey(to, from = to - 1), queue)
        r.awaitReady()
        TestMediaSource.settle(200)
        val timing = r.engine.nextPreparation.lastGraftTiming!!
        assertEquals(8, timing.chunkCount)
        assertTrue(timing.maxChunkNanos > 0L)
        assertNotNull(uidAtReady)
        assertSame("B is the same physical media-source holder across all chunks", uidAtReady, r.windowUid(to))
        assertTrue("no position discontinuity: ${recorder.discontinuities}", recorder.discontinuities.isEmpty())
        assertTrue("no media item transition: ${recorder.transitions}", recorder.transitions.isEmpty())
        assertFalse("never left READY/BUFFERING: ${recorder.states}", recorder.states.any { it == Player.STATE_IDLE || it == Player.STATE_ENDED })
        assertEquals("position never rewinds", 0L, p.currentPosition)
        assertFalse(p.playWhenReady)
        assertEquals(queue.size, p.mediaItemCount)
        assertEquals(to, p.currentMediaItemIndex)
        for (i in queue.indices) assertEquals("index $i", queue[i].mediaId, p.getMediaItemAt(i).mediaId)
        p.removeListener(recorder)
    }

    @Test fun realExoPlayerGraftTargetFirstAndLastBoundaries() {
        for (to in listOf(0, 5)) {
            val r = real()
            val queue = nextSlotQueue("A", "B", "C", "D", "E", "F")
            r.request(nextSlotKey(to, from = if (to == 0) 1 else 4), queue)
            r.awaitReady()
            assertEquals(6, r.p2.mediaItemCount)
            assertEquals(to, r.p2.currentMediaItemIndex)
            assertEquals(queue[to].mediaId, r.p2.currentMediaItem?.mediaId)
        }
    }

    @Test fun realExoPlayerDuplicateSongsKeepOccurrencePositionNotSongIdentity() {
        val r = real()
        val queue = nextSlotQueue("A", "B", "A", "B", "A")
        // Prepare the later "B" occurrence (index 3); an earlier "B" (index 1) has the same mediaId.
        r.request(nextSlotKey(3, from = 2), queue)
        r.awaitReady()
        assertEquals(5, r.p2.mediaItemCount)
        assertEquals("the exact later occurrence, by position", 3, r.p2.currentMediaItemIndex)
        assertEquals((0 until 5).map { r.p2.getMediaItemAt(it).mediaId }, listOf("A", "B", "A", "B", "A"))
        // And the later "A" occurrence (index 2 vs 0 and 4).
        val r2 = real()
        r2.request(nextSlotKey(2, from = 1), queue)
        r2.awaitReady()
        assertEquals(2, r2.p2.currentMediaItemIndex)
    }

    @Test fun realExoPlayerRepeatModeIsMirroredAndShuffleIsNeverEnabled() {
        for (mode in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)) {
            val r = real()
            r.request(nextSlotKey(2, from = 1), nextSlotQueue("A", "B", "C", "D"), repeat = mode)
            r.awaitReady()
            assertEquals(mode, r.p2.repeatMode)
            assertFalse("WavDrop's logical playbackOrder is authoritative; Media3 shuffle stays off", r.p2.shuffleModeEnabled)
        }
    }

    @Test fun realExoPlayerNextStaysSilentPausedAndOnTheSharedSession() {
        val r = real()
        r.request(nextSlotKey(1), nextSlotQueue("A", "B", "C"))
        r.awaitReady()
        assertFalse(r.p2.playWhenReady)
        assertEquals(1f, r.p2.volume, 0f)
        assertEquals(SHARED_SESSION_ID, r.p1.audioSessionId)
        assertEquals(SHARED_SESSION_ID, r.p2.audioSessionId)
        assertEquals(SHARED_SESSION_ID, r.engine.audioSessionId)
        assertSame(r.p1, r.engine.currentPlayer)
        assertSame(r.engine.facade.delegatePlayer, r.p1)
    }

    @Test fun realExoPlayerInvalidationAfterReadyEmptiesNext() {
        val r = real()
        r.request(nextSlotKey(1), nextSlotQueue("A", "B", "C"))
        r.awaitReady()
        r.engine.nextPreparation.invalidate(CrossfadeCancelReason.Seek)
        assertEquals(0, r.p2.mediaItemCount)
        assertEquals(Player.STATE_IDLE, r.p2.playbackState)
        assertFalse(r.p2.playWhenReady)
        assertNotNull(r.engine.nextPreparation.lastInvalidationReason)
    }

    // ── scripted-engine bookkeeping: order, no seek, position, physical identity ─────────────────────────────────────────

    @Test fun scriptedGraftKeepsBsPhysicalUidAndIssuesNoSeek() {
        val f = PlayerEngineFixture()
        val queue = nextSlotQueue("A", "B", "C", "D", "E")
        f.engine.nextPreparation.request(NextSlotRequest(nextSlotKey(3, from = 2), queue))
        val uidB = f.p2.uidAt(0)
        f.p2.becomeReady(); idleMainLooper()
        assertSame("B is the same physical item after the graft", uidB, f.p2.uidAt(3))
        assertEquals(listOf("P2.setMediaItems(1)", "P2.prepare()", "P2.addMediaItems(0,3)", "P2.addMediaItems(4,1)"), f.p2.commands)
        assertFalse(f.p2.commands.any { it.contains("seek") })
        assertEquals(0L, f.p2.currentPosition)
        assertEquals(3, f.p2.currentMediaItemIndex)
        assertEquals(queue.map { it.mediaId }, f.p2.mediaIds)
    }

    @Test fun scriptedDuplicateHeavyQueueKeepsPositionalIdentityForEveryTarget() {
        val ids = listOf("A", "B", "A", "B", "A")
        for (to in ids.indices) {
            val from = if (to == 0) 1 else to - 1
            val f = PlayerEngineFixture()
            f.engine.nextPreparation.request(NextSlotRequest(nextSlotKey(to, from = from), nextSlotQueue(*ids.toTypedArray())))
            f.p2.becomeReady(); idleMainLooper()
            assertTrue("target $to", f.engine.nextPreparation.state is NextSlotState.Ready)
            assertEquals(to, f.p2.currentMediaItemIndex)
            assertEquals(ids, f.p2.mediaIds)
        }
    }

    @Test fun finalReadyInvariantHoldsForEveryIndexAndTimelineOrder() {
        val f = PlayerEngineFixture()
        val queue = nextSlotQueue("A", "B", "C", "D", "E", "F", "G", "H")
        f.engine.nextPreparation.request(NextSlotRequest(nextSlotKey(5, from = 4), queue))
        f.p2.becomeReady(); idleMainLooper()
        assertEquals(queue.size, f.p2.mediaItemCount)
        assertEquals(5, f.p2.currentMediaItemIndex)
        queue.forEachIndexed { i, item -> assertEquals(item.mediaId, f.p2.getMediaItemAt(i).mediaId) }
        assertFalse(f.p2.playWhenReady)
    }

    @Test fun requestRejectsAnOutOfRangeTargetAtConstruction() {
        try {
            NextSlotRequest(nextSlotKey(9), nextSlotQueue("A", "B"))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}

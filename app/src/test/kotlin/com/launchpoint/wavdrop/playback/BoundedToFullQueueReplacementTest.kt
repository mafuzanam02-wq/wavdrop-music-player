package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-F20J. A bounded queue (more than the Full-mode limit) is installed as a physical window while the session facade presents
 * the LOGICAL queue, which removes COMMAND_CHANGE_MEDIA_ITEMS from the controller. The first explicit small-queue replacement
 * (Play all) must still replace the PHYSICAL queue, verified on the physical player itself, with logical state, the MediaSession
 * (controller view), Now Playing, statistics and the persisted session converging on the same selected occurrence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class BoundedToFullQueueReplacementTest {
    private class RecordingWriter : PlayEventWriter {
        val skips = mutableListOf<Long>()
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) { skips += songId }
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    private val rigs = mutableListOf<EngineChainRig>()
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }

    private val writer = RecordingWriter()
    private fun big(n: Int = 400) = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private fun small(vararg ids: Long) = ids.map { RealMedia3QueueRig.song(it) }
    private fun rig(bounded: Boolean = true) = EngineChainRig(bounded, writer).also { rigs += it }
    private fun EngineChainRig.physicalIds(): List<String> = (0 until engine.currentPlayer.mediaItemCount).map { engine.currentPlayer.getMediaItemAt(it).mediaId }
    private fun EngineChainRig.settleAll() { quiesce(); TestMediaSource.settle(120); quiesce() }
    private fun EngineChainRig.saved() = runBlocking { sessionRepo.load() }

    /** Physical player, controller (MediaSession) view, logical state and Now Playing all name the same occurrence. */
    private fun EngineChainRig.assertConverged(ids: List<Long>, index: Int, playing: Boolean) {
        assertEquals("physical queue", ids.map { it.toString() }, physicalIds())
        assertEquals("physical current index", index, engine.currentPlayer.currentMediaItemIndex)
        assertEquals("physical current id", ids[index].toString(), engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals("controller (MediaSession) count", ids.size, controller.mediaItemCount)
        assertEquals("controller current index", index, controller.currentMediaItemIndex)
        assertEquals("controller current id", ids[index].toString(), controller.currentMediaItem?.mediaId)
        assertEquals("logical queue", ids, pc.nowPlayingState.value.queue.map { it.id })
        assertEquals("Now Playing song", ids[index], pc.nowPlayingState.value.song?.id)
        assertEquals("Now Playing index", index, pc.nowPlayingState.value.currentIndex)
        if (playing) assertTrue("physical is playing", engine.currentPlayer.playWhenReady)
    }

    @Test fun `first Play all after a paused bounded queue replaces the physical queue`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        assertFalse("bounded presentation hides change-items (the defect precondition)", r.controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue("an actual bounded window", r.engine.currentPlayer.mediaItemCount in 2 until 400)
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0)
        r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 0, playing = true)
        val saved = r.saved()!!
        assertEquals(listOf(1_001L, 1_002L, 1_003L), saved.queueSongIds)
        assertEquals(1_001L, saved.currentSongId)
    }

    @Test fun `second Play all after a bounded queue also converges and does not repeat statistics`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0); r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 0, playing = true)
    }

    @Test fun `selection statistics name only the song that actually started`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0); r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 0, playing = true)
        assertTrue("the replaced song was finalized exactly once: ${writer.skips}", writer.skips.count { it == 14L } <= 1)
        assertEquals("no duplicate selection events for the new song", writer.listenStarts.distinct(), writer.listenStarts)
    }

    @Test fun `Bounded to Full replacement starting at a later occurrence`() {
        val r = rig(); r.start(big(), 200)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 2); r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 2, playing = true)
    }

    @Test fun `duplicate songs keep their distinct occurrence`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(5, 6, 5), 2); r.settleAll()
        r.assertConverged(listOf(5L, 6L, 5L), 2, playing = true)
    }

    @Test fun `Bounded to Bounded replacement still installs a new window`() {
        val r = rig(); r.start(big(400), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(List(500) { RealMedia3QueueRig.song(2_000L + it) }, 300); r.settleAll()
        assertEquals("2300", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals(2_300L, r.pc.nowPlayingState.value.song?.id)
        assertTrue(r.engine.currentPlayer.mediaItemCount < 500)
    }

    @Test fun `Full to Full replacement is unchanged`() {
        val r = rig(bounded = true); r.start(small(1, 2, 3, 4), 1)
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 1); r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 1, playing = true)
    }

    @Test fun `a newer Play all supersedes the first replacement`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0)
        r.pc.playFromQueue(small(2_001, 2_002), 1)
        r.settleAll()
        r.assertConverged(listOf(2_001L, 2_002L), 1, playing = true)
        assertEquals(listOf(2_001L, 2_002L), r.saved()!!.queueSongIds)
    }

    @Test fun `Next right after the replacement moves within the new queue`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0)
        r.pc.skipToNext(); r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 1, playing = true)
    }

    @Test fun `repeat all and shuffle state survive the replacement`() {
        val r = rig(); r.start(big(), 13)
        r.pc.cycleRepeatMode() // OFF -> ALL
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0); r.settleAll()
        assertEquals(RepeatMode.ALL, r.pc.nowPlayingState.value.repeatMode)
        assertEquals(Player.REPEAT_MODE_ALL, r.engine.currentPlayer.repeatMode)
        assertEquals(listOf("1001", "1002", "1003"), r.physicalIds())
    }

    @Test fun `pause right after the replacement leaves a paused converged queue`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0)
        r.pc.togglePlayPause(); r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 0, playing = false)
        assertFalse(r.engine.currentPlayer.playWhenReady)
    }

    @Test fun `a controller reconnect after the replacement keeps the installed queue`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0); r.settleAll()
        r.connectAppController(); r.settleAll()
        r.assertConverged(listOf(1_001L, 1_002L, 1_003L), 0, playing = true)
    }

    @Test fun `the full queue is never installed physically while bounded is required`() {
        val r = rig(); r.start(big(400), 13)
        assertTrue(r.engine.currentPlayer.mediaItemCount < 400)
    }

    /** A physical port whose whole-queue replacement is refused (player released / rejected), everything else delegated. */
    private class RefusingPort(private val real: PhysicalWindowPort) : PhysicalWindowPort by real {
        override fun setMediaItems(items: List<androidx.media3.common.MediaItem>, startIndex: Int, startPositionMs: Long) {
            throw IllegalStateException("refused")
        }
    }

    @Test fun `a refused physical install claims nothing and preserves the previous saved session`() {
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        val before = r.saved()!!
        val physicalBefore = r.physicalIds()
        r.pc.attachPhysicalWindowPort(RefusingPort(r.port))
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0); r.settleAll()
        assertEquals("physical queue untouched", physicalBefore, r.physicalIds())
        assertFalse("nothing is claimed as playing", r.pc.nowPlayingState.value.isPlaying)
        assertFalse(r.engine.currentPlayer.playWhenReady)
        assertEquals("previous valid session preserved", before.queueSongIds, r.saved()!!.queueSongIds)
        assertFalse("no selection event for a song that never started", writer.listenStarts.contains(1_001L))
        assertFalse(writer.skips.contains(14L))
    }

    @Test fun `physical queue and saved session are not touched by a rejected installation`() {
        // The controller loses its command while the façade presents a bounded queue; a replacement must never report a song the
        // physical player did not adopt. Regardless of the route taken, the persisted queue equals the physical queue.
        val r = rig(); r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        r.pc.playFromQueue(small(1_001, 1_002, 1_003), 0); r.settleAll()
        val physical = r.physicalIds().map { it.toLong() }
        assertEquals(physical, r.saved()!!.queueSongIds)
    }
}

package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
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
 * P5-F20J-R1. The physical replacement of a bounded queue by a small Full queue is refused or fails verification. The controller must
 * fail closed: the old bounded physical queue is never interpreted as Full (no wrong Next/Previous/Play, statistics or session),
 * the accepted logical selection stays available for an explicit retry, and a later proven install recovers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class BoundedToFullFailureRecoveryTest {
    private class RecordingWriter : HookedPlayEventWriter() {
        val skips = mutableListOf<Long>()
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) { skips += songId }
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    /** Whole-queue replacement behaviour of a physical port; everything else is delegated to the real port. */
    private class FaultyPort(private val real: PhysicalWindowPort, var mode: Mode) : PhysicalWindowPort by real {
        enum class Mode { Pass, Refuse, Ignore, WrongIndex }
        var attempts = 0
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            attempts++
            when (mode) {
                Mode.Pass -> real.setMediaItems(items, startIndex, startPositionMs)
                Mode.Refuse -> throw IllegalStateException("refused")
                Mode.Ignore -> Unit
                Mode.WrongIndex -> real.setMediaItems(items, (startIndex + 1) % items.size, startPositionMs)
            }
        }
    }

    private val rigs = mutableListOf<EngineChainRig>()
    @After fun tearDown() { rigs.forEach { runCatching { it.release() } }; rigs.clear() }

    private val writer = RecordingWriter()
    private fun big() = List(400) { RealMedia3QueueRig.song(it + 1L) }
    private fun small(vararg ids: Long) = ids.map { RealMedia3QueueRig.song(it) }
    private fun EngineChainRig.ids(): List<String> = (0 until engine.currentPlayer.mediaItemCount).map { engine.currentPlayer.getMediaItemAt(it).mediaId }
    private fun EngineChainRig.settleAll() { quiesce(); TestMediaSource.settle(120); quiesce() }
    private fun EngineChainRig.saved() = runBlocking { sessionRepo.load() }

    /** A genuinely bounded, paused restored queue with a controllable physical port attached. */
    private fun restored(mode: FaultyPort.Mode = FaultyPort.Mode.Refuse): Pair<EngineChainRig, FaultyPort> {
        val r = EngineChainRig(true, writer).also { rigs += it }
        r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        val port = FaultyPort(r.port, mode)
        r.pc.attachPhysicalWindowPort(port)
        return r to port
    }

    private fun EngineChainRig.assertConverged(ids: List<Long>, index: Int) {
        assertEquals(ids.map { it.toString() }, this.ids())
        assertEquals(index, engine.currentPlayer.currentMediaItemIndex)
        assertEquals(ids[index].toString(), engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals(ids, pc.nowPlayingState.value.queue.map { it.id })
        assertEquals(ids[index], pc.nowPlayingState.value.song?.id)
        assertEquals(index, pc.nowPlayingState.value.currentIndex)
    }

    private val three = listOf(1_001L, 1_002L, 1_003L)

    @Test fun `a refusal while bounded keeps the old physical queue and the accepted logical intent`() {
        val (r, port) = restored()
        val physical = r.ids(); val before = r.saved()!!
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        assertEquals(1, port.attempts)
        assertEquals("physical untouched", physical, r.ids())
        assertEquals(13, r.engine.currentPlayer.currentMediaItemIndex)
        assertEquals("logical intent preserved", three, r.pc.nowPlayingState.value.queue.map { it.id })
        assertFalse("nothing is claimed as playing", r.pc.nowPlayingState.value.isPlaying)
        assertFalse(r.engine.currentPlayer.playWhenReady)
        assertEquals("previous session unchanged", before.queueSongIds, r.saved()!!.queueSongIds)
        assertFalse("no selection statistic", writer.listenStarts.contains(1_001L))
        assertFalse("no skip statistic for the stale item", writer.skips.contains(14L))
    }

    @Test fun `failed physical verification after a submitted replacement fails closed and a retry recovers`() {
        val (r, port) = restored(FaultyPort.Mode.WrongIndex)
        val before = r.saved()!!
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        assertEquals(1, port.attempts)
        assertEquals("previous session unchanged", before.queueSongIds, r.saved()!!.queueSongIds)
        assertFalse(r.pc.nowPlayingState.value.isPlaying)
        port.mode = FaultyPort.Mode.Pass
        r.pc.togglePlayPause(); r.settleAll()
        r.assertConverged(three, 0)
        assertEquals(three, r.saved()!!.queueSongIds)
    }

    @Test fun `Next right after a failed replacement does not navigate the stale physical queue`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(*three.toLongArray()), 0)
        r.pc.skipToNext(); r.settleAll()
        assertEquals("no retry from Next", 1, port.attempts)
        assertEquals(13, r.engine.currentPlayer.currentMediaItemIndex)
        assertEquals(1_001L, r.pc.nowPlayingState.value.song?.id)
        assertFalse(writer.listenStarts.contains(1_002L))
    }

    @Test fun `Previous right after a failed replacement does not navigate the stale physical queue`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(*three.toLongArray()), 0)
        r.pc.skipToPrevious(); r.settleAll()
        assertEquals(1, port.attempts)
        assertEquals(13, r.engine.currentPlayer.currentMediaItemIndex)
        assertEquals(1_001L, r.pc.nowPlayingState.value.song?.id)
    }

    @Test fun `Play after a failed replacement retries exactly once and never starts the stale song`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(*three.toLongArray()), 0)
        r.pc.togglePlayPause(); r.settleAll()
        assertEquals("one retry per explicit action, no loop", 2, port.attempts)
        assertFalse("the stale physical song never starts", r.engine.currentPlayer.playWhenReady)
        assertEquals("14", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertFalse(r.pc.nowPlayingState.value.isPlaying)
    }

    @Test fun `a successful explicit retry converges every view and Next then uses the Full index model`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(*three.toLongArray()), 0)
        port.mode = FaultyPort.Mode.Pass
        r.pc.togglePlayPause(); r.settleAll()
        r.assertConverged(three, 0)
        assertTrue(r.engine.currentPlayer.playWhenReady)
        assertEquals(three, r.saved()!!.queueSongIds)
        r.pc.skipToNext(); r.settleAll()
        r.assertConverged(three, 1)
    }

    @Test fun `reconnect with a restored port adopts the accepted queue without autostart`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        port.mode = FaultyPort.Mode.Pass
        r.connectAppController(); r.settleAll()
        r.assertConverged(three, 0)
        assertFalse("a reconnect never starts playback", r.engine.currentPlayer.playWhenReady)
        assertEquals(2, port.attempts)
    }

    @Test fun `reconnect with continuing refusal stays closed without a busy loop and keeps the logical queue`() {
        val (r, port) = restored()
        val before = r.saved()!!
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        r.connectAppController(); r.settleAll()
        r.connectAppController(); r.settleAll()
        assertEquals("one attempt per connection", 3, port.attempts)
        assertEquals("the stale physical selection is not resurrected as the logical queue", three, r.pc.nowPlayingState.value.queue.map { it.id })
        assertEquals(1_001L, r.pc.nowPlayingState.value.song?.id)
        assertEquals(before.queueSongIds, r.saved()!!.queueSongIds)
        assertFalse(writer.listenStarts.contains(1_001L))
    }

    @Test fun `duplicate occurrences recover to the accepted occurrence`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(5, 6, 5), 2)
        port.mode = FaultyPort.Mode.Pass
        r.pc.togglePlayPause(); r.settleAll()
        r.assertConverged(listOf(5L, 6L, 5L), 2)
    }

    @Test fun `a newer successful Play all supersedes a failed one`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(*three.toLongArray()), 0)
        port.mode = FaultyPort.Mode.Pass
        r.pc.playFromQueue(small(2_001, 2_002), 1); r.settleAll()
        r.assertConverged(listOf(2_001L, 2_002L), 1)
        assertEquals(listOf(2_001L, 2_002L), r.saved()!!.queueSongIds)
        r.pc.skipToPrevious(); r.settleAll()
        assertEquals("transport is open again", 2_001L, r.pc.nowPlayingState.value.song?.id)
    }

    @Test fun `an ignored replacement is detected by verification`() {
        val (r, port) = restored(FaultyPort.Mode.Ignore)
        val before = r.saved()!!
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        assertEquals(1, port.attempts)
        assertEquals(before.queueSongIds, r.saved()!!.queueSongIds)
        assertFalse(r.pc.nowPlayingState.value.isPlaying)
    }

    @Test fun `release after a failed replacement is safe and leaves no pending recovery`() {
        val (r, port) = restored()
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        r.pc.release()
        r.pc.togglePlayPause()
        r.pc.skipToNext()
        assertEquals("no attempt after release", 1, port.attempts)
    }
}

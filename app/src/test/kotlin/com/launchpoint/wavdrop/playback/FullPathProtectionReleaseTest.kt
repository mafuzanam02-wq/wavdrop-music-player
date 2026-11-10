package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
 * P5-F20J-R4. The ordinary FULL branch of `installPlaybackQueue` must not release the unverified-install protection merely because a
 * controller command was available and submitted. Three conditions are distinct and asserted separately:
 *   command AVAILABLE (controller view) / command SUBMITTED (asynchronous IPC) / queue ADOPTED (physical player holds it).
 * Reachability: a Full-mode install whose physical replacement was refused while the controller still showed the (stale) bounded
 * command set leaves the protection set with the installed mode Full; once the controller view catches up the next Full selection
 * sees the command available.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class FullPathProtectionReleaseTest {
    private class NullWriter : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private class FaultyPort(private val real: PhysicalWindowPort, var refuse: Boolean = false) : PhysicalWindowPort by real {
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (refuse) throw IllegalStateException("refused")
            real.setMediaItems(items, startIndex, startPositionMs)
        }
    }

    private val rigs = mutableListOf<EngineChainRig>()
    @After fun tearDown() { rigs.forEach { runCatching { it.release() } }; rigs.clear() }

    private fun big() = List(400) { RealMedia3QueueRig.song(it + 1L) }
    private fun small(vararg ids: Long) = ids.map { RealMedia3QueueRig.song(it) }
    private fun EngineChainRig.ids(): List<String> = (0 until engine.currentPlayer.mediaItemCount).map { engine.currentPlayer.getMediaItemAt(it).mediaId }
    private fun EngineChainRig.idle() { idleMainLooper(); TestMediaSource.settle(120); idleMainLooper() }
    private fun EngineChainRig.settleAll() { quiesce(); TestMediaSource.settle(120); quiesce() }
    private fun EngineChainRig.saved() = runBlocking { sessionRepo.load() }
    /**
     * Installed mode Full + protection set (reachable in production): a small Full queue is playing, then a BOUNDED selection is refused
     * immediately by the physical port. The physical queue is untouched (still the Full queue), the previous mode (Full) is restored and
     * the accepted bounded selection stays unverified.
     */
    private fun fullModeUnverified(): Pair<EngineChainRig, FaultyPort> {
        val r = EngineChainRig(true, NullWriter()).also { rigs += it }
        r.start(small(1, 2, 3, 4), 1)
        r.settleAll()
        val port = FaultyPort(r.port)
        r.pc.attachPhysicalWindowPort(port)
        port.refuse = true
        runCatching { r.pc.playFromQueue(List(400) { RealMedia3QueueRig.song(5_000L + it) }, 10) }
        port.refuse = false
        r.idle()
        assertTrue("precondition: protection active after the refused bounded replacement", r.pc.isInstallUnverified)
        assertEquals("physical player still holds the previous Full queue", listOf("1", "2", "3", "4"), r.ids())
        assertTrue("the controller command is AVAILABLE (Full mode forwards)", r.controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        return r to port
    }

    @Test fun `protection is released only once the physical player holds the selection`() {
        val (r, _) = fullModeUnverified()
        val target = listOf("3001", "3002", "3003")
        r.pc.playFromQueue(small(3_001, 3_002, 3_003), 0) // command available; no idle: SUBMITTED at most, not yet adopted
        val physicalNow = r.ids()
        assertTrue(
            "protection released (${!r.pc.isInstallUnverified}) while the physical player holds $physicalNow, not $target",
            r.pc.isInstallUnverified || physicalNow == target,
        )
        r.settleAll()
        assertEquals("ADOPTED", target, r.ids())
        assertFalse("released after adoption", r.pc.isInstallUnverified)
        assertEquals(3_001L, r.pc.nowPlayingState.value.song?.id)
        assertEquals(listOf(3_001L, 3_002L, 3_003L), r.saved()!!.queueSongIds)
    }

    @Test fun `without a physical port nothing can prove adoption and the protection stays`() {
        val (r, _) = fullModeUnverified()
        r.pc.attachPhysicalWindowPort(null)
        r.pc.playFromQueue(small(3_001, 3_002, 3_003), 0); r.settleAll()
        assertTrue("an unprovable install cannot release the protection", r.pc.isInstallUnverified)
        assertFalse(r.pc.nowPlayingState.value.isPlaying)
        assertEquals("previous valid session kept", listOf(1L, 2L, 3L, 4L), r.saved()!!.queueSongIds)
    }

    @Test fun `a newer explicit selection supersedes the unverified one and normal transport returns`() {
        val (r, _) = fullModeUnverified()
        r.pc.playFromQueue(small(3_001, 3_002, 3_003), 0); r.settleAll()
        r.pc.playFromQueue(small(4_001, 4_002), 1); r.settleAll()
        assertEquals(listOf("4001", "4002"), r.ids())
        assertEquals(1, r.engine.currentPlayer.currentMediaItemIndex)
        assertFalse(r.pc.isInstallUnverified)
        assertTrue(r.engine.currentPlayer.playWhenReady)
    }
}

package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-AUDIT-03. A preserve-search selection (and a queue synchronisation) whose physical install is refused (bounded rejection) or
 * returns Failed must fail closed: no exception to the user-facing action, no stale physical audio under the new selection, the last
 * valid session kept, no listen statistics for audio that never played, and the accepted selection recoverable by explicit Play or
 * reconnect without double counting. Failures are injected with the same physical-port refusal the F20J tests use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PreserveSearchFailureSafetyTest {
    private class RecWriter : PlayEventWriter {
        val starts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) { starts += songId }
    }

    private class FaultyPort(private val real: PhysicalWindowPort, var refuse: Boolean = false) : PhysicalWindowPort by real {
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (refuse) throw IllegalStateException("refused")
            real.setMediaItems(items, startIndex, startPositionMs)
        }
    }

    private val writer = RecWriter()
    private val rigs = mutableListOf<EngineChainRig>()
    private val sessions = mutableListOf<MediaSession>()
    private val controllers = mutableListOf<MediaController>()
    @After fun tearDown() {
        controllers.forEach { runCatching { it.release() } }; sessions.forEach { runCatching { it.release() } }
        rigs.forEach { runCatching { it.release() } }; rigs.clear()
    }

    private fun big(base: Long, n: Int = 400) = List(n) { RealMedia3QueueRig.song(base + it) }
    private fun small(vararg ids: Long) = ids.map { RealMedia3QueueRig.song(it) }
    private val target = RealMedia3QueueRig.song(9_001)
    private fun EngineChainRig.ids(): List<String> = (0 until engine.currentPlayer.mediaItemCount).map { engine.currentPlayer.getMediaItemAt(it).mediaId }
    private fun EngineChainRig.cur() = engine.currentPlayer.currentMediaItem?.mediaId
    private fun EngineChainRig.idle() { idleMainLooper(); TestMediaSource.settle(120); idleMainLooper() }
    private fun EngineChainRig.settleAll() { quiesce(); TestMediaSource.settle(120); quiesce() }
    private fun EngineChainRig.saved() = runBlocking { sessionRepo.load() }
    private fun EngineChainRig.dirty(): Boolean =
        PlayerController::class.java.getDeclaredField("queueDirtyReason").apply { isAccessible = true }.get(pc) != null

    private class H(val r: EngineChainRig, val port: FaultyPort, val savedBefore: List<Long>)

    /** Bounded (400) or Full (4) session, optionally paused, then the port starts refusing. */
    private fun harness(bounded: Boolean, playing: Boolean): H {
        val r = EngineChainRig(true, writer).also { rigs += it }
        if (bounded) r.start(big(1), 13) else r.start(small(1, 2, 3, 4), 1)
        r.settleAll()
        if (!playing) { r.pc.togglePlayPause(); r.settleAll() }
        val before = r.saved()!!.queueSongIds
        val port = FaultyPort(r.port).also { r.pc.attachPhysicalWindowPort(it) }
        port.refuse = true
        return H(r, port, before)
    }

    /**
     * The failed Full case needs the Full port path (protection owed), so a refused bounded replacement is staged first; the preserve-search
     * then runs over a Full-size visible queue exactly as in the audit probe.
     */
    private fun fullFailureHarness(playing: Boolean): H {
        val h = harness(false, playing)
        runCatching { h.r.pc.playFromQueue(big(5_000), 10) }; h.r.idle()
        h.r.pc.playFromQueue(small(3_001, 3_002, 3_003), 0); h.r.settleAll()
        assertTrue("precondition: unverified before the preserve-search", h.r.pc.isInstallUnverified)
        return H(h.r, h.port, h.savedBefore)
    }

    private fun H.select(): Throwable? {
        var threw: Throwable? = null
        runCatching { r.pc.playSearchResultPreservingQueue(target) }.onFailure { threw = it }
        r.idle()
        return threw
    }

    private fun H.assertFailedClosed(threw: Throwable?) {
        assertNull("no exception escapes the user-facing search action: $threw", threw)
        assertTrue("protection owed", r.pc.isInstallUnverified)
        assertFalse("UI does not claim playback", r.pc.nowPlayingState.value.isPlaying)
        assertFalse("stale physical audio is stopped (current=${r.cur()})", r.engine.currentPlayer.playWhenReady)
        assertEquals("last valid session kept", savedBefore, r.saved()!!.queueSongIds)
        assertFalse("no listen recorded for audio that never played", writer.starts.contains(9_001L))
        assertEquals("the accepted selection stays the logical current", 9_001L, r.pc.nowPlayingState.value.song?.id)
    }

    @Test fun `C1 rejected bounded install while playing fails closed and recovers by explicit Play`() {
        val h = harness(bounded = true, playing = true)
        h.assertFailedClosed(h.select())
        h.port.refuse = false
        h.r.pc.togglePlayPause(); h.r.settleAll()
        assertFalse("recovered", h.r.pc.isInstallUnverified)
        assertEquals("9001", h.r.cur())
        assertTrue(h.r.engine.currentPlayer.playWhenReady)
        assertTrue("no duplicate listen after recovery", writer.starts.count { it == 9_001L } <= 1)
    }

    @Test fun `C1 rejected bounded install while paused fails closed`() {
        val h = harness(bounded = true, playing = false)
        h.assertFailedClosed(h.select())
    }

    @Test fun `C2 Failed Full install while playing fails closed and recovers by explicit Play`() {
        val h = fullFailureHarness(playing = true)
        h.assertFailedClosed(h.select())
        h.port.refuse = false
        h.r.pc.togglePlayPause(); h.r.settleAll()
        assertFalse("recovered", h.r.pc.isInstallUnverified)
        assertEquals("9001", h.r.cur())
        assertTrue(h.r.engine.currentPlayer.playWhenReady)
        assertTrue("no duplicate listen after recovery", writer.starts.count { it == 9_001L } <= 1)
    }

    @Test fun `C2 Failed Full install while paused fails closed`() {
        val h = fullFailureHarness(playing = false)
        h.assertFailedClosed(h.select())
    }

    @Test fun `C2 recovery through controller reconnect`() {
        val h = fullFailureHarness(playing = true)
        h.assertFailedClosed(h.select())
        h.port.refuse = false
        h.r.connectAppController(); h.r.settleAll()
        assertFalse("reconnect recovered the accepted selection", h.r.pc.isInstallUnverified)
        assertEquals("9001", h.r.cur())
    }

    @Test fun `a newer selection supersedes the older failed preserve-search intent`() {
        val h = fullFailureHarness(playing = true)
        h.assertFailedClosed(h.select())
        h.port.refuse = false
        h.r.pc.playFromQueue(small(4_001, 4_002), 1); h.r.settleAll()
        assertEquals(listOf("4001", "4002"), h.r.ids())
        assertEquals("4002", h.r.cur())
        assertFalse(h.r.pc.isInstallUnverified)
        h.r.pc.togglePlayPause(); h.r.settleAll()
        h.r.pc.togglePlayPause(); h.r.settleAll()
        assertEquals("the failed intent is never resurrected", listOf("4001", "4002"), h.r.ids())
    }

    @Test fun `external transport cannot start or navigate stale audio after a failed preserve-search`() {
        val h = fullFailureHarness(playing = true)
        val policy = PreviousBehaviorPlayer(
            player = h.r.engine.facade, thresholdProvider = { 3_000L }, scope = CoroutineScope(Dispatchers.Unconfined),
            onExternalTransport = {}, hydrateForPlay = { PlayerHydrationResult.Hydrated }, songsProvider = { emptyList() }, logResume = {},
            sessionProvider = { sessions.lastOrNull() },
            onExplicitPause = {}, onExplicitSeek = {}, onExplicitNavigation = {}, onExplicitRepeatChange = {},
            transportBlocked = { h.r.pc.isInstallUnverified },
        )
        val session = MediaSession.Builder(h.r.context, policy).setId("a03-policy").build().also { sessions += it }
        val future = MediaController.Builder(h.r.context, session.token).setConnectionHints(android.os.Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) idleMainLooper()
        val ext = future.get(5, TimeUnit.SECONDS).also { controllers += it; h.r.idle() }
        h.assertFailedClosed(h.select())
        val idx = h.r.engine.currentPlayer.currentMediaItemIndex
        ext.play(); ext.seekToNext(); ext.seekToNextMediaItem(); ext.seekToPrevious(); h.r.idle()
        assertFalse("external Play must not start stale audio", h.r.engine.currentPlayer.playWhenReady)
        assertEquals("external navigation is refused", idx, h.r.engine.currentPlayer.currentMediaItemIndex)
    }

    @Test fun `normal Full preserve-search still plays and persists`() {
        val r = EngineChainRig(true, writer).also { rigs += it }
        r.start(small(1, 2, 3, 4), 1); r.settleAll()
        r.pc.playSearchResultPreservingQueue(target); r.settleAll()
        assertEquals("9001", r.cur()); assertTrue(r.engine.currentPlayer.playWhenReady)
        assertFalse(r.pc.isInstallUnverified)
        assertTrue(r.saved()!!.queueSongIds.contains(9_001L))
    }

    @Test fun `normal Bounded preserve-search still plays and persists`() {
        val r = EngineChainRig(true, writer).also { rigs += it }
        r.start(big(1), 13); r.settleAll()
        r.pc.playSearchResultPreservingQueue(target); r.settleAll()
        assertEquals("9001", r.cur()); assertTrue(r.engine.currentPlayer.playWhenReady)
        assertFalse(r.pc.isInstallUnverified)
        assertTrue(r.saved()!!.queueSongIds.contains(9_001L))
    }

    // ── B: queue synchronisation ──
    @Test fun `B a failed physical replacement during current-song deletion keeps the queue dirty and issues no play`() {
        val h = fullFailureHarness(playing = true)
        assertTrue(h.r.dirty())
        runCatching { h.r.pc.handleSongDeleted(3_001L) }.also { assertTrue("no exception: ${it.exceptionOrNull()}", it.isSuccess) }
        h.r.idle()
        assertTrue("an unproven replacement stays dirty", h.r.dirty())
        assertTrue(h.r.pc.isInstallUnverified)
        assertFalse("no play against stale audio", h.r.engine.currentPlayer.playWhenReady)
        assertEquals("last valid session kept", h.savedBefore, h.r.saved()!!.queueSongIds)
        h.port.refuse = false
        h.r.pc.togglePlayPause(); h.r.settleAll()
        assertFalse("recoverable", h.r.pc.isInstallUnverified)
        assertEquals(listOf("3002", "3003"), h.r.ids())
    }

    @Test fun `B a successful current-song deletion in Full mode is unchanged`() {
        val r = EngineChainRig(true, writer).also { rigs += it }
        r.start(small(1, 2, 3, 4), 1); r.settleAll()
        r.pc.handleSongDeleted(2L); r.settleAll()
        assertEquals(listOf("1", "3", "4"), r.ids())
        assertEquals("3", r.cur())
        assertTrue(r.engine.currentPlayer.playWhenReady)
        assertFalse(r.pc.isInstallUnverified)
    }
}

package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * P5-F20J-R3. After a FAILED Bounded-to-Full replacement ([PlayerController.isInstallUnverified]) a NEWER bounded selection is
 * submitted. A submitted bounded install is not an adopted one: the protection must hold until the bounded adapter's physical
 * acknowledgement (Active) of the newest install. The adapter outcome is controlled through the production verifier seam
 * ([PlayerController.boundedVerifierFactory]); the commands travel the real MediaSession -> PreviousBehaviorPlayer path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class UnverifiedUntilBoundedAdoptionTest {
    private class RecordingWriter : PlayEventWriter {
        val skips = mutableListOf<Long>()
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) { skips += songId }
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    private class FaultyPort(private val real: PhysicalWindowPort, var refuse: Boolean) : PhysicalWindowPort by real {
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (refuse) throw IllegalStateException("refused")
            real.setMediaItems(items, startIndex, startPositionMs)
        }
    }

    /** Holds every acknowledgement until the test releases (physically proven) or fails it. */
    private class HeldVerifier(private val real: PhysicalInstallVerifier) : PhysicalInstallVerifier {
        private val held = mutableListOf<Pair<PlanToken, SettableFuture<PhysicalObservation>>>()
        override fun observe(token: PlanToken): com.google.common.util.concurrent.ListenableFuture<PhysicalObservation> =
            SettableFuture.create<PhysicalObservation>().also { held += token to it }
        fun releaseAll() { held.toList().forEach { (t, f) -> f.setFuture(real.observe(t)) }; held.clear() }
        fun failAll() { held.toList().forEach { (_, f) -> f.setException(IllegalStateException("no acknowledgement")) }; held.clear() }
        
        fun releaseOldest() { val e = held.firstOrNull() ?: return; held.remove(e); e.second.setFuture(real.observe(e.first)) }
        val count get() = held.size
    }

    private val rigs = mutableListOf<EngineChainRig>()
    private val sessions = mutableListOf<MediaSession>()
    private val controllers = mutableListOf<MediaController>()
    @After fun tearDown() {
        controllers.forEach { runCatching { it.release() } }; sessions.forEach { runCatching { it.release() } }
        rigs.forEach { runCatching { it.release() } }
    }

    private val writer = RecordingWriter()
    private val three = listOf(1_001L, 1_002L, 1_003L)
    private fun big(base: Long, n: Int = 400) = List(n) { RealMedia3QueueRig.song(base + it) }
    private fun small(vararg ids: Long) = ids.map { RealMedia3QueueRig.song(it) }
    private fun EngineChainRig.settleAll() { quiesce(); TestMediaSource.settle(120); quiesce() }
    private fun EngineChainRig.idle() { idleMainLooper(); TestMediaSource.settle(120); idleMainLooper() }
    private fun EngineChainRig.saved() = runBlocking { sessionRepo.load() }
    private fun EngineChainRig.id() = engine.currentPlayer.currentMediaItem?.mediaId

    private class H(val r: EngineChainRig, val port: FaultyPort, val held: HeldVerifier, val ext: MediaController, val before: List<Long>)

    private fun policySession(r: EngineChainRig): MediaSession {
        val policy = PreviousBehaviorPlayer(
            player = r.engine.facade, thresholdProvider = { 3_000L }, scope = CoroutineScope(Dispatchers.Unconfined),
            onExternalTransport = {}, hydrateForPlay = { PlayerHydrationResult.Hydrated }, songsProvider = { emptyList() }, logResume = {},
            sessionProvider = { sessions.lastOrNull() },
            onExplicitPause = {}, onExplicitSeek = {}, onExplicitNavigation = {}, onExplicitRepeatChange = {},
            transportBlocked = { r.pc.isInstallUnverified },
        )
        return MediaSession.Builder(r.context, policy).setId("r3-policy-${counter++}").build().also { sessions += it }
    }

    private fun external(r: EngineChainRig, session: MediaSession): MediaController {
        val future = MediaController.Builder(r.context, session.token).setConnectionHints(android.os.Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) idleMainLooper()
        return future.get(5, TimeUnit.SECONDS).also { controllers += it; idleMainLooper(); TestMediaSource.settle(60) }
    }

    /** Bounded restored paused queue, then a FAILED Bounded-to-Full replacement (unverified), acknowledgements held thereafter. */
    private fun unverifiedHarness(): H {
        val r = EngineChainRig(true, writer).also { rigs += it }
        r.start(big(1), 13)
        r.pc.togglePlayPause(); r.settleAll()
        val before = r.saved()!!.queueSongIds
        val port = FaultyPort(r.port, refuse = true)
        r.pc.attachPhysicalWindowPort(port)
        val ext = external(r, policySession(r))
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        assertTrue("precondition: the failed Bounded-to-Full replacement left the install unverified", r.pc.isInstallUnverified)
        port.refuse = false
        val held = HeldVerifier(r.port.verifier!!)
        r.pc.boundedVerifierFactory = { held }
        return H(r, port, held, ext, before)
    }

    /** A newer bounded selection: submitted to the physical player, acknowledgement still held (NOT adopted). */
    private fun H.selectNewerBounded(base: Long = 3_000L, start: Int = 10) {
        r.pc.playFromQueue(big(base), start); r.idle()
    }

    private fun H.assertNoStartOrNavigation(note: String) {
        val idx = r.engine.currentPlayer.currentMediaItemIndex; val id = r.id()
        ext.play(); r.idle()
        assertFalse("$note: external Play must not start unadopted audio", r.engine.currentPlayer.playWhenReady)
        ext.seekToNext(); ext.seekToPrevious(); ext.seekToNextMediaItem(); ext.seekToPreviousMediaItem(); r.idle()
        assertEquals("$note: no navigation", idx, r.engine.currentPlayer.currentMediaItemIndex)
        val pos = r.engine.currentPlayer.currentPosition
        ext.seekTo(7_000L); ext.seekTo(0, 0L); r.idle()
        assertEquals("$note: no seek", pos, r.engine.currentPlayer.currentPosition)
        assertEquals(id, r.id())
        assertTrue(r.pc.isInstallUnverified)
    }

    private fun H.assertNoFalseClaims() {
        assertFalse("UI does not claim playback", r.pc.nowPlayingState.value.isPlaying)
        assertEquals("previous valid session preserved", before, r.saved()!!.queueSongIds)
        assertFalse("no selection statistic before adoption", writer.listenStarts.any { it >= 3_000L })
    }

    @Test fun `a pending bounded install keeps external transport closed`() {
        val h = harness()
        h.selectNewerBounded()
        assertEquals("the acknowledgement is held (install submitted, not adopted)", 1, h.held.count)
        h.assertNoStartOrNavigation("pending"); h.assertNoFalseClaims()
        h.ext.pause(); h.ext.stop(); h.r.idle() // always available, no exception
        assertFalse(h.r.engine.currentPlayer.playWhenReady)
    }

    @Test fun `a bounded install whose verification fails keeps transport closed and recovers on retry`() {
        val h = harness()
        h.selectNewerBounded(); h.held.failAll(); h.r.idle()
        h.assertNoStartOrNavigation("verification failed"); h.assertNoFalseClaims()
        h.r.pc.togglePlayPause(); h.r.idle() // explicit in-app retry of the accepted selection
        h.held.releaseAll(); h.r.settleAll()
        assertFalse("adopted: transport restored", h.r.pc.isInstallUnverified)
    }

    @Test fun `an immediately refused bounded install keeps the stale item unreachable`() {
        val h = harness()
        h.port.refuse = true
        runCatching { h.r.pc.playFromQueue(big(3_000L), 10) }; h.r.idle()
        h.assertNoStartOrNavigation("refused bounded install")
        assertFalse(h.r.engine.currentPlayer.playWhenReady)
    }

    @Test fun `an older install that becomes stale never releases the protection`() {
        val h = harness()
        h.selectNewerBounded(3_000L, 10)
        h.selectNewerBounded(5_000L, 20) // newer selection supersedes the pending one
        h.held.releaseOldest(); h.r.idle() // the older acknowledgement arrives late (Stale)
        h.assertNoStartOrNavigation("stale older install")
        assertEquals(5_020L, h.r.pc.nowPlayingState.value.song?.id)
        h.assertNoFalseClaims()
    }

    @Test fun `verified adoption of the newest install restores transport and statistics`() {
        val h = harness()
        h.selectNewerBounded(3_000L, 10)
        h.held.releaseAll(); h.r.settleAll()
        assertFalse("physically proven: protection released", h.r.pc.isInstallUnverified)
        assertEquals("3010", h.r.id())
        assertTrue("no duplicate selection events", writer.listenStarts.filter { it >= 3_000L }.size <= 1)
        h.ext.play(); h.r.settleAll()
        assertTrue("normal external Play after adoption", h.r.engine.currentPlayer.playWhenReady)
        h.ext.pause(); h.r.settleAll(); assertFalse(h.r.engine.currentPlayer.playWhenReady)
    }

    @Test fun `a user Play queued behind the unverified bounded install starts only after adoption`() {
        val h = harness()
        h.selectNewerBounded(3_000L, 10)
        assertFalse("not started while pending", h.r.engine.currentPlayer.playWhenReady)
        h.held.releaseAll(); h.r.settleAll()
        assertTrue("the accepted selection's own Play runs once adopted", h.r.engine.currentPlayer.playWhenReady)
        assertEquals("3010", h.r.id())
    }

    private fun harness() = unverifiedHarness()
    private companion object { var counter = 0 }
}

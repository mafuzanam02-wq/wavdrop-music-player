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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-F20J-R2. While a newly accepted Full queue is NOT adopted by the physical player (refused or unverified replacement), no
 * EXTERNAL route (notification, lock screen, media buttons, Bluetooth/AVRCP, Android Auto - all of which reach the player through a
 * MediaSession controller) may start or navigate the stale physical item. The external controller here is a real in-process
 * MediaController connected to a real MediaSession whose player is the PRODUCTION PreviousBehaviorPlayer over the engine façade.
 * Pause and Stop stay safe. Hardware routes themselves (Bluetooth, Android Auto) are NOT exercised: only the session boundary they use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ExternalTransportWhileUnverifiedTest {
    private class RecordingWriter : HookedPlayEventWriter() {
        val skips = mutableListOf<Long>()
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) { skips += songId }
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    private class FaultyPort(private val real: PhysicalWindowPort, var mode: Mode) : PhysicalWindowPort by real {
        enum class Mode { Pass, Refuse, WrongIndex }
        var attempts = 0
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            attempts++
            when (mode) {
                Mode.Pass -> real.setMediaItems(items, startIndex, startPositionMs)
                Mode.Refuse -> throw IllegalStateException("refused")
                Mode.WrongIndex -> real.setMediaItems(items, (startIndex + 1) % items.size, startPositionMs)
            }
        }
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
    private fun big() = List(400) { RealMedia3QueueRig.song(it + 1L) }
    private fun small(vararg ids: Long) = ids.map { RealMedia3QueueRig.song(it) }
    private fun EngineChainRig.ids(): List<String> = (0 until engine.currentPlayer.mediaItemCount).map { engine.currentPlayer.getMediaItemAt(it).mediaId }
    private fun EngineChainRig.settleAll() { quiesce(); TestMediaSource.settle(120); quiesce() }
    private fun EngineChainRig.saved() = runBlocking { sessionRepo.load() }

    private class Harness(val r: EngineChainRig, val port: FaultyPort, val ext: MediaController)

    /** The PRODUCTION session player (policy) over the engine façade, as PlaybackService builds it. */
    private fun policySession(r: EngineChainRig): MediaSession {
        val policy = PreviousBehaviorPlayer(
            player = r.engine.facade,
            thresholdProvider = { 3_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            onExternalTransport = {},
            hydrateForPlay = { PlayerHydrationResult.Hydrated },
            songsProvider = { emptyList() },
            logResume = {},
            sessionProvider = { sessions.lastOrNull() },
            onExplicitPause = {}, onExplicitSeek = {}, onExplicitNavigation = {}, onExplicitRepeatChange = {},
            transportBlocked = { r.pc.isInstallUnverified },
        )
        return MediaSession.Builder(r.context, policy).setId("r2-policy-${counter++}").build().also { sessions += it }
    }

    private fun external(r: EngineChainRig, session: MediaSession): MediaController {
        val future = MediaController.Builder(r.context, session.token).setConnectionHints(android.os.Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 200) idleMainLooper()
        return future.get(5, TimeUnit.SECONDS).also { controllers += it; idleMainLooper(); TestMediaSource.settle(60) }
    }

    private fun harness(mode: FaultyPort.Mode = FaultyPort.Mode.Refuse): Harness {
        val r = EngineChainRig(true, writer).also { rigs += it }
        r.start(big(), 13)
        r.pc.togglePlayPause(); r.settleAll()
        val port = FaultyPort(r.port, mode)
        r.pc.attachPhysicalWindowPort(port)
        val ext = external(r, policySession(r))
        r.pc.playFromQueue(small(*three.toLongArray()), 0); r.settleAll()
        return Harness(r, port, ext)
    }

    private fun Harness.settle() { r.settleAll() }
    private fun Harness.assertStaleUntouched(index: Int = 13, id: String = "14") {
        assertEquals(index, r.engine.currentPlayer.currentMediaItemIndex)
        assertEquals(id, r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertFalse("stale physical media must not play", r.engine.currentPlayer.playWhenReady)
        assertFalse(r.engine.currentPlayer.isPlaying)
        assertEquals("logical selection intact", three, r.pc.nowPlayingState.value.queue.map { it.id })
        assertEquals(1_001L, r.pc.nowPlayingState.value.song?.id)
        assertFalse(writer.listenStarts.contains(1_001L)); assertFalse(writer.skips.contains(14L))
    }

    @Test fun `external Play after a refused replacement does not start the stale item`() {
        val h = harness(); val before = h.r.saved()!!.queueSongIds
        h.ext.play(); h.settle()
        h.assertStaleUntouched()
        assertEquals("session not overwritten", before, h.r.saved()!!.queueSongIds)
    }

    @Test fun `external Play after an incorrect-index verification does not start the stale item`() {
        val h = harness(FaultyPort.Mode.WrongIndex)
        val idx = h.r.engine.currentPlayer.currentMediaItemIndex
        h.ext.play(); h.settle()
        assertFalse(h.r.engine.currentPlayer.playWhenReady)
        assertEquals(idx, h.r.engine.currentPlayer.currentMediaItemIndex)
        assertEquals(1_001L, h.r.pc.nowPlayingState.value.song?.id)
    }

    @Test fun `external Next and Previous do not navigate the stale queue`() {
        val h = harness()
        h.ext.seekToNext(); h.settle(); h.assertStaleUntouched()
        h.ext.seekToPrevious(); h.settle(); h.assertStaleUntouched()
        h.ext.seekToNextMediaItem(); h.ext.seekToPreviousMediaItem(); h.settle(); h.assertStaleUntouched()
    }

    @Test fun `external seek does not move the stale item`() {
        val h = harness()
        val pos = h.r.engine.currentPlayer.currentPosition
        h.ext.seekTo(5_000L); h.settle()
        h.ext.seekTo(0, 0L); h.settle()
        h.assertStaleUntouched()
        assertEquals(pos, h.r.engine.currentPlayer.currentPosition)
    }

    @Test fun `external Pause and Stop stay safe`() {
        val h = harness()
        h.ext.pause(); h.settle(); h.assertStaleUntouched()
        h.ext.stop(); h.settle()
        assertFalse(h.r.engine.currentPlayer.playWhenReady)
        assertEquals(three, h.r.pc.nowPlayingState.value.queue.map { it.id })
    }

    @Test fun `repeated external Play never starts the stale item and never retries`() {
        val h = harness()
        repeat(4) { h.ext.play(); h.settle() }
        h.assertStaleUntouched()
        assertEquals("external Play is refused, not an install attempt", 1, h.port.attempts)
    }

    @Test fun `in-app recovery restores normal external transport`() {
        val h = harness()
        h.port.mode = FaultyPort.Mode.Pass
        h.r.pc.togglePlayPause(); h.settle()
        assertEquals(three.map { it.toString() }, h.r.ids())
        assertTrue(h.r.engine.currentPlayer.playWhenReady)
        h.ext.pause(); h.settle(); assertFalse(h.r.engine.currentPlayer.playWhenReady)
        h.ext.play(); h.settle(); assertTrue("verified adoption restores external Play", h.r.engine.currentPlayer.playWhenReady)
        h.ext.seekToNext(); h.settle()
        assertEquals(1, h.r.engine.currentPlayer.currentMediaItemIndex)
    }

    @Test fun `a failed reconnect followed by external Play stays closed`() {
        val h = harness()
        h.r.connectAppController(); h.settle()
        h.ext.play(); h.settle()
        h.assertStaleUntouched()
    }

    @Test fun `a successful reconnect recovers without autoplay and external Play then works`() {
        val h = harness()
        h.port.mode = FaultyPort.Mode.Pass
        h.r.connectAppController(); h.settle()
        assertEquals(three.map { it.toString() }, h.r.ids())
        assertFalse("no autoplay", h.r.engine.currentPlayer.playWhenReady)
        h.ext.play(); h.settle()
        assertTrue(h.r.engine.currentPlayer.playWhenReady)
        assertEquals("1001", h.r.engine.currentPlayer.currentMediaItem?.mediaId)
    }

    @Test fun `external controller disconnect during recovery is harmless`() {
        val h = harness()
        h.ext.release(); h.settle()
        h.port.mode = FaultyPort.Mode.Pass
        h.r.pc.togglePlayPause(); h.settle()
        assertEquals(three.map { it.toString() }, h.r.ids())
        assertEquals(three, h.r.saved()!!.queueSongIds)
    }

    @Test fun `release with an external controller attached is safe`() {
        val h = harness()
        h.r.pc.release()
        h.ext.play(); h.ext.seekToNext(); h.ext.pause(); h.settle()
        assertEquals(1, h.port.attempts)
        assertFalse(h.r.engine.currentPlayer.playWhenReady)
    }

    private companion object { var counter = 0 }
}

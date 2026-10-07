package com.launchpoint.wavdrop.playback

import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * ST-1: the physical enforcement of the terminal boundary, against a REAL ExoPlayer whose clock is advanced so items play to
 * their natural end. Pause-at-end-of-media-items is what makes "stop at the natural end of the armed occurrence" hold for Repeat
 * Off / One / All, at the first, middle and last item, without changing the repeat mode or the queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SleepBoundaryPhysicalPlayerTest {

    private class Events(player: Player) : Player.Listener {
        val log = mutableListOf<String>()
        init { player.addListener(this) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { log += "T($reason)" }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { log += "PWR($playWhenReady,$reason)" }
    }

    private fun items(vararg ids: String) = ids.map { MediaItem.Builder().setMediaId(it).setUri("content://x/$it").build() }

    private fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(50L, left)
            ShadowSystemClock.advanceBy(Duration.ofMillis(step))
            Thread.sleep(8)
            shadowOf(Looper.getMainLooper()).idle()
            left -= step
        }
    }

    private fun player(ids: List<String>, repeat: Int, startIndex: Int, hold: Boolean, play: Boolean = true): Pair<ExoPlayer, Events> {
        val p = TestMediaSource.newPlayer(RuntimeEnvironment.getApplication())
        val e = Events(p)
        p.setMediaItems(items(*ids.toTypedArray()))
        p.repeatMode = repeat
        p.pauseAtEndOfMediaItems = hold
        p.prepare()
        TestMediaSource.awaitCondition { p.playbackState == Player.STATE_READY }
        p.seekTo(startIndex, p.duration - 300L)
        TestMediaSource.settle(100)
        if (play) p.play()
        advance(1_200)
        return p to e
    }

    private fun repeatName(mode: Int) = when (mode) { Player.REPEAT_MODE_OFF -> "OFF"; Player.REPEAT_MODE_ONE -> "ONE"; else -> "ALL" }

    @Test fun `with the hold armed every repeat mode stops at the end of the first middle and last item without advancing`() {
        for (repeat in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
            for (start in 0..2) {
                val (p, e) = player(listOf("a", "b", "c"), repeat, start, hold = true)
                val label = "repeat=${repeatName(repeat)} start=$start $e.log"
                assertEquals(label, start, p.currentMediaItemIndex)
                assertFalse(label, p.playWhenReady)
                assertFalse(label, p.isPlaying)
                assertTrue(label, p.currentPosition >= p.duration - 50L)
                assertTrue("exactly one stop with END_OF_MEDIA_ITEM and no transition: ${e.log}", e.log.contains("PWR(false,${Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM})"))
                assertTrue("no transition: ${e.log}", e.log.none { it.startsWith("T(") && it != "T(${Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED})" && it != "T(${Player.MEDIA_ITEM_TRANSITION_REASON_SEEK})" })
                assertEquals("the saved repeat mode is untouched", repeat, p.repeatMode)
                p.release()
            }
        }
    }

    @Test fun `without the hold the same setup does advance - the hold is what enforces the boundary`() {
        val (off, _) = player(listOf("a", "b", "c"), Player.REPEAT_MODE_OFF, 1, hold = false)
        assertEquals(2, off.currentMediaItemIndex)
        off.release()
        val (all, _) = player(listOf("a", "b", "c"), Player.REPEAT_MODE_ALL, 2, hold = false)
        assertEquals("wrapped", 0, all.currentMediaItemIndex)
        all.release()
        val (one, e) = player(listOf("a", "b", "c"), Player.REPEAT_MODE_ONE, 1, hold = false)
        assertEquals(1, one.currentMediaItemIndex)
        assertTrue("it looped: ${e.log}", one.currentPosition < 60_000L && one.isPlaying)
        one.release()
    }

    @Test fun `duplicate media ids - the occurrence that was playing is the one that stops`() {
        val (p, _) = player(listOf("s", "x", "s", "y"), Player.REPEAT_MODE_ALL, 2, hold = true)
        assertEquals("the second occurrence of the same media id stopped, not the first and not the next", 2, p.currentMediaItemIndex)
        assertFalse(p.playWhenReady)
        p.release()
    }

    @Test fun `armed while paused nothing moves, and playing resumes then stops at the end`() {
        val (p, _) = player(listOf("a", "b", "c"), Player.REPEAT_MODE_ALL, 1, hold = true, play = false)
        assertEquals(1, p.currentMediaItemIndex)
        assertFalse(p.playWhenReady)
        p.play()
        advance(1_200)
        assertEquals(1, p.currentMediaItemIndex)
        assertFalse(p.playWhenReady)
        p.release()
    }

    @Test fun `releasing the hold after the stop lets ordinary playback continue to the next item`() {
        val (p, _) = player(listOf("a", "b", "c"), Player.REPEAT_MODE_OFF, 0, hold = true)
        assertEquals(0, p.currentMediaItemIndex)
        p.pauseAtEndOfMediaItems = false
        p.play()
        advance(600)
        assertEquals(1, p.currentMediaItemIndex)
        assertTrue(p.playWhenReady)
        p.release()
    }

    @Test fun `a seek inside the armed item does not defeat the hold`() {
        val (p, _) = player(listOf("a", "b", "c"), Player.REPEAT_MODE_ALL, 1, hold = true, play = false)
        p.seekTo(1, 10_000L)
        TestMediaSource.settle(50)
        p.seekTo(1, p.duration - 300L)
        TestMediaSource.settle(50)
        p.play()
        advance(1_200)
        assertEquals(1, p.currentMediaItemIndex)
        assertFalse(p.playWhenReady)
        p.release()
    }

    // ── assembly: both physical players, one flag ───────────────────────────────────────────────────────────────────────

    @Test fun `the gated assembly applies and releases the hold on both physical players`() {
        val assembly = assemblePlayback(RuntimeEnvironment.getApplication(), true, AudioAttributes.DEFAULT, sessionIdProvider = { 4242 })
        val engine = assembly.engine!!
        assembly.setPauseAtEndOfMediaItems(true)
        assertTrue(engine.currentPlayer.pauseAtEndOfMediaItems)
        assertTrue(engine.nextPlayer.pauseAtEndOfMediaItems)
        assembly.setPauseAtEndOfMediaItems(false)
        assertFalse(engine.currentPlayer.pauseAtEndOfMediaItems)
        assertFalse(engine.nextPlayer.pauseAtEndOfMediaItems)
        engine.release()
    }

    @Test fun `the single player assembly applies and releases the hold`() {
        val assembly = assemblePlayback(RuntimeEnvironment.getApplication(), false, AudioAttributes.DEFAULT)
        assembly.setPauseAtEndOfMediaItems(true)
        assertTrue(assembly.primaryPlayer.pauseAtEndOfMediaItems)
        assembly.setPauseAtEndOfMediaItems(false)
        assertFalse(assembly.primaryPlayer.pauseAtEndOfMediaItems)
        assembly.primaryPlayer.release()
    }
}

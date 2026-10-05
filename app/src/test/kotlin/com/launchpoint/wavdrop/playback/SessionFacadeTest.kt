package com.launchpoint.wavdrop.playback

import android.os.HandlerThread
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CF-2M2: proves, with hand-written fakes and the real Media3 [androidx.media3.common.SimpleBasePlayer] contract, that
 * [SessionFacade] is transparent in front of ONE physical player and that its (unused-in-production) delegate-swap seam
 * behaves deterministically. The swap tests record the exact raw event sequences that CF-2M1 listed as open questions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SessionFacadeTest {

    private fun abc(name: String, index: Int = 0, positionMs: Long = 0L, playing: Boolean = true) =
        ScriptedPlayer(name, titles = listOf("A", "B", "C"), index = index, positionMs = positionMs, playing = playing)

    // ── 1. initial state ────────────────────────────────────────────────────────────────────────

    @Test fun initialStateEqualsDelegateState() {
        val physical = abc("P", index = 1, positionMs = 42_000L)
        val facade = SessionFacade(physical)
        idleMainLooper()
        assertEquals(physical.currentMediaItem?.mediaId, facade.currentMediaItem?.mediaId)
        assertEquals("B", facade.currentMediaItem?.mediaId)
        assertEquals(physical.currentMediaItemIndex, facade.currentMediaItemIndex)
        assertEquals(physical.currentPosition, facade.currentPosition)
        assertEquals(42_000L, facade.currentPosition)
        assertEquals(physical.duration, facade.duration)
        assertEquals(physical.playbackState, facade.playbackState)
        assertEquals(physical.playWhenReady, facade.playWhenReady)
        assertEquals(physical.isPlaying, facade.isPlaying)
        assertTrue(facade.isPlaying)
        assertEquals(physical.repeatMode, facade.repeatMode)
        assertEquals(physical.shuffleModeEnabled, facade.shuffleModeEnabled)
        assertEquals(physical.playbackParameters, facade.playbackParameters)
        assertEquals(physical.audioAttributes, facade.audioAttributes)
        assertEquals(physical.deviceInfo, facade.deviceInfo)
        assertEquals(physical.currentTracks, facade.currentTracks)
        assertEquals(physical.mediaMetadata.title, facade.mediaMetadata.title)
        assertEquals(physical.playbackSuppressionReason, facade.playbackSuppressionReason)
        assertEquals(physical.playerError, facade.playerError)
        assertEquals(physical.availableCommands, facade.availableCommands)
        assertEquals(physical.volume, facade.volume, 0f)
        // the REAL timeline is exposed, not a synthesized one
        assertEquals(physical.mediaItemCount, facade.mediaItemCount)
        assertEquals(3, facade.mediaItemCount)
        for (i in 0 until 3) assertEquals(physical.getMediaItemAt(i).mediaId, facade.getMediaItemAt(i).mediaId)
        assertEquals(physical.currentTimeline.windowCount, facade.currentTimeline.windowCount)
    }

    @Test fun constructingAndIdlingEmitsNoEvents() {
        val physical = abc("P")
        val facade = SessionFacade(physical)
        val rec = EventRecorder().also { facade.addListener(it) }
        idleMainLooper()
        assertEquals(emptyList<String>(), rec.events)
    }

    // ── 2. command routing (single delegate) ───────────────────────────────────────────────────

    @Test fun normalCommandsRouteToTheDelegate() {
        val physical = abc("P")
        val facade = SessionFacade(physical)
        facade.pause()
        facade.play()
        facade.seekTo(2, 5_000L)
        facade.setRepeatMode(Player.REPEAT_MODE_ALL)
        facade.prepare()
        idleMainLooper()
        assertEquals(
            listOf("P.setPlayWhenReady(false)", "P.setPlayWhenReady(true)", "P.seek(2,5000)", "P.setRepeatMode(2)", "P.prepare()"),
            physical.commands,
        )
    }

    // ── 3. single-delegate event parity (façade vs the same player observed directly) ───────────

    private fun parity(label: String, op: (ScriptedPlayer) -> Unit): Pair<List<String>, List<String>> {
        val direct = abc("D")
        val directRec = EventRecorder().also { direct.addListener(it) }
        op(direct)
        idleMainLooper()
        val physical = abc("F")
        val facade = SessionFacade(physical)
        val facadeRec = EventRecorder().also { facade.addListener(it) }
        op(physical)
        idleMainLooper()
        assertEquals("parity: $label", directRec.events, facadeRec.events)
        return directRec.events to facadeRec.events
    }

    @Test fun pauseAndPlayHaveExactParityAndNoFakeEdges() {
        val (pause, _) = parity("pause") { it.mutate { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) } }
        assertEquals(listOf("playWhenReady(false,1)", "isPlaying(false)"), pause)
        // play is its own scenario starting from a paused player
        val direct = abc("D", playing = false)
        val directRec = EventRecorder().also { direct.addListener(it) }
        direct.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        val physical = abc("F", playing = false)
        val facade = SessionFacade(physical)
        val facadeRec = EventRecorder().also { facade.addListener(it) }
        physical.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        idleMainLooper()
        assertEquals(directRec.events, facadeRec.events)
        assertEquals(listOf("playWhenReady(true,1)", "isPlaying(true)"), facadeRec.events)
    }

    @Test fun seekHasExactParity() {
        val (events, _) = parity("seek") {
            it.mutate { setCurrentMediaItemIndex(0).setContentPositionMs(5_000L).setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, 5_000L) }
        }
        assertEquals(listOf("discontinuity(0@0->0@5000,SEEK)"), events)
    }

    @Test fun naturalAutoTransitionHasExactParityWithOneTransitionAndOneDiscontinuity() {
        val (events, _) = parity("auto") {
            it.mutate { setCurrentMediaItemIndex(1).setContentPositionMs(0L).setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0L) }
        }
        assertEquals(
            listOf("discontinuity(0@0->1@0,AUTO_TRANSITION)", "transition(B,AUTO)", "metadata(B)"),
            events,
        )
        assertEquals(1, events.count { it.startsWith("transition") })
        assertEquals(1, events.count { it.startsWith("discontinuity") })
    }

    @Test fun playbackStateRepeatAndErrorHaveExactParity() {
        val (buffering, _) = parity("buffering") { it.mutate { setPlaybackState(Player.STATE_BUFFERING) } }
        assertEquals(listOf("state(2)", "isPlaying(false)"), buffering)
        val (repeat, _) = parity("repeat") { it.mutate { setRepeatMode(Player.REPEAT_MODE_ALL) } }
        assertEquals(listOf("repeat(2)"), repeat)
        val (error, _) = parity("error") {
            it.mutate {
                setPlaybackState(Player.STATE_IDLE)
                setPlayerError(PlaybackException("boom", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            }
        }
        assertTrue(error.any { it.startsWith("error(") })
    }

    @Test fun timelineAndMetadataChangesHaveExactParity() {
        val (events, _) = parity("append-item") {
            it.mutate {
                setPlaylist(ScriptedPlayer.buildState(listOf("A", "B", "C", "D"), 0, 0L, true, Player.STATE_READY).let { s ->
                    List(s.playlist.size) { i -> s.playlist[i] }
                })
            }
        }
        assertTrue(events.any { it.startsWith("timeline(count=4") })
        // (parity() already proved the façade added no transition/discontinuity of its own)
    }

    // ── 4/5. swap: looper contract ──────────────────────────────────────────────────────────────

    @Test fun sameLooperReplacementSucceedsAndTheFacadeIsTheSameObject() {
        val p1 = abc("P1")
        val p2 = abc("P2", index = 1)
        val facade = SessionFacade(p1)
        val identity = facade
        assertSame(p1, facade.delegatePlayer)
        facade.replaceDelegate(p2)
        assertSame(identity, facade)
        assertSame(p2, facade.delegatePlayer)
    }

    @Test fun incompatibleLooperReplacementFailsClearlyAndChangesNothing() {
        val thread = HandlerThread("cf2m2-other-looper").also { it.start() }
        try {
            val p1 = abc("P1")
            val other = ScriptedPlayer("P2", looper = thread.looper, titles = listOf("A", "B", "C"), index = 1, eagerInit = false)
            val facade = SessionFacade(p1)
            val rec = EventRecorder().also { facade.addListener(it) }
            try {
                facade.replaceDelegate(other)
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("application looper"))
            }
            idleMainLooper()
            assertSame(p1, facade.delegatePlayer) // not swapped
            assertEquals("A", facade.currentMediaItem?.mediaId)
            assertEquals(emptyList<String>(), rec.events)
        } finally {
            thread.quitSafely()
        }
    }

    // ── 6/7/8/9/10. swap behaviour ──────────────────────────────────────────────────────────────

    @Test fun afterReplacementCommandsRouteOnlyToTheNewDelegate() {
        val p1 = abc("P1", index = 0, positionMs = 170_000L)
        val p2 = abc("P2", index = 1, positionMs = 1_000L)
        val facade = SessionFacade(p1)
        facade.pause(); facade.play(); facade.seekTo(0, 10L)
        idleMainLooper()
        assertEquals(listOf("P1.setPlayWhenReady(false)", "P1.setPlayWhenReady(true)", "P1.seek(0,10)"), p1.commands)
        p1.commands.clear()

        facade.replaceDelegate(p2)
        idleMainLooper()
        facade.pause(); facade.play(); facade.seekTo(1, 2_000L); facade.setRepeatMode(Player.REPEAT_MODE_ONE)
        idleMainLooper()
        assertEquals(emptyList<String>(), p1.commands) // P1 receives no façade-routed command after the swap
        assertEquals(
            listOf("P2.setPlayWhenReady(false)", "P2.setPlayWhenReady(true)", "P2.seek(1,2000)", "P2.setRepeatMode(1)"),
            p2.commands,
        )
    }

    @Test fun oldDelegateEventsStopAndNewDelegateEventsAreForwarded() {
        val p1 = abc("P1")
        val p2 = abc("P2", index = 1)
        val facade = SessionFacade(p1)
        val rec = EventRecorder().also { facade.addListener(it) }
        idleMainLooper()
        facade.replaceDelegate(p2)
        idleMainLooper()
        rec.events.clear()

        // the old physical player keeps changing (it is being retired): nothing may leak
        p1.mutate { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        p1.mutate { setPlaybackState(Player.STATE_ENDED) }
        p1.mutate { setRepeatMode(Player.REPEAT_MODE_ALL) }
        idleMainLooper()
        assertEquals(emptyList<String>(), rec.events)
        assertTrue(facade.isPlaying) // still the new delegate's value

        // the new physical player's changes DO flow
        p2.mutate { setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        idleMainLooper()
        assertEquals(listOf("playWhenReady(false,1)", "isPlaying(false)"), rec.events)
        assertFalse(facade.isPlaying)
    }

    @Test fun playingToPlayingSwapNeverFlickersIsPlayingOrPlayWhenReadyOrState() {
        val p1 = abc("P1", index = 0, positionMs = 170_000L)
        val p2 = abc("P2", index = 1, positionMs = 1_000L)
        val facade = SessionFacade(p1)
        val rec = EventRecorder().also { facade.addListener(it) }
        idleMainLooper()
        facade.replaceDelegate(p2)
        idleMainLooper()
        assertTrue(rec.events.none { it.startsWith("isPlaying") })
        assertTrue(rec.events.none { it.startsWith("playWhenReady") })
        assertTrue(rec.events.none { it.startsWith("state(") })
        assertTrue(facade.isPlaying)
    }

    @Test fun currentItemAndPositionImmediatelySourceFromTheNewDelegate() {
        val p1 = abc("P1", index = 0, positionMs = 170_000L)
        val p2 = abc("P2", index = 1, positionMs = 1_000L)
        val facade = SessionFacade(p1)
        assertEquals("A", facade.currentMediaItem?.mediaId)
        assertEquals(170_000L, facade.currentPosition)
        facade.replaceDelegate(p2) // note: NO looper idle between the swap and the reads
        assertEquals("B", facade.currentMediaItem?.mediaId)
        assertEquals(1, facade.currentMediaItemIndex)
        assertEquals(1_000L, facade.currentPosition)
        assertNotNull(facade.currentTimeline)
    }

    // ── 11. the raw event sequences ─────────────────────────────────────────────────────────────

    @Test fun rawSwapSequencePlayingAToPlayingBWithDefaultReasons() {
        val p1 = abc("P1", index = 0, positionMs = 170_000L)
        val p2 = abc("P2", index = 1, positionMs = 1_000L)
        val facade = SessionFacade(p1)
        val rec = EventRecorder().also { facade.addListener(it) }
        idleMainLooper()
        facade.replaceDelegate(p2)
        idleMainLooper()
        // One coherent diff. Media3 reports it honestly as INTERNAL / PLAYLIST_CHANGED; the timeline did not change.
        assertEquals(
            listOf("discontinuity(0@170000->1@1000,INTERNAL)", "transition(B,PLAYLIST_CHANGED)", "metadata(B)"),
            rec.events,
        )
    }

    @Test fun rawSwapSequenceCanBePresentedAsOneLogicalAutoTransition() {
        val p1 = abc("P1", index = 0, positionMs = 170_000L)
        val p2 = abc("P2", index = 1, positionMs = 1_000L)
        val facade = SessionFacade(p1)
        val rec = EventRecorder().also { facade.addListener(it) }
        idleMainLooper()
        facade.replaceDelegate(p2, presentAsAutoTransition = true)
        idleMainLooper()
        assertEquals(
            listOf("discontinuity(0@170000->1@1000,AUTO_TRANSITION)", "transition(B,AUTO)", "metadata(B)"),
            rec.events,
        )
        assertEquals(1, rec.events.count { it.startsWith("transition") })
        assertEquals(1, rec.events.count { it.startsWith("discontinuity") })
        assertTrue(rec.events.none { it.startsWith("isPlaying") || it.startsWith("state(") || it.startsWith("playWhenReady") })
        // the pin is one-shot: later physical changes carry no leftover discontinuity
        rec.events.clear()
        p2.mutate { setRepeatMode(Player.REPEAT_MODE_ALL) }
        idleMainLooper()
        assertEquals(listOf("repeat(2)"), rec.events) // no leftover AUTO discontinuity
    }

    @Test fun swapToADifferentTimelineReportsTheTimelineChangeOnce() {
        // CF-2M4 relevance: a NEXT slot that is not yet grafted has a different (single-item) timeline.
        val p1 = abc("P1", index = 0, positionMs = 170_000L)
        val p2 = ScriptedPlayer("P2", titles = listOf("B"), index = 0, positionMs = 1_000L)
        val facade = SessionFacade(p1)
        val rec = EventRecorder().also { facade.addListener(it) }
        idleMainLooper()
        facade.replaceDelegate(p2)
        idleMainLooper()
        assertEquals(1, rec.events.count { it.startsWith("timeline(count=1") })
        assertEquals(1, rec.events.count { it.startsWith("transition") })
        assertTrue(rec.events.none { it.startsWith("isPlaying") })
        assertEquals(1, facade.mediaItemCount)
    }

    @Test fun releaseIsForwardedToTheCurrentDelegateOnly() {
        val p1 = abc("P1")
        val p2 = abc("P2", index = 1)
        val facade = SessionFacade(p1)
        facade.replaceDelegate(p2)
        idleMainLooper()
        facade.release()
        idleMainLooper()
        assertEquals(emptyList<String>(), p1.commands)
        assertEquals(listOf("P2.release()"), p2.commands)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}

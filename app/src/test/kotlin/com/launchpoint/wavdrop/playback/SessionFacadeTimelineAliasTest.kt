package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CF-2M5: the façade aliases a new delegate's private Timeline uids to the already-presented ones ONLY when the new delegate
 * mirrors the same logical queue (same size, equal MediaItem at every index). It never hides a genuine queue difference and does
 * not suppress later timeline mutations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SessionFacadeTimelineAliasTest {

    /** P1 plays [first] at [from]; P2 holds [second] (built through commands, so its window uids differ from P1's) at [to]. */
    private class Pair2(first: List<String>, second: List<String>, from: Int, to: Int) {
        val p1 = ScriptedPlayer("P1", titles = first, index = from, playing = true)
        val p2 = ScriptedPlayer("P2", titles = emptyList(), playing = false, state = Player.STATE_IDLE).also {
            it.setMediaItems(second.map(ScriptedPlayer::mediaItem), to, 0L)
            it.prepare()
            it.becomeReady()
            it.mutate { setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        }
        val facade = SessionFacade(p1)
        val events = EventRecorder().also { facade.addListener(it) }

        init { idleMainLooper() }

        fun swap() { facade.replaceDelegate(p2, presentAsAutoTransition = true); idleMainLooper() }
        fun windowUids(): List<Any> {
            val w = Timeline.Window()
            return (0 until facade.currentTimeline.windowCount).map { facade.currentTimeline.getWindow(it, w).uid }
        }
    }

    private val abcd = listOf("A", "B", "C", "D")

    @Test fun equivalentQueueWithDifferentPhysicalUidsEmitsOnlyTheAutoTransition() {
        val t = Pair2(abcd, abcd, from = 1, to = 2)
        val before = t.windowUids()
        t.swap()
        val e = t.events.events
        assertFalse("no synthetic timeline event: $e", e.any { it.startsWith("timeline(") })
        assertEquals(1, e.count { it.startsWith("transition(") })
        assertTrue(e.contains("transition(C,AUTO)"))
        assertEquals(1, e.count { it.startsWith("discontinuity(") })
        assertTrue(e.single { it.startsWith("discontinuity(") }.endsWith("AUTO_TRANSITION)"))
        assertEquals("the façade's logical timeline identity is stable", before, t.windowUids())
        assertEquals("C", t.facade.currentMediaItem?.mediaId)
    }

    @Test fun aDifferentQueueStillEmitsATimelineChange() {
        val t = Pair2(abcd, listOf("A", "B", "X", "D"), from = 1, to = 2)
        t.swap()
        assertTrue(t.events.events.toString(), t.events.events.any { it.startsWith("timeline(") })
    }

    @Test fun aDifferentItemCountStillEmitsATimelineChange() {
        val t = Pair2(abcd, listOf("A", "B", "C"), from = 1, to = 2)
        t.swap()
        assertTrue(t.events.events.toString(), t.events.events.any { it.startsWith("timeline(") })
    }

    @Test fun aDifferentOrderStillEmitsATimelineChange() {
        val t = Pair2(abcd, listOf("A", "C", "B", "D"), from = 1, to = 2)
        t.swap()
        assertTrue(t.events.events.toString(), t.events.events.any { it.startsWith("timeline(") })
    }

    @Test fun duplicateHeavyQueuesAreComparedPositionallyNotByMediaIdSet() {
        val dup = listOf("A", "B", "A", "B", "A")
        val same = Pair2(dup, dup, from = 2, to = 3)
        same.swap()
        assertFalse("equivalent duplicate-heavy queue: ${same.events.events}", same.events.events.any { it.startsWith("timeline(") })
        // Same multiset of ids, different positions: must NOT be treated as equivalent.
        val reordered = Pair2(dup, listOf("B", "A", "A", "B", "A"), from = 2, to = 3)
        reordered.swap()
        assertTrue(reordered.events.events.toString(), reordered.events.events.any { it.startsWith("timeline(") })
    }

    @Test fun laterGenuineMutationsOnTheNewDelegateStillPropagate() {
        val t = Pair2(abcd, abcd, from = 1, to = 2)
        t.swap()
        t.events.events.clear()
        t.p2.addMediaItem(ScriptedPlayer.mediaItem("E")); idleMainLooper()
        assertTrue("an insert after the swap is a real timeline change: ${t.events.events}", t.events.events.any { it.startsWith("timeline(count=5") })
        assertEquals(5, t.facade.mediaItemCount)
        t.events.events.clear()
        t.p2.removeMediaItem(4); idleMainLooper()
        assertTrue(t.events.events.any { it.startsWith("timeline(count=4") })
    }
}

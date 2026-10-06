package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The assumptions the reconciler depends on, proven on REAL Media3 (ExoPlayer behind a MediaSession, read and mutated through a
 * MediaController): after a mutation that does not include the current item, `currentMediaItemIndex` read IMMEDIATELY on the
 * controller (before any looper turn) already reflects the shift, the current MediaItem is the same, position and play state are
 * preserved. Observed callback behaviour is documented too: the SERVER player reports only a timeline change, but the
 * MediaController re-reports a stale automatic discontinuity (and sometimes a media-item transition naming the unchanged current
 * item) after controller-issued mutations; PlayerController filters those (see [isStaleAutomaticAdvanceEcho]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RealMedia3TimelineSemanticsTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()

    @After fun tearDown() { rigs.forEach { it.releaseAll() }; rigs.clear() }

    private fun rig(ids: LongArray, current: Int, positionMs: Long = 12_345L, playing: Boolean = false): RealMedia3QueueRig =
        RealMedia3QueueRig(RealMedia3QueueRig.songs(*ids), current, positionMs, playing).also { rigs += it }

    private fun items(vararg ids: Long) = RealMedia3QueueRig.songs(*ids).map { it.toPlaybackMediaItem() }

    /** Both observers on the same mutations: the server player (ground truth) and the controller (what the app sees). */
    private class Observers(rig: RealMedia3QueueRig) {
        val server = RealMedia3QueueRig.Callbacks().also { rig.player.addListener(it) }
        val controller = RealMedia3QueueRig.Callbacks().also { rig.controller.addListener(it) }
        fun clear() { server.events.clear(); controller.events.clear() }
    }

    /** A transition, if the controller re-reports one, may only name the unchanged current item. */
    private fun assertNoSongChange(events: List<String>, currentMediaId: String?, label: String) {
        val transitions = events.filter { it.startsWith("transition(") }
        assertTrue("$label: transitions may only echo the unchanged current item ($currentMediaId): $events", transitions.all { it.startsWith("transition($currentMediaId,") })
    }

    private fun assertCurrentPreserved(before: RealMedia3QueueRig.Snapshot, after: RealMedia3QueueRig.Snapshot, expectedIndex: Int, label: String) {
        assertEquals("$label current media id", before.mediaId, after.mediaId)
        assertEquals("$label index", expectedIndex, after.index)
        assertEquals("$label playWhenReady", before.playWhenReady, after.playWhenReady)
        assertTrue("$label position preserved (${before.positionMs} -> ${after.positionMs})", after.positionMs >= before.positionMs - 50 && after.positionMs <= before.positionMs + 2_000)
    }

    @Test fun removeBeforeCurrentShiftsTheIndexImmediatelyAndKeepsTheCurrentItem() {
        for (playing in listOf(false, true)) {
            val r = rig(longArrayOf(1, 2, 3, 4, 5), current = 3, playing = playing)
            r.settle(300)
            val obs = Observers(r)
            val before = r.snapshot()
            assertEquals("4", before.mediaId)

            r.controller.removeMediaItems(0, 2)
            val immediate = r.snapshot() // NO looper turn yet
            r.settle()
            val eventual = r.snapshot()

            println("REAL-M3 remove-before playing=$playing before=${before.index} immediate=${immediate.index} eventual=${eventual.index} server=${r.player.currentMediaItemIndex} serverEvents=${obs.server.events} controllerEvents=${obs.controller.events}")
            assertCurrentPreserved(before, immediate, expectedIndex = 1, label = "immediate(playing=$playing)")
            assertCurrentPreserved(before, eventual, expectedIndex = 1, label = "eventual(playing=$playing)")
            assertEquals(listOf("3", "4", "5"), eventual.ids)
            assertEquals(r.serverIds(), eventual.ids)
            assertTrue("server emits no transition/discontinuity: ${obs.server.events}", obs.server.events.none { it.startsWith("transition(") || it.startsWith("discontinuity(") })
            assertNoSongChange(obs.controller.events, before.mediaId, "remove-before playing=$playing")
        }
    }

    @Test fun insertBeforeCurrentShiftsTheIndexImmediately() {
        val r = rig(longArrayOf(3, 4, 5), current = 1)
        r.settle(300)
        val obs = Observers(r)
        val before = r.snapshot()

        r.controller.replaceMediaItems(0, 0, items(1, 2))
        val immediate = r.snapshot()
        r.settle()
        val eventual = r.snapshot()

        println("REAL-M3 insert-before before=${before.index} immediate=${immediate.index} eventual=${eventual.index} serverEvents=${obs.server.events} controllerEvents=${obs.controller.events}")
        assertCurrentPreserved(before, immediate, 3, "immediate")
        assertCurrentPreserved(before, eventual, 3, "eventual")
        assertEquals(listOf("1", "2", "3", "4", "5"), eventual.ids)
        assertTrue(obs.server.events.none { it.startsWith("transition(") || it.startsWith("discontinuity(") })
        assertNoSongChange(obs.controller.events, before.mediaId, "insert-before")
    }

    @Test fun replacingAPrefixWithAShorterAndALongerRangeKeepsTheSameCurrentItem() {
        val r = rig(longArrayOf(1, 2, 3, 4, 5, 6), current = 4)
        val before = r.snapshot()
        assertEquals("5", before.mediaId)

        r.controller.replaceMediaItems(0, 3, items(9)) // 3 -> 1: shorter
        val shorter = r.snapshot()
        assertCurrentPreserved(before, shorter, 2, "shorter immediate")
        assertEquals(listOf("9", "4", "5", "6"), r.snapshot().ids)

        r.controller.replaceMediaItems(0, 1, items(7, 8, 10, 11)) // 1 -> 4: longer
        val longer = r.snapshot()
        r.settle()
        val eventual = r.snapshot()
        println("REAL-M3 replace-prefix before=${before.index} shorter=${shorter.index} longer=${longer.index} eventual=${eventual.index}")
        assertCurrentPreserved(before, longer, 5, "longer immediate")
        assertCurrentPreserved(before, eventual, 5, "longer eventual")
        assertEquals(listOf("7", "8", "10", "11", "4", "5", "6"), eventual.ids)
        assertEquals(r.serverIds(), eventual.ids)
    }

    @Test fun replacingAfterCurrentNeverMovesTheCurrentIndexOrItem() {
        val r = rig(longArrayOf(1, 2, 3, 4, 5), current = 1)
        r.settle(300)
        val obs = Observers(r)
        val before = r.snapshot()

        r.controller.replaceMediaItems(2, 5, items(8, 9))
        val immediate = r.snapshot()
        r.settle()

        assertCurrentPreserved(before, immediate, 1, "immediate")
        assertCurrentPreserved(before, r.snapshot(), 1, "eventual")
        assertEquals(listOf("1", "2", "8", "9"), r.snapshot().ids)
        assertTrue(obs.server.events.none { it.startsWith("transition(") || it.startsWith("discontinuity(") })
        assertNoSongChange(obs.controller.events, before.mediaId, "replace-after")
    }

    @Test fun replacingRangesSurroundingButNotIncludingCurrentKeepsTheInvariant() {
        val r = rig(longArrayOf(1, 2, 3, 4, 5, 6, 7), current = 3, playing = true)
        r.settle(300)
        val obs = Observers(r)
        val before = r.snapshot()

        r.controller.replaceMediaItems(4, 7, items(20, 21)) // after
        r.controller.replaceMediaItems(0, 3, items(10, 11, 12, 13, 14)) // before, longer
        val immediate = r.snapshot()
        r.settle()

        println("REAL-M3 surround before=${before.index} immediate=${immediate.index} eventual=${r.snapshot().index} serverEvents=${obs.server.events} controllerEvents=${obs.controller.events}")
        assertCurrentPreserved(before, immediate, 5, "immediate")
        assertCurrentPreserved(before, r.snapshot(), 5, "eventual")
        assertEquals(listOf("10", "11", "12", "13", "14", "4", "20", "21"), r.snapshot().ids)
        assertEquals(r.serverIds(), r.snapshot().ids)
        assertTrue(obs.server.events.none { it.startsWith("transition(") || it.startsWith("discontinuity(") })
        assertNoSongChange(obs.controller.events, before.mediaId, "surround")
    }

    @Test fun repairStyleMutationsNeverChangeTheServersCurrentItemOrPlayState() {
        val r = rig(longArrayOf(1, 2, 3, 4, 5), current = 3, playing = true)
        r.settle(300)
        val obs = Observers(r)

        r.controller.removeMediaItems(0, 2) // prefix removal
        r.settle()
        val removal = obs.server.events.toList() to obs.controller.events.toList()
        obs.clear()
        r.controller.replaceMediaItems(0, 0, items(7, 8)) // prefix insertion
        r.settle()
        val insertion = obs.server.events.toList() to obs.controller.events.toList()
        obs.clear()
        r.controller.replaceMediaItems(r.controller.currentMediaItemIndex + 1, r.controller.mediaItemCount, items(30, 31, 32)) // suffix replace
        r.settle()
        val suffix = obs.server.events.toList() to obs.controller.events.toList()

        println("REAL-M3 callbacks removal=$removal insertion=$insertion suffix=$suffix")
        for ((label, pair) in listOf("removal" to removal, "insertion" to insertion, "suffix" to suffix)) {
            val (server, controller) = pair
            assertTrue("$label: the server reports a timeline change: $server", server.any { it.startsWith("timeline(") })
            assertTrue("$label: the server reports no transition/discontinuity/play-state change: $server", server.none { it.startsWith("transition(") || it.startsWith("discontinuity(") || it.startsWith("playWhenReady(") })
            assertNoSongChange(controller, "4", label)
            assertTrue("$label: no play state change at the controller: $controller", controller.none { it.startsWith("playWhenReady(") })
        }
        assertEquals("4", r.snapshot().mediaId)
        assertTrue(r.controller.playWhenReady)
    }

    @Test fun theStaleEchoClassifierRecognisesTheObservedEchoAndKeepsRealAdvancesAndLoops() {
        // After a repair the resolved occurrence equals what Now Playing already synced: an echo.
        assertTrue(isStaleAutomaticAdvanceEcho(resolvedIndex = 3, syncedIndex = 3, queueSize = 8, repeatMode = RepeatMode.OFF))
        assertTrue(isStaleAutomaticAdvanceEcho(3, 3, 8, RepeatMode.ALL))
        // A genuine natural advance (including the repeat-all wrap) lands on a different occurrence.
        assertTrue(!isStaleAutomaticAdvanceEcho(4, 3, 8, RepeatMode.OFF))
        assertTrue(!isStaleAutomaticAdvanceEcho(0, 7, 8, RepeatMode.ALL))
        // Loops onto the same occurrence keep the existing handling.
        assertTrue(!isStaleAutomaticAdvanceEcho(3, 3, 8, RepeatMode.ONE))
        assertTrue(!isStaleAutomaticAdvanceEcho(0, 0, 1, RepeatMode.ALL))
    }
}

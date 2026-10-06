package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The PRODUCTION [PlayerController] (not a model of it) driven through its public transport API against REAL Media3: a genuine
 * MediaController over a genuine MediaSession over a real ExoPlayer. Only the controller connection is wired by reflection (the
 * service that normally hosts the session does not exist under Robolectric) and the dirty flag is armed by reflection to put the
 * controller in the exact state the earlier slice reasons about. A recording session player proves which Media3 commands
 * actually reached the player (so "no full setMediaItems" is observed, not assumed).
 *
 * Boundary (what remains for physical QA): real MediaStore files and codecs, Bluetooth/focus, the PlaybackService lifecycle,
 * crossfade during repair, and large real libraries. Natural playback completion is exercised on the real player only through
 * the production decision method, because a trackless 3-minute source cannot be played to its end in a JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerRealMedia3DirtyQueueTest {

    private class Writer : PlayEventWriter {
        val plays = mutableListOf<Long>()
        val skips = mutableListOf<Long>()
        val listenStarts = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) { plays += songId }
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) { skips += songId }
        override suspend fun recordListenStart(songId: Long, contentUri: String) { listenStarts += songId }
    }

    private inner class Harness {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pc")
        val writer = Writer()
        var clockMs = 0L
        val tracker = StatsTracker(writer).also {
            it.scope = CoroutineScope(Dispatchers.Unconfined)
            it.clock = { clockMs }
        }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, tracker, PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        val controller: MediaController get() = rig.controller
        val calls get() = rig.recording.calls

        init {
            // The service that would host the session does not exist: mark the connection as established so the
            // init-time connection request is a no-op, then hand the real controller to the production code.
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(rig.controller))
            rig.settle(100)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
            PlayerController::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(pc, *args)

        fun markDirty() { invoke("markQueueDirty", arrayOf(QueueDirtyReason::class.java), arrayOf(QueueDirtyReason.PhysicalCurrentMismatch)) }
        val dirty: Boolean get() = field("queueDirty").getBoolean(pc)

        fun start(queue: List<Song>, index: Int, playing: Boolean) {
            pc.playFromQueue(queue, index)
            rig.settle(300)
            if (!playing) { controller.pause(); rig.settle(150) }
        }

        fun settle(ms: Long = 300) = rig.settle(ms)
        fun count(prefix: String) = rig.recording.count(prefix)
        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }
        fun currentId() = controller.currentMediaItem?.mediaId
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness() = Harness().also { harnesses += it }
    @After fun tearDown() { harnesses.forEach { it.releaseAll() }; harnesses.clear() }

    private fun song(id: Long) = RealMedia3QueueRig.song(id)
    private fun songs(vararg ids: Long) = ids.map(::song)
    private fun sequence(n: Int) = (1..n).map { song(it.toLong()) }

    /** Every Media3 command that reached the session player after [from], excluding the baseline. */
    private fun Harness.since(from: Int) = calls.drop(from)

    private fun assertNoWholeQueuePush(h: Harness, baseline: Int, label: String) {
        val after = h.since(baseline)
        assertTrue("$label: no setMediaItems/prepare after the baseline: $after", after.none { it.startsWith("setMediaItems") || it == "prepare" })
        fun size(call: String) = Regex("""(\d+)\)$""").find(call)!!.groupValues[1].toInt()
        val mutations = { calls: List<String> -> calls.filter { it.startsWith("replace(") || it.startsWith("add(") || it.startsWith("append(") } }
        // The synchronous part (before the transport seek) is only the bounded target window; every background chunk is <= one chunk.
        val beforeSeek = after.takeWhile { !it.startsWith("seekTo(") }
        if (after.any { it.startsWith("seekTo(") }) assertTrue("$label: synchronous repair is a small window op: $beforeSeek", mutations(beforeSeek).all { size(it) <= 8 })
        assertTrue("$label: every mutation is at most one chunk: $after", mutations(after).all { size(it) <= PhysicalQueueReconciler.DEFAULT_CHUNK_SIZE })
    }

    // ---------------------------------------------------------------- dirty Next after a Play Next insertion

    private fun dirtyNextAfterPlayNext(playing: Boolean) {
        val h = harness()
        h.start(sequence(20), index = 5, playing = playing)
        assertEquals("6", h.currentId())
        val baseline = h.calls.size
        h.markDirty()
        assertTrue(h.dirty)

        h.pc.playNext(song(99)) // logical-only while dirty: the physical timeline stays stale
        assertEquals("physical stale before Next", 20, h.controller.mediaItemCount)
        h.pc.skipToNext()

        println("REAL-M3 dirtyNext playing=$playing calls=${h.since(baseline)} idx=${h.controller.currentMediaItemIndex} id=${h.currentId()}")
        assertEquals("99", h.currentId()) // the inserted song, not the stale physical neighbour
        assertEquals(6, h.controller.currentMediaItemIndex)
        h.settle()
        assertEquals("99", h.currentId())
        assertEquals(6, h.pc.nowPlayingState.value.currentIndex)
        assertEquals(99L, h.pc.nowPlayingState.value.song?.id)
        assertEquals("play state preserved", playing, h.controller.playWhenReady)
        assertNoWholeQueuePush(h, baseline, "dirty Next playing=$playing")
        assertTrue("dirty seek issues exactly the target seek", h.since(baseline).count { it.startsWith("seekTo(6,") } == 1)
    }

    @Test fun dirtyNextAfterPlayNextInsertionMovesToTheInsertedSongWhilePlaying() = dirtyNextAfterPlayNext(playing = true)
    @Test fun dirtyNextAfterPlayNextInsertionMovesToTheInsertedSongWhilePaused() = dirtyNextAfterPlayNext(playing = false)

    // ---------------------------------------------------------------- duplicate occurrences

    @Test fun dirtyNextPreviousAndJumpHitThePositionalOccurrenceNotTheFirstDuplicate() {
        val h = harness()
        // A B A C A D with A = 1: the SECOND A (index 2) is current.
        h.start(songs(1, 2, 1, 3, 1, 4), index = 2, playing = true)
        assertEquals(2, h.controller.currentMediaItemIndex)
        val baseline = h.calls.size
        h.rig.player.removeMediaItem(3) // physical loses C: stale timeline  A B A A D
        h.markDirty()
        h.settle(50)

        h.pc.skipToNext()
        assertEquals("3", h.currentId()) // C
        assertEquals(3, h.controller.currentMediaItemIndex)
        assertEquals(3, h.pc.nowPlayingState.value.currentIndex)

        h.pc.skipToPrevious()
        assertEquals(2, h.controller.currentMediaItemIndex) // the SECOND A, never index 0
        assertEquals("1", h.currentId())
        assertEquals(2, h.pc.nowPlayingState.value.currentIndex)

        h.pc.jumpToQueueItem(4) // the THIRD A
        assertEquals(4, h.controller.currentMediaItemIndex)
        assertEquals("1", h.currentId())
        assertEquals(4, h.pc.nowPlayingState.value.currentIndex)
        h.settle()
        assertEquals(listOf("1", "2", "1", "3", "1", "4").subList(0, 5), h.rig.snapshot().ids.subList(0, 5))
        assertNoWholeQueuePush(h, baseline, "duplicates")
    }

    // ---------------------------------------------------------------- repeat modes

    private fun cycleRepeat(h: Harness, times: Int) = repeat(times) { h.pc.cycleRepeatMode() }

    private fun nextIndexFrom(repeatCycles: Int, dirty: Boolean, ids: LongArray, start: Int): Pair<Int, List<String>> {
        val h = harness()
        h.start(songs(*ids), index = start, playing = true)
        cycleRepeat(h, repeatCycles)
        h.settle(100)
        val baseline = h.calls.size
        if (dirty) {
            // physical stale: lose the item right before the current one (negative offset) when possible
            if (start > 0) h.rig.player.removeMediaItem(start - 1)
            h.markDirty()
        }
        h.pc.skipToNext()
        h.settle(100)
        if (dirty) assertNoWholeQueuePush(h, baseline, "repeat=$repeatCycles")
        return h.controller.currentMediaItemIndex to h.since(baseline)
    }

    @Test fun repeatOffAtTheLastOccurrenceIsANoOpDirtyOrAligned() {
        val ids = longArrayOf(1, 2, 3, 1)
        val aligned = nextIndexFrom(0, dirty = false, ids = ids, start = 3)
        val dirty = nextIndexFrom(0, dirty = true, ids = ids, start = 3)
        assertEquals(3, aligned.first) // QueueNavigator: no next at the end with Repeat OFF
        assertEquals(3, dirty.first) // logical occurrence unchanged (the background repair restored the lost earlier item)
        assertTrue("no navigation command was issued: ${dirty.second}", dirty.second.none { it.startsWith("seekTo(") })
    }

    @Test fun repeatAllWrapsFromTheLastToTheFirstLogicalOccurrenceWithoutAFullPush() {
        val h = harness()
        h.start(songs(1, 2, 3, 1), index = 3, playing = true) // first and last share song id 1
        cycleRepeat(h, 1) // OFF -> ALL
        h.settle(100)
        val baseline = h.calls.size
        h.rig.player.removeMediaItem(1) // physical loses an item before the current one: offset -1
        h.markDirty()

        h.pc.skipToNext()

        println("REAL-M3 repeatAll wrap calls=${h.since(baseline)} idx=${h.controller.currentMediaItemIndex}")
        assertEquals(0, h.controller.currentMediaItemIndex) // the FIRST logical occurrence
        assertEquals(0, h.pc.nowPlayingState.value.currentIndex)
        assertEquals("1", h.currentId())
        h.settle()
        assertEquals(listOf("1", "2", "3", "1"), h.rig.snapshot().ids.take(4))
        assertNoWholeQueuePush(h, baseline, "repeat ALL wrap")
    }

    @Test fun repeatOneKeepsTheExistingExplicitNextSemanticsDirtyOrAligned() {
        val ids = longArrayOf(1, 2, 3, 4, 5)
        val aligned = nextIndexFrom(2, dirty = false, ids = ids, start = 2) // OFF -> ALL -> ONE
        val dirty = nextIndexFrom(2, dirty = true, ids = ids, start = 2)
        // The existing QueueNavigator defines explicit Next under Repeat ONE; the dirty path must land on the same logical item.
        assertEquals(QueueNavigator.nextIndex(5, 2, RepeatMode.ONE), aligned.first)
        assertEquals(aligned.first, dirty.first)
    }

    @Test fun repairNeverAdvancesUnderRepeatOne() {
        val h = harness()
        h.start(sequence(30), index = 10, playing = true)
        cycleRepeat(h, 2) // ONE
        h.settle(100)
        h.rig.player.addMediaItems(0, listOf(song(900).toPlaybackMediaItem(), song(901).toPlaybackMediaItem())) // stale: 2 extra
        h.markDirty()
        h.settle(1500) // background repair runs to completion
        assertFalse("repair completed", h.dirty)
        assertEquals("11", h.currentId())
        assertEquals(10, h.controller.currentMediaItemIndex)
        assertEquals(10, h.pc.nowPlayingState.value.currentIndex)
        assertTrue(h.writer.plays.isEmpty() && h.writer.skips.isEmpty())
    }

    // ---------------------------------------------------------------- natural boundary decision on real indices

    private fun boundaryDecision(h: Harness): Boolean = h.invoke("handlePendingAutomaticTransition", emptyArray(), emptyArray()) as Boolean

    @Test fun aNaturalAdvanceOntoAReconcilerVerifiedIndexIsNotAFullPushAndAnUnverifiedOneIsLabelledConservative() {
        val h = harness()
        h.start(sequence(40), index = 10, playing = true)
        val baseline = h.calls.size
        h.markDirty()
        // No looper turn happens until the decisions below, so the background pass cannot run and the state stays dirty.
        h.pc.playNext(song(77))                       // logical insert at 11, physical stale
        h.invoke("reconcilePhysicalQueue", arrayOf(MediaController::class.java, Int::class.javaPrimitiveType!!, Integer::class.java, java.util.Collection::class.java),
            arrayOf(h.controller, 0, null, emptyList<Int>())) // the synchronous window repair a transport command performs first
        assertTrue(h.dirty)
        // Media3 advances natively from index 10 to 11 (what the real boundary does): the player is already there.
        h.controller.seekTo(11, 0L)
        val needsRepush = boundaryDecision(h)
        assertFalse("verified landing follows state, no re-push", needsRepush)
        assertTrue(h.dirty) // still repairing in the background
        h.settle(50)
        assertNoWholeQueuePush(h, baseline, "verified natural advance")

        // Unverified: put the physical player on an index the reconciler never verified, then take the boundary decision.
        h.invoke("markQueueDirty", arrayOf(QueueDirtyReason::class.java), arrayOf(QueueDirtyReason.PhysicalCurrentMismatch)) // clears verification, still no idle
        val callsBeforeDecision = h.calls.size
        h.controller.seekTo(30, 0L)
        val repush = boundaryDecision(h)
        h.settle(300)
        assertTrue("unverified landing takes the labelled conservative fallback", repush)
        assertTrue("fallback is the full push: ${h.since(callsBeforeDecision)}", h.since(callsBeforeDecision).any { it.startsWith("setMediaItems(") })
    }

    // ---------------------------------------------------------------- reconnect + pending Next

    @Test fun dirtyReconnectRepairsTheWindowBeforeAPendingNextRunsAndNeverUsesAStaleIndex() {
        val h = harness()
        h.start(sequence(25), index = 7, playing = true)
        val baseline = h.calls.size
        h.markDirty()
        // Controller drops; the queue changes while disconnected; the user presses Next (captured as a pending intent).
        h.field("mediaController").set(h.pc, null)
        h.pc.playNext(song(88))
        h.pc.skipToNext()
        // Reconnect with the same real controller.
        h.invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(h.controller))
        h.settle(200)

        println("REAL-M3 reconnect calls=${h.since(baseline)} id=${h.currentId()} idx=${h.controller.currentMediaItemIndex}")
        assertEquals("88", h.currentId()) // Next resolved against the repaired queue: the inserted song at logical 8
        assertEquals(8, h.controller.currentMediaItemIndex)
        assertEquals(8, h.pc.nowPlayingState.value.currentIndex)
        assertNoWholeQueuePush(h, baseline, "reconnect + pending Next")
    }

    @Test fun anUnprovableCurrentOccurrenceOnReconnectKeepsTheConservativeFullPush() {
        val h = harness()
        h.start(sequence(25), index = 7, playing = true)
        val baseline = h.calls.size
        h.markDirty()
        h.field("mediaController").set(h.pc, null)
        h.pc.playNext(song(88))
        // The physical current item is not the logical one: cannot be proven.
        h.rig.player.replaceMediaItem(7, song(555).toPlaybackMediaItem())
        h.settle(100)
        h.invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(h.controller))
        h.settle(300)

        assertTrue("conservative fallback ran: ${h.since(baseline)}", h.since(baseline).any { it.startsWith("setMediaItems(") })
        assertFalse(h.dirty)
        assertEquals(26, h.controller.mediaItemCount)
    }

    // ---------------------------------------------------------------- stats / callback safety

    @Test fun backgroundRepairNeverFabricatesPlaySkipOrASongTransition() {
        val h = harness()
        h.start(sequence(30), index = 12, playing = true)
        h.settle(300)
        h.clockMs += 120_000L // well past any play threshold: a spurious same-song reselect would credit a PLAY
        h.rig.player.addMediaItems(0, listOf(song(900).toPlaybackMediaItem(), song(901).toPlaybackMediaItem(), song(902).toPlaybackMediaItem()))
        h.rig.player.removeMediaItem(20) // and a hole after the current item
        h.markDirty()
        h.settle(2000) // the background repair removes the 3 surplus prefix items and refills the hole (controller-issued: echoes)
        h.settle(500)

        println("REAL-M3 stats-safety dirty=${h.dirty} calls=${h.calls} plays=${h.writer.plays} skips=${h.writer.skips}")
        assertFalse("repair completed", h.dirty)
        assertEquals("13", h.currentId())
        assertEquals(12, h.controller.currentMediaItemIndex)
        assertEquals(12, h.pc.nowPlayingState.value.currentIndex)
        assertEquals(13L, h.pc.nowPlayingState.value.song?.id)
        assertTrue("no PLAY fabricated: ${h.writer.plays}", h.writer.plays.isEmpty())
        assertTrue("no SKIP fabricated: ${h.writer.skips}", h.writer.skips.isEmpty())
        assertEquals((1..30).map { it.toString() }, h.rig.snapshot().ids)
        assertTrue(h.controller.playWhenReady)

    }
}

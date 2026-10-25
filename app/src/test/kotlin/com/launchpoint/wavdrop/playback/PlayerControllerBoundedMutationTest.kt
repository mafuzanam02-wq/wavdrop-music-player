package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-D at the PRODUCTION [PlayerController] level: every queue mutation in a bounded (test-only enabled, committed gate false)
 * window, against REAL Media3 with the real physical acknowledgement. After each edit the test compares the controller's complete
 * logical queue with a model, and the physical window with the positions the adapter claims, so a wrong occurrence, an unbounded
 * operation or a stale window cannot pass unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerBoundedMutationTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class H(bounded: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "pcm", physicalAck = true)
        val tracker = StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } }
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
            pc.boundedInstallEnabledForTesting = bounded
            pc.attachPhysicalWindowPort(rig.port)
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(rig.controller))
            rig.settle(100)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
            PlayerController::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(pc, *args)

        val adapter: PhysicalWindowAdapter get() = field("physicalWindowAdapter").get(pc) as PhysicalWindowAdapter
        val listener: Player.Listener get() = field("playerListener").get(pc) as Player.Listener

        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper() }
        fun start(queue: List<Song>, index: Int, playing: Boolean = true) {
            pc.playFromQueue(queue.toList(), index) // a COPY: the controller keeps the reference and the test mutates its model in place
            quiesce()
            if (!playing) { controller.pause(); rig.settle(100) }
        }

        val queue: List<Song> get() = pc.nowPlayingState.value.queue
        val logical: Int get() = pc.nowPlayingState.value.currentIndex
        fun serverIds(): List<String> = rig.serverIds()
        fun currentId(): String? = rig.player.currentMediaItem?.mediaId
        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }

        /** Playlist operations that reached the physical player after [from]. */
        fun ops(from: Int) = calls.drop(from).filter { it.startsWith("add(") || it.startsWith("append(") || it.startsWith("remove(") || it.startsWith("replace(") || it.startsWith("setMediaItems(") }
        fun listOps(from: Int) = ops(from).filter { !it.startsWith("remove(") }
        fun itemCount(call: String) = Regex("""(\d+)\)$""").find(call)!!.groupValues[1].toInt()

        /**
         * The strongest invariant: the controller's COMPLETE logical queue equals [model]; the physical window is exactly the
         * positions the adapter claims; the physical current is the logical current's song at the mapped index; the bound holds.
         */
        fun assertConsistent(model: List<Song>, label: String) {
            val got = queue.map { it.id }; val want = model.map { it.id }
            val firstDiff = got.indices.firstOrNull { it >= want.size || got[it] != want[it] } ?: -1
            assertTrue("$label: logical queue differs at $firstDiff (got size ${got.size}, want ${want.size}; got ${got.drop(maxOf(0, firstDiff - 2)).take(6)} want ${want.drop(maxOf(0, firstDiff - 2)).take(6)})", got == want)
            val a = adapter.active
            assertNotNull("$label: a verified window is active (state=${adapter.state})", a)
            a!!
            assertEquals("$label: physical ids == window over the logical queue",
                (0 until a.installedCount).map { model[a.physicalToLogical(it)!!].id.toString() }, serverIds())
            assertEquals("$label: current song", model[logical].id.toString(), currentId())
            assertEquals("$label: current physical index", a.logicalToPhysical(logical), rig.player.currentMediaItemIndex)
            assertTrue("$label: bound (${rig.player.mediaItemCount})", rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
            assertEquals("$label: the window belongs to the live logical generation", field("queueGeneration").getLong(pc), a.window.logicalGeneration)
        }
    }

    private val hs = mutableListOf<H>()
    private fun h(bounded: Boolean = true) = H(bounded).also { hs += it }
    @After fun tearDown() { hs.forEach { it.releaseAll() }; hs.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private fun s(id: Long) = RealMedia3QueueRig.song(id)

    // ── insertions ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun playNextInsertsExactlyAfterTheCurrentOccurrenceWithoutRestartingIt() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 500)
        h.rig.player.seekTo(20_000L) // position the physical player is mid-track; an edit must not disturb it
        TestMediaSource.awaitCondition { h.rig.player.currentPosition >= 20_000L }
        val before = h.calls.size
        h.pc.playNext(s(90_001L)); model.add(501, s(90_001L)); h.quiesce()
        h.assertConsistent(model, "play next")
        assertEquals(500, h.logical)
        assertEquals("90001", h.rig.player.getMediaItemAt(h.rig.player.currentMediaItemIndex + 1).mediaId)
        assertTrue("position preserved: ${h.rig.player.currentPosition}", h.rig.player.currentPosition >= 20_000L)
        assertTrue("bounded edit, never a reinstall: ${h.ops(before)}", h.ops(before).none { it.startsWith("setMediaItems(") })
        assertTrue(h.listOps(before).all { h.itemCount(it) <= 2 })
    }

    @Test fun playAllNextLargerThanTheWindowSubmitsOnlyTheRelevantSpan() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 500)
        val batch = List(5_000) { s(200_000L + it) }
        val before = h.calls.size
        h.pc.playAllNext(batch); model.addAll(501, batch); h.quiesce()
        h.assertConsistent(model, "play all next 5000")
        assertEquals(30_000, h.queue.size)
        assertEquals("the first entries after the current are the batch", (0 until 5).map { (200_000L + it).toString() },
            (1..5).map { h.rig.player.getMediaItemAt(h.rig.player.currentMediaItemIndex + it).mediaId })
        assertTrue("no list operation near the batch size: ${h.listOps(before)}", h.listOps(before).all { h.itemCount(it) <= PhysicalWindowPolicy.W_MAX })
        assertTrue(h.ops(before).none { it.startsWith("setMediaItems(") })
    }

    @Test fun addToQueueAndBatchAppendFarFromTheWindowAreLogicalOnly() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_000)
        val gen0 = h.adapter.active!!.window.logicalGeneration
        val before = h.calls.size
        h.pc.addToQueue(s(90_002L)); model.add(s(90_002L)); h.quiesce()
        h.pc.addAllToQueue(List(3_000) { s(300_000L + it) }); model.addAll(List(3_000) { s(300_000L + it) }); h.quiesce()
        h.assertConsistent(model, "far appends")
        assertEquals("no Media3 playlist operation at all", emptyList<String>(), h.ops(before))
        assertTrue("the window was rebased onto the new generation", h.adapter.active!!.window.logicalGeneration > gen0)
    }

    @Test fun appendAtTheGenuineEndExtendsTheWindowWithoutMovingTheCurrentItem() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 24_995)
        assertTrue(h.adapter.active!!.window.endsAtLogicalEnd)
        val tail = List(8) { s(400_000L + it) }
        h.pc.addAllToQueue(tail); model.addAll(tail); h.quiesce()
        h.assertConsistent(model, "append at the end")
        assertEquals(24_995, h.logical)
        assertTrue("the appended entries are physically present after the old end", h.serverIds().contains("400000"))
    }

    @Test fun appendUnderRepeatAllPlacesNewEntriesAfterTheOldEndNotBeforeTheCurrent() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 24_980)
        h.pc.cycleRepeatMode(); h.quiesce() // ALL: ring window
        assertTrue(h.adapter.active!!.window.wraps)
        val tail = List(5) { s(500_000L + it) }
        h.pc.addAllToQueue(tail); model.addAll(tail); h.quiesce()
        h.assertConsistent(model, "append under repeat all")
        val ids = h.serverIds()
        val cur = h.rig.player.currentMediaItemIndex
        assertTrue("never before the current item", ids.subList(0, cur).none { it.toLong() >= 500_000L })
        assertEquals("directly after the old last entry", "500000", ids[ids.indexOf("25000") + 1])
    }

    // ── removals ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun removalsInsideAndOutsideTheWindowKeepTheExactCurrentOccurrence() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        val before = h.calls.size
        h.pc.removeFromQueue(12_503); model.removeAt(12_503); h.quiesce()      // upcoming, inside
        h.assertConsistent(model, "remove upcoming inside")
        h.pc.removeFromQueue(12_497); model.removeAt(12_497); h.quiesce()      // earlier, inside: the logical current drops by one
        h.assertConsistent(model, "remove earlier inside")
        assertEquals(12_499, h.logical)
        val mid = h.calls.size
        h.pc.removeFromQueue(20_000); model.removeAt(20_000); h.quiesce()      // upcoming, outside
        h.pc.removeFromQueue(100); model.removeAt(100); h.quiesce()            // earlier, outside
        h.assertConsistent(model, "removals outside")
        assertEquals(12_498, h.logical)
        assertEquals("outside the window: no physical operation", emptyList<String>(), h.ops(mid))
        assertTrue(h.ops(before).none { it.startsWith("setMediaItems(") })
    }

    @Test fun removingADuplicateRemovesTheChosenOccurrenceAndNotTheOthers() {
        val h = h()
        val model = List(2_000) { s(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }.toMutableList()
        h.start(model, 1_002) // an A
        h.pc.removeFromQueue(1_000); model.removeAt(1_000); h.quiesce() // the EARLIER A two positions behind
        h.assertConsistent(model, "remove duplicate")
        assertEquals(1_001, h.logical)
        h.pc.removeFromQueue(1_004); model.removeAt(1_004); h.quiesce()
        h.assertConsistent(model, "remove later duplicate")
    }

    @Test fun clearEarlierAndClearUpNextPreserveTheCurrentItemAndItsPosition() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        h.rig.player.seekTo(15_000L)
        TestMediaSource.awaitCondition { h.rig.player.currentPosition >= 15_000L }
        val before = h.calls.size
        assertTrue(h.pc.clearEarlierQueue()); repeat(12_500) { model.removeAt(0) }; h.quiesce()
        h.assertConsistent(model, "clear earlier")
        assertEquals(0, h.logical)
        assertTrue("position preserved ${h.rig.player.currentPosition}", h.rig.player.currentPosition >= 15_000L)
        assertTrue(h.ops(before).none { it.startsWith("setMediaItems(") })
        assertTrue(h.pc.clearUpNext()); while (model.size > 1) model.removeAt(model.size - 1); h.quiesce()
        h.assertConsistent(model, "clear up next")
        assertEquals(1, h.rig.player.mediaItemCount)
        assertTrue(h.rig.player.currentPosition >= 15_000L)
    }

    // ── reordering ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun reordersInsideOutsideAndAcrossTheWindowBoundary() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        val before = h.calls.size
        h.pc.moveQueueItemDown(12_510); model.add(12_511, model.removeAt(12_510)); h.quiesce()  // inside
        h.assertConsistent(model, "move down inside")
        h.pc.moveQueueItemUp(12_520); model.add(12_519, model.removeAt(12_520)); h.quiesce()
        h.assertConsistent(model, "move up inside")
        val mid = h.calls.size
        h.pc.moveQueueItemTo(18_000, 18_300); model.add(18_300, model.removeAt(18_000)); h.quiesce() // entirely outside
        h.assertConsistent(model, "move outside")
        assertEquals("outside the window: no physical operation", emptyList<String>(), h.ops(mid))
        h.pc.moveQueueItemTo(12_505, 19_000); model.add(19_000, model.removeAt(12_505)); h.quiesce() // inside -> outside
        h.assertConsistent(model, "inside to outside")
        h.pc.moveQueueItemTo(21_000, 12_507); model.add(12_507, model.removeAt(21_000)); h.quiesce() // outside -> inside
        h.assertConsistent(model, "outside to inside")
        assertTrue(h.ops(before).none { it.startsWith("setMediaItems(") })
        assertEquals(12_500, h.logical)
    }

    @Test fun moveToPlayNextBringsAFarEntryDirectlyAfterTheCurrentOccurrence() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 12_500)
        val far = model[22_000]
        h.pc.moveToPlayNext(22_000); model.add(12_501, model.removeAt(22_000)); h.quiesce()
        h.assertConsistent(model, "move to play next")
        assertEquals(far.id.toString(), h.rig.player.getMediaItemAt(h.rig.player.currentMediaItemIndex + 1).mediaId)
    }

    // ── shuffle and repeat ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun shuffleToggleKeepsTheCurrentOccurrencePlayingAndRebuildsOnlyTheSpansAroundIt() {
        val h = h(); val original = queue(25_000)
        h.start(original, 12_500)
        h.rig.player.seekTo(25_000L)
        TestMediaSource.awaitCondition { h.rig.player.currentPosition >= 25_000L }
        val currentId = h.currentId()
        val before = h.calls.size
        h.pc.toggleShuffle(); h.quiesce()
        val shuffled = h.queue
        assertEquals("a permutation of the same 25000 songs", original.map { it.id }.toSet(), shuffled.map { it.id }.toSet())
        assertFalse("the order really changed", shuffled.map { it.id } == original.map { it.id })
        h.assertConsistent(shuffled, "shuffle on")
        assertEquals("the same song is still playing", currentId, h.currentId())
        assertTrue("not restarted: ${h.rig.player.currentPosition}", h.rig.player.currentPosition >= 25_000L)
        assertTrue("no reinstall for a shuffle: ${h.ops(before)}", h.ops(before).none { it.startsWith("setMediaItems(") })
        assertTrue(h.listOps(before).all { h.itemCount(it) <= PhysicalWindowPolicy.W_MAX })
        h.pc.toggleShuffle(); h.quiesce()
        h.assertConsistent(h.queue, "shuffle off")
        assertEquals(currentId, h.currentId())
        assertEquals("library order restored", original.map { it.id }, h.queue.map { it.id })
    }

    @Test fun repeatedShuffleTogglesWithDuplicateSongsKeepTheExactOccurrence() {
        val h = h()
        val model = List(2_000) { s(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        h.start(model, 1_002)
        repeat(4) { i ->
            h.pc.toggleShuffle(); h.quiesce()
            h.assertConsistent(h.queue, "toggle #$i")
            assertEquals(2_000, h.queue.size)
        }
    }

    @Test fun repeatTransitionsTrimAndExtendTheRingWithoutRestartingTheCurrentItem() {
        val h = h(); val model = queue(25_000)
        h.start(model, 24_995)
        val before = h.calls.size
        h.pc.cycleRepeatMode(); h.quiesce() // OFF -> ALL
        assertEquals(Player.REPEAT_MODE_OFF, h.controller.repeatMode)
        assertTrue(h.adapter.active!!.window.wraps)
        h.assertConsistent(model, "repeat ALL")
        h.pc.cycleRepeatMode(); h.quiesce() // ALL -> ONE
        assertEquals(Player.REPEAT_MODE_ONE, h.controller.repeatMode)
        assertFalse(h.adapter.active!!.window.wraps)
        h.assertConsistent(model, "repeat ONE")
        h.pc.cycleRepeatMode(); h.quiesce() // ONE -> OFF
        assertEquals(Player.REPEAT_MODE_OFF, h.controller.repeatMode)
        h.assertConsistent(model, "repeat OFF")
        assertEquals(24_995, h.logical)
        assertTrue(h.ops(before).none { it.startsWith("setMediaItems(") })
    }

    // ── deletion ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun deletingTheCurrentSongContinuesAtTheSurvivorWithOneBoundedInstallAndKeepsPlayIntent() {
        for (playing in listOf(true, false)) {
            val h = h(); val model = queue(25_000).toMutableList()
            h.start(model, 12_500, playing)
            val before = h.calls.size
            h.pc.handleSongDeleted(12_501L); model.removeAt(12_500); h.quiesce()
            h.assertConsistent(model, "delete current playing=$playing")
            assertEquals(12_500, h.logical)
            assertEquals("the successor now plays", "12502", h.currentId())
            assertEquals("play intent preserved", playing, h.controller.playWhenReady)
            assertEquals(listOf("setMediaItems(100)"), h.ops(before).filter { it.startsWith("setMediaItems(") })
        }
    }

    @Test fun deletingTheCurrentSongRemovesEveryDuplicateOccurrence() {
        val h = h()
        val model = List(2_000) { s(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }.toMutableList()
        h.start(model, 1_002) // an A
        h.pc.handleSongDeleted(1L); model.removeAll { it.id == 1L }; h.quiesce()
        h.assertConsistent(model, "delete current duplicate")
        assertTrue(h.queue.none { it.id == 1L })
        assertTrue("a survivor plays", h.currentId() in setOf("2", "3"))
    }

    @Test fun deletingANonCurrentSongRemovesAllItsOccurrencesInsideAndOutsideTheWindow() {
        val h = h()
        val model = List(2_000) { s(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }.toMutableList()
        h.start(model, 1_001) // a B
        val currentBefore = h.currentId()
        h.pc.handleSongDeleted(3L); model.removeAll { it.id == 3L }; h.quiesce()
        h.assertConsistent(model, "delete non-current")
        assertEquals(currentBefore, h.currentId())
    }

    @Test fun deletingTheOnlyDistinctSongClearsTheWindowSafely() {
        val h = h()
        val model = List(400) { s(7L) }
        h.start(model, 5)
        h.pc.handleSongDeleted(7L); h.quiesce()
        assertEquals(0, h.queue.size)
        TestMediaSource.awaitCondition { h.rig.player.mediaItemCount == 0 } // the clear is applied asynchronously by the session
        assertNull(h.adapter.active)
        assertEquals(WindowInstallState.Idle, h.adapter.state)
    }

    // ── offline, reconnect, deferred ────────────────────────────────────────────────────────────────────────────────────

    @Test fun editsAcceptedWhileDisconnectedStayLogicalAndReconnectInstallsOneBoundedWindowAroundTheExactCurrent() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 9_000)
        h.field("mediaController").set(h.pc, null)
        h.pc.playNext(s(91_000L)); model.add(9_001, s(91_000L))
        h.pc.removeFromQueue(8_990); model.removeAt(8_990)
        h.pc.addToQueue(s(91_001L)); model.add(s(91_001L))
        assertEquals("the logical queue is authoritative offline", model.map { it.id }, h.queue.map { it.id })
        assertEquals(8_999, h.logical)
        assertNull("no mapping can be trusted without a player", h.adapter.active)
        val before = h.calls.size
        h.invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(h.rig.controller))
        h.quiesce()
        h.assertConsistent(model, "after reconnect")
        assertEquals(8_999, h.logical)
        assertEquals(listOf("setMediaItems(100)"), h.ops(before).filter { it.startsWith("setMediaItems(") })
    }

    @Test fun aDeferredJumpFollowsItsOccurrenceThroughAnOfflineInsertion() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 9_000)
        h.field("mediaController").set(h.pc, null)
        h.pc.jumpToQueueItem(9_600) // deferred, bound to song 9601
        h.pc.playNext(s(91_000L)); model.add(9_001, s(91_000L))
        h.invoke("onControllerConnected", arrayOf(MediaController::class.java), arrayOf(h.rig.controller))
        h.quiesce()
        h.assertConsistent(model, "deferred jump after offline edit")
        assertEquals(9_601, h.logical)
        assertEquals("9601", h.currentId())
    }

    // ── fail-closed and Full-mode regression ────────────────────────────────────────────────────────────────────────────

    @Test fun anExternalPhysicalEditNeverChangesTheLogicalQueueAndTheNextEditHealsTheWindow() {
        val h = h(); val model = queue(25_000).toMutableList()
        h.start(model, 5_000)
        // An external index-bearing command removes physical item 3 (a physical index, not a logical one).
        h.rig.player.removeMediaItem(3)
        // The physical window no longer matches what the adapter verified: the occurrence is unprovable, so ONE bounded re-anchor
        // around the last authoritative occurrence runs (fail closed). Wait for it to finish.
        TestMediaSource.awaitCondition { h.calls.count { it.startsWith("setMediaItems(") } >= 2 }
        h.quiesce()
        assertEquals("the logical queue is untouched", model.map { it.id }, h.queue.map { it.id })
        h.assertConsistent(model, "healed")
        h.pc.playNext(s(92_000L)); model.add(5_001, s(92_000L)); h.quiesce()
        h.assertConsistent(model, "after external physical edit")
        assertEquals(5_000, h.logical)
    }

    @Test fun noBoundedMutationPathUsesTheFullQueuePush() {
        val source = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()
        val start = source.indexOf("private fun applyBoundedQueueMutation(")
        val end = source.indexOf("private fun MediaController.setMeasuredMediaItems(")
        assertTrue(start in 0 until end)
        val section = source.substring(start, end)
        for (forbidden in listOf("syncPlayerQueueAt(", "setMeasuredMediaItems(", ".setMediaItems(", "reconcilePhysicalQueue(", "kickQueueRepair(", "applyPhysicalEdit(")) {
            assertFalse("bounded mutation section must not call $forbidden", section.contains(forbidden))
        }
        // Every mutation site branches to the bounded path before any Full-mode physical edit.
        // One definition; every mutation site (insert, append, remove, clear, delete x2, move x2, swap, shuffle) calls it.
        assertTrue(Regex("""applyBoundedQueueMutation\(""").findAll(source).count() - 1 >= 12)
        assertFalse(source.contains("rejectBoundedQueueMutation"))
    }

    @Test fun fullModeEditsAreUnchangedWithTheCommittedGate() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
        val h = h(bounded = false); val model = queue(1_000).toMutableList()
        h.start(model, 400)
        assertEquals(QueueMode.Full, h.field("installedQueueMode").get(h.pc))
        h.pc.playNext(s(93_000L)); model.add(401, s(93_000L)); h.rig.settle(150)
        assertEquals(1_001, h.rig.player.mediaItemCount)
        assertEquals("93000", h.rig.player.getMediaItemAt(401).mediaId)
        h.pc.removeFromQueue(410); model.removeAt(410); h.rig.settle(150)
        assertEquals(1_000, h.rig.player.mediaItemCount)
        assertEquals(model.map { it.id.toString() }, h.serverIds())
        assertEquals(400, h.logical)
    }

    // ── evidence ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun largeQueueMutationEvidence() {
        println("P5-D evidence: logical | physical | playNext ops | playAllNext(2000) ops | logical-only ops | remove ops | shuffle ops | delete-current ops | ms")
        for (n in intArrayOf(1_000, 5_000, 10_000, 25_000, 50_000)) {
            val h = h(); val model = queue(n).toMutableList()
            val t0 = System.nanoTime()
            h.start(model, n / 2)
            var mark = h.calls.size
            h.pc.playNext(s(900_000L)); model.add(n / 2 + 1, s(900_000L)); h.quiesce()
            val playNext = h.ops(mark); mark = h.calls.size
            val batch = List(2_000) { s(1_000_000L + it) }
            h.pc.playAllNext(batch); model.addAll(n / 2 + 1, batch); h.quiesce()
            val playAll = h.ops(mark); mark = h.calls.size
            h.pc.addToQueue(s(901_000L)); model.add(s(901_000L)); h.quiesce()
            val logicalOnly = h.ops(mark); mark = h.calls.size
            h.pc.removeFromQueue(n / 2 + 40); model.removeAt(n / 2 + 40); h.quiesce()
            val remove = h.ops(mark); mark = h.calls.size
            h.assertConsistent(model, "size $n")
            h.pc.toggleShuffle(); h.quiesce()
            val shuffle = h.ops(mark); mark = h.calls.size
            h.assertConsistent(h.queue, "size $n shuffled")
            val current = h.currentId()!!.toLong()
            h.pc.handleSongDeleted(current); h.quiesce()
            val delete = h.ops(mark)
            h.assertConsistent(h.queue, "size $n after delete")
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            println("P5-D evidence: ${h.queue.size} | ${h.rig.player.mediaItemCount} | ${playNext.size} | ${playAll.size} | ${logicalOnly.size} | ${remove.size} | ${shuffle.size} | ${delete.size} | %.0f".format(ms))
            assertEquals(emptyList<String>(), logicalOnly)
            assertTrue((playNext + playAll + remove + shuffle + delete).all { call ->
                !(call.startsWith("setMediaItems(") || call.startsWith("append(") || call.startsWith("add(")) || h.itemCount(call) <= PhysicalWindowPolicy.W_MAX
            })
        }
    }
}

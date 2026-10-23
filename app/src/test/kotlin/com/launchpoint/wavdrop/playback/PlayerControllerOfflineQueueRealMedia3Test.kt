package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.SettableFuture
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
 * Offline queue consistency: the PRODUCTION [PlayerController] edits its queue while no controller exists (or the controller
 * cannot change items), against REAL Media3. Every accepted structural edit must either reach the player or leave a dirty repair
 * obligation that the reconnect converges, with exact ids, the exact current occurrence and the play/pause state intact.
 * Assertions read the server-side player (the real ExoPlayer behind the session), never source text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerOfflineQueueRealMedia3Test {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class Harness {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "oq")
        val tracker = StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined) }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, tracker, PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        val attempts = mutableListOf<SettableFuture<MediaController>>()
        private val controllers = mutableListOf<MediaController>()
        val calls get() = rig.recording.calls

        init {
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            rig.settle(50)
            pc.controllerFutureFactory = { SettableFuture.create<MediaController>().also { attempts += it } }
            field("controllerConnectionState").set(pc, ControllerConnectionState.Disconnected)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun newController(): MediaController {
            val future = MediaController.Builder(context, rig.session.token).setConnectionHints(Bundle()).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 200) rig.settle(20)
            return future.get().also { controllers += it }
        }
        fun complete(attempt: Int? = null, settleMs: Long = 600): MediaController {
            rig.settle(50)
            val controller = newController()
            attempts[attempt ?: attempts.lastIndex].set(controller)
            rig.settle(settleMs)
            return controller
        }
        fun disconnect(controller: MediaController) {
            (field("controllerLifecycleListener").get(pc) as MediaController.Listener).onDisconnected(controller)
            rig.settle(50)
        }

        /** Installs [queue] at [index] through a real connection, then pauses when [playing] is false. Returns the connection. */
        fun startConnected(queue: List<Song>, index: Int, playing: Boolean): MediaController {
            pc.playFromQueue(queue, startIndex = index)
            val c = complete()
            if (!playing) { pc.togglePlayPause(); rig.settle(300) }
            return c
        }

        val dirty: Boolean get() = field("queueDirty").getBoolean(pc)
        val queueGeneration: Long get() = field("queueGeneration").getLong(pc)
        val serverIds get() = rig.serverIds()
        val serverIndex get() = rig.player.currentMediaItemIndex
        val serverCurrentId get() = rig.player.currentMediaItem?.mediaId
        val serverPlayWhenReady get() = rig.player.playWhenReady
        val logical get() = pc.nowPlayingState.value
        val logicalIds get() = logical.queue.map { it.id.toString() }
        fun count(prefix: String) = rig.recording.count(prefix)
        fun settle(ms: Long = 300) = rig.settle(ms)
        fun releaseAll() { runCatching { pc.release() }; controllers.forEach { runCatching { it.release() } }; rig.releaseAll() }
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness() = Harness().also { harnesses += it }
    @After fun tearDown() { harnesses.forEach { it.releaseAll() }; harnesses.clear() }

    private fun song(id: Long) = RealMedia3QueueRig.song(id)
    private fun range(from: Long, to: Long) = (from..to).map(::song)

    // ------------------------------------------------------------------------------------------------ the offline matrix

    /**
     * Connected, installed and playing/paused at index 2 of [1..8]; disconnect; run [edit]; the edit must leave a repair obligation and
     * demand the controller; reconnect; the player must converge to the logical queue at the logical current entry with the same
     * play/pause state, without a whole-queue push.
     */
    private fun offlineEdit(
        playing: Boolean,
        queue: List<Song> = range(1, 8),
        startIndex: Int = 2,
        expectFullPush: Boolean = false,
        edit: (Harness) -> Unit,
    ) {
        val h = harness()
        val c = h.startConnected(queue, startIndex, playing)
        assertEquals(playing, h.serverPlayWhenReady)
        h.disconnect(c)
        val attemptsBefore = h.attempts.size
        val baseline = h.calls.size

        edit(h)
        h.settle(50)

        assertTrue("an edit with no controller leaves a repair obligation", h.dirty)
        assertEquals("the edit demanded the controller", attemptsBefore + 1, h.attempts.size)
        assertEquals("nothing reached the player while disconnected", baseline, h.calls.size)
        val expectedIds = h.logicalIds
        val expectedCurrent = h.logical.song?.id?.toString()
        val expectedIndex = h.logical.currentIndex

        h.complete()

        assertEquals("physical order converges to the logical order", expectedIds, h.serverIds)
        assertEquals("exact current occurrence", expectedIndex, h.serverIndex)
        assertEquals(expectedCurrent, h.serverCurrentId)
        assertEquals("play/pause intent is preserved (no autoplay, no pause)", playing, h.serverPlayWhenReady)
        assertFalse("repaired, not falsely clean before and not stuck dirty after", h.dirty)
        if (!expectFullPush) {
            assertEquals("no whole-queue push as the routine response: ${h.calls.drop(baseline)}", 1, h.count("setMediaItems"))
        }
    }

    private fun both(edit: (Harness) -> Unit) { offlineEdit(playing = true, edit = edit); offlineEdit(playing = false, edit = edit) }

    @Test fun appendOneSong() = both { it.pc.addToQueue(song(50)) }
    @Test fun appendABatch() = both { it.pc.addAllToQueue(listOf(song(50), song(51), song(52))) }
    @Test fun playNextOneSong() = both { it.pc.playNext(song(99)) }
    @Test fun playNextABatch() = both { it.pc.playAllNext(listOf(song(98), song(99))) }
    @Test fun removeAnUpcomingSong() = both { it.pc.removeFromQueue(5) }
    @Test fun removeAnEarlierSong() = both { it.pc.removeFromQueue(0) }
    @Test fun clearEarlierSongs() = both { assertTrue(it.pc.clearEarlierQueue()) }
    @Test fun clearUpcomingSongs() = both { assertTrue(it.pc.clearUpNext()) }
    @Test fun moveAnUpcomingSongUp() = both { it.pc.moveQueueItemUp(5) }
    @Test fun moveAnUpcomingSongDown() = both { it.pc.moveQueueItemDown(4) }
    @Test fun moveAnUpcomingSongToAChosenPosition() = both { it.pc.moveQueueItemTo(6, 3) }
    @Test fun moveAFutureEntryToPlayNext() = both { it.pc.moveToPlayNext(6) }
    @Test fun deleteANonCurrentSong() = both { it.pc.handleSongDeleted(7L) }
    @Test fun shuffleChange() = both { it.pc.toggleShuffle() }

    @Test fun deleteTheCurrentSongWithSurvivorsKeepsTheSurvivingQueueAndStartsTheSurvivorAtItsOccurrence() {
        for (playing in listOf(true, false)) {
            val h = harness()
            val c = h.startConnected(range(1, 6), 2, playing) // song 3
            h.disconnect(c)
            h.pc.handleSongDeleted(3L)
            h.settle(50)
            assertEquals("survivors are NOT cleared", listOf("1", "2", "4", "5", "6"), h.logicalIds)
            assertEquals(2, h.logical.currentIndex)
            h.complete()

            assertEquals(listOf("1", "2", "4", "5", "6"), h.serverIds)
            assertEquals(2, h.serverIndex)
            assertEquals("4", h.serverCurrentId)
            assertEquals("playing=$playing intent survives the deletion", playing, h.serverPlayWhenReady)
            assertTrue(h.rig.player.currentPosition < 5_000L)
        }
    }

    @Test fun deletingTheOnlySongClearsTheQueueAndCancelsAnythingDeferred() {
        val h = harness()
        h.pc.playFromQueue(range(1, 1), startIndex = 0)
        h.settle(50)
        h.pc.handleSongDeleted(1L)
        h.complete()
        assertEquals(emptyList<String>(), h.logicalIds)
        assertFalse(h.calls.contains("play"))
    }

    // ------------------------------------------------------------------------------------------------ duplicates and shuffle

    @Test fun duplicateBearingQueueWithTheFirstCopyCurrentRemovesOnlyTheOtherCopyOffline() {
        val h = harness()
        val a = song(1)
        val c = h.startConnected(listOf(a, song(2), a, song(3)), 0, true)
        h.disconnect(c)
        h.pc.removeFromQueue(2) // the SECOND A
        h.complete()
        assertEquals(listOf("1", "2", "3"), h.serverIds)
        assertEquals(0, h.serverIndex)
    }

    @Test fun duplicateBearingQueueWithTheSecondCopyCurrentRefusesToRemoveItAndRemovesTheFirst() {
        val h = harness()
        val a = song(1)
        val c = h.startConnected(listOf(a, song(2), a, song(3)), 2, true)
        h.disconnect(c)
        h.pc.removeFromQueue(2) // the CURRENT occurrence: refused
        assertEquals(listOf("1", "2", "1", "3"), h.logicalIds)
        h.pc.removeFromQueue(0) // the first copy: allowed
        h.complete()
        assertEquals(listOf("2", "1", "3"), h.serverIds)
        assertEquals("the selected copy shifted from 2 to 1", 1, h.serverIndex)
        assertEquals("1", h.serverCurrentId)
    }

    @Test fun aShuffledDuplicateBearingQueueConvergesAfterAnOfflineEdit() {
        val h = harness()
        val a = song(1)
        val queue = listOf(a, song(2), a, song(3), a, song(4), a, song(5), a, song(6))
        h.pc.playFromQueueShuffled(queue)
        val c = h.complete()
        h.disconnect(c)
        h.pc.playNext(song(99))
        h.pc.addToQueue(song(50))
        val expected = h.logicalIds
        val expectedIndex = h.logical.currentIndex
        h.complete()
        assertEquals(expected, h.serverIds)
        assertEquals(expectedIndex, h.serverIndex)
        assertEquals(10 + 2, h.serverIds.size)
        assertTrue(h.logical.shuffleEnabled)
    }

    // ------------------------------------------------------------------------------------------------ current occurrence authority

    private fun forceStaleStateIndex(h: Harness, staleIndex: Int) {
        @Suppress("UNCHECKED_CAST")
        val flow = h.field("_nowPlayingState").get(h.pc) as kotlinx.coroutines.flow.MutableStateFlow<NowPlayingState>
        flow.value = flow.value.copy(currentIndex = staleIndex, song = h.logical.queue[staleIndex])
    }

    @Test fun aPhysicalTransitionAheadOfNowPlayingStateProtectsTheActuallyPlayingEntry() {
        val h = harness()
        h.startConnected(range(1, 6), 2, true) // playing song 3 at index 2
        forceStaleStateIndex(h, staleIndex = 1) // Now Playing lags: believes index 1

        h.pc.removeFromQueue(2) // the REAL current entry
        assertEquals("the playing entry is never removed", listOf("1", "2", "3", "4", "5", "6"), h.serverIds)
        assertEquals(6, h.logical.queue.size)

        h.pc.removeFromQueue(1) // a noncurrent entry that the stale state would have treated as current
        h.settle(300)
        assertEquals(listOf("1", "3", "4", "5", "6"), h.serverIds)
        assertEquals("song 3 keeps playing, now at index 1", "3", h.serverCurrentId)
        assertEquals(1, h.logical.currentIndex)
    }

    @Test fun aStaleStateIndexDoesNotLetReorderingMoveTheActualCurrentEntry() {
        val h = harness()
        h.startConnected(range(1, 6), 2, true)
        forceStaleStateIndex(h, staleIndex = 1)
        val baseline = h.calls.size

        h.pc.moveQueueItemUp(2) // the current entry: refused
        h.pc.moveQueueItemDown(2)
        h.pc.moveQueueItemTo(2, 4)
        h.pc.moveToPlayNext(2)
        h.settle(200)

        assertEquals(listOf("1", "2", "3", "4", "5", "6"), h.serverIds)
        assertEquals(2, h.serverIndex)
        assertEquals("3", h.serverCurrentId)
        assertTrue("no physical move reached the player: ${h.calls.drop(baseline)}", h.calls.drop(baseline).none { it.startsWith("move") })

        h.pc.moveQueueItemDown(3) // noncurrent: allowed relative to the REAL current
        h.settle(300)
        assertEquals(listOf("1", "2", "3", "5", "4", "6"), h.serverIds)
        assertEquals("3", h.serverCurrentId)
        assertEquals(2, h.serverIndex)
    }

    @Test fun anUnprovableCurrentOccurrenceRejectsRemovalAndReorderingEntirely() {
        val h = harness()
        val c = h.startConnected(range(1, 5), 2, true)
        h.disconnect(c)
        @Suppress("UNCHECKED_CAST")
        val flow = h.field("_nowPlayingState").get(h.pc) as kotlinx.coroutines.flow.MutableStateFlow<NowPlayingState>
        flow.value = flow.value.copy(currentIndex = 99, song = null)
        val generation = h.queueGeneration

        h.pc.removeFromQueue(1)
        h.pc.moveQueueItemUp(3)
        h.pc.moveQueueItemDown(1)
        h.pc.moveQueueItemTo(3, 4)
        h.pc.moveToPlayNext(4)

        assertEquals("nothing mutated", generation, h.queueGeneration)
        assertEquals(listOf("1", "2", "3", "4", "5"), h.pc.nowPlayingState.value.queue.map { it.id.toString() })
    }

    // ------------------------------------------------------------------------------------------------ repair and lifecycle

    @Test fun aWarmReconnectOfAnAlignedQueueIssuesNoEditAtAll() {
        val h = harness()
        val c = h.startConnected(range(1, 6), 2, true)
        h.disconnect(c)
        h.pc.togglePlayPause() // demand only; the queue never changed
        h.settle(50)
        val baseline = h.calls.size
        h.complete()

        val after = h.calls.drop(baseline)
        assertTrue("no queue command on an aligned reconnect: $after", after.none {
            it.startsWith("setMediaItems") || it.startsWith("replace") || it.startsWith("add") || it.startsWith("append") || it.startsWith("remove") || it.startsWith("move")
        })
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), h.serverIds)
    }

    @Test fun aFailedAttemptKeepsTheObligationAndTheRetryConverges() {
        val h = harness()
        val c = h.startConnected(range(1, 6), 2, true)
        h.disconnect(c)
        h.pc.playNext(song(99))
        h.settle(50)
        h.attempts.last().setException(IllegalStateException("simulated"))
        h.settle(100)
        assertTrue("a failed attempt never clears the obligation", h.dirty)

        h.pc.addToQueue(song(50)) // a later demand retries
        h.settle(50)
        val expected = h.logicalIds
        h.complete()
        assertEquals(expected, h.serverIds)
        assertEquals("3", h.serverCurrentId)
        assertFalse(h.dirty)
    }

    @Test fun anEditThroughAControllerThatCannotChangeItemsIsNeverTreatedAsApplied() {
        val h = harness()
        val c = h.startConnected(range(1, 6), 2, true)
        c.release() // the app's controller is dead but still referenced: its commands are unavailable
        assertFalse(h.dirty)

        h.pc.addToQueue(song(50))

        assertTrue("an unavailable command leaves the obligation, not a false success", h.dirty)
        assertEquals("nothing was applied to the player", listOf("1", "2", "3", "4", "5", "6"), h.serverIds)
    }

    @Test fun aNewerEditArrivingWhileRepairIsInProgressStillConverges() {
        val h = harness()
        val big = range(1, 700)
        val c = h.startConnected(big, 10, true)
        h.disconnect(c)
        h.pc.playAllNext((1000L..1300L).map(::song)) // larger than one repair chunk
        h.settle(20)
        h.complete(settleMs = 0)
        h.pc.addToQueue(song(5000)) // newer generation while the chunked repair runs
        h.pc.removeFromQueue(400)
        h.settle(2_000)

        assertEquals(h.logicalIds, h.serverIds)
        assertEquals("11", h.serverCurrentId)
        assertTrue(h.serverPlayWhenReady)
        assertFalse(h.dirty)
    }

    @Test fun everyExplicitMutationCancelsCrossfadeOwnershipBeforeTheEditEvenOffline() {
        val h = harness()
        val c = h.startConnected(range(1, 8), 2, true)
        h.disconnect(c)
        var removal = 0; var reorder = 0; var playNext = 0; var add = 0; var deletion = 0
        h.pc.setExplicitQueueRemovalListener { removal++ }
        h.pc.setExplicitQueueReorderListener { reorder++ }
        h.pc.setExplicitPlayNextMutationListener { playNext++ }
        h.pc.setExplicitAddToQueueMutationListener { add++ }
        h.pc.setExplicitLibraryDeletionListener { deletion++ }

        h.pc.removeFromQueue(5)
        h.pc.moveQueueItemUp(5)
        h.pc.playNext(song(99))
        h.pc.addToQueue(song(50))
        h.pc.handleSongDeleted(8L)
        h.complete()

        assertEquals(1, removal); assertEquals(1, reorder); assertEquals(1, playNext); assertEquals(1, add); assertEquals(1, deletion)
        assertEquals(h.logicalIds, h.serverIds)
    }

    @Test fun repeatedEditsWhileDisconnectedShareOneConnectionAttempt() {
        val h = harness()
        val c = h.startConnected(range(1, 8), 2, true)
        h.disconnect(c)
        val before = h.attempts.size
        h.pc.addToQueue(song(50)); h.pc.playNext(song(99)); h.pc.removeFromQueue(6); h.pc.moveQueueItemDown(4)
        h.settle(100)
        assertEquals("demand is deduplicated by the connection state", before + 1, h.attempts.size)
        h.complete()
        assertEquals(h.logicalIds, h.serverIds)
        assertEquals(1, h.count("setMediaItems"))
    }
}

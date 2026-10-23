package com.launchpoint.wavdrop.playback

import android.content.Context
import android.net.Uri
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The PRODUCTION [PlayerController] with NO usable controller, driven through its public API, against REAL Media3 (a genuine
 * MediaController over a genuine MediaSession over a real ExoPlayer). Only the controller *connection attempt* is replaced, by a
 * [SettableFuture] per attempt (the service that normally hosts the session does not exist under Robolectric), so a test decides
 * exactly when an attempt completes, fails, or completes late. A recording session player proves which Media3 commands actually
 * reached the player and in which order; assertions are on those commands, the installed queue identities, the playback state and
 * the current occurrence, never on source text.
 *
 * Boundary (what remains for physical QA): the real PlaybackService/process lifecycle, audio focus, Bluetooth, real codecs and
 * very large libraries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerDeferredStartRealMedia3Test {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class Harness {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "ds")
        val tracker = StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined) }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, tracker, PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )

        /** One entry per connection attempt PlayerController started, in order. */
        val attempts = mutableListOf<SettableFuture<MediaController>>()
        private val controllers = mutableListOf<MediaController>()
        val calls get() = rig.recording.calls

        init {
            // The constructor posted one connection request. Neutralise it (state Connected => UseExisting), let it run, then
            // install the deterministic attempt factory and return to a genuine "no usable controller" state.
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

        /** Completes [attempt] (default: the newest) with a fresh controller. The demand is posted to the main looper, so run it first. */
        fun complete(attempt: Int? = null, settleMs: Long = 300): MediaController {
            rig.settle(50)
            val controller = newController()
            attempts[attempt ?: attempts.lastIndex].set(controller)
            rig.settle(settleMs)
            return controller
        }

        fun fail(attempt: Int? = null) {
            rig.settle(50)
            attempts[attempt ?: attempts.lastIndex].setException(IllegalStateException("simulated connection failure"))
            rig.settle(100)
        }

        fun disconnect(controller: MediaController) {
            (field("controllerLifecycleListener").get(pc) as MediaController.Listener).onDisconnected(controller)
            rig.settle(50)
        }

        val connectedController: Any? get() = field("mediaController").get(pc)
        val queueGeneration: Long get() = field("queueGeneration").getLong(pc)
        val connectionState: Any? get() = field("controllerConnectionState").get(pc)
        fun settle(ms: Long = 300) = rig.settle(ms)
        fun count(prefix: String) = rig.recording.count(prefix)
        val serverIds get() = rig.serverIds()
        val serverIndex get() = rig.player.currentMediaItemIndex
        val serverPlayWhenReady get() = rig.player.playWhenReady
        val logical get() = pc.nowPlayingState.value
        fun releaseAll() {
            runCatching { pc.release() }
            controllers.forEach { runCatching { it.release() } }
            rig.releaseAll()
        }
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness() = Harness().also { harnesses += it }

    @After fun tearDown() {
        harnesses.forEach { it.releaseAll() }
        harnesses.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private fun song(id: Long) = RealMedia3QueueRig.song(id)
    private fun songs(vararg ids: Long) = ids.map(::song)
    private fun range(from: Long, to: Long) = (from..to).map(::song)
    private fun ids(list: List<Song>) = list.map { it.id.toString() }

    // ------------------------------------------------------------------------------------------------ acquisition

    @Test fun anAcceptedFreshStartWithNoControllerRequestsOneConnectionAndInstallsOnCompletion() {
        val h = harness()
        val queue = range(1, 5)
        h.pc.playFromQueue(queue, startIndex = 2)
        h.settle(50)

        assertEquals("the accepted start demands the controller", 1, h.attempts.size)
        assertEquals("nothing is installed before a controller exists", 0, h.calls.size)
        assertEquals(3L, h.logical.song?.id)
        assertEquals(2, h.logical.currentIndex)

        h.complete()

        assertEquals(ids(queue), h.serverIds)
        assertEquals(2, h.serverIndex)
        assertEquals("3", h.rig.player.currentMediaItem?.mediaId)
        assertTrue("a fresh start plays", h.serverPlayWhenReady)
        assertEquals(1, h.count("setMediaItems"))
        assertEquals(1, h.count("prepare"))
        assertEquals(1, h.count("play"))
        assertEquals(1, h.attempts.size)
    }

    @Test fun aFailedAttemptKeepsTheSelectionAndTheNextDemandRetriesAndInstallsIt() {
        val h = harness()
        val queue = range(1, 5)
        h.pc.playFromQueue(queue, startIndex = 1)
        h.settle(50)
        h.fail()

        assertEquals(ControllerConnectionState.Disconnected, h.connectionState)
        assertEquals("a failed attempt installs nothing", 0, h.calls.size)
        assertEquals("the valid selection is still the logical truth", 2L, h.logical.song?.id)

        h.pc.jumpToQueueItem(3) // a later demand re-requests the controller and retargets the still-pending start
        h.settle(50)
        assertEquals("exactly one retry attempt", 2, h.attempts.size)
        h.complete()

        assertEquals(ids(queue), h.serverIds)
        assertEquals(3, h.serverIndex)
        assertEquals(1, h.count("setMediaItems"))
        assertTrue(h.serverPlayWhenReady)
    }

    @Test fun anAttemptThatCannotEvenStartIsRetryableAndKeepsThePendingStart() {
        val h = harness()
        var throwing = true
        h.pc.controllerFutureFactory = { if (throwing) throw IllegalStateException("no session token") else SettableFuture.create<MediaController>().also { h.attempts += it } }
        h.pc.playFromQueue(range(1, 3), startIndex = 0)
        h.settle(50)
        assertEquals(ControllerConnectionState.Disconnected, h.connectionState)

        throwing = false
        h.pc.togglePlayPause() // a later demand
        h.settle(50)
        assertEquals(1, h.attempts.size)
        h.complete()

        assertEquals(listOf("1", "2", "3"), h.serverIds)
        assertEquals(1, h.count("setMediaItems"))
    }

    @Test fun repeatedTapsWhileConnectingShareOneAttemptAndOnlyTheLastSelectionInstalls() {
        val h = harness()
        val queue = range(1, 8)
        for (index in listOf(0, 3, 5, 2, 6)) {
            h.pc.playFromQueue(queue, startIndex = index)
            h.settle(20)
        }
        assertEquals("never a second buildAsync while one is in flight", 1, h.attempts.size)
        assertEquals(0, h.calls.size)

        h.complete()

        assertEquals(1, h.count("setMediaItems"))
        assertEquals(6, h.serverIndex)
        assertEquals("7", h.rig.player.currentMediaItem?.mediaId)
        assertEquals(1, h.attempts.size)
    }

    @Test fun rejectedInvalidAndAmbiguousStartsDoNotDemandAConnection() {
        val h = harness()
        h.pc.playFromQueue(emptyList(), startIndex = 0)
        h.pc.playFromQueue(range(1, 3), startIndex = 9)
        h.pc.playFromQueue(range(1, 3), startIndex = -1)
        val a = song(1)
        h.pc.playFromQueue(listOf(a, song(2), a), startSong = a) // ambiguous start song: never guessed
        h.settle(100)

        assertEquals("a rejected start must not acquire a controller", 0, h.attempts.size)
        assertNull(h.logical.song)
    }

    // ------------------------------------------------------------------------------------------------ latest wins

    private enum class Kind { LIBRARY, SEARCH, EXTERNAL }

    /** Accepts one start of [kind] and returns the unique id it introduced (library start song / tapped song / external song). */
    private fun Harness.accept(kind: Kind, tag: Long): Long = when (kind) {
        Kind.LIBRARY -> {
            pc.playFromQueue(range(tag * 100 + 1, tag * 100 + 4), startIndex = 2)
            tag * 100 + 3
        }
        Kind.SEARCH -> {
            pc.playSearchResultPreservingQueue(song(tag * 100 + 50))
            tag * 100 + 50
        }
        Kind.EXTERNAL -> {
            pc.playExternalUri(Uri.parse("content://external/audio/$tag"), "external-$tag")
            logical.song!!.id
        }
    }

    @Test fun everyOrderedPairOfStartTypesInstallsOnlyTheNewestSelectionExactlyOnce() {
        for (first in Kind.values()) for (second in Kind.values()) {
            val h = harness()
            val firstId = h.accept(first, tag = 1)
            h.settle(20)
            val secondId = h.accept(second, tag = 2)
            h.settle(20)
            val expectedQueue = h.logical.queue.map { it.id.toString() }
            val expectedIndex = h.logical.currentIndex
            assertEquals("$first then $second: one attempt", 1, h.attempts.size)
            assertEquals("$first then $second: nothing before connect", 0, h.calls.size)

            h.complete()

            val label = "$first then $second"
            assertEquals("$label: exactly one install (the superseded one never executes)", 1, h.count("setMediaItems"))
            assertEquals("$label: one prepare", 1, h.count("prepare"))
            assertEquals("$label: one play", 1, h.count("play"))
            assertEquals("$label: installed queue is the newest selection's", expectedQueue, h.serverIds)
            assertEquals("$label: occurrence", expectedIndex, h.serverIndex)
            assertEquals("$label: current item is the newest selection", secondId.toString(), h.rig.player.currentMediaItem?.mediaId)
            if (first == Kind.EXTERNAL && second == Kind.EXTERNAL) {
                assertTrue("$label: the newest external uri is the installed one", h.rig.player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/2"))
            } else if (second != Kind.SEARCH) {
                assertFalse("$label: the superseded selection's own item is absent ($firstId)", h.serverIds.contains(firstId.toString()))
            }
            assertTrue("$label: playing", h.serverPlayWhenReady)
            h.releaseAll()
            harnesses.remove(h)
        }
    }

    @Test fun threeDifferentStartTypesInstallOnlyTheThird() {
        val h = harness()
        h.accept(Kind.LIBRARY, 1)
        h.accept(Kind.EXTERNAL, 2)
        val third = h.accept(Kind.SEARCH, 3)
        h.complete()

        assertEquals(1, h.count("setMediaItems"))
        assertEquals(third.toString(), h.rig.player.currentMediaItem?.mediaId)
        assertFalse(h.serverIds.contains("103")) // the first start's song
        assertEquals(1, h.attempts.size)
    }

    @Test fun aShuffledStartInstallsTheOrderDrawnAtAcceptanceWithoutReshuffling() {
        val h = harness()
        val queue = range(1, 30)
        h.pc.playFromQueueShuffled(queue)
        h.settle(50)
        val drawn = h.logical.queue.map { it.id.toString() }
        val drawnIndex = h.logical.currentIndex
        val generationAtAccept = h.queueGeneration
        assertTrue(h.logical.shuffleEnabled)

        h.complete()

        assertEquals("the physical order is the order the user was shown", drawn, h.serverIds)
        assertEquals(drawnIndex, h.serverIndex)
        assertEquals("the drain does not bump the queue generation", generationAtAccept, h.queueGeneration)
        assertEquals(drawn, h.logical.queue.map { it.id.toString() })
    }

    // ------------------------------------------------------------------------------------------------ transport after a start

    @Test fun startThenPauseInstallsTheQueuePaused() {
        val h = harness()
        h.pc.playFromQueue(range(1, 4), startIndex = 1)
        h.pc.togglePlayPause() // pending start implies play, so the toggle is a Pause
        h.complete()

        assertEquals(listOf("1", "2", "3", "4"), h.serverIds)
        assertEquals(1, h.serverIndex)
        assertFalse("the later Pause wins", h.serverPlayWhenReady)
        assertEquals("the start never plays then pauses; it installs paused", 0, h.count("play"))
        assertEquals(1, h.count("setMediaItems"))
    }

    @Test fun startThenPauseThenPlayEndsPlaying() {
        val h = harness()
        h.pc.playFromQueue(range(1, 4), startIndex = 1)
        h.pc.togglePlayPause()
        h.pc.togglePlayPause()
        h.complete()

        assertTrue(h.serverPlayWhenReady)
        assertEquals(1, h.serverIndex)
        assertEquals(1, h.count("setMediaItems"))
    }

    @Test fun aLaterPauseNeverLeavesAnAccidentalPlay() {
        val h = harness()
        h.pc.playFromQueue(range(1, 4), startIndex = 0)
        h.pc.togglePlayPause() // pause
        h.pc.togglePlayPause() // play
        h.pc.togglePlayPause() // pause
        h.complete()

        assertFalse(h.serverPlayWhenReady)
        assertEquals(0, h.count("play"))
    }

    @Test fun pauseThenPlayWithoutAStartReplaysOnlyTheLatestIntentOnReconnect() {
        val h = harness()
        h.pc.playFromQueue(range(1, 4), startIndex = 0)
        val c1 = h.complete()
        h.disconnect(c1)
        val baseline = h.calls.size

        h.pc.togglePlayPause() // while playing => pause
        h.pc.togglePlayPause() // => play
        h.settle(50)
        assertEquals(2, h.attempts.size)
        h.complete()

        val after = h.calls.drop(baseline)
        assertTrue("only the final intent (play) executes: $after", after.none { it == "pause" })
        assertTrue(h.serverPlayWhenReady)
        assertEquals("no queue push on a warm reconnect", 1, h.count("setMediaItems"))
    }

    @Test fun startThenSeekAppliesToTheInstalledMatchingOccurrence() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 2)
        h.pc.seekTo(30_000L)
        h.complete()

        assertEquals(2, h.serverIndex)
        assertEquals("the position is part of the single install (no seek command Media3 could drop)", 1, h.count("setMediaItems"))
        assertTrue("position applied to the started occurrence", h.rig.player.currentPosition >= 29_000L)
    }

    @Test fun aSeekCapturedAgainstAnEarlierStartIsStaleAfterANewerStart() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 2)
        h.pc.seekTo(30_000L)
        h.pc.playFromQueue(range(11, 15), startIndex = 1)
        h.complete()

        assertEquals(listOf("11", "12", "13", "14", "15"), h.serverIds)
        assertEquals(1, h.serverIndex)
        assertTrue("the stale seek is rejected: ${h.calls}", h.calls.none { it.startsWith("seekTo(") && it.contains("30000") })
        assertTrue(h.rig.player.currentPosition < 5_000L)
    }

    @Test fun aSeekFollowedByNextIsStaleBecauseItsOccurrenceIsNoLongerCurrent() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 1)
        h.pc.seekTo(30_000L)
        h.pc.skipToNext()
        h.complete()

        assertEquals(2, h.serverIndex)
        assertTrue("the seek targeted the previous occurrence: ${h.calls}", h.calls.none { it.startsWith("seekTo(") && it.contains("30000") })
    }

    @Test fun startThenNextMovesToTheFollowingOccurrenceOfTheInstalledQueue() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 1)
        h.pc.skipToNext()
        h.complete()

        assertEquals(listOf("1", "2", "3", "4", "5"), h.serverIds)
        assertEquals(2, h.serverIndex)
        assertEquals("3", h.rig.player.currentMediaItem?.mediaId)
        assertEquals(2, h.logical.currentIndex)
        assertTrue(h.serverPlayWhenReady)
        assertEquals(1, h.count("setMediaItems"))
    }

    @Test fun startThenPreviousMovesToThePrecedingOccurrence() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 3)
        h.pc.skipToPrevious()
        h.complete()

        assertEquals(2, h.serverIndex)
        assertEquals("3", h.rig.player.currentMediaItem?.mediaId)
        assertEquals(2, h.logical.currentIndex)
    }

    @Test fun aNextIssuedForAnOlderStartNeverReplaysAgainstANewerOne() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 1)
        h.pc.skipToNext()
        h.pc.playFromQueue(range(21, 25), startIndex = 3)
        h.complete()

        assertEquals(listOf("21", "22", "23", "24", "25"), h.serverIds)
        assertEquals("the old Next is gone: B stays where it was started", 3, h.serverIndex)
        assertEquals("24", h.rig.player.currentMediaItem?.mediaId)
    }

    @Test fun anOlderTransportNeverReplaysAfterALaterStartEvenOfTheSameQueue() {
        val h = harness()
        val queue = range(1, 5)
        h.pc.playFromQueue(queue, startIndex = 0)
        h.pc.togglePlayPause() // pause
        h.pc.playFromQueue(queue, startIndex = 2) // newer start: plays, and the old pause is gone
        h.complete()

        assertTrue("the newer start plays; the old Pause must not replay", h.serverPlayWhenReady)
        assertEquals(2, h.serverIndex)
    }

    // ------------------------------------------------------------------------------------------------ jumps

    @Test fun aQueueJumpAfterAStartRetargetsTheStartInsteadOfInstallingTwice() {
        val h = harness()
        val queue = range(1, 6)
        h.pc.playFromQueue(queue, startIndex = 0)
        h.pc.jumpToQueueItem(4)
        h.complete()

        assertEquals(1, h.count("setMediaItems"))
        assertEquals(4, h.serverIndex)
        assertEquals("5", h.rig.player.currentMediaItem?.mediaId)
        assertEquals(4, h.logical.currentIndex)
        assertEquals(ids(queue), h.serverIds)
    }

    @Test fun aDeferredJumpNeverOverridesANewerReplacement() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 0)
        val c1 = h.complete()
        h.disconnect(c1)
        h.pc.jumpToQueueItem(3) // held while disconnected
        h.settle(50)
        h.pc.playFromQueue(range(21, 24), startIndex = 1) // newer replacement
        val baseline = h.calls.size
        h.complete()

        val after = h.calls.drop(baseline)
        assertEquals(listOf("21", "22", "23", "24"), h.serverIds)
        assertEquals(1, h.serverIndex)
        assertTrue("the old jump (index 3) never executes: $after", after.none { it == "seekTo(3,0)" })
        assertEquals("one new install after the baseline", 1, after.count { it.startsWith("setMediaItems") })
    }

    @Test fun aHeldJumpWithNoStartExecutesOnceAgainstTheLiveQueue() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 0)
        val c1 = h.complete()
        h.disconnect(c1)
        assertTrue(h.pc.jumpToSongById(4L))
        h.settle(50)
        val baseline = h.calls.size
        h.complete()

        assertEquals(3, h.serverIndex)
        assertEquals("4", h.rig.player.currentMediaItem?.mediaId)
        assertEquals("no whole-queue push on a warm reconnect", 0, h.calls.drop(baseline).count { it.startsWith("setMediaItems") })
        assertEquals(1, h.calls.drop(baseline).count { it == "seekTo(3,0)" })
    }

    // ------------------------------------------------------------------------------------------------ duplicates

    @Test fun aDuplicateBearingQueueInstallsAndNavigatesFromTheExactOccurrence() {
        val h = harness()
        val a = song(1)
        val queue = listOf(a, song(2), a, song(3), a)
        h.pc.playFromQueue(queue, startIndex = 2) // the SECOND copy of song 1
        h.settle(50)
        assertEquals(2, h.logical.currentIndex)
        h.pc.skipToNext() // anchored to occurrence 2, not to "song 1"
        h.complete()

        assertEquals(listOf("1", "2", "1", "3", "1"), h.serverIds)
        assertEquals("Next from occurrence 2 is occurrence 3 (song 3), never relative to the first copy", 3, h.serverIndex)
        assertEquals("3", h.rig.player.currentMediaItem?.mediaId)
    }

    @Test fun aDeferredJumpToADuplicateOccurrenceKeepsTheExactIndex() {
        val h = harness()
        val a = song(1)
        val queue = listOf(a, song(2), a, song(3), a)
        h.pc.playFromQueue(queue, startIndex = 0)
        h.pc.jumpToQueueItem(4) // the LAST copy
        h.complete()

        assertEquals(4, h.serverIndex)
        assertEquals(4, h.logical.currentIndex)
        assertEquals(listOf("1", "2", "1", "3", "1"), h.serverIds)
    }

    // ------------------------------------------------------------------------------------------------ exact occurrence identity

    @Test fun aStartAtTheSecondOccurrenceOfADuplicateQueueInstallsThatOccurrenceWithoutMutation() {
        val h = harness()
        val a = song(1)
        h.pc.playFromQueue(listOf(a, song(2), a), startIndex = 2)
        h.complete()

        assertEquals(listOf("1", "2", "1"), h.serverIds)
        assertEquals("the SECOND copy, not the first", 2, h.serverIndex)
        assertEquals(2, h.logical.currentIndex)
        assertTrue(h.serverPlayWhenReady)
        assertEquals(1, h.count("setMediaItems"))
    }

    /** A changed-generation deferred start is rejected: nothing plays, nothing is installed as the selected occurrence. */
    private fun assertStartRejected(h: Harness, label: String) {
        assertFalse("$label: the rejected start never plays: ${h.calls}", h.calls.contains("play"))
        assertFalse("$label: not playing", h.serverPlayWhenReady)
        assertNull("$label: consumed exactly once", (h.field("deferred").get(h.pc) as DeferredPlaybackCommands).start)
    }

    @Test fun anInterveningRemovalBeforeTheSelectedSongRejectsTheStartInsteadOfRebasingByUniqueId() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 2) // song 3
        h.pc.removeFromQueue(0) // shifts song 3, but occurrence continuity is not provable
        h.settle(50)
        h.complete()
        assertStartRejected(h, "removal before")
    }

    @Test fun anInterveningInsertionAfterTheSelectedOccurrenceRejectsTheStart() {
        val h = harness()
        h.pc.playFromQueue(range(1, 4), startIndex = 1)
        h.pc.playNext(song(99))
        h.settle(50)
        h.complete()
        assertStartRejected(h, "insertion after")
    }

    @Test fun anEditAroundASelectedDuplicateNeverPlaysTheSiblingCopyAsTheSelectedOccurrence() {
        val h = harness()
        val a = song(1)
        h.pc.playFromQueue(listOf(a, song(2), a), startIndex = 2) // selected = the LAST A
        h.pc.removeFromQueue(2) // refused: the current item cannot be removed (the selected copy stays)
        h.pc.removeFromQueue(1) // [A(first), A(selected)]: the surviving siblings are indistinguishable by id
        h.settle(50)
        h.complete()
        assertStartRejected(h, "[A(first), B, A(selected)] edited") // the exact [A, B] shape is covered by the pure resolver test
        assertTrue("the remaining first A is never started as the selected occurrence: ${h.calls}", h.calls.none { it.startsWith("seekTo(0,") })
    }

    @Test fun removalFollowedByReinsertionOfTheSameIdDoesNotRevalidateTheStart() {
        val h = harness()
        h.pc.playFromQueue(range(1, 4), startIndex = 3) // song 4 selected at the end
        h.pc.removeFromQueue(0)
        h.pc.playNext(song(1)) // the same id comes back, at a different position
        h.settle(50)
        h.complete()
        assertStartRejected(h, "remove then reinsert")
    }
    @Test fun aDuplicateSelectionWhoseOccurrenceCannotBeProvenAfterAMutationIsRejectedNotGuessedByTheSameId() {
        val h = harness()
        val a = song(1)
        h.pc.playFromQueue(listOf(a, song(2), a), startIndex = 2) // second copy of song 1
        h.pc.removeFromQueue(1) // [1, 1]: which copy was selected is no longer provable
        h.settle(50)
        h.complete()

        assertFalse("the ambiguous start is not executed (no play)", h.calls.contains("play"))
        assertFalse(h.serverPlayWhenReady)
        assertTrue("it is not installed as a fresh start on a guessed copy: ${h.calls}", h.calls.none { it == "seekTo(0,0)" })
        assertNull(h.pc.let { h.field("deferred").get(it) as DeferredPlaybackCommands }.start)
    }

    @Test fun aDeferredJumpToADuplicateFollowedByAStructuralEditIsRejectedAsAmbiguous() {
        val h = harness()
        val a = song(1)
        h.pc.playFromQueue(listOf(a, song(2), a, song(3), a), startIndex = 0)
        h.pc.jumpToQueueItem(4) // last copy of song 1
        h.pc.removeFromQueue(1)
        h.settle(50)
        h.complete()

        assertFalse("ambiguous after the edit: no start executes", h.calls.contains("play"))
        assertFalse(h.serverPlayWhenReady)
    }

    @Test fun aDeferredJumpToAUniqueSongFollowedByAStructuralEditIsRejected() {
        val h = harness()
        val a = song(1)
        h.pc.playFromQueue(listOf(a, song(2), a, song(3), a), startIndex = 0)
        h.pc.jumpToQueueItem(3) // the unique song 3
        h.pc.removeFromQueue(0)
        h.settle(50)
        h.complete()
        assertStartRejected(h, "unique jump then edit")
    }
    @Test fun aJumpWithNoEditAfterwardsInstallsTheExactDuplicateOccurrence() {
        val h = harness()
        val a = song(1)
        h.pc.playFromQueue(listOf(a, song(2), a), startIndex = 0)
        h.pc.jumpToQueueItem(2)
        h.complete()

        assertEquals(2, h.serverIndex)
        assertEquals(listOf("1", "2", "1"), h.serverIds)
    }

    @Test fun supersedingAStartWithTheSameSongAtAnotherOccurrenceInstallsOnlyTheNewerOccurrence() {
        val a = song(1)
        val queue = listOf(a, song(2), a)
        val first = harness()
        first.pc.playFromQueue(queue, startIndex = 0)
        first.pc.playFromQueue(queue, startIndex = 2)
        first.complete()
        assertEquals(2, first.serverIndex)
        assertEquals(1, first.count("setMediaItems"))

        val second = harness()
        second.pc.playFromQueue(queue, startIndex = 2)
        second.pc.playFromQueue(queue, startIndex = 0)
        second.complete()
        assertEquals(0, second.serverIndex)
        assertEquals(listOf("1", "2", "1"), second.serverIds)
        assertEquals(1, second.count("setMediaItems"))
    }

    @Test fun aSeekCapturedBeforeAShiftingEditIsStaleAndNeverSeeksTheShiftedOccurrence() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 2)
        h.pc.seekTo(30_000L)
        h.pc.removeFromQueue(0)
        h.settle(50)
        h.complete()

        assertEquals(1, h.serverIndex)
        assertEquals("3", h.rig.player.currentMediaItem?.mediaId)
        assertTrue("the stale seek is not applied: ${h.calls}", h.calls.none { it.startsWith("seekTo(") && it.contains("30000") })
        assertTrue(h.rig.player.currentPosition < 5_000L)
    }

    @Test fun aNavigationCapturedBeforeAShiftingEditIsStaleButOneIssuedAfterItIsHonoured() {
        val before = harness()
        before.pc.playFromQueue(range(1, 5), startIndex = 2)
        before.pc.skipToNext() // anchored to occurrence 2
        before.pc.removeFromQueue(0) // that occurrence is now 1
        before.settle(50)
        before.complete()
        assertEquals("the old Next is discarded", 1, before.serverIndex)
        assertEquals("3", before.rig.player.currentMediaItem?.mediaId)

        val after = harness()
        after.pc.playFromQueue(range(1, 5), startIndex = 2)
        after.pc.removeFromQueue(0)
        after.pc.skipToNext() // anchored to the new occurrence 1
        after.settle(50)
        after.complete()
        assertEquals(listOf("2", "3", "4", "5"), after.serverIds)
        assertEquals(2, after.serverIndex)
        assertEquals("4", after.rig.player.currentMediaItem?.mediaId)
    }
    // ------------------------------------------------------------------------------------------------ races

    @Test fun aLateCompletionAfterReleaseInstallsNothingAndReleasesItsController() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 2)
        h.settle(50)
        h.pc.release() // teardown while the attempt is in flight
        val late = h.newController()
        h.attempts[0].set(late)
        h.settle(300)

        assertEquals("a released PlayerController is never resurrected", 0, h.calls.size)
        assertNull(h.connectedController)
        assertEquals(emptyList<String>(), h.serverIds)
        assertFalse("the stale attempt's controller is released", late.isConnected)
    }

    @Test fun aStaleAttemptCompletingAfterANewerOneInstallsNothingAndTheNewerOneInstallsOnce() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 1)
        h.settle(50)
        assertEquals(1, h.attempts.size)

        // The first attempt is abandoned (e.g. the connection layer was reset); the next demand starts attempt 2.
        h.field("controllerConnectionState").set(h.pc, ControllerConnectionState.Disconnected)
        h.pc.togglePlayPause()
        h.settle(50)
        assertEquals(2, h.attempts.size)

        val stale = h.newController()
        h.attempts[0].set(stale)
        h.settle(200)
        assertEquals("the delayed stale completion executes nothing", 0, h.calls.size)
        assertNull(h.connectedController)
        assertFalse(stale.isConnected)

        h.complete(attempt = 1)
        assertEquals(1, h.count("setMediaItems"))
        assertEquals(1, h.serverIndex)
        assertFalse("the Pause issued after the start is honoured", h.serverPlayWhenReady)
    }

    @Test fun disconnectThenReconnectExecutesEachCommandExactlyOnce() {
        val h = harness()
        h.pc.playFromQueue(range(1, 5), startIndex = 1)
        val c1 = h.complete()
        assertEquals(1, h.count("setMediaItems"))
        h.disconnect(c1)
        assertNull(h.connectedController)

        h.pc.skipToNext()
        h.settle(50)
        assertEquals(2, h.attempts.size)
        val baseline = h.calls.size
        h.complete()
        assertEquals(2, h.serverIndex)
        assertEquals(1, h.calls.drop(baseline).count { it == "seekTo(2,0)" })

        // A further reconnect with nothing pending replays nothing.
        val c2 = h.connectedController as MediaController
        h.disconnect(c2)
        h.pc.togglePlayPause()
        h.settle(50)
        val before = h.calls.size
        h.complete()
        val replay = h.calls.drop(before)
        assertEquals("the earlier start is never replayed", 1, h.count("setMediaItems"))
        assertEquals("the earlier Next is never replayed", 0, replay.count { it.startsWith("seekTo(") })
        assertEquals(2, h.serverIndex)
    }

    @Test fun connectingTwiceWithNothingPendingDispatchesNothing() {
        val h = harness()
        h.pc.playFromQueue(range(1, 4), startIndex = 1)
        h.complete()
        val baseline = h.calls.size
        val method = PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java).apply { isAccessible = true }
        method.invoke(h.pc, h.newController())
        h.settle(200)

        val replay = h.calls.drop(baseline)
        assertTrue("no install, prepare, play, pause or seek is replayed: $replay", replay.none {
            it.startsWith("setMediaItems") || it == "prepare" || it == "play" || it == "pause" || it.startsWith("seekTo(")
        })
    }

    // ------------------------------------------------------------------------------------------------ crossfade + generation

    @Test fun anAcceptedDeferredStartCancelsCrossfadeOncePerSelectionAndTheDrainDoesNotNotifyAgain() {
        val h = harness()
        var notifications = 0
        h.pc.setExplicitQueueReplacementListener { notifications++ }

        h.pc.playFromQueue(range(1, 4), startIndex = 0)
        assertEquals(1, notifications)
        h.pc.playFromQueue(range(11, 14), startIndex = 1)
        assertEquals(2, notifications)
        val generation = h.queueGeneration
        h.complete()

        assertEquals("the drain is not a new user selection", 2, notifications)
        assertEquals("physical install does not bump the queue generation", generation, h.queueGeneration)
    }

    // ------------------------------------------------------------------------------------------------ debug trace

    @Test fun theStartupTraceStaysValidAcrossParkedSupersededAndDrainedRequests() {
        val lines = mutableListOf<String>()
        PlaybackStartupTrace.resetForTest()
        PlaybackStartupTrace.enabled = true
        PlaybackStartupTrace.sink = { lines += it }
        val h = harness()

        h.pc.playFromQueue(range(1, 4), startIndex = 0)
        h.pc.playFromQueue(range(11, 14), startIndex = 2) // supersedes the parked one
        h.complete()

        fun first(token: String, from: Int = 0) = lines.drop(from).indexOfFirst { it.contains(token) }.let { if (it < 0) -1 else it + from }
        val parked = first("controller_unavailable_parked")
        val superseded = first("superseded")
        val drain = first("controller_connected_pending_drain")
        val install = first("playlist_install_started", drain)
        val prepare = first("prepare_issued", drain)
        val play = first("play_issued", drain)
        assertTrue("parked is traced: $lines", parked >= 0)
        assertTrue("the first request is traced as superseded, not drained: $lines", superseded > parked)
        assertTrue("the drain follows: $lines", drain > superseded)
        assertTrue("the install belongs to the drain: $lines", install > drain)
        assertTrue("prepare after install: $lines", prepare > install)
        assertTrue("play after prepare: $lines", play > prepare)
        assertEquals("exactly one install is traced", 1, lines.count { it.contains("playlist_install_started") })
        assertTrue("no identifying metadata in the trace: $lines", lines.none { it.contains("Song ") || it.contains("content://") })
    }
}

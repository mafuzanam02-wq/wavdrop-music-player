package com.launchpoint.wavdrop.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.util.UnstableApi
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-F14 at the PRODUCTION [PlayerController] level, against real Media3: Play Next / Add to Queue return a result that was
 * re-verified against the committed logical queue, in a Full queue (<= FULL_MODE_MAX) and a Bounded one (> FULL_MODE_MAX).
 * The result must agree with the real queue: same position, same size, existing duplicate semantics untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerControllerQueueActionResultTest {

    private class Writer : HookedPlayEventWriter() {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class H {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "qar", physicalAck = true)
        val tracker = StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } }
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val pc = PlayerController(
            context, tracker, PlaybackSessionRepository(store("sessionstore")), ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )

        init {
            pc.attachPhysicalWindowPort(rig.port)
            field("controllerConnectionState").set(pc, ControllerConnectionState.Connected)
            invoke("onControllerConnected", arrayOf(androidx.media3.session.MediaController::class.java), arrayOf(rig.controller))
            rig.settle(100)
        }

        fun field(name: String) = PlayerController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String, types: Array<Class<*>>, args: Array<Any?>): Any? =
            PlayerController::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(pc, *args)
        val adapter: PhysicalWindowAdapter get() = field("physicalWindowAdapter").get(pc) as PhysicalWindowAdapter
        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper() }
        fun start(queue: List<Song>, index: Int) { pc.playFromQueue(queue.toList(), index); quiesce() }
        val queue: List<Song> get() = pc.nowPlayingState.value.queue
        val logical: Int get() = pc.nowPlayingState.value.currentIndex
        fun releaseAll() { runCatching { pc.release() }; rig.releaseAll() }
    }

    private val hs = mutableListOf<H>()
    private fun h() = H().also { hs += it }
    @After fun tearDown() { hs.forEach { it.releaseAll() }; hs.clear() }

    private fun queue(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private fun s(id: Long) = RealMedia3QueueRig.song(id)

    // ── Full queue (<= 300) ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fullQueuePlayNextIsConfirmedAtCurrentPlusOne() {
        val h = h(); h.start(queue(50), 10)
        val r = h.pc.playNext(s(900)); h.quiesce()
        assertEquals(QueueActionResult.Queued(QueueAction.PlayNext, QueuePlacement.PlayNext, 11), r)
        assertEquals(51, h.queue.size)
        assertEquals(900L, h.queue[11].id)
        assertEquals("the current item did not move", 10, h.logical)
    }

    @Test fun fullQueueAddToQueueIsConfirmedAtTheTail() {
        val h = h(); h.start(queue(50), 10)
        val r = h.pc.addToQueue(s(900)); h.quiesce()
        assertEquals(QueueActionResult.Queued(QueueAction.AddToQueue, QueuePlacement.Appended, 50), r)
        assertEquals(900L, h.queue.last().id)
        assertEquals(51, h.queue.size)
    }

    @Test fun exactlyAtTheFullModeLimitStillConfirmsCorrectly() {
        val h = h(); h.start(queue(PhysicalWindowPolicy.FULL_MODE_MAX), 100)
        val r = h.pc.playNext(s(900)); h.quiesce()
        assertEquals(QueueActionResult.Queued(QueueAction.PlayNext, QueuePlacement.PlayNext, 101), r)
        assertEquals(900L, h.queue[101].id)
    }

    @Test fun emptyQueueStartsPlaybackAndSaysSo() {
        val h = h()
        val r = h.pc.addToQueue(s(900)); h.quiesce()
        assertEquals(QueueActionResult.StartedQueue, r)
        assertEquals(listOf(900L), h.queue.map { it.id })
    }

    // ── existing duplicate semantics + repeated actions ─────────────────────────────────────────────────────────────────

    @Test fun duplicatesAreStillDeliberatelyAllowedAndEachIsConfirmedAtItsOwnPosition() {
        val h = h(); h.start(queue(20), 5)
        val a = h.pc.addToQueue(s(3)); val b = h.pc.addToQueue(s(3)); h.quiesce() // id 3 is already in the queue
        assertEquals(QueueActionResult.Queued(QueueAction.AddToQueue, QueuePlacement.Appended, 20), a)
        assertEquals(QueueActionResult.Queued(QueueAction.AddToQueue, QueuePlacement.Appended, 21), b)
        assertEquals("three occurrences of id 3", 3, h.queue.count { it.id == 3L })
        assertEquals(22, h.queue.size)
    }

    @Test fun repeatedPlayNextStacksInFrontOfThePreviousOneAndEachIsConfirmed() {
        val h = h(); h.start(queue(20), 5)
        val a = h.pc.playNext(s(901)); val b = h.pc.playNext(s(902)); h.quiesce()
        assertEquals(6, (a as QueueActionResult.Queued).position)
        assertEquals(6, (b as QueueActionResult.Queued).position) // existing semantics: each lands right after the current item
        assertEquals(listOf(902L, 901L), h.queue.subList(6, 8).map { it.id })
    }

    // ── Bounded (> 300) ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun boundedQueuePlayNextIsConfirmedAtCurrentPlusOneAndMatchesTheRealQueue() {
        val h = h(); val n = 1_000; h.start(queue(n), 500)
        val r = h.pc.playNext(s(90_001)); h.quiesce()
        assertEquals(QueueActionResult.Queued(QueueAction.PlayNext, QueuePlacement.PlayNext, 501), r)
        assertEquals(n + 1, h.queue.size)
        assertEquals(90_001L, h.queue[501].id)
        assertEquals(500, h.logical)
        assertEquals("physical neighbour agrees", "90001", h.rig.player.getMediaItemAt(h.rig.player.currentMediaItemIndex + 1).mediaId)
    }

    @Test fun boundedQueueAddToQueueIsConfirmedAtTheTailEvenOutsideThePhysicalWindow() {
        val h = h(); val n = 1_000; h.start(queue(n), 500)
        val r = h.pc.addToQueue(s(90_002)); h.quiesce()
        assertEquals(QueueActionResult.Queued(QueueAction.AddToQueue, QueuePlacement.Appended, n), r)
        assertEquals(90_002L, h.queue.last().id)
        assertTrue("the physical window stays bounded", h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
    }

    @Test fun boundedRepeatedAddsAreEachConfirmed() {
        val h = h(); h.start(queue(400), 10)
        val rs = (1..3).map { h.pc.addToQueue(s(95_000L + it)) }; h.quiesce()
        assertEquals(listOf(400, 401, 402), rs.map { (it as QueueActionResult.Queued).position })
        assertEquals(403, h.queue.size)
    }
}

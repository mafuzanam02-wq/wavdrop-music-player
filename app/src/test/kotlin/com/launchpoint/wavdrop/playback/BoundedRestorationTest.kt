package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionRepository
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 * P5-E Checkpoint D: bounded restoration from the existing persisted LOGICAL snapshot only (there is no second persisted physical
 * queue), through the real [PlaybackResumptionMapper] and [PlayerController.adoptPlaybackResumption], on the production chain
 * physical ExoPlayer -> SessionFacade -> MediaSession. What Media3 installs is applied through Media3's own
 * `MediaUtils.setMediaItemsWithStartIndexAndPosition` (the exact function MediaSessionImpl uses for a resumption result), so the
 * contract being tested is the real one, not a re-implementation of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class BoundedRestorationTest {

    private class Writer : PlayEventWriter {
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
        override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
    }

    private inner class H(connect: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val rig = RealMedia3QueueRig(emptyList(), 0, loadInitialQueue = false, sessionName = "rst", physicalAck = true, withFacade = true)
        val facade = rig.facade!!
        private fun store(name: String) = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job()),
            produceFile = { File.createTempFile(name, ".preferences_pb").also { f -> f.delete(); f.deleteOnExit() } },
        )
        val sessionRepo = PlaybackSessionRepository(store("sessionstore"))
        val pc = PlayerController(
            context,
            StatsTracker(Writer()).also { it.scope = CoroutineScope(Dispatchers.Unconfined); it.clock = { 0L } },
            sessionRepo, ResumeBehaviorSettingsRepository(store("resumestore")), AppSettingsRepository(store("appstore")),
        )
        val extra = mutableListOf<MediaController>()

        init {
            pc.boundedInstallEnabledForTesting = true
            pc.attachPhysicalWindowPort(rig.port)
            facade.bindLogicalQueue(pc.logicalQueuePresentation, pc.logicalQueueCommands)
            pc.setLogicalPresentationInvalidator { facade.invalidateLogicalState() }
            if (connect) connectAppController()
        }

        fun connectAppController() {
            PlayerController::class.java.getDeclaredField("controllerConnectionState").apply { isAccessible = true }
                .set(pc, ControllerConnectionState.Connected)
            PlayerController::class.java.getDeclaredMethod("onControllerConnected", MediaController::class.java)
                .apply { isAccessible = true }.invoke(pc, rig.controller)
            rig.settle(100)
        }

        val adapter: PhysicalWindowAdapter
            get() = PlayerController::class.java.getDeclaredField("physicalWindowAdapter").apply { isAccessible = true }.get(pc) as PhysicalWindowAdapter

        fun quiesce() { TestMediaSource.awaitCondition { !adapter.isBusy }; idleMainLooper(); rig.settle(60) }

        fun externalController(): MediaController {
            val future = MediaController.Builder(context, rig.session.token).setConnectionHints(Bundle()).buildAsync()
            var guard = 0
            while (!future.isDone && guard++ < 200) idleMainLooper()
            return future.get(5, TimeUnit.SECONDS).also { extra += it; idleMainLooper(); rig.settle(60) }
        }

        /** The service's onPlaybackResumption, minus the coroutine plumbing: map, adopt, then apply what Media3 would. */
        fun resume(snapshot: PlaybackSessionSnapshot, songs: List<Song>, isForPlayback: Boolean = true): MediaSession.MediaItemsWithStartPosition {
            val result = PlaybackResumptionMapper.map(snapshot, ResumeBehaviorSettings(), songs, boundedModeFor = pc::boundedModeForResumption)
            val plan = (result as PlaybackResumptionResult.Ready).plan
            val media3 = pc.adoptPlaybackResumption(plan)
            applyLikeMedia3(media3, isForPlayback)
            return media3
        }

        fun applyLikeMedia3(media3: MediaSession.MediaItemsWithStartPosition, play: Boolean) {
            val utils = Class.forName("androidx.media3.session.MediaUtils")
            utils.getDeclaredMethod("setMediaItemsWithStartIndexAndPosition", Player::class.java, MediaSession.MediaItemsWithStartPosition::class.java)
                .apply { isAccessible = true }.invoke(null, facade, media3)
            if (play) androidx.media3.common.util.Util.handlePlayButtonAction(facade)
            idleMainLooper()
        }

        fun release() { extra.forEach { runCatching { it.release() } }; runCatching { pc.release() }; rig.releaseAll() }
    }

    private val hs = mutableListOf<H>()
    private fun h(connect: Boolean = true) = H(connect).also { hs += it }
    @After fun tearDown() { hs.forEach { it.release() }; hs.clear() }

    private fun songs(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun snapshot(
        n: Int, current: Int, positionMs: Long = 0L, repeat: RepeatMode = RepeatMode.OFF,
        queueIds: List<Long>? = null, shuffle: Boolean = false, order: List<Int>? = null,
    ) = PlaybackSessionSnapshot(
        queueSongIds = queueIds ?: List(n) { it + 1L },
        playbackOrder = order, currentSongId = (queueIds ?: List(n) { it + 1L })[current], currentIndex = current,
        positionMs = positionMs, repeatMode = repeat, shuffleEnabled = shuffle, updatedAtMs = 0L,
    )

    private fun setMediaItemsCalls(h: H) = h.rig.recording.count("setMediaItems")

    // ── the contract ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aBoundedPlanNeverMaterializesTheFullQueueForMedia3() {
        val h = h(connect = false)
        val plan = (PlaybackResumptionMapper.map(snapshot(25_000, 12_345, 33_000L), ResumeBehaviorSettings(), songs(25_000), boundedModeFor = h.pc::boundedModeForResumption)
            as PlaybackResumptionResult.Ready).plan
        assertTrue(plan.bounded)
        assertTrue("no 25k MediaItem list for Media3", plan.mediaItems.isEmpty())
        assertEquals("the logical queue is still fully restored", 25_000, plan.playbackQueue.size)
        assertEquals(12_345, plan.startPlaybackIndex)
        // Full queues are unchanged.
        val full = (PlaybackResumptionMapper.map(snapshot(200, 50), ResumeBehaviorSettings(), songs(200), boundedModeFor = h.pc::boundedModeForResumption) as PlaybackResumptionResult.Ready).plan
        assertFalse(full.bounded)
        assertEquals(200, full.mediaItems.size)
    }

    @Test fun coldRestoreInstallsOneVerifiedWindowAtTheSavedPositionAndMedia3DoesNotInstallAgain() {
        val h = h(connect = false)
        val media3 = h.resume(snapshot(25_000, 12_345, 33_000L), songs(25_000))
        h.quiesce()
        assertTrue("Media3 gets only the window (${media3.mediaItems.size})", media3.mediaItems.size in 1..PhysicalWindowPolicy.W_MAX)
        assertTrue("PHYSICAL start index, not the logical one (${media3.startIndex})", media3.startIndex in 0 until media3.mediaItems.size)
        assertEquals("12346", media3.mediaItems[media3.startIndex].mediaId)
        assertEquals(33_000L, media3.startPositionMs)
        assertEquals("exactly one physical install: Media3's own is skipped", 1, setMediaItemsCalls(h))
        assertNotNull("verified in-process", h.adapter.active)
        assertTrue(h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertTrue("saved position kept (${h.rig.player.currentPosition})", h.rig.player.currentPosition in 32_000L..40_000L)
        // The façade presents the complete logical queue to controllers that connect afterwards.
        val ext = h.externalController()
        assertEquals(25_000, ext.mediaItemCount)
        assertEquals(12_345, ext.currentMediaItemIndex)
        assertEquals("12346", ext.currentMediaItem?.mediaId)
        // And the app's own controller connecting later neither re-anchors nor re-installs.
        h.connectAppController()
        h.quiesce()
        assertEquals("reconnect of a verified window installs nothing", 1, setMediaItemsCalls(h))
        assertEquals(12_345, h.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun everyBoundaryOccurrenceRestoresExactly() {
        for ((current, repeat) in listOf(0 to RepeatMode.OFF, 24_999 to RepeatMode.OFF, 24_999 to RepeatMode.ALL, 0 to RepeatMode.ALL, 7 to RepeatMode.ONE)) {
            val h = h(connect = false)
            val media3 = h.resume(snapshot(25_000, current, 1_000L, repeat), songs(25_000))
            h.quiesce()
            assertEquals("current=$current repeat=$repeat", (current + 1).toString(), media3.mediaItems[media3.startIndex].mediaId)
            assertEquals(1, setMediaItemsCalls(h))
            val ext = h.externalController()
            assertEquals(current, ext.currentMediaItemIndex)
            assertEquals(25_000, ext.mediaItemCount)
            assertEquals(
                when (repeat) { RepeatMode.OFF -> Player.REPEAT_MODE_OFF; RepeatMode.ALL -> Player.REPEAT_MODE_ALL; RepeatMode.ONE -> Player.REPEAT_MODE_ONE },
                ext.repeatMode,
            )
            assertEquals("the physical mirror is never ALL", if (repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF, h.rig.player.repeatMode)
            h.release(); hs.remove(h)
        }
    }

    @Test fun duplicateOccurrencesAndAShuffledOrderRestoreTheSavedOccurrence() {
        val h = h(connect = false)
        val ids = List(2_000) { longArrayOf(1, 2, 1, 3, 1)[it % 5] }
        val library = ids.indices.map { RealMedia3QueueRig.song(ids[it]) }
        // The library holds each song once per distinct id; the saved queue lists ids with duplicates.
        val unique = listOf(1L, 2L, 3L).map { RealMedia3QueueRig.song(it) }
        val media3 = h.resume(snapshot(2_000, 1_002, 5_000L, queueIds = ids), unique)
        h.quiesce()
        assertEquals("1", media3.mediaItems[media3.startIndex].mediaId)
        val ext = h.externalController()
        assertEquals("the saved OCCURRENCE (index), not the first song with that id", 1_002, ext.currentMediaItemIndex)
        assertEquals(2_000, ext.mediaItemCount)
        assertEquals(library.size, h.pc.nowPlayingState.value.queue.size)

        val s = h(connect = false)
        val order = (0 until 1_000).toList().reversed() // a deterministic stand-in for a shuffled playback order
        val media3Shuffled = s.resume(snapshot(1_000, 400, 0L, shuffle = true, order = order), songs(1_000))
        s.quiesce()
        val startSong = (400 + 1).toString()
        assertEquals(startSong, media3Shuffled.mediaItems[media3Shuffled.startIndex].mediaId)
        assertEquals(1, setMediaItemsCalls(s))
        assertEquals("playback order index of library index 400", order.indexOf(400), s.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun afterARestoreNextAndPreviousAreOrdinaryBoundedNavigation() {
        val h = h(connect = false)
        h.resume(snapshot(25_000, 12_345, 10_000L), songs(25_000))
        h.quiesce()
        h.connectAppController()
        h.pc.skipToNext(); h.quiesce()
        assertEquals(12_346, h.pc.nowPlayingState.value.currentIndex)
        h.pc.skipToPrevious(); h.quiesce()
        assertEquals(12_345, h.pc.nowPlayingState.value.currentIndex)
        assertEquals("navigation inside the window installs nothing more", 1, setMediaItemsCalls(h))
    }

    // ── races ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aNewerSelectionDuringTheRestoreWinsAndLeavesOneBoundedWindowAroundIt() {
        val h = h()
        val media3 = PlaybackResumptionMapper.map(snapshot(25_000, 12_345, 33_000L), ResumeBehaviorSettings(), songs(25_000), boundedModeFor = h.pc::boundedModeForResumption)
        h.pc.adoptPlaybackResumption((media3 as PlaybackResumptionResult.Ready).plan)
        // Before the restore is acknowledged, the user picks something else.
        h.pc.playFromQueue(songs(25_000), 3_000)
        h.quiesce()
        assertEquals(3_000, h.pc.nowPlayingState.value.currentIndex)
        assertEquals("3001", h.rig.player.currentMediaItem?.mediaId)
        assertTrue(h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertNotNull(h.adapter.active)
        assertEquals(3_000, h.adapter.active!!.logicalCurrent)
    }

    @Test fun pausingDuringTheRestoreIsNotUndoneByTheRestore() {
        val h = h()
        val plan = (PlaybackResumptionMapper.map(snapshot(25_000, 500, 5_000L), ResumeBehaviorSettings(), songs(25_000), boundedModeFor = h.pc::boundedModeForResumption) as PlaybackResumptionResult.Ready).plan
        val media3 = h.pc.adoptPlaybackResumption(plan)
        h.rig.controller.pause()
        h.applyLikeMedia3(media3, play = false) // a non-playback resumption: Media3 prepares nothing and plays nothing
        h.quiesce()
        h.rig.settle(150)
        assertFalse("nothing started playback", h.rig.player.playWhenReady)
        assertEquals(500, h.pc.nowPlayingState.value.currentIndex)
    }

    @Test fun aFailedPhysicalAcknowledgementLeavesNoMappingAndExactlyOneRepairInstallAfterwards() {
        val h = h(connect = false)
        h.pc.boundedVerifierFactory = { PhysicalInstallVerifier { Futures.immediateFailedFuture(IllegalStateException("probe lost")) } }
        h.resume(snapshot(25_000, 12_345, 0L), songs(25_000))
        TestMediaSource.awaitCondition { !h.adapter.isBusy }
        assertNull("nothing is published without the physical acknowledgement", h.adapter.active)
        assertEquals("no full-queue install as a fallback", 1, setMediaItemsCalls(h))
        assertTrue(h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        // The probe works again: connecting the app controller repairs with ONE bounded install around the saved occurrence.
        h.pc.boundedVerifierFactory = { it.verifier }
        h.connectAppController()
        h.quiesce()
        assertEquals(2, setMediaItemsCalls(h))
        assertNotNull(h.adapter.active)
        assertEquals(12_345, h.adapter.active!!.logicalCurrent)
    }

    @Test fun theAppSideSessionHydrationAlsoInstallsOneBoundedWindowAtTheSavedPosition() {
        val h = h()
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { h.sessionRepo.save(snapshot(25_000, 9_000, 61_000L)) }
        var result: PlayerHydrationResult? = null
        CoroutineScope(Dispatchers.Main).launch { result = h.pc.restoreSessionIfNeeded(songs(25_000)) }
        TestMediaSource.awaitCondition { result != null }
        h.quiesce()
        assertEquals(PlayerHydrationResult.Hydrated, result)
        assertEquals(9_000, h.pc.nowPlayingState.value.currentIndex)
        assertEquals("9001", h.rig.player.currentMediaItem?.mediaId)
        assertTrue(h.rig.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertEquals(1, setMediaItemsCalls(h))
        assertTrue("saved position kept (${h.rig.player.currentPosition})", h.rig.player.currentPosition in 60_000L..70_000L)
        assertEquals(25_000, h.externalController().mediaItemCount)
    }
}

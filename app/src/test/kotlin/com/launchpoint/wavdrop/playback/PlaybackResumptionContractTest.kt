package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `onPlaybackResumption` contract through the REAL [PlaybackResumptionHandler] (the logic PlaybackService delegates to), on the
 * production topology. Media3 1.11.1 applies a result to the player only for isForPlayback = true; for false (the library "recent
 * item" request) the result is only turned into a browse item list. The helper that stands in for Media3 below therefore applies a
 * result ONLY when isForPlayback is true, exactly like MediaSessionImpl, and never adopts anything itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackResumptionContractTest {

    private val rigs = mutableListOf<EngineChainRig>()
    private fun rig(bounded: Boolean = true) = EngineChainRig(bounded).also { rigs += it }
    @After fun tearDown() { rigs.forEach { it.release() }; rigs.clear() }

    private fun songs(n: Int) = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun snapshot(n: Int, current: Int, positionMs: Long = 0L) = PlaybackSessionSnapshot(
        queueSongIds = List(n) { it + 1L }, playbackOrder = null, currentSongId = current + 1L, currentIndex = current,
        positionMs = positionMs, repeatMode = RepeatMode.OFF, shuffleEnabled = false, updatedAtMs = 0L,
    )

    private class Counters(r: EngineChainRig) {
        var playlistChanges = 0
        private val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) playlistChanges++
            }
        }
        init { r.p1.addListener(listener); r.p2.addListener(listener) }
    }

    private fun handler(
        r: EngineChainRig,
        snapshot: PlaybackSessionSnapshot,
        songs: List<Song>,
        gate: CompletableDeferred<Unit>? = null,
        crossfadeCancels: IntArray = IntArray(1),
    ) = PlaybackResumptionHandler(
        scope = CoroutineScope(Dispatchers.Main),
        loadSnapshot = { gate?.await(); snapshot },
        loadSettings = { ResumeBehaviorSettings() },
        loadSongs = { songs },
        playerController = r.pc,
        cancelCrossfade = { crossfadeCancels[0]++ },
        log = {},
    )

    private fun await(f: ListenableFuture<*>) = TestMediaSource.awaitCondition { f.isDone }

    /** What MediaSessionImpl does with a result: apply it for a playback request, never for a query. */
    private fun mediaApplies(r: EngineChainRig, result: MediaSession.MediaItemsWithStartPosition, isForPlayback: Boolean) {
        if (!isForPlayback) return
        Class.forName("androidx.media3.session.MediaUtils")
            .getDeclaredMethod("setMediaItemsWithStartIndexAndPosition", Player::class.java, MediaSession.MediaItemsWithStartPosition::class.java)
            .apply { isAccessible = true }.invoke(null, r.engine.facade, result)
        androidx.media3.common.util.Util.handlePlayButtonAction(r.engine.facade)
        idleMainLooper()
    }

    private fun query(r: EngineChainRig, snap: PlaybackSessionSnapshot, q: List<Song>): MediaSession.MediaItemsWithStartPosition {
        val f = handler(r, snap, q).resume(isForPlayback = false) { error("a query must never apply transport state") }
        await(f)
        val result = f.get()
        mediaApplies(r, result, isForPlayback = false)
        return result
    }

    /** Everything a read-only request must leave exactly as it was. */
    private class Observed(r: EngineChainRig) {
        val generation = r.pc.resumptionAuthority()
        val state = r.pc.nowPlayingState.value
        val queue = state.queue
        val index = state.currentIndex
        val activeWindow = r.adapter.active
        val busy = r.adapter.isBusy
        val currentCount = r.engine.currentPlayer.mediaItemCount
        val nextCount = r.engine.nextPlayer.mediaItemCount
        val currentId = r.engine.currentPlayer.currentMediaItem?.mediaId
        val playWhenReady = r.engine.currentPlayer.playWhenReady
        val nextState = r.engine.nextPreparation.state
        val windowGeneration = r.adapter.active?.window?.windowGeneration
    }

    private fun assertUntouched(r: EngineChainRig, before: Observed, counters: Counters, label: String) {
        assertEquals("$label: queue generation", before.generation, r.pc.resumptionAuthority())
        assertSame("$label: logical queue", before.queue, r.pc.nowPlayingState.value.queue)
        assertEquals("$label: current index", before.index, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("$label: no physical playlist operation", 0, counters.playlistChanges)
        assertEquals("$label: CURRENT playlist", before.currentCount, r.engine.currentPlayer.mediaItemCount)
        assertEquals("$label: NEXT playlist", before.nextCount, r.engine.nextPlayer.mediaItemCount)
        assertEquals("$label: current song", before.currentId, r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertEquals("$label: play intent", before.playWhenReady, r.engine.currentPlayer.playWhenReady)
        assertSame("$label: verified window instance", before.activeWindow, r.adapter.active)
        assertEquals("$label: window generation", before.windowGeneration, r.adapter.active?.window?.windowGeneration)
        assertEquals("$label: NEXT preparation", before.nextState, r.engine.nextPreparation.state)
    }

    // ── query-only requests ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aBoundedQueryOnAnEmptyPlayerReturnsOneItemAndTouchesNothing() {
        val r = rig()
        val before = Observed(r)
        val counters = Counters(r)
        val cancels = IntArray(1)
        val f = handler(r, snapshot(25_000, 12_345, 33_000L), songs(25_000), crossfadeCancels = cancels).resume(false) { error("no transport on a query") }
        await(f)
        val result = f.get()
        assertEquals("one recent item, never the 25k queue", 1, result.mediaItems.size)
        assertEquals("12346", result.mediaItems[0].mediaId)
        assertEquals(0, result.startIndex)
        assertEquals(33_000L, result.startPositionMs)
        assertEquals("a query never cancels a crossfade", 0, cancels[0])
        mediaApplies(r, result, isForPlayback = false)
        assertUntouched(r, before, counters, "empty player")
        assertEquals(0, r.engine.currentPlayer.mediaItemCount)
        assertTrue(r.pc.nowPlayingState.value.queue.isEmpty())
    }

    @Test fun aQueryWhileAnotherQueueIsPlayingChangesNothing() {
        val r = rig()
        r.start(songs(5_000), 1_000)
        val before = Observed(r)
        val counters = Counters(r)
        query(r, snapshot(25_000, 12_345, 33_000L), songs(25_000))
        assertUntouched(r, before, counters, "other queue playing")
        assertEquals(5_000, r.controller.mediaItemCount)
    }

    @Test fun aQueryWhilePausedDoesNotChangePlayIntentOrPosition() {
        val r = rig()
        r.start(songs(5_000), 1_000)
        r.controller.pause()
        TestMediaSource.awaitCondition { !r.engine.currentPlayer.playWhenReady }
        val position = r.engine.currentPlayer.currentPosition
        val before = Observed(r)
        val counters = Counters(r)
        query(r, snapshot(25_000, 12_345, 33_000L), songs(25_000))
        assertUntouched(r, before, counters, "paused")
        assertFalse(r.engine.currentPlayer.playWhenReady)
        assertEquals(position, r.engine.currentPlayer.currentPosition)
    }

    @Test fun aQueryDuringAPhysicalWindowAdjustmentDoesNotDisturbIt() {
        val r = rig()
        r.start(songs(25_000), 12_000)
        r.pc.jumpToQueueItem(20_000) // re-anchor in flight
        val generation = r.pc.resumptionAuthority()
        val counters = Counters(r)
        query(r, snapshot(25_000, 12_345, 33_000L), songs(25_000))
        assertEquals(generation, r.pc.resumptionAuthority())
        r.quiesce()
        assertEquals(20_000, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("20001", r.engine.currentPlayer.currentMediaItem?.mediaId)
        assertTrue("at most the one re-anchor of the jump itself (${counters.playlistChanges})", counters.playlistChanges <= 1)
    }

    @Test fun aQueryWhileNextIsReadyKeepsNextReady() {
        val r = rig()
        r.start(songs(25_000), 12_000)
        r.prepareNext()
        val before = Observed(r)
        val counters = Counters(r)
        val cancels = IntArray(1)
        val f = handler(r, snapshot(25_000, 12_345), songs(25_000), crossfadeCancels = cancels).resume(false) { error("no transport on a query") }
        await(f)
        assertEquals(0, cancels[0])
        assertUntouched(r, before, counters, "NEXT ready")
        assertTrue(r.engine.nextPreparation.state is NextSlotState.Ready)
        assertTrue(r.promoteAndSettle() is PromotionOutcome.Completed)
    }

    @Test fun aQueryFollowedByALaterExplicitPlaybackRequestAdoptsOnlyThePlaybackRequest() {
        val r = rig()
        val snap = snapshot(25_000, 12_345, 33_000L)
        val q = songs(25_000)
        query(r, snap, q)
        assertTrue(r.pc.nowPlayingState.value.queue.isEmpty())
        val generation = r.pc.resumptionAuthority()
        val cancels = IntArray(1)
        val f = handler(r, snap, q, crossfadeCancels = cancels).resume(true) { error("bounded plans carry no Media3 transport") }
        await(f)
        val result = f.get()
        mediaApplies(r, result, isForPlayback = true)
        r.quiesce()
        assertTrue("adoption bumps the generation exactly because playback was requested", r.pc.resumptionAuthority() > generation)
        assertEquals(1, cancels[0])
        assertEquals(25_000, r.pc.nowPlayingState.value.queue.size)
        assertEquals(12_345, r.pc.nowPlayingState.value.currentIndex)
        assertTrue(r.engine.currentPlayer.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertNotNull(r.adapter.active)
        assertEquals(25_000, r.controller.mediaItemCount)
    }

    // ── latest wins ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aPlaybackRequestWhoseMappingCompletesAfterANewerSelectionIsDropped() {
        val r = rig()
        val gate = CompletableDeferred<Unit>()
        val cancels = IntArray(1)
        val f = handler(r, snapshot(25_000, 12_345, 33_000L), songs(25_000), gate, cancels).resume(true) { error("dropped") }
        // While the saved session is still being loaded and mapped, the user starts something else.
        r.pc.playFromQueue(songs(5_000), 3_000)
        r.quiesce()
        gate.complete(Unit)
        await(f)
        assertTrue(f.isDone)
        assertTrue("the stale resumption fails instead of installing", runCatching { f.get() }.isFailure)
        assertEquals("a dropped resumption never cancels the newer selection's crossfade", 0, cancels[0])
        assertEquals(5_000, r.pc.nowPlayingState.value.queue.size)
        assertEquals(3_000, r.pc.nowPlayingState.value.currentIndex)
        assertEquals("3001", r.engine.currentPlayer.currentMediaItem?.mediaId)
    }

    // ── Full mode ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fullModeQueryAndAdoptionKeepTheirPreviousBehavior() {
        val r = rig(bounded = false)
        val q = songs(200)
        val snap = snapshot(200, 50, 7_000L)
        val before = Observed(r)
        val counters = Counters(r)
        val queryFuture = handler(r, snap, q).resume(false) { error("no transport on a query") }
        await(queryFuture)
        val queryResult = queryFuture.get()
        assertEquals("a Full query still returns the whole queue", 200, queryResult.mediaItems.size)
        assertEquals(50, queryResult.startIndex)
        assertEquals(7_000L, queryResult.startPositionMs)
        assertEquals(before.generation, r.pc.resumptionAuthority())
        assertEquals(0, counters.playlistChanges)

        var transport: PlaybackResumptionPlan? = null
        val playFuture = handler(r, snap, q).resume(true) { transport = it }
        await(playFuture)
        val playResult = playFuture.get()
        assertEquals(200, playResult.mediaItems.size)
        assertEquals(50, playResult.startIndex)
        assertNotNull("Full mode applies repeat/shuffle after adoption", transport)
        assertEquals(200, r.pc.nowPlayingState.value.queue.size)
    }

    @Test fun aQueryGeneratesNoMediaItemsForTheFullQueue() {
        val r = rig()
        val plan = (PlaybackResumptionMapper.map(snapshot(25_000, 7, 1_000L), ResumeBehaviorSettings(), songs(25_000), boundedModeFor = r.pc::boundedModeForResumption)
            as PlaybackResumptionResult.Ready).plan
        assertTrue(plan.bounded)
        assertTrue(plan.mediaItems.isEmpty())
        val result = plan.toQueryResult()
        assertEquals(listOf<MediaItem>(), result.mediaItems.drop(1))
    }
}

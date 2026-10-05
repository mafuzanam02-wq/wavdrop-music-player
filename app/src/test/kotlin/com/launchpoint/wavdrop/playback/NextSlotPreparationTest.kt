package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun nextSlotQueue(vararg ids: String): List<MediaItem> = ids.map { ScriptedPlayer.mediaItem(it) }

internal fun nextSlotKey(to: Int, from: Int = 0, generation: Long = 1L) = CrossfadeTransitionKey(generation, from, to)

/**
 * CF-2M4: the preparation owner's state machine, observed on a scripted NEXT physical (real [PlayerEngine], real façade,
 * real focus manager). Occurrence identity, idempotency, supersession, stale callbacks, failure and "never starts B".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class NextSlotPreparationTest {

    private val queue = nextSlotQueue("A", "B", "C", "D", "E")

    private fun PlayerEngineFixture.request(key: CrossfadeTransitionKey, q: List<MediaItem> = queue, repeat: Int = Player.REPEAT_MODE_OFF) =
        engine.nextPreparation.request(NextSlotRequest(key, q, repeat))

    // ── 1-4 ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun nextBeginsEmptyAndIdle() {
        val f = PlayerEngineFixture()
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        assertEquals(0, f.p2.mediaItemCount)
        assertEquals(Player.STATE_IDLE, f.p2.playbackState)
    }

    @Test fun requestLoadsExactlyTheTargetFirstPausedAndPreparesIt() {
        val f = PlayerEngineFixture()
        val state = f.request(nextSlotKey(to = 3))
        assertTrue(state is NextSlotState.PreparingTarget)
        assertEquals(listOf("D"), f.p2.mediaIds)
        assertFalse(f.p2.playWhenReady)
        assertEquals(listOf("P2.setMediaItems(1)", "P2.prepare()"), f.p2.commands)
        assertEquals(Player.STATE_BUFFERING, f.p2.playbackState)
        assertEquals("not yet READY: nothing may be grafted", 1, f.p2.mediaItemCount)
    }

    @Test fun readyTriggersGraftAndReachesReady() {
        val f = PlayerEngineFixture()
        f.request(nextSlotKey(to = 3))
        f.p2.becomeReady(); idleMainLooper()
        val ready = f.engine.nextPreparation.state as NextSlotState.Ready
        assertEquals(nextSlotKey(3), ready.key)
        assertEquals(listOf("A", "B", "C", "D", "E"), f.p2.mediaIds)
        assertEquals(3, f.p2.currentMediaItemIndex)
        assertEquals(1, f.engine.nextPreparation.graftsCompleted)
    }

    // ── 13/14 idempotency, 15/16 supersession ────────────────────────────────────────────────────────────────────────────

    @Test fun sameKeyWhilePreparingIsIdempotent() {
        val f = PlayerEngineFixture()
        val first = f.request(nextSlotKey(2))
        f.p2.commands.clear()
        val second = f.request(nextSlotKey(2))
        assertEquals(first, second)
        assertTrue("no second prepare, no second setMediaItems", f.p2.commands.isEmpty())
        assertEquals(1, f.engine.nextPreparation.preparationsStarted)
    }

    @Test fun sameKeyWhileReadyIsIdempotent() {
        val f = PlayerEngineFixture()
        f.request(nextSlotKey(2)); f.p2.becomeReady(); idleMainLooper()
        val ready = f.engine.nextPreparation.state
        f.p2.commands.clear()
        assertEquals(ready, f.request(nextSlotKey(2)))
        assertTrue(f.p2.commands.isEmpty())
        assertEquals(1, f.engine.nextPreparation.graftsCompleted)
    }

    @Test fun newerKeySupersedesAnOlderPreparingRequestWithAFreshToken() {
        val f = PlayerEngineFixture()
        val first = f.request(nextSlotKey(2)) as NextSlotState.PreparingTarget
        val second = f.request(nextSlotKey(3, from = 1)) as NextSlotState.PreparingTarget
        assertNotEquals(first.token, second.token)
        assertEquals(listOf("D"), f.p2.mediaIds)
        f.p2.becomeReady(); idleMainLooper()
        assertEquals(nextSlotKey(3, from = 1), (f.engine.nextPreparation.state as NextSlotState.Ready).key)
        assertEquals(3, f.p2.currentMediaItemIndex)
    }

    @Test fun newerKeySupersedesAnOlderReadyRequest() {
        val f = PlayerEngineFixture()
        f.request(nextSlotKey(2)); f.p2.becomeReady(); idleMainLooper()
        val oldReady = f.engine.nextPreparation.state as NextSlotState.Ready
        val state = f.request(nextSlotKey(4, from = 3)) as NextSlotState.PreparingTarget
        assertNotEquals(oldReady.token, state.token)
        assertEquals("the old graft was torn down; NEXT holds only the new target", listOf("E"), f.p2.mediaIds)
        f.p2.becomeReady(); idleMainLooper()
        assertEquals(4, f.p2.currentMediaItemIndex)
        assertEquals(5, f.p2.mediaItemCount)
    }

    // ── 17/18 stale callbacks ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun staleReadyCallbackCannotCompleteANewerRequest() {
        val f = PlayerEngineFixture()
        val first = f.request(nextSlotKey(2)) as NextSlotState.PreparingTarget
        val second = f.request(nextSlotKey(3, from = 1)) as NextSlotState.PreparingTarget
        // Request 1's late READY callback arrives while request 2 owns NEXT.
        f.engine.nextPreparation.onPhysicalReady(first.token)
        assertEquals("a stale token must not complete anything", second, f.engine.nextPreparation.state)
        assertEquals(1, f.p2.mediaItemCount)
        // Request 2's own READY then completes it normally.
        f.p2.becomeReady(); idleMainLooper()
        assertTrue(f.engine.nextPreparation.state is NextSlotState.Ready)
        assertEquals(second.token, (f.engine.nextPreparation.state as NextSlotState.Ready).token)
    }

    @Test fun staleErrorCallbackCannotFailANewerRequest() {
        val f = PlayerEngineFixture()
        val first = f.request(nextSlotKey(2)) as NextSlotState.PreparingTarget
        val second = f.request(nextSlotKey(3, from = 1)) as NextSlotState.PreparingTarget
        f.engine.nextPreparation.onPhysicalError(first.token)
        assertEquals(second, f.engine.nextPreparation.state)
    }

    @Test fun readyAfterInvalidationDoesNothing() {
        val f = PlayerEngineFixture()
        val preparing = f.request(nextSlotKey(2)) as NextSlotState.PreparingTarget
        f.engine.nextPreparation.invalidate(CrossfadeCancelReason.Seek)
        f.engine.nextPreparation.onPhysicalReady(preparing.token)
        f.engine.nextPreparation.onPhysicalError(preparing.token)
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        assertEquals(0, f.p2.mediaItemCount)
    }

    @Test fun aReadyThatRacedANewerRequestIsIgnoredUntilTheLivePlayerAgrees() {
        val f = PlayerEngineFixture()
        val preparing = f.request(nextSlotKey(2)) as NextSlotState.PreparingTarget
        // Token matches but NEXT is still BUFFERING (a READY that raced a re-request): live facts disagree -> ignored.
        f.engine.nextPreparation.onPhysicalReady(preparing.token)
        assertEquals(preparing, f.engine.nextPreparation.state)
    }

    // ── 19/20 failure ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun targetPreparationFailureLeavesCurrentUntouchedAndSurfacesNoLogicalError() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        f.events.events.clear()
        f.p1.commands.clear()
        val queueBefore = f.p1.mediaIds
        f.request(nextSlotKey(1))
        f.p2.failWith(); idleMainLooper()
        val failed = f.engine.nextPreparation.state as NextSlotState.Failed
        assertEquals(NextSlotFailure.PrepareError, failed.reason)
        assertEquals(nextSlotKey(1), failed.key)
        // CURRENT: untouched. No logical error, no seek, no queue change, still playing.
        assertTrue(f.p1.commands.isEmpty())
        assertTrue(f.events.events.none { it.startsWith("error") })
        assertTrue(f.facade.isPlaying)
        assertEquals(queueBefore, f.p1.mediaIds)
        assertSame(f.p1, f.facade.delegatePlayer)
        // NEXT: inert again.
        assertEquals(0, f.p2.mediaItemCount)
        assertEquals(Player.STATE_IDLE, f.p2.playbackState)
        assertFalse(f.p2.playWhenReady)
    }

    @Test fun failureIsTransitionLocalAndNotRetriedForTheSameKeyButNewKeyStartsFresh() {
        val f = PlayerEngineFixture()
        f.request(nextSlotKey(1)); f.p2.failWith()
        f.p2.commands.clear()
        assertTrue(f.request(nextSlotKey(1)) is NextSlotState.Failed)
        assertTrue("same key is not retried (no storm)", f.p2.commands.isEmpty())
        assertTrue(f.request(nextSlotKey(2, from = 1)) is NextSlotState.PreparingTarget)
        f.p2.becomeReady(); idleMainLooper()
        assertTrue(f.engine.nextPreparation.state is NextSlotState.Ready)
    }

    @Test fun endedBeforeReadyFailsClosed() {
        val f = PlayerEngineFixture()
        f.request(nextSlotKey(1))
        f.p2.mutate { setPlaybackState(Player.STATE_ENDED) }
        idleMainLooper()
        assertEquals(NextSlotFailure.EndedBeforeReady, (f.engine.nextPreparation.state as NextSlotState.Failed).reason)
    }

    // ── invalidation result ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun invalidationReturnsNextToInertAndLeavesEverythingElseAlone() {
        val f = PlayerEngineFixture()
        f.facade.play(); idleMainLooper()
        f.request(nextSlotKey(3), repeat = Player.REPEAT_MODE_ALL); f.p2.becomeReady(); idleMainLooper()
        f.p1.commands.clear()
        f.events.events.clear()
        f.engine.nextPreparation.invalidate(CrossfadeCancelReason.QueueMutation)
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        assertEquals(0, f.p2.mediaItemCount)
        assertEquals(Player.STATE_IDLE, f.p2.playbackState)
        assertFalse(f.p2.playWhenReady)
        assertEquals(Player.REPEAT_MODE_OFF, f.p2.repeatMode)
        assertEquals(CrossfadeCancelReason.QueueMutation, f.engine.nextPreparation.lastInvalidationReason)
        assertTrue("CURRENT untouched", f.p1.commands.isEmpty())
        assertTrue("no logical event from NEXT teardown", f.events.events.isEmpty())
        assertSame(f.p1, f.facade.delegatePlayer)
        assertEquals(PlayerSlotRole.NEXT, f.engine.roleOf(f.engine.nextSlot))
    }

    @Test fun invalidatingWhenIdleDoesNothing() {
        val f = PlayerEngineFixture()
        f.engine.nextPreparation.invalidate(CrossfadeCancelReason.Seek)
        assertEquals(0, f.engine.nextPreparation.invalidations)
        assertTrue(f.p2.commands.isEmpty())
    }

    // ── never starts, never touches focus / session / volume ─────────────────────────────────────────────────────────────

    @Test fun nextIsNeverStartedNeverRequestsFocusAndKeepsSessionAndVolume() {
        val f = PlayerEngineFixture()
        val audio = org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        f.request(nextSlotKey(3)); f.p2.becomeReady(); idleMainLooper()
        assertTrue(f.engine.nextPreparation.state is NextSlotState.Ready)
        assertFalse(f.p2.playWhenReady)
        assertFalse(f.p2.isPlaying)
        assertEquals(1f, f.p2.volume, 0f)
        assertEquals(null, org.robolectric.Shadows.shadowOf(audio).lastAudioFocusRequest)
        assertEquals(SHARED_SESSION_ID, f.p2.audioSessionId)
        assertEquals(SHARED_SESSION_ID, f.p1.audioSessionId)
        assertEquals(SHARED_SESSION_ID, f.engine.audioSessionId)
        assertFalse(f.p2.commands.any { it.contains("setPlayWhenReady(true)") || it.contains("seek") })
        assertSame(f.p1, f.facade.delegatePlayer)
        assertSame(f.p2, f.engine.nextPlayer)
    }

    @Test fun nextPhysicalEventsNeverReachLogicalListeners() {
        val f = PlayerEngineFixture()
        f.events.events.clear()
        f.request(nextSlotKey(3))
        f.p2.becomeReady()
        f.p2.mutate { setPlaybackState(Player.STATE_BUFFERING) }
        f.p2.mutate { setPlaybackState(Player.STATE_READY) }
        f.p2.failWith(PlaybackException.ERROR_CODE_DECODING_FAILED)
        idleMainLooper()
        assertTrue("NEXT events must be physical-only: ${f.events.events}", f.events.events.isEmpty())
    }

    @Test fun releaseDetachesTheObserverAndOwnsNothing() {
        val f = PlayerEngineFixture()
        val preparing = f.request(nextSlotKey(2)) as NextSlotState.PreparingTarget
        f.engine.release()
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        f.engine.nextPreparation.onPhysicalReady(preparing.token)
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        assertTrue(f.engine.nextPreparation.request(NextSlotRequest(nextSlotKey(1), queue)) is NextSlotState.Idle)
    }

    @Test fun roleSwapSeamEndsPreparationBeforeTheRolesMove() {
        val f = PlayerEngineFixture()
        f.request(nextSlotKey(2)); f.p2.becomeReady()
        f.engine.swapRolesForTest()
        assertEquals(NextSlotState.Idle, f.engine.nextPreparation.state)
        assertEquals("the preparing physical was reset before it became CURRENT", 0, f.p2.mediaItemCount)
    }
}

package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared cancellation family that survives CF-2M8: each `recoverCrossfadeFrom...` hook notifies the one sink exactly once with
 * the documented reason (a null sink is harmless), the pure should-cancel decisions, the live-ownership check and the physical
 * interruption classification. What an overlap DOES with each reason is covered by CrossfadeOverlapInterruptionTest.
 */
class CrossfadeCancellationTest {

    private class Recorder : CrossfadeCancelSink {
        val reasons = mutableListOf<CrossfadeCancelReason>()
        override fun cancel(reason: CrossfadeCancelReason) { reasons += reason }
    }

    private val hooks: List<Pair<(CrossfadeCancelSink?) -> Unit, CrossfadeCancelReason>> = listOf(
        ::recoverCrossfadeFromPrimaryPlaybackError to CrossfadeCancelReason.PlaybackError,
        ::recoverCrossfadeFromExplicitPause to CrossfadeCancelReason.Pause,
        ::recoverCrossfadeFromExplicitSeek to CrossfadeCancelReason.Seek,
        ::recoverCrossfadeFromExplicitNavigation to CrossfadeCancelReason.ManualNavigation,
        ::recoverCrossfadeFromRepeatChange to CrossfadeCancelReason.RepeatChanged,
        ::recoverCrossfadeFromShuffleChange to CrossfadeCancelReason.ShuffleChanged,
        ::recoverCrossfadeFromPlayNextMutation to CrossfadeCancelReason.QueueMutation,
        ::recoverCrossfadeFromAddToQueueMutation to CrossfadeCancelReason.QueueMutation,
        ::recoverCrossfadeFromQueueReorder to CrossfadeCancelReason.QueueMutation,
        ::recoverCrossfadeFromQueueRemoval to CrossfadeCancelReason.QueueMutation,
        ::recoverCrossfadeFromLibraryDeletion to CrossfadeCancelReason.QueueMutation,
        ::recoverCrossfadeFromQueueReplacement to CrossfadeCancelReason.QueueMutation,
        ::recoverCrossfadeFromPlaybackResumption to CrossfadeCancelReason.QueueMutation,
        ::recoverCrossfadeFromPrimaryTerminalState to CrossfadeCancelReason.PrimaryPlaybackTerminated,
        ::recoverCrossfadeFromControllerDisconnected to CrossfadeCancelReason.ControllerDisconnected,
        ::recoverCrossfadeFromPrimaryInterruption to CrossfadeCancelReason.Pause,
        ::recoverCrossfadeFromEqualizerEnabled to CrossfadeCancelReason.PlanInvalidated,
    )

    @Test fun everyHookNotifiesTheSinkExactlyOnceWithItsReasonAndANullSinkIsHarmless() {
        for ((hook, reason) in hooks) {
            val sink = Recorder()
            hook(sink)
            assertEquals(listOf(reason), sink.reasons)
            hook(null)
        }
    }

    @Test fun theNamedReasonSeamsMatchTheirHooks() {
        assertEquals(CrossfadeCancelReason.RepeatChanged, REPEAT_CHANGE_CANCEL_REASON)
        assertEquals(CrossfadeCancelReason.ShuffleChanged, SHUFFLE_CHANGE_CANCEL_REASON)
        listOf(PLAY_NEXT_MUTATION_CANCEL_REASON, ADD_TO_QUEUE_MUTATION_CANCEL_REASON, QUEUE_REORDER_CANCEL_REASON, QUEUE_REMOVAL_CANCEL_REASON,
            LIBRARY_DELETION_CANCEL_REASON, QUEUE_REPLACEMENT_CANCEL_REASON, PLAYBACK_RESUMPTION_CANCEL_REASON)
            .forEach { assertEquals(CrossfadeCancelReason.QueueMutation, it) }
        assertEquals(CrossfadeCancelReason.PrimaryPlaybackTerminated, PRIMARY_TERMINAL_STATE_CANCEL_REASON)
        assertEquals(CrossfadeCancelReason.ControllerDisconnected, CONTROLLER_DISCONNECTED_CANCEL_REASON)
        assertEquals(CrossfadeCancelReason.Pause, PRIMARY_INTERRUPTION_CANCEL_REASON)
    }

    @Test fun onlyAnAdoptedReadyResumptionCancels() {
        assertTrue(shouldCancelCrossfadeForPlaybackResumption(resultReady = true, isForPlayback = true))
        assertFalse(shouldCancelCrossfadeForPlaybackResumption(resultReady = true, isForPlayback = false))
        assertFalse(shouldCancelCrossfadeForPlaybackResumption(resultReady = false, isForPlayback = true))
        assertFalse(shouldCancelCrossfadeForPlaybackResumption(resultReady = false, isForPlayback = false))
    }

    @Test fun onlyAnEqualizerOffToOnChangeCancels() {
        assertTrue(shouldCancelCrossfadeForEqualizerChange(previousEnabled = false, newEnabled = true))
        assertFalse(shouldCancelCrossfadeForEqualizerChange(previousEnabled = true, newEnabled = false))
        assertFalse(shouldCancelCrossfadeForEqualizerChange(previousEnabled = true, newEnabled = true))
        assertFalse(shouldCancelCrossfadeForEqualizerChange(previousEnabled = false, newEnabled = false))
    }

    @Test fun onlyIdleAndEndedAreTerminalPlaybackStates() {
        assertTrue(isPrimaryTerminalPlaybackState(Player.STATE_IDLE))
        assertTrue(isPrimaryTerminalPlaybackState(Player.STATE_ENDED))
        assertFalse(isPrimaryTerminalPlaybackState(Player.STATE_BUFFERING))
        assertFalse(isPrimaryTerminalPlaybackState(Player.STATE_READY))
    }

    // ── live ownership ───────────────────────────────────────────────────────────────────────────────────────────────────

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val key = CrossfadeTransitionKey(5L, 1, 2)
    private fun snapshot(
        generation: Long = 5L, index: Int = 1, repeat: RepeatMode = RepeatMode.OFF, playing: Boolean = true, connected: Boolean = true,
        external: Boolean = false, dirty: Boolean = false, eq: Boolean = false, queue: List<Song> = (1L..4L).map(::song),
    ) = CrossfadeRuntimeSnapshot(
        queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = index, repeatMode = repeat, shuffleEnabled = false,
        isPlaying = playing, isExternalPlayback = external, playerQueueNeedsSync = dirty, controllerConnected = connected, equalizerEnabled = eq,
    )

    @Test fun anUnchangedLiveStateStillOwnsTheKey() {
        assertNull(crossfadeOwnershipLossReason(snapshot(), key))
    }

    @Test fun eachLostFactYieldsItsReason() {
        assertEquals(CrossfadeCancelReason.ControllerDisconnected, crossfadeOwnershipLossReason(snapshot(connected = false), key))
        assertEquals(CrossfadeCancelReason.ExternalPlayback, crossfadeOwnershipLossReason(snapshot(external = true), key))
        assertEquals(CrossfadeCancelReason.QueueBecameDirty, crossfadeOwnershipLossReason(snapshot(dirty = true), key))
        assertEquals(CrossfadeCancelReason.Pause, crossfadeOwnershipLossReason(snapshot(playing = false), key))
        assertEquals(CrossfadeCancelReason.PlanInvalidated, crossfadeOwnershipLossReason(snapshot(eq = true), key))
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snapshot(generation = 6L), key))
        assertEquals(CrossfadeCancelReason.ManualNavigation, crossfadeOwnershipLossReason(snapshot(index = 2), key))
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snapshot(queue = (1L..2L).map(::song)), key))
    }

    @Test fun repeatOneOrAWrapTargetChangeLosesOwnershipButHarmlessRepeatChangesDoNot() {
        assertEquals(CrossfadeCancelReason.RepeatChanged, crossfadeOwnershipLossReason(snapshot(repeat = RepeatMode.ONE), key))
        assertNull("repeat ALL leaves a mid-queue target unchanged", crossfadeOwnershipLossReason(snapshot(repeat = RepeatMode.ALL), key))
        val wrap = CrossfadeTransitionKey(5L, 3, 0)
        assertNull(crossfadeOwnershipLossReason(snapshot(index = 3, repeat = RepeatMode.ALL), wrap))
        assertEquals(CrossfadeCancelReason.RepeatChanged, crossfadeOwnershipLossReason(snapshot(index = 3, repeat = RepeatMode.OFF), wrap))
    }

    @Test fun duplicateSongsDoNotAffectOccurrenceBasedOwnership() {
        val duplicates = listOf(song(1), song(2), song(1), song(2))
        assertNull(crossfadeOwnershipLossReason(snapshot(queue = duplicates), key))
        assertEquals(CrossfadeCancelReason.ManualNavigation, crossfadeOwnershipLossReason(snapshot(queue = duplicates, index = 3), key))
    }
}

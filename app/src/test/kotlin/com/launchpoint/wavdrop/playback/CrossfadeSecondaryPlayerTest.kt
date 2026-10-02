package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeSecondaryPlayerTest {

    /** Records everything the owner asks of the backend. There is deliberately no play(). */
    private class FakeBackend : SecondaryPlayerBackend {
        data class Prepared(val attempt: Long, val item: MediaItem)

        val prepared = mutableListOf<Prepared>()
        val events = mutableListOf<String>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var releases = 0

        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += Prepared(attempt, item)
            events += "prepare"
            this.callbacks = callbacks
        }

        override fun reset() { resets++; events += "reset" }
        override fun release() { releases++ }
        override fun setGain(gain: Float): Boolean = true

        val starts = mutableListOf<Float>()
        var startResult = true
        override fun start(initialGain: Float): Boolean { starts += initialGain; events += "start"; return startResult }

        fun ready(attempt: Long, durationMs: Long = 180_000L) = callbacks!!.onReady(attempt, durationMs)
        fun error(attempt: Long) = callbacks!!.onError(attempt)
    }

    private class RecordingListener : CrossfadeSecondaryListener {
        val readies = mutableListOf<Pair<CrossfadeTransitionKey, Long>>()
        val failures = mutableListOf<CrossfadeTransitionKey>()
        override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) {
            readies += key to preparedDurationMs
        }
        override fun onSecondaryFailed(key: CrossfadeTransitionKey) { failures += key }
    }

    private val backend = FakeBackend()
    private val listener = RecordingListener()
    private var factoryCalls = 0
    private val owner = CrossfadeSecondaryPlayer(
        backendFactory = { factoryCalls++; backend },
        listener = listener,
    )

    private val keyA = CrossfadeTransitionKey(1L, 0, 1)
    private val keyB = CrossfadeTransitionKey(1L, 1, 2)

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun attemptOf(index: Int) = backend.prepared[index].attempt

    @Test fun gateDefaultsToFalse() {
        assertFalse(PlaybackService.CROSSFADE_SECONDARY_RUNTIME_ENABLED)
    }

    @Test fun backendIsNotCreatedUntilFirstPrepare() {
        assertEquals(0, factoryCalls)
        assertNull(owner.currentKey)
    }

    @Test fun firstPrepareBindsExactKeyAndLoadsOneCanonicalItem() {
        assertTrue(owner.prepare(keyA, song(7)))
        assertEquals(keyA, owner.currentKey)
        assertEquals(1, backend.prepared.size)
        val item = backend.prepared.single().item
        val expected = song(7).toPlaybackMediaItem()
        assertEquals(expected.mediaId, item.mediaId)
        assertEquals(expected.localConfiguration?.uri, item.localConfiguration?.uri)
        assertEquals(expected.mediaMetadata.title, item.mediaMetadata.title)
        assertEquals(1, factoryCalls)
    }

    @Test fun prepareNeverResetsOrReleasesOnItsOwnAndReportsNothingUntilReady() {
        owner.prepare(keyA, song(1))
        assertEquals(0, backend.resets)
        assertEquals(0, backend.releases)
        assertTrue(listener.readies.isEmpty())
        assertTrue(listener.failures.isEmpty())
    }

    @Test fun currentReadyEmitsOnceWithActualDuration() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0), 123_456L)
        assertEquals(listOf(keyA to 123_456L), listener.readies)
        assertEquals(keyA, owner.currentKey)
        assertTrue(listener.failures.isEmpty())
    }

    @Test fun unknownOrNonPositiveDurationFailsClosed() {
        for (d in listOf(0L, -1L, Long.MIN_VALUE)) {
            val b = FakeBackend()
            val l = RecordingListener()
            val o = CrossfadeSecondaryPlayer({ b }, l)
            o.prepare(keyA, song(1))
            b.ready(b.prepared.single().attempt, d)
            assertEquals(listOf(keyA), l.failures)
            assertTrue(l.readies.isEmpty())
            assertEquals(1, b.resets)
            assertNull(o.currentKey)
            b.ready(b.prepared.single().attempt, 100L)
            b.error(b.prepared.single().attempt)
            assertEquals(listOf(keyA), l.failures)
            assertTrue(l.readies.isEmpty())
        }
    }

    @Test fun currentFailureEmitsOnce() {
        owner.prepare(keyA, song(1))
        backend.error(attemptOf(0))
        backend.error(attemptOf(0))
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
        assertTrue(listener.readies.isEmpty())
        assertNull(owner.currentKey)
    }

    @Test fun prepareBSupersedesA() {
        owner.prepare(keyA, song(1))
        owner.prepare(keyB, song(2))
        assertEquals(keyB, owner.currentKey)
        assertEquals(2, backend.prepared.size)
        assertEquals(1, factoryCalls)
        assertTrue(attemptOf(1) > attemptOf(0))
    }

    @Test fun lateReadyFromAAfterBIsIgnored() {
        owner.prepare(keyA, song(1))
        owner.prepare(keyB, song(2))
        backend.ready(attemptOf(0))
        assertTrue(listener.readies.isEmpty())
        backend.ready(attemptOf(1))
        assertEquals(listOf(keyB to 180_000L), listener.readies)
    }

    @Test fun lateFailureFromAAfterBIsIgnored() {
        owner.prepare(keyA, song(1))
        owner.prepare(keyB, song(2))
        backend.error(attemptOf(0))
        assertTrue(listener.failures.isEmpty())
        // B is untouched and can still become ready.
        backend.ready(attemptOf(1))
        assertEquals(1, listener.readies.size)
    }

    @Test fun staleAbandonDoesNotDestroyActive() {
        owner.prepare(keyA, song(1))
        owner.prepare(keyB, song(2))
        assertFalse(owner.abandon(keyA))
        assertEquals(keyB, owner.currentKey)
        assertEquals(0, backend.resets)
        backend.ready(attemptOf(1))
        assertEquals(1, listener.readies.size)
    }

    @Test fun matchingAbandonClearsAndResets() {
        owner.prepare(keyB, song(2))
        assertTrue(owner.abandon(keyB))
        assertNull(owner.currentKey)
        assertEquals(1, backend.resets)
        assertEquals(0, backend.releases)
    }

    @Test fun callbackAfterAbandonIsIgnored() {
        owner.prepare(keyA, song(1))
        owner.abandon(keyA)
        backend.ready(attemptOf(0))
        backend.error(attemptOf(0))
        assertTrue(listener.readies.isEmpty())
        assertTrue(listener.failures.isEmpty())
    }

    @Test fun duplicateAbandonIsHarmless() {
        owner.prepare(keyA, song(1))
        assertTrue(owner.abandon(keyA))
        assertFalse(owner.abandon(keyA))
        assertEquals(1, backend.resets)
    }

    @Test fun preparableAgainAfterAbandon() {
        owner.prepare(keyA, song(1))
        owner.abandon(keyA)
        assertTrue(owner.prepare(keyB, song(2)))
        backend.ready(attemptOf(1))
        assertEquals(listOf(keyB to 180_000L), listener.readies)
    }

    @Test fun duplicateReadyIsIgnored() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        backend.ready(attemptOf(0))
        assertEquals(1, listener.readies.size)
    }

    @Test fun readyThenFailureDeliversBothAndDropsOwnership() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        backend.error(attemptOf(0))
        assertEquals(1, listener.readies.size)
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
        assertNull(owner.currentKey)
        assertFalse(owner.start(keyA, 0f))
    }

    @Test fun startedThenFailureDeliversFailureOnceAndResets() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertTrue(owner.start(keyA, 0f))
        backend.error(attemptOf(0))
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
        assertNull(owner.currentKey)
        assertFalse(owner.start(keyA, 0f))
        assertEquals(1, backend.starts.size)
    }

    @Test fun duplicateFailureAfterReadyAndStartIsIgnored() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        owner.start(keyA, 0f)
        backend.error(attemptOf(0))
        backend.error(attemptOf(0))
        assertEquals(1, listener.failures.size)
        assertEquals(1, backend.resets)
    }

    @Test fun lateReadyAfterPostReadyFailureDoesNotResurrect() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        backend.error(attemptOf(0))
        backend.ready(attemptOf(0))
        assertEquals(1, listener.readies.size)
        assertNull(owner.currentKey)
        assertFalse(owner.start(keyA, 0f))
    }

    @Test fun supersededPreparedAttemptLateErrorLeavesNewOwnerUnaffected() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        owner.prepare(keyB, song(2))
        backend.error(attemptOf(0)) // late error of A
        assertTrue(listener.failures.isEmpty())
        assertEquals(keyB, owner.currentKey)
        backend.ready(attemptOf(1))
        assertTrue(owner.start(keyB, 0f))
    }

    @Test fun supersedingAStartedSecondaryStopsItBeforePreparingTheNext() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertTrue(owner.start(keyA, 0.2f))
        owner.prepare(keyB, song(2))
        // A is reset (stopped/cleared) BEFORE B is prepared; ownership never moves while A keeps playing.
        assertEquals(listOf("prepare", "start", "reset", "prepare"), backend.events)
        assertEquals(keyB, owner.currentKey)
        assertFalse(owner.start(keyA, 0f)) // A's authority is gone
        backend.error(attemptOf(0)) // late A error
        backend.ready(attemptOf(0)) // late A ready
        assertTrue(listener.failures.isEmpty())
        assertEquals(1, listener.readies.size)
        backend.ready(attemptOf(1))
        assertTrue(owner.start(keyB, 0.3f))
    }

    @Test fun supersedingAPreparedButNotStartedSecondaryDoesNotNeedAReset() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        owner.prepare(keyB, song(2))
        assertEquals(0, backend.resets) // the real backend silences on prepare; nothing was playing
    }

    @Test fun readyIsAcceptedOnlyWhilePreparing() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        backend.ready(attemptOf(0))
        assertEquals(1, listener.readies.size)
    }

    @Test fun failureThenReadyIgnoresReady() {
        owner.prepare(keyA, song(1))
        backend.error(attemptOf(0))
        backend.ready(attemptOf(0))
        assertEquals(1, listener.failures.size)
        assertTrue(listener.readies.isEmpty())
    }

    @Test fun lateReadyAndErrorAfterTerminalFailureAreIgnored() {
        owner.prepare(keyA, song(1))
        backend.error(attemptOf(0))
        backend.ready(attemptOf(0))
        backend.error(attemptOf(0))
        assertEquals(listOf(keyA), listener.failures)
        assertTrue(listener.readies.isEmpty())
        assertNull(owner.currentKey)
        assertEquals(1, backend.resets)
    }

    @Test fun newPreparationAfterFailureBindsAndBecomesReady() {
        owner.prepare(keyA, song(1))
        backend.error(attemptOf(0))
        assertTrue(owner.prepare(keyB, song(2)))
        assertEquals(keyB, owner.currentKey)
        backend.ready(attemptOf(1), 99_000L)
        assertEquals(listOf(keyB to 99_000L), listener.readies)
        assertEquals(keyB, owner.currentKey)
    }

    @Test fun failureListenerStartingNewPreparationIsNotWiped() {
        val b = FakeBackend()
        lateinit var o: CrossfadeSecondaryPlayer
        val failures = mutableListOf<CrossfadeTransitionKey>()
        val l = object : CrossfadeSecondaryListener {
            override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) = Unit
            override fun onSecondaryFailed(key: CrossfadeTransitionKey) {
                failures += key
                o.prepare(keyB, song(2))
            }
        }
        o = CrossfadeSecondaryPlayer({ b }, l)
        o.prepare(keyA, song(1))
        b.error(b.prepared[0].attempt)
        assertEquals(listOf(keyA), failures)
        assertEquals(keyB, o.currentKey)
        assertEquals(1, b.resets) // reset happened before B started, not after
        b.error(b.prepared[0].attempt) // late A callback ignored
        assertEquals(listOf(keyA), failures)
        b.ready(b.prepared[1].attempt)
        assertEquals(keyB, o.currentKey)
    }

    @Test fun releaseInvalidatesActivePreparationAndReleasesBackend() {
        owner.prepare(keyA, song(1))
        owner.release()
        assertNull(owner.currentKey)
        assertEquals(1, backend.releases)
    }

    @Test fun callbackAfterReleaseIsIgnored() {
        owner.prepare(keyA, song(1))
        owner.release()
        backend.ready(attemptOf(0))
        backend.error(attemptOf(0))
        assertTrue(listener.readies.isEmpty())
        assertTrue(listener.failures.isEmpty())
    }

    @Test fun repeatedReleaseIsHarmless() {
        owner.prepare(keyA, song(1))
        owner.release()
        owner.release()
        assertEquals(1, backend.releases)
    }

    @Test fun releaseWithoutPrepareNeverCreatesBackend() {
        owner.release()
        assertEquals(0, factoryCalls)
        assertEquals(0, backend.releases)
    }

    @Test fun prepareAfterReleaseDoesNotResurrect() {
        owner.prepare(keyA, song(1))
        owner.release()
        assertFalse(owner.prepare(keyB, song(2)))
        assertNull(owner.currentKey)
        assertEquals(1, backend.prepared.size)
        assertEquals(1, factoryCalls)
        assertFalse(owner.abandon(keyB))
    }

    @Test fun duplicateSongIdsDoNotAffectOwnership() {
        // Same song id at two positions: distinct positional keys remain distinct occurrences.
        val sameSong = song(42)
        val k1 = CrossfadeTransitionKey(3L, 0, 2)
        val k2 = CrossfadeTransitionKey(3L, 1, 3)
        owner.prepare(k1, sameSong)
        owner.prepare(k2, sameSong)
        assertEquals(k2, owner.currentKey)
        backend.ready(attemptOf(0))
        assertTrue(listener.readies.isEmpty())
        assertFalse(owner.abandon(k1))
        backend.ready(attemptOf(1))
        assertEquals(listOf(k2 to 180_000L), listener.readies)
    }

    @Test fun sameIndicesInNewerGenerationAreADifferentPreparation() {
        val old = CrossfadeTransitionKey(1L, 0, 1)
        val newer = CrossfadeTransitionKey(2L, 0, 1)
        owner.prepare(old, song(1))
        owner.prepare(newer, song(1))
        assertFalse(owner.abandon(old))
        backend.ready(attemptOf(0))
        assertTrue(listener.readies.isEmpty())
        backend.ready(attemptOf(1))
        assertEquals(newer, listener.readies.single().first)
    }
}

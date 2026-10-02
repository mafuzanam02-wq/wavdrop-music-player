package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeSecondaryStartTest {

    // -- Pure late-start progress / gain ------------------------------------------

    @Test fun progressIsLatenessOverDuration() {
        assertEquals(0.0f, initialCrossfadeProgress(0L, 6_000L)!!, 0f)
        assertEquals(0.25f, initialCrossfadeProgress(1_500L, 6_000L)!!, 0f)
        assertEquals(0.5f, initialCrossfadeProgress(3_000L, 6_000L)!!, 0f)
        assertEquals(1.0f, initialCrossfadeProgress(6_000L, 6_000L)!!, 0f)
    }

    @Test fun invalidProgressInputsFailClosed() {
        assertNull(initialCrossfadeProgress(-1L, 6_000L))
        assertNull(initialCrossfadeProgress(0L, 0L))
        assertNull(initialCrossfadeProgress(0L, -6_000L))
        assertNull(initialCrossfadeProgress(6_001L, 6_000L))
        assertNull(initialCrossfadeProgress(Long.MAX_VALUE, 6_000L))
    }

    @Test fun initialIncomingGainComesFromTheSingleEqualPowerCurve() {
        for (late in listOf(0L, 1_500L, 3_000L, 6_000L)) {
            val progress = initialCrossfadeProgress(late, 6_000L)!!
            assertEquals(CrossfadeGainCurve.equalPower(progress).incoming, initialIncomingGain(late, 6_000L)!!, 0f)
        }
        assertEquals(0f, initialIncomingGain(0L, 6_000L)!!, 0f)
        assertEquals(1f, initialIncomingGain(6_000L, 6_000L)!!, 1e-6f)
        // Equal-power, not linear: a quarter in is sin(pi/8) ~ 0.3827, not 0.25.
        assertEquals(0.3827f, initialIncomingGain(1_500L, 6_000L)!!, 1e-3f)
        assertNull(initialIncomingGain(7_000L, 6_000L))
    }

    @Test fun gainValidationRejectsNonFiniteAndOutOfRange() {
        for (g in listOf(0f, 0.5f, 1f)) assertTrue(isValidInitialGain(g))
        for (g in listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse("gain $g", isValidInitialGain(g))
        }
    }

    // -- Owner start authority ---------------------------------------------------

    private class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        val starts = mutableListOf<Float>()
        var startResult = true
        var onStart: (() -> Unit)? = null
        var resets = 0
        var releases = 0
        var order = mutableListOf<String>()

        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            order += "prepare"
            this.callbacks = callbacks
        }

        override fun start(initialGain: Float): Boolean {
            order += "start"
            starts += initialGain
            onStart?.invoke()
            return startResult
        }

        override fun reset() { resets++; order += "reset" }
        override fun release() { releases++; order += "release" }

        val gains = mutableListOf<Float>()
        var setGainResult = true
        var setGainThrows = false
        override fun setGain(gain: Float): Boolean {
            gains += gain
            if (setGainThrows) throw IllegalStateException("boom")
            return setGainResult
        }

        fun ready(attempt: Long, durationMs: Long = 180_000L) = callbacks!!.onReady(attempt, durationMs)
        fun error(attempt: Long) = callbacks!!.onError(attempt)
    }

    private val backend = FakeBackend()
    private val owner = CrossfadeSecondaryPlayer({ backend }, CrossfadeSecondaryListener.NoOp)
    private val keyA = CrossfadeTransitionKey(1L, 0, 1)
    private val keyB = CrossfadeTransitionKey(1L, 1, 2)

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private fun attemptOf(i: Int) = backend.prepared[i]

    @Test fun prepareAndReadyDoNotStart() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertTrue(backend.starts.isEmpty())
        assertFalse("start" in backend.order)
    }

    @Test fun startBeforePrepareIsRejected() {
        assertFalse(owner.start(keyA, 0.5f))
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun startWhilePreparingIsRejected() {
        owner.prepare(keyA, song(1))
        assertFalse(owner.start(keyA, 0.5f))
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun startAfterMatchingReadySucceedsOnceWithTheExactGain() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertTrue(owner.start(keyA, 0.3827f))
        assertEquals(listOf(0.3827f), backend.starts)
    }

    @Test fun duplicateStartIsRejected() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertTrue(owner.start(keyA, 0f))
        assertFalse(owner.start(keyA, 0f))
        assertEquals(1, backend.starts.size)
    }

    @Test fun staleKeyStartIsRejected() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertFalse(owner.start(keyB, 0f))
        assertFalse(owner.start(keyA.copy(queueGeneration = 2L), 0f))
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun supersededKeyStartIsRejectedAndNewPreparationUnaffected() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        owner.prepare(keyB, song(2)) // supersedes A
        backend.ready(attemptOf(0)) // late Ready from A
        assertFalse(owner.start(keyA, 0f))
        assertEquals(keyB, owner.currentKey)
        assertFalse(owner.start(keyB, 0f)) // B is still only preparing
        backend.ready(attemptOf(1))
        assertTrue(owner.start(keyB, 0.1f))
        assertEquals(listOf(0.1f), backend.starts)
    }

    @Test fun abandonedPreparationStartIsRejected() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        owner.abandon(keyA)
        assertFalse(owner.start(keyA, 0f))
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun failedPreparationStartIsRejected() {
        owner.prepare(keyA, song(1))
        backend.error(attemptOf(0))
        assertFalse(owner.start(keyA, 0f))
        // late Ready after failure cannot restore authority
        backend.ready(attemptOf(0))
        assertFalse(owner.start(keyA, 0f))
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun unusableDurationReadyNeverGrantsStartAuthority() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0), durationMs = 0L)
        assertFalse(owner.start(keyA, 0f))
    }

    @Test fun releasedOwnerStartIsRejected() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        owner.release()
        assertFalse(owner.start(keyA, 0f))
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun invalidGainIsRejectedWithoutBackendStartAndKeepsAuthority() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        for (g in listOf(-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse("gain $g", owner.start(keyA, g))
        }
        assertTrue(backend.starts.isEmpty())
        assertTrue(owner.start(keyA, 1f)) // a valid start is still possible afterwards
        assertEquals(listOf(1f), backend.starts)
    }

    @Test fun backendRefusalDoesNotConsumeStartAuthority() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        backend.startResult = false
        assertFalse(owner.start(keyA, 0.2f))
        backend.startResult = true
        assertTrue(owner.start(keyA, 0.2f))
    }

    @Test fun abandonAfterStartResetsBackendAndRemovesAuthority() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertTrue(owner.start(keyA, 0f))
        assertTrue(owner.abandon(keyA))
        assertEquals(1, backend.resets)
        assertNull(owner.currentKey)
        assertFalse(owner.start(keyA, 0f))
        assertEquals(1, backend.starts.size)
    }

    @Test fun releaseAfterStartIsIdempotentAndLateCallbacksAreHarmless() {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        owner.start(keyA, 0f)
        owner.release()
        owner.release()
        assertEquals(1, backend.releases)
        backend.ready(attemptOf(0))
        backend.error(attemptOf(0))
        assertFalse(owner.start(keyA, 0f))
        assertEquals(1, backend.starts.size)
    }

    @Test fun reentrantListenerSupersessionCannotTransferAuthority() {
        lateinit var o: CrossfadeSecondaryPlayer
        val b = FakeBackend()
        var startedFromListener = false
        val listener = object : CrossfadeSecondaryListener {
            override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) {
                if (key == keyA) {
                    o.prepare(keyB, song(2)) // supersede from inside the ready callback
                    startedFromListener = o.start(keyA, 0f) // old authority must not carry over
                }
            }
            override fun onSecondaryFailed(key: CrossfadeTransitionKey) = Unit
        }
        o = CrossfadeSecondaryPlayer({ b }, listener)
        o.prepare(keyA, song(1))
        b.ready(b.prepared[0])
        assertFalse(startedFromListener)
        assertEquals(keyB, o.currentKey)
        assertTrue(b.starts.isEmpty())
        b.ready(b.prepared[1])
        assertTrue(o.start(keyB, 0f))
    }

    private class RecordingListener : CrossfadeSecondaryListener {
        val readies = mutableListOf<CrossfadeTransitionKey>()
        val failures = mutableListOf<CrossfadeTransitionKey>()
        var onFailure: ((CrossfadeTransitionKey) -> Unit)? = null
        override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) { readies += key }
        override fun onSecondaryFailed(key: CrossfadeTransitionKey) { failures += key; onFailure?.invoke(key) }
    }

    @Test fun synchronousErrorDuringStartMakesStartReturnFalse() {
        val b = FakeBackend()
        val l = RecordingListener()
        val o = CrossfadeSecondaryPlayer({ b }, l)
        o.prepare(keyA, song(1))
        b.ready(b.prepared[0])
        b.onStart = { b.error(b.prepared[0]) } // backend reports an error synchronously, then returns true
        assertFalse(o.start(keyA, 0.2f))
        assertEquals(listOf(keyA), l.failures)
        assertEquals(1, b.resets)
        assertNull(o.currentKey)
        b.onStart = null
        assertFalse(o.start(keyA, 0.2f)) // no Started authority remains
        assertEquals(1, b.starts.size) // only the one attempted backend start
    }

    @Test fun reentrantFailureListenerPreparingBIsNotOverwrittenByStartResult() {
        val b = FakeBackend()
        val l = RecordingListener()
        lateinit var o: CrossfadeSecondaryPlayer
        l.onFailure = { key -> if (key == keyA) o.prepare(keyB, song(2)) }
        o = CrossfadeSecondaryPlayer({ b }, l)
        o.prepare(keyA, song(1))
        b.ready(b.prepared[0])
        b.onStart = { b.error(b.prepared[0]) }
        assertFalse(o.start(keyA, 0f))
        b.onStart = null
        assertEquals(keyB, o.currentKey)
        assertFalse(o.start(keyA, 0f)) // A is not restored
        assertFalse(o.start(keyB, 0f)) // B is still only preparing
        b.error(b.prepared[0]) // late A callbacks are ignored
        b.ready(b.prepared[0])
        assertEquals(listOf(keyA), l.failures)
        assertEquals(keyB, o.currentKey)
        b.ready(b.prepared[1])
        assertTrue(o.start(keyB, 0.4f))
        assertEquals(listOf(0f, 0.4f), b.starts)
    }

    @Test fun reentrantSameKeyRePrepareDuringStartIsNotMistakenForTheOldAttempt() {
        val b = FakeBackend()
        val l = RecordingListener()
        lateinit var o: CrossfadeSecondaryPlayer
        l.onFailure = { key -> o.prepare(key, song(1)) } // re-prepare the SAME key inside the failure callback
        o = CrossfadeSecondaryPlayer({ b }, l)
        o.prepare(keyA, song(1))
        b.ready(b.prepared[0])
        b.onStart = {
            b.error(b.prepared[0])
            b.ready(b.prepared[1]) // the new same-key attempt becomes Prepared before start() returns
        }
        assertFalse(o.start(keyA, 0f)) // the old attempt's start must not be credited to the new attempt
        b.onStart = null
        assertEquals(keyA, o.currentKey)
        assertTrue(o.start(keyA, 0f)) // new attempt is Prepared and still startable exactly once
        assertFalse(o.start(keyA, 0f))
    }

    @Test fun duplicateSongIdsDoNotAffectStartAuthorization() {
        val same = song(42)
        val k1 = CrossfadeTransitionKey(3L, 0, 2)
        val k2 = CrossfadeTransitionKey(3L, 1, 3)
        owner.prepare(k1, same)
        backend.ready(attemptOf(0))
        owner.prepare(k2, same) // same song id, different occurrence
        assertFalse(owner.start(k1, 0f))
        backend.ready(attemptOf(1))
        assertFalse(owner.start(k1, 0f))
        assertTrue(owner.start(k2, 0f))
    }

    @Test fun lateStartPathUsesLatenessDerivedGain() {
        // Due(key, latenessMs = 1500) of a 6000 ms overlap starts the secondary at the 25%-progress gain.
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        val gain = initialIncomingGain(1_500L, 6_000L)!!
        assertTrue(owner.start(keyA, gain))
        assertEquals(CrossfadeGainCurve.equalPower(0.25f).incoming, backend.starts.single(), 0f)
    }

    // -- CF-2C7A: occurrence-owned dynamic gain --------------------------------------

    private fun startA(gain: Float = 0f) {
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(0))
        assertTrue(owner.start(keyA, gain))
    }

    @Test fun startedOwnerAcceptsRepeatedExactKeyGains() {
        startA()
        assertTrue(owner.setGain(keyA, 0.75f))
        assertTrue(owner.setGain(keyA, 0.25f))
        assertEquals(listOf(0.75f, 0.25f), backend.gains)
    }

    @Test fun boundaryGainsAreAccepted() {
        startA()
        assertTrue(owner.setGain(keyA, 0f))
        assertTrue(owner.setGain(keyA, 1f))
        assertEquals(listOf(0f, 1f), backend.gains)
    }

    @Test fun invalidGainsNeverReachTheBackend() {
        startA()
        for (g in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.01f)) {
            assertFalse("gain $g", owner.setGain(keyA, g))
        }
        assertTrue(backend.gains.isEmpty())
    }

    @Test fun setGainWhilePreparingOrPreparedIsRejected() {
        owner.prepare(keyA, song(1))
        assertFalse(owner.setGain(keyA, 0.5f))
        backend.ready(attemptOf(0))
        assertFalse(owner.setGain(keyA, 0.5f))
        assertTrue(backend.gains.isEmpty())
    }

    @Test fun wrongKeyCannotSetGainAndOwnershipSurvives() {
        startA()
        assertFalse(owner.setGain(keyB, 0.5f))
        assertTrue(backend.gains.isEmpty())
        assertEquals(keyA, owner.currentKey)
        assertTrue(owner.setGain(keyA, 0.5f))
    }

    @Test fun supersededKeyCannotSetGainAndNewKeyNeedsStart() {
        startA()
        owner.prepare(keyB, song(2))
        assertFalse(owner.setGain(keyA, 0.5f))
        assertFalse(owner.setGain(keyB, 0.5f)) // Preparing
        backend.ready(attemptOf(1))
        assertFalse(owner.setGain(keyB, 0.5f)) // Prepared, not started
        assertTrue(owner.start(keyB, 0f))
        assertTrue(owner.setGain(keyB, 0.5f))
        assertEquals(listOf(0.5f), backend.gains)
    }

    @Test fun abandonAndReleaseBlockSetGain() {
        startA()
        assertTrue(owner.abandon(keyA))
        assertFalse(owner.setGain(keyA, 0.5f))
        owner.prepare(keyA, song(1))
        backend.ready(attemptOf(1))
        assertTrue(owner.start(keyA, 0f))
        owner.release()
        assertFalse(owner.setGain(keyA, 0.5f))
        assertTrue(backend.gains.isEmpty())
    }

    @Test fun backendFalseKeepsOwnershipAndLaterWriteCanSucceed() {
        startA()
        backend.setGainResult = false
        assertFalse(owner.setGain(keyA, 0.5f))
        assertEquals(keyA, owner.currentKey)
        backend.setGainResult = true
        assertTrue(owner.setGain(keyA, 0.6f))
        assertEquals(0, backend.resets)
    }

    @Test fun backendExceptionIsContainedWithoutListenerOrReset() {
        val b = FakeBackend()
        val failures = mutableListOf<CrossfadeTransitionKey>()
        val o = CrossfadeSecondaryPlayer({ b }, object : CrossfadeSecondaryListener {
            override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) = Unit
            override fun onSecondaryFailed(key: CrossfadeTransitionKey) { failures += key }
        })
        o.prepare(keyA, song(1))
        b.ready(b.prepared.single())
        assertTrue(o.start(keyA, 0f))
        b.setGainThrows = true
        assertFalse(o.setGain(keyA, 0.5f))
        assertTrue(failures.isEmpty())
        assertEquals(0, b.resets)
        assertEquals(keyA, o.currentKey)
        b.setGainThrows = false
        assertTrue(o.setGain(keyA, 0.5f))
    }

    @Test fun duplicateSongIdAtAnotherOccurrenceCannotMutate() {
        val same = song(5)
        val k1 = CrossfadeTransitionKey(3L, 0, 2)
        val k2 = CrossfadeTransitionKey(3L, 1, 2)
        owner.prepare(k1, same)
        backend.ready(attemptOf(0))
        assertTrue(owner.start(k1, 0f))
        assertFalse(owner.setGain(k2, 0.5f))
        assertTrue(backend.gains.isEmpty())
        assertTrue(owner.setGain(k1, 0.5f))
    }
}

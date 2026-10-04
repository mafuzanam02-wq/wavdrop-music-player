package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CF-2I1: exact-key, attempt-owned visibility of the secondary audio-session id (observation only). */
class CrossfadeSecondarySessionOwnershipTest {

    private class Backend : SecondaryPlayerBackend {
        var callbacks: SecondaryBackendCallbacks? = null
        var lastAttempt = 0L
        var sessionId: Int = 42
        var throwOnSession = false
        var sessionReads = 0
        var resets = 0
        var starts = 0
        var gains = 0
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            lastAttempt = attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean { starts++; return true }
        override fun setGain(gain: Float): Boolean { gains++; return true }
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = null
        override fun audioSessionSnapshot(): SecondaryAudioSessionSnapshot? {
            sessionReads++
            if (throwOnSession) error("boom")
            return SecondaryAudioSessionSnapshot(sessionId)
        }
        override fun reset() { resets++ }
        override fun release() {}
        fun ready() = callbacks!!.onReady(lastAttempt, 180_000L)
        fun fail() = callbacks!!.onError(lastAttempt)
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val backend = Backend()
    private val failed = mutableListOf<CrossfadeTransitionKey>()
    private val secondary = CrossfadeSecondaryPlayer(
        backendFactory = { backend },
        listener = object : CrossfadeSecondaryListener {
            override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) = Unit
            override fun onSecondaryFailed(key: CrossfadeTransitionKey) { failed += key }
        },
        mediaItemFactory = { MediaItem.Builder().setMediaId(it.id.toString()).build() },
    )
    private val keyA = CrossfadeTransitionKey(1L, 0, 1)
    private val keyB = CrossfadeTransitionKey(1L, 1, 2)

    @Test fun noPreparationIsNull() {
        assertNull(secondary.audioSessionSnapshot(keyA))
        assertEquals(0, backend.sessionReads)
    }

    @Test fun preparingExposesValidSession() {
        secondary.prepare(keyA, song(1))
        assertEquals(SecondaryAudioSessionSnapshot(42), secondary.audioSessionSnapshot(keyA))
    }

    @Test fun preparedExposesValidSession() {
        secondary.prepare(keyA, song(1))
        backend.ready()
        assertEquals(SecondaryAudioSessionSnapshot(42), secondary.audioSessionSnapshot(keyA))
    }

    @Test fun startedExposesValidSession() {
        secondary.prepare(keyA, song(1))
        backend.ready()
        assertEquals(true, secondary.start(keyA, 0.5f))
        assertEquals(SecondaryAudioSessionSnapshot(42), secondary.audioSessionSnapshot(keyA))
    }

    @Test fun wrongKeyIsNullWithoutBackendRead() {
        secondary.prepare(keyA, song(1))
        assertNull(secondary.audioSessionSnapshot(keyB))
        assertEquals(0, backend.sessionReads)
    }

    @Test fun supersededKeyIsNullAndNewKeyOwnsReads() {
        secondary.prepare(keyA, song(1))
        secondary.prepare(keyB, song(2))
        assertNull(secondary.audioSessionSnapshot(keyA))
        assertEquals(SecondaryAudioSessionSnapshot(42), secondary.audioSessionSnapshot(keyB))
    }

    @Test fun afterAbandonIsNull() {
        secondary.prepare(keyA, song(1))
        secondary.abandon(keyA)
        assertNull(secondary.audioSessionSnapshot(keyA))
    }

    @Test fun afterTerminalFailureIsNull() {
        secondary.prepare(keyA, song(1))
        backend.ready()
        backend.fail()
        assertEquals(listOf(keyA), failed)
        assertNull(secondary.audioSessionSnapshot(keyA))
    }

    @Test fun afterReleaseIsNull() {
        secondary.prepare(keyA, song(1))
        secondary.release()
        assertNull(secondary.audioSessionSnapshot(keyA))
    }

    @Test fun backendExceptionIsNullAndOwnershipUnchanged() {
        secondary.prepare(keyA, song(1))
        backend.ready()
        backend.throwOnSession = true
        assertNull(secondary.audioSessionSnapshot(keyA))
        assertEquals(keyA, secondary.currentKey)
        assertEquals(true, secondary.start(keyA, 0.5f)) // still Prepared, still owned
        assertEquals(emptyList<CrossfadeTransitionKey>(), failed)
    }

    @Test fun invalidBackendIdIsNull() {
        secondary.prepare(keyA, song(1))
        backend.sessionId = 0
        assertNull(secondary.audioSessionSnapshot(keyA))
        backend.sessionId = -7
        assertNull(secondary.audioSessionSnapshot(keyA))
    }

    @Test fun readIsPureAndSessionIdIsNotIdentity() {
        secondary.prepare(keyA, song(1))
        backend.ready()
        repeat(3) { secondary.audioSessionSnapshot(keyA) }
        assertEquals(0, backend.starts)
        assertEquals(0, backend.gains)
        assertEquals(0, backend.resets)
        // the same Android session id is reported for the replacement, yet the old key stays dead
        secondary.prepare(keyB, song(2))
        assertNull(secondary.audioSessionSnapshot(keyA))
        assertEquals(SecondaryAudioSessionSnapshot(42), secondary.audioSessionSnapshot(keyB))
    }
}

package com.launchpoint.wavdrop.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControllerSessionHardeningTest {

    @Test
    fun `empty logical queue clears persisted session`() {
        assertEquals(
            PlaybackSessionPersistenceAction.CLEAR,
            playbackSessionPersistenceAction(
                isExternalPlayback = false,
                queueIsEmpty = true,
            ),
        )
    }

    @Test
    fun `external playback leaves prior library session untouched`() {
        assertEquals(
            PlaybackSessionPersistenceAction.NONE,
            playbackSessionPersistenceAction(
                isExternalPlayback = true,
                queueIsEmpty = true,
            ),
        )
    }

    @Test
    fun `non-empty logical queue saves persisted session`() {
        assertEquals(
            PlaybackSessionPersistenceAction.SAVE,
            playbackSessionPersistenceAction(
                isExternalPlayback = false,
                queueIsEmpty = false,
            ),
        )
    }

    @Test
    fun `older pending save cannot overwrite newer clear`() = runBlocking {
        val gate = PlaybackSessionPersistenceGate()
        val operations = mutableListOf<String>()
        val firstOperationStarted = CountDownLatch(1)
        val allowFirstOperationToFinish = CountDownLatch(1)

        val saveRevision = gate.nextRevision()
        val oldSave = launch(Dispatchers.Default) {
            gate.runIfLatest(saveRevision) {
                firstOperationStarted.countDown()
                check(allowFirstOperationToFinish.await(2, TimeUnit.SECONDS))
                operations += "save"
            }
        }

        assertTrue(firstOperationStarted.await(2, TimeUnit.SECONDS))
        val clearRevision = gate.nextRevision()
        val newerClear = launch(Dispatchers.Default) {
            gate.runIfLatest(clearRevision) {
                operations += "clear"
            }
        }

        allowFirstOperationToFinish.countDown()
        oldSave.join()
        newerClear.join()

        assertEquals(listOf("save", "clear"), operations)
        assertEquals("clear", operations.last())
    }

    @Test
    fun `pending stale save is skipped when newer clear is already requested`() = runBlocking {
        val gate = PlaybackSessionPersistenceGate()
        val operations = mutableListOf<String>()
        val saveRevision = gate.nextRevision()
        val clearRevision = gate.nextRevision()

        val saveRan = gate.runIfLatest(saveRevision) { operations += "save" }
        val clearRan = gate.runIfLatest(clearRevision) { operations += "clear" }

        assertFalse(saveRan)
        assertTrue(clearRan)
        assertEquals(listOf("clear"), operations)
    }

    @Test
    fun `startup and reconnect restore paths share one claim`() {
        val claim = PlaybackSessionRestoreClaim()

        assertTrue("first restore path should claim", claim.claim())
        assertFalse("second restore path must not mutate state", claim.claim())
    }

    @Test
    fun `startup restore skips active logical queue`() {
        assertTrue(
            shouldSkipStartupSessionRestore(
                isExternalPlayback = false,
                hasLogicalQueue = true,
                hasMediaQueue = false,
            ),
        )
    }

    @Test
    fun `startup restore skips paused prepared media queue`() {
        assertTrue(
            shouldSkipStartupSessionRestore(
                isExternalPlayback = false,
                hasLogicalQueue = false,
                hasMediaQueue = true,
            ),
        )
    }

    @Test
    fun `normal cold-start restore remains eligible`() {
        assertFalse(
            shouldSkipStartupSessionRestore(
                isExternalPlayback = false,
                hasLogicalQueue = false,
                hasMediaQueue = false,
            ),
        )
    }
}

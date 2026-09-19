package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
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

    private fun song(id: Long) = Song(
        id = id, title = "T$id", artist = "A", album = "B",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

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
    fun `session save resolves second duplicate from playback position`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        assertEquals(2, resolveSessionCurrentLibraryIndex(queue, queue.indices.toList(), 2, null, 1L))
    }

    @Test
    fun `session save resolves middle of three duplicates`() {
        val queue = listOf(song(1), song(1), song(1))
        assertEquals(1, resolveSessionCurrentLibraryIndex(queue, queue.indices.toList(), 1, null, 1L))
    }

    @Test
    fun `session save maps shuffled playback position to library occurrence`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        assertEquals(2, resolveSessionCurrentLibraryIndex(queue, listOf(2, 3, 0, 1), 0, null, 1L))
    }

    @Test
    fun `session save rejects invalid position with ambiguous duplicate id`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        assertEquals(null, resolveSessionCurrentLibraryIndex(queue, queue.indices.toList(), 99, null, 1L))
    }

    @Test
    fun `session save permits unique song id fallback`() {
        val queue = listOf(song(1), song(2), song(3))
        assertEquals(1, resolveSessionCurrentLibraryIndex(queue, queue.indices.toList(), 99, null, 2L))
    }

    @Test
    fun `session save uses exact library index before song id fallback`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        assertEquals(2, resolveSessionCurrentLibraryIndex(queue, emptyList(), null, 2, 1L))
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
    fun `hydrated results allow caller to apply autoplay policy`() {
        assertTrue(playerHydrationAllowsPlay(PlayerHydrationResult.Hydrated))
        assertTrue(playerHydrationAllowsPlay(PlayerHydrationResult.AlreadyHydrated))
    }

    @Test
    fun `failed or skipped hydration results do not autoplay`() {
        val noPlayResults = PlayerHydrationResult.entries -
            setOf(PlayerHydrationResult.Hydrated, PlayerHydrationResult.AlreadyHydrated)

        noPlayResults.forEach { result ->
            assertFalse("$result must not autoplay", playerHydrationAllowsPlay(result))
        }
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

    @Test
    fun `continuous playback checkpoint becomes eligible after interval`() {
        assertTrue(
            shouldCheckpointPlaybackPosition(
                isPlaying = true,
                isExternalPlayback = false,
                queueIsEmpty = false,
                nowElapsedRealtimeMs = 10_000L,
                lastCheckpointElapsedRealtimeMs = 0L,
                currentPositionMs = 10_000L,
                lastCheckpointPositionMs = 0L,
            ),
        )
    }

    @Test
    fun `continuous playback checkpoint is not eligible before interval`() {
        assertFalse(
            shouldCheckpointPlaybackPosition(
                isPlaying = true,
                isExternalPlayback = false,
                queueIsEmpty = false,
                nowElapsedRealtimeMs = 9_999L,
                lastCheckpointElapsedRealtimeMs = 0L,
                currentPositionMs = 9_999L,
                lastCheckpointPositionMs = 0L,
            ),
        )
    }

    @Test
    fun `external playback is not checkpointed`() {
        assertFalse(
            shouldCheckpointPlaybackPosition(
                isPlaying = true,
                isExternalPlayback = true,
                queueIsEmpty = false,
                nowElapsedRealtimeMs = 20_000L,
                lastCheckpointElapsedRealtimeMs = 0L,
                currentPositionMs = 20_000L,
                lastCheckpointPositionMs = 0L,
            ),
        )
    }

    @Test
    fun `empty queue is not checkpointed`() {
        assertFalse(
            shouldCheckpointPlaybackPosition(
                isPlaying = true,
                isExternalPlayback = false,
                queueIsEmpty = true,
                nowElapsedRealtimeMs = 20_000L,
                lastCheckpointElapsedRealtimeMs = 0L,
                currentPositionMs = 20_000L,
                lastCheckpointPositionMs = 0L,
            ),
        )
    }

    @Test
    fun `position without meaningful change is not checkpointed`() {
        assertFalse(
            shouldCheckpointPlaybackPosition(
                isPlaying = true,
                isExternalPlayback = false,
                queueIsEmpty = false,
                nowElapsedRealtimeMs = 20_000L,
                lastCheckpointElapsedRealtimeMs = 0L,
                currentPositionMs = 4_999L,
                lastCheckpointPositionMs = 0L,
            ),
        )
    }
}

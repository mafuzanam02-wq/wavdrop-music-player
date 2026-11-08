package com.launchpoint.wavdrop.data.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.RepeatMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedQueueRestorePlannerTest {

    private fun song(id: Long) = Song(
        id = id, title = "T$id", artist = "A", album = "B", albumId = 0L, duration = 180_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun snap(
        ids: List<Long>,
        order: List<Int>? = ids.indices.toList(),
        currentIndex: Int = 0,
        currentSongId: Long? = ids.getOrNull(currentIndex),
        positionMs: Long = 7_000L,
        repeat: RepeatMode = RepeatMode.ALL,
        shuffle: Boolean = false,
    ) = PlaybackSessionSnapshot(
        queueSongIds = ids, playbackOrder = order, currentSongId = currentSongId, currentIndex = currentIndex,
        positionMs = positionMs, repeatMode = repeat, shuffleEnabled = shuffle, updatedAtMs = 1L,
    )

    private fun restored(s: PlaybackSessionSnapshot, library: List<Song>): SavedQueueRestore =
        (SavedQueueRestorePlanner.plan(s, library) as SavedQueueRestoreResult.Restored).restore

    @Test fun `all saved songs available is not partial and equals the saved queue`() {
        val r = restored(snap(listOf(1, 2, 3), order = listOf(2, 0, 1), currentIndex = 0, shuffle = true), listOf(1L, 2L, 3L).map(::song))
        assertFalse(r.isPartial)
        assertEquals(listOf(1L, 2L, 3L), r.songs.map { it.id })
        assertEquals(listOf(2, 0, 1), r.playbackOrder)
        assertEquals(0, r.currentLibraryIndex)
        assertEquals(7_000L, r.positionMs)
        assertFalse(r.currentOccurrenceWasMissing)
    }

    @Test fun `one missing song restores survivors in order with position kept`() {
        val r = restored(snap(listOf(1, 2, 3), currentIndex = 2), listOf(1L, 3L).map(::song))
        assertEquals(listOf(1L, 3L), r.songs.map { it.id })
        assertEquals(1, r.missingCount)
        assertTrue(r.isPartial)
        assertEquals(listOf(0, 2), r.originalIndices)
        assertEquals(listOf(0, -1, 1), r.oldToNew)
        assertEquals(1, r.currentLibraryIndex)
        assertEquals(7_000L, r.positionMs)
    }

    @Test fun `multiple missing songs including first and last`() {
        val r = restored(snap(listOf(1, 2, 3, 4, 5), currentIndex = 2), listOf(3L, 4L).map(::song))
        assertEquals(listOf(3L, 4L), r.songs.map { it.id })
        assertEquals(3, r.missingCount)
        assertEquals(0, r.currentLibraryIndex)
    }

    @Test fun `no survivors is unresolvable`() {
        val result = SavedQueueRestorePlanner.plan(snap(listOf(1, 2)), emptyList())
        assertEquals(
            SavedQueueRestoreResult.Unresolvable(SavedQueueRestoreResult.Reason.NO_SURVIVORS),
            result,
        )
    }

    @Test fun `empty saved queue is unresolvable`() {
        assertEquals(
            SavedQueueRestoreResult.Unresolvable(SavedQueueRestoreResult.Reason.EMPTY_SAVED_QUEUE),
            SavedQueueRestorePlanner.plan(snap(emptyList(), order = null, currentIndex = 0, currentSongId = null), listOf(song(1))),
        )
    }

    @Test fun `duplicate occurrences are kept or dropped independently`() {
        // Queue 1,2,1,3 with the SECOND occurrence of song 1 current. Song 2 missing: both copies of 1 survive.
        val r = restored(snap(listOf(1, 2, 1, 3), currentIndex = 2), listOf(1L, 3L).map(::song))
        assertEquals(listOf(1L, 1L, 3L), r.songs.map { it.id })
        assertEquals(1, r.currentLibraryIndex) // second copy, not the first
        assertEquals(listOf(0, 2, 3), r.originalIndices)
    }

    @Test fun `current is the second duplicate and only the first copy position is identified by index`() {
        val r = restored(snap(listOf(1, 1, 1), currentIndex = 1), listOf(song(1)))
        assertEquals(listOf(1L, 1L, 1L), r.songs.map { it.id })
        assertEquals(1, r.currentLibraryIndex)
    }

    @Test fun `valid shuffled order is remapped preserving relative order`() {
        val r = restored(
            snap(listOf(1, 2, 3, 4), order = listOf(3, 1, 0, 2), currentIndex = 3, shuffle = true),
            listOf(1L, 3L, 4L).map(::song), // song 2 (old index 1) missing
        )
        // old order 3,1,0,2 -> drop 1 -> 3,0,2 -> map (0->0, 2->1, 3->2) -> 2,0,1
        assertEquals(listOf(2, 0, 1), r.playbackOrder)
        assertEquals(2, r.currentLibraryIndex)
    }

    @Test fun `invalid saved order is rebuilt without inventing identity`() {
        val r = restored(snap(listOf(1, 2, 3), order = listOf(0, 0, 2), currentIndex = 2), listOf(1L, 3L).map(::song))
        assertEquals(listOf(0, 1), r.playbackOrder) // identity rebuild, shuffle off
        assertEquals(1, r.currentLibraryIndex)
    }

    @Test fun `current occurrence missing picks next survivor in saved playback order paused at zero`() {
        // Saved order 2,0,3,1; current = 0 (missing). Next in order after 0 is 3 (survives).
        val r = restored(
            snap(listOf(1, 2, 3, 4), order = listOf(2, 0, 3, 1), currentIndex = 0, shuffle = true),
            listOf(2L, 3L, 4L).map(::song),
        )
        assertTrue(r.currentOccurrenceWasMissing)
        assertEquals(0L, r.positionMs)
        assertEquals(4L, r.songs[r.currentLibraryIndex].id)
    }

    @Test fun `current occurrence missing wraps around the saved order`() {
        // Order 0,1,2; current = 2 (last, missing) -> wraps to 0.
        val r = restored(snap(listOf(1, 2, 3), currentIndex = 2), listOf(1L, 2L).map(::song))
        assertEquals(0, r.currentLibraryIndex)
        assertEquals(0L, r.positionMs)
    }

    @Test fun `unidentifiable current occurrence is not guessed`() {
        // Index out of range and the saved id appears twice: never invented.
        val result = SavedQueueRestorePlanner.plan(
            snap(listOf(1, 2, 1), currentIndex = 99, currentSongId = 1),
            listOf(1L, 2L).map(::song),
        )
        assertEquals(
            SavedQueueRestoreResult.Unresolvable(SavedQueueRestoreResult.Reason.CURRENT_OCCURRENCE_UNIDENTIFIABLE),
            result,
        )
    }

    @Test fun `unique saved id resolves when the saved index is stale`() {
        val r = restored(snap(listOf(1, 2, 3), currentIndex = 9, currentSongId = 3), listOf(1L, 2L, 3L).map(::song))
        assertEquals(2, r.currentLibraryIndex)
    }

    @Test fun `empty library returns unresolvable`() {
        assertTrue(SavedQueueRestorePlanner.plan(snap(listOf(1, 2, 3)), emptyList()) is SavedQueueRestoreResult.Unresolvable)
    }

    @Test fun `songs returning later restore the full queue`() {
        val s = snap(listOf(1, 2, 3), currentIndex = 1)
        assertTrue(restored(s, listOf(1L, 3L).map(::song)).isPartial)
        val full = restored(s, listOf(1L, 2L, 3L).map(::song))
        assertFalse(full.isPartial)
        assertEquals(listOf(1L, 2L, 3L), full.songs.map { it.id })
        assertEquals(1, full.currentLibraryIndex)
    }

    @Test fun `repeat and shuffle live in the snapshot and are untouched by the planner`() {
        val s = snap(listOf(1, 2, 3), repeat = RepeatMode.ONE, shuffle = true, order = listOf(1, 2, 0), currentIndex = 1)
        // Planner output never changes flags; the controller reads them from the snapshot.
        val r = restored(s, listOf(1L, 2L).map(::song))
        assertEquals(listOf(1, 0), r.playbackOrder) // 1,2,0 -> drop 2 -> 1,0
        assertEquals(RepeatMode.ONE, s.repeatMode)
        assertTrue(s.shuffleEnabled)
    }

    // ── property test ────────────────────────────────────────────────────────

    @Test fun `property survivors order and current invariants hold for random queues`() {
        val rnd = Random(20260801)
        repeat(3_000) { iteration ->
            val n = rnd.nextInt(1, 14)
            val ids = List(n) { rnd.nextLong(1, 6) } // many duplicates
            val shuffled = rnd.nextBoolean()
            val order = if (shuffled) ids.indices.shuffled(rnd) else ids.indices.toList()
            val oldCurrent = rnd.nextInt(n)
            val dropIds = (1L..5L).filter { rnd.nextInt(3) == 0 }.toSet()
            val library = (1L..5L).filter { it !in dropIds }.map(::song)
            val s = snap(ids, order = order, currentIndex = oldCurrent, shuffle = shuffled, positionMs = 1234L)

            val survivors = ids.indices.filter { ids[it] !in dropIds }
            val result = SavedQueueRestorePlanner.plan(s, library)
            if (survivors.isEmpty()) {
                assertEquals("iter $iteration", SavedQueueRestoreResult.Unresolvable(SavedQueueRestoreResult.Reason.NO_SURVIVORS), result)
                return@repeat
            }
            val r = (result as SavedQueueRestoreResult.Restored).restore
            assertEquals(survivors.map { ids[it] }, r.songs.map { it.id })
            assertEquals(survivors, r.originalIndices)
            assertEquals(n - survivors.size, r.missingCount)
            assertEquals(survivors.size, r.playbackOrder.size)
            assertEquals((0 until survivors.size).toSet(), r.playbackOrder.toSet())
            assertTrue(r.currentLibraryIndex in r.playbackOrder)
            // relative order of the saved order is preserved
            val expectedOrder = order.filter { oldIdx -> oldIdx in survivors }.map { survivors.indexOf(it) }
            assertEquals("iter $iteration", expectedOrder, r.playbackOrder)
            if (oldCurrent in survivors) {
                assertEquals(survivors.indexOf(oldCurrent), r.currentLibraryIndex)
                assertEquals(1234L, r.positionMs)
                assertFalse(r.currentOccurrenceWasMissing)
            } else {
                assertTrue(r.currentOccurrenceWasMissing)
                assertEquals(0L, r.positionMs)
                val at = order.indexOf(oldCurrent)
                val next = (1..order.size).map { order[(at + it) % order.size] }.first { it in survivors }
                assertEquals(survivors.indexOf(next), r.currentLibraryIndex)
            }
            // oldToNew consistency
            r.oldToNew.forEachIndexed { old, new ->
                if (old in survivors) assertEquals(survivors.indexOf(old), new) else assertEquals(-1, new)
            }
        }
    }
}

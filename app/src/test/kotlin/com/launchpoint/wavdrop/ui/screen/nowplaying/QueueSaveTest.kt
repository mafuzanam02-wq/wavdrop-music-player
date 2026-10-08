package com.launchpoint.wavdrop.ui.screen.nowplaying

import com.launchpoint.wavdrop.data.model.ExternalAudioIdentity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.QueueSaveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** QSP-1: the pure queue-save rules (snapshot, availability, outcome mapping). No playback, no Android. */
class QueueSaveTest {

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "A", album = "B", albumId = 0L, duration = 120_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020, folderPath = "Music", folderName = "Music",
    )

    @Test fun `the snapshot copies the current queue order exactly, one id per occurrence`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        assertEquals(listOf(1L, 2L, 1L, 3L), queueSongIdsSnapshot(queue))
    }

    @Test fun `earlier, current and up next are all included in playback order`() {
        val earlierCurrentUpNext = listOf(song(10), song(11), song(12), song(13), song(14))
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), queueSongIdsSnapshot(earlierCurrentUpNext))
    }

    @Test fun `the snapshot is a new list and saving never mutates the queue`() {
        val queue = listOf(song(3), song(1), song(2))
        val before = queue.toList()
        val ids = queueSongIdsSnapshot(queue)
        assertNotSame(queue, ids)
        assertEquals(before, queue)
        assertEquals(listOf(3L, 1L, 2L), ids)
    }

    @Test fun `a queue that changed before Save is saved as the later queue`() {
        var live = listOf(song(1), song(2), song(3))
        val openedDialogWith = queueSongIdsSnapshot(live)
        live = listOf(song(2), song(3), song(4), song(2)) // playback advanced / the user reordered while the dialog was open
        val onSave = queueSongIdsSnapshot(live) // Save reads the queue at execution time
        assertEquals(listOf(1L, 2L, 3L), openedDialogWith)
        assertEquals(listOf(2L, 3L, 4L, 2L), onSave)
    }

    @Test fun `an earlier snapshot is not affected by later queue changes`() {
        val queue = mutableListOf(song(1), song(2))
        val ids = queueSongIdsSnapshot(queue)
        queue.clear()
        assertEquals(listOf(1L, 2L), ids)
    }

    private fun externalSong() = song(ExternalAudioIdentity.SONG_ID)

    @Test fun `A and B - an ordinary non-empty queue is saveable and an empty queue is not`() {
        assertTrue(canSaveQueue(listOf(song(1))))
        assertTrue(canSaveQueue(listOf(song(1), song(2), song(1))))
        assertTrue(canSaveQueue(List(500) { song(it.toLong() + 1) }))
        assertFalse(canSaveQueue(emptyList()))
        assertEquals(emptyList<Long>(), queueSongIdsSnapshot(emptyList()))
    }

    @Test fun `C - an external ACTION_VIEW queue is not saveable`() {
        assertFalse(canSaveQueue(listOf(externalSong())))
    }

    @Test fun `G - a queue that merely contains external audio is refused whole, never partially filtered`() {
        assertFalse(canSaveQueue(listOf(song(1), externalSong(), song(2))))
        assertFalse(canSaveQueue(listOf(externalSong(), song(1))))
        // the snapshot helper never drops anything: what is saved is exactly what the guard accepted or nothing
        assertEquals(listOf(1L, ExternalAudioIdentity.SONG_ID, 2L), queueSongIdsSnapshot(listOf(song(1), externalSong(), song(2))))
    }

    @Test fun `H - duplicate occurrences in an ordinary queue stay saveable and exact`() {
        val queue = listOf(song(1), song(2), song(1), song(3), song(1))
        assertTrue(canSaveQueue(queue))
        assertEquals(listOf(1L, 2L, 1L, 3L, 1L), queueSongIdsSnapshot(queue))
    }

    @Test fun `the external audio identity is one shared constant and a predicate`() {
        assertEquals(Long.MIN_VALUE, ExternalAudioIdentity.SONG_ID)
        assertTrue(ExternalAudioIdentity.isExternalAudio(externalSong()))
        assertTrue(ExternalAudioIdentity.isExternalAudioId(Long.MIN_VALUE))
        assertFalse(ExternalAudioIdentity.isExternalAudio(song(1)))
        assertFalse(ExternalAudioIdentity.isExternalAudioId(0L))
        assertFalse(ExternalAudioIdentity.isExternalAudioId(-1L))
    }

    @Test fun `repository results map to the dialog outcome and the exact copy`() {
        assertEquals(QueueSaveOutcome.Saved, queueSaveOutcome(QueueSaveResult.Success(playlistId = 4L, songCount = 3)))
        assertEquals(QueueSaveOutcome.Error("Enter a playlist name"), queueSaveOutcome(QueueSaveResult.BlankName))
        assertEquals(QueueSaveOutcome.Error("A playlist with this name already exists"), queueSaveOutcome(QueueSaveResult.DuplicateName))
        assertEquals(QueueSaveOutcome.Error("The queue is empty"), queueSaveOutcome(QueueSaveResult.EmptyQueue))
        assertEquals(QueueSaveOutcome.Error("This queue can't be saved as a playlist"), queueSaveOutcome(QueueSaveResult.UnsavableQueue))
        assertEquals("Queue saved as playlist", QUEUE_SAVED_MESSAGE)
        assertEquals("Save queue as playlist", QUEUE_SAVE_MENU_LABEL)
    }
}

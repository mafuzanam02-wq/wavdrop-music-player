package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerControllerBulkQueueTest {

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    @Test
    fun `playAllNext preserves supplied order with active queue`() {
        val songs = listOf(song(1), song(2), song(3))

        val plan = planPlayAllNext(songs, hasActiveCurrentItem = true)
            as PlayAllNextPlan.InsertAfterCurrent

        val current = song(99)
        val resultingQueue = plan.songs.asReversed().fold(listOf(current)) { queue, next ->
            QueueMutation.insertAfterCurrent(
                playbackQueue = queue,
                song = next,
                currentPlaybackIndex = 0,
            )!!
        }
        assertEquals(listOf(current) + songs, resultingQueue)
    }

    @Test
    fun `playAllNext preserves supplied order with empty queue`() {
        val songs = listOf(song(1), song(2), song(3))

        val plan = planPlayAllNext(songs, hasActiveCurrentItem = false)
            as PlayAllNextPlan.StartQueue

        assertEquals(songs, plan.queue)
        assertSame(songs.first(), plan.startSong)
    }

    @Test
    fun `addAllToQueue appends in supplied order`() {
        val existing = listOf(song(99))
        val songs = listOf(song(1), song(2), song(3))

        val resultingQueue = songs.fold(existing) { queue, next ->
            QueueMutation.append(queue, next)
        }

        assertEquals(existing + songs, resultingQueue)
    }

    @Test
    fun `bulk queue actions preserve duplicate tracks`() {
        val duplicate = song(1)
        val songs = listOf(duplicate, song(2), duplicate)

        val plan = planPlayAllNext(songs, hasActiveCurrentItem = false)
            as PlayAllNextPlan.StartQueue

        assertEquals(listOf(1L, 2L, 1L), plan.queue.map { it.id })
    }
}

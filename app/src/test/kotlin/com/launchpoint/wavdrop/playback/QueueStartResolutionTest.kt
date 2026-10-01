package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueStartResolutionTest {

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    @Test fun uniqueMiddleSongResolvesItsExactIndex() {
        val q = listOf(song(1), song(2), song(3))
        assertEquals(1, resolveQueueStartBySong(q, song(2))?.startIndex)
    }

    @Test fun emptyQueueNormalizesToStartSongAtZero() {
        val start = resolveQueueStartBySong(emptyList(), song(9))!!
        assertEquals(listOf(song(9)), start.queue)
        assertEquals(0, start.startIndex)
    }

    @Test fun absentSongDoesNotSilentlyStartIndexZero() {
        assertNull(resolveQueueStartBySong(listOf(song(1), song(2)), song(7)))
    }

    @Test fun ambiguousDuplicateIsRejectedNotFirstMatched() {
        val q = listOf(song(1, 0), song(2), song(1, 2))
        assertNull(resolveQueueStartBySong(q, song(1, 2)))
    }

    @Test fun allSameIdsAreRejected() {
        val q = listOf(song(1, 0), song(1, 1), song(1, 2))
        assertNull(resolveQueueStartBySong(q, song(1, 1)))
    }

    @Test fun explicitIndexCanSelectThirdDuplicate() {
        val q = listOf(song(1, 0), song(1, 1), song(1, 2))
        val start = resolveQueueStart(q, 2)!!
        assertEquals(2, start.startIndex)
        assertEquals(2L, start.startSong.dateAdded)
    }

    @Test fun freshQueueBatchStartAtIndexZeroWorksWithDuplicateIds() {
        // addAllToQueue / Play All start with a known index 0 even if the first song repeats later.
        val q = listOf(song(1, 0), song(2), song(1, 2))
        val start = resolveQueueStart(q, 0)!!
        assertEquals(0, start.startIndex)
        assertEquals(0L, start.startSong.dateAdded)
    }

    @Test fun explicitIndexOutOfRangeIsRejected() {
        assertNull(resolveQueueStart(listOf(song(1)), 1))
        assertNull(resolveQueueStart(emptyList(), 0))
    }
}

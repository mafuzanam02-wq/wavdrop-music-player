package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPlaybackPlannerTest {

    private fun song(id: Long, label: String) = Song(
        id = id,
        title = label,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private val a = song(1, "A")
    private val b = song(2, "B")
    private val c = song(3, "C")
    private val m = song(13, "M")
    private val n = song(14, "N")
    private val o = song(15, "O")
    private val x = song(99, "X")

    @Test
    fun `preserve queue inserts search result after current effective item`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, m, n, o),
            currentPlaybackIndex = 3,
            song = x,
        )

        assertEquals(listOf(a, b, c, m, x, n, o), plan.queue)
        assertEquals(4, plan.currentIndex)
        assertEquals(m, plan.queue[plan.currentIndex - 1])
        assertEquals(n, plan.queue[plan.currentIndex + 1])
    }

    @Test
    fun `preserve queue moves later duplicate search result after current`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, m, x, n),
            currentPlaybackIndex = 3,
            song = x,
        )

        assertEquals(listOf(a, b, c, m, x, n), plan.queue)
        assertEquals(4, plan.currentIndex)
        assertEquals(1, plan.queue.count { it.id == x.id })
        assertEquals(m, plan.queue[plan.currentIndex - 1])
        assertEquals(n, plan.queue[plan.currentIndex + 1])
    }

    @Test
    fun `replace queue uses search context and selected song index`() {
        val plan = SearchPlaybackPlanner.replaceQueue(
            searchContext = listOf(c, x, o),
            song = x,
        )

        assertEquals(listOf(c, x, o), plan.queue)
        assertEquals(1, plan.currentIndex)
    }
}

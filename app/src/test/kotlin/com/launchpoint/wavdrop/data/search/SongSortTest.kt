package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SongSortTest {

    @Test
    fun `titles sort case insensitively into matching alphabet range`() {
        val sortedTitles = listOf(
            song(1, "Zoo"),
            song(2, "apple"),
            song(3, "Banana"),
            song(4, "banana"),
            song(5, "Alpha"),
        ).sortedWith(SongSort.byTitle).map { it.title }

        assertEquals(listOf("Alpha", "apple", "Banana", "banana", "Zoo"), sortedTitles)
    }

    @Test
    fun `case insensitive order uses id as final stable tiebreaker`() {
        val sortedIds = listOf(
            song(3, "echo"),
            song(1, "echo"),
            song(2, "echo"),
        ).sortedWith(SongSort.byTitle).map { it.id }

        assertEquals(listOf(1L, 2L, 3L), sortedIds)
    }

    private fun song(id: Long, title: String) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )
}

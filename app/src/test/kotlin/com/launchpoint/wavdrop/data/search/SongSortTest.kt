package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.SongSortMode
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

    @Test
    fun `default sort is title A-Z`() {
        val sortedTitles = SongSort.sortSongs(
            listOf(song(1, "Zoo"), song(2, "Alpha"), song(3, "middle")),
        ).map { it.title }

        assertEquals(listOf("Alpha", "middle", "Zoo"), sortedTitles)
    }

    @Test
    fun `recently added sorts newest first with title tiebreak`() {
        val sortedTitles = SongSort.sortSongs(
            songs = listOf(
                song(1, "Beta", dateAdded = 20),
                song(2, "Alpha", dateAdded = 20),
                song(3, "Newest", dateAdded = 30),
                song(4, "Oldest", dateAdded = 10),
            ),
            mode = SongSortMode.RECENTLY_ADDED,
        ).map { it.title }

        assertEquals(listOf("Newest", "Alpha", "Beta", "Oldest"), sortedTitles)
    }

    @Test
    fun `all time most played sorts by play count with title tiebreak`() {
        val sortedTitles = SongSort.sortSongs(
            songs = listOf(song(1, "Beta"), song(2, "Alpha"), song(3, "Zero")),
            mode = SongSortMode.MOST_PLAYED_ALL_TIME,
            allTimePlayCounts = mapOf(1L to 4, 2L to 4),
        ).map { it.title }

        assertEquals(listOf("Alpha", "Beta", "Zero"), sortedTitles)
    }

    @Test
    fun `this month most played sorts by monthly play count with title tiebreak`() {
        val sortedTitles = SongSort.sortSongs(
            songs = listOf(song(1, "Beta"), song(2, "Alpha"), song(3, "Zero")),
            mode = SongSortMode.MOST_PLAYED_THIS_MONTH,
            thisMonthPlayCounts = mapOf(1L to 2, 2L to 2),
        ).map { it.title }

        assertEquals(listOf("Alpha", "Beta", "Zero"), sortedTitles)
    }

    @Test
    fun `zero play songs remain visible after played songs`() {
        val sortedTitles = SongSort.sortSongs(
            songs = listOf(song(1, "Unplayed B"), song(2, "Played"), song(3, "Unplayed A")),
            mode = SongSortMode.MOST_PLAYED_ALL_TIME,
            allTimePlayCounts = mapOf(2L to 1),
        ).map { it.title }

        assertEquals(listOf("Played", "Unplayed A", "Unplayed B"), sortedTitles)
    }

    private fun song(id: Long, title: String, dateAdded: Long = 0L) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = dateAdded,
        trackNumber = 0,
        year = 2020,
    )
}

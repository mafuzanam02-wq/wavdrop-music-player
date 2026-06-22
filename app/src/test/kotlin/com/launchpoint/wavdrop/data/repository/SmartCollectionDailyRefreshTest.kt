package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionDailyRefreshTest {

    @Test
    fun `forgotten gems refreshes from day signal without playback emission`() = runBlocking {
        val beforeMidnight = Instant.parse("2026-06-22T23:59:00Z").toEpochMilli()
        val afterMidnight = Instant.parse("2026-06-23T00:01:00Z").toEpochMilli()
        val lastPlayedAt = afterMidnight - 60L * 24 * 60 * 60 * 1_000L - 1L
        val song = song(1)
        val stats = TrackStatsEntity(
            songId = song.id,
            contentUri = song.uri,
            playCount = 5,
            lastPlayedAt = lastPlayedAt,
        )

        val emissions = observeSmartCollectionsFromInputs(
            songs = flowOf(listOf(song)),
            stats = flowOf(listOf(stats)),
            completions = flowOf(emptyList()),
            dayRefresh = flowOf(beforeMidnight, afterMidnight),
        ).take(2).toList()

        assertTrue(emissions.first().none { it.type == SmartCollectionType.FORGOTTEN_GEMS })
        assertEquals(
            1,
            emissions.last().first { it.type == SmartCollectionType.FORGOTTEN_GEMS }.songCount,
        )
    }

    @Test
    fun `daily refresh waits until next local midnight`() {
        val now = Instant.parse("2026-06-22T23:30:00Z").toEpochMilli()

        assertEquals(
            30L * 60 * 1_000L,
            millisUntilNextLocalDay(now, ZoneOffset.UTC),
        )
    }

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 180_000L,
        uri = "content://media/external/audio/media/$id",
        dateAdded = id,
        trackNumber = 1,
        year = 2020,
    )
}

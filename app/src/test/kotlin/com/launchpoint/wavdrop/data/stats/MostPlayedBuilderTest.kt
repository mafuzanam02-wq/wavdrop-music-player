package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.Song
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MostPlayedBuilderTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val nowMs: Long = LocalDateTime
        .of(2026, 6, 15, 12, 0)
        .atZone(utc)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `all time uses aggregate play count and excludes zero plays`() {
        val result = MostPlayedBuilder.build(
            songs = listOf(song(1), song(2), song(3)),
            stats = listOf(
                stats(songId = 1, playCount = 4),
                stats(songId = 2, playCount = 0),
                stats(songId = 3, playCount = 7),
            ),
            events = emptyList(),
            period = MostPlayedPeriod.ALL_TIME,
            nowMs = nowMs,
            zone = utc,
        )

        assertEquals(listOf(3L, 1L), result.map { it.song.id })
        assertEquals(listOf(7, 4), result.map { it.playCount })
    }

    @Test
    fun `all time ignores orphan stats`() {
        val result = MostPlayedBuilder.build(
            songs = listOf(song(1)),
            stats = listOf(
                stats(songId = 1, playCount = 2),
                stats(songId = 99, playCount = 100),
            ),
            events = emptyList(),
            period = MostPlayedPeriod.ALL_TIME,
            nowMs = nowMs,
            zone = utc,
        )

        assertEquals(listOf(1L), result.map { it.song.id })
    }

    @Test
    fun `this month counts only play events inside current calendar month`() {
        val month = ListeningPeriodRange.month(2026, 6, utc)
        val previousMonth = ListeningPeriodRange.month(2026, 5, utc)

        val result = MostPlayedBuilder.build(
            songs = listOf(song(1), song(2), song(3)),
            stats = listOf(
                stats(songId = 1, playCount = 100),
                stats(songId = 2, playCount = 50),
                stats(songId = 3, playCount = 25),
            ),
            events = listOf(
                play(songId = 1, occurredAt = month.fromMs + 1_000L),
                play(songId = 1, occurredAt = month.fromMs + 2_000L),
                play(songId = 2, occurredAt = month.fromMs + 3_000L),
                play(songId = 3, occurredAt = previousMonth.toMs),
                skip(songId = 2, occurredAt = month.fromMs + 4_000L),
            ),
            period = MostPlayedPeriod.THIS_MONTH,
            nowMs = nowMs,
            zone = utc,
        )

        assertEquals(listOf(1L, 2L), result.map { it.song.id })
        assertEquals(listOf(2, 1), result.map { it.playCount })
    }

    @Test
    fun `this month does not use aggregate imported counts without matching events`() {
        val result = MostPlayedBuilder.build(
            songs = listOf(song(1)),
            stats = listOf(stats(songId = 1, playCount = 999)),
            events = emptyList(),
            period = MostPlayedPeriod.THIS_MONTH,
            nowMs = nowMs,
            zone = utc,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `this month ignores orphan events`() {
        val month = ListeningPeriodRange.month(2026, 6, utc)

        val result = MostPlayedBuilder.build(
            songs = listOf(song(1)),
            stats = emptyList(),
            events = listOf(
                play(songId = 99, occurredAt = month.fromMs + 1_000L),
                play(songId = 1, occurredAt = month.fromMs + 2_000L),
            ),
            period = MostPlayedPeriod.THIS_MONTH,
            nowMs = nowMs,
            zone = utc,
        )

        assertEquals(listOf(1L), result.map { it.song.id })
    }

    @Test
    fun `display limit caps all time and this month lists`() {
        val month = ListeningPeriodRange.month(2026, 6, utc)
        val songs = (1L..12L).map { song(it) }
        val stats = (1L..12L).map { stats(songId = it, playCount = it.toInt()) }
        val events = (1L..12L).flatMap { songId ->
            List(songId.toInt()) { index ->
                play(songId = songId, occurredAt = month.fromMs + songId * 1_000L + index)
            }
        }

        val allTime = MostPlayedBuilder.build(
            songs = songs,
            stats = stats,
            events = emptyList(),
            period = MostPlayedPeriod.ALL_TIME,
            limit = MostPlayedDisplayLimit.TOP_10,
            nowMs = nowMs,
            zone = utc,
        )
        val thisMonth = MostPlayedBuilder.build(
            songs = songs,
            stats = emptyList(),
            events = events,
            period = MostPlayedPeriod.THIS_MONTH,
            limit = MostPlayedDisplayLimit.TOP_10,
            nowMs = nowMs,
            zone = utc,
        )

        assertEquals(10, allTime.size)
        assertEquals(10, thisMonth.size)
        assertEquals(12L, allTime.first().song.id)
        assertEquals(12L, thisMonth.first().song.id)
    }

    private fun song(id: Long): Song = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2026,
    )

    private fun stats(
        songId: Long,
        playCount: Int,
    ): TrackStatsEntity = TrackStatsEntity(
        songId = songId,
        contentUri = "content://media/$songId",
        playCount = playCount,
    )

    private fun play(
        songId: Long,
        occurredAt: Long,
        listenedMs: Long = 60_000L,
    ): TrackListenEventEntity = TrackListenEventEntity(
        songId = songId,
        eventType = TrackListenEventEntity.TYPE_PLAY,
        occurredAt = occurredAt,
        listenedMs = listenedMs,
        durationMs = 180_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private fun skip(
        songId: Long,
        occurredAt: Long,
    ): TrackListenEventEntity = TrackListenEventEntity(
        songId = songId,
        eventType = TrackListenEventEntity.TYPE_SKIP,
        occurredAt = occurredAt,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )
}

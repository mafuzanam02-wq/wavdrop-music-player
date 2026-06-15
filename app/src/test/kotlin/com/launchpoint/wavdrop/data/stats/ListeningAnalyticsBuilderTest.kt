package com.launchpoint.wavdrop.data.stats

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ListeningAnalyticsBuilderTest {

    private val utc: ZoneId = ZoneOffset.UTC

    // ── Empty / no activity ───────────────────────────────────────────────────

    @Test
    fun `empty events produce all-zero summary`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1)), emptyList(), emptyList())

        assertEquals(0, result.totalPlayCount)
        assertEquals(0, result.totalSkipCount)
        assertEquals(0L, result.totalListeningTimeMs)
        assertEquals(0, result.tracksPlayedCount)
        assertEquals(0, result.artistsPlayedCount)
        assertEquals(0, result.albumsPlayedCount)
        assertTrue(result.topSongs.isEmpty())
        assertTrue(result.topArtists.isEmpty())
        assertTrue(result.topAlbums.isEmpty())
        assertNull(result.mostSkippedTrack)
        assertTrue(result.recentlyPlayed.isEmpty())
        assertEquals(0, result.listeningDaysCount)
        assertNull(result.busiestDay)
        assertEquals(0, result.busiestDayPlayCount)
        assertEquals(0.0, result.averagePlaysPerActiveDay, 0.0)
        assertFalse(result.hasActivity)
        assertEquals(ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE, result.emptyState.reason)
        assertTrue(result.emptyState.isEmpty)
    }

    // ── Range filtering ────────────────────────────────────────────────────────

    @Test
    fun `events outside range are excluded from all totals`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 15), listenedMs = 30_000L), // inside
            playEvent(songId = 2, occurredAt = epochMs(2026, 5, 31), listenedMs = 60_000L), // before range
            playEvent(songId = 3, occurredAt = epochMs(2026, 7, 1),  listenedMs = 45_000L), // after range
        )
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1), song(2), song(3)), emptyList(), events)

        assertEquals(1, result.totalPlayCount)
        assertEquals(30_000L, result.totalListeningTimeMs)
        assertEquals(1, result.tracksPlayedCount)
    }

    @Test
    fun `range boundary is inclusive on both ends`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = range.fromMs),       // exactly at start
            playEvent(songId = 2, occurredAt = range.toMs),         // exactly at end
            playEvent(songId = 3, occurredAt = range.fromMs - 1L),  // 1ms before start
            playEvent(songId = 4, occurredAt = range.toMs + 1L),    // 1ms after end
        )
        val songs = (1L..4L).map { song(it) }
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(2, result.totalPlayCount)
    }

    @Test
    fun `year range factory covers all 12 months`() {
        val range = ListeningPeriodRange.year(2026, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 1, 1)),   // Jan 1
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 15)),  // mid-year
            playEvent(songId = 3, occurredAt = epochMs(2026, 12, 31)), // Dec 31
            playEvent(songId = 4, occurredAt = epochMs(2025, 12, 31)), // prev year
            playEvent(songId = 5, occurredAt = epochMs(2027, 1, 1)),   // next year
        )
        val songs = (1L..5L).map { song(it) }
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(3, result.totalPlayCount)
    }

    @Test
    fun `current month range includes only events from that calendar month`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 30)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 5, 31)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 7, 1)),
        )
        val songs = (1L..3L).map { song(it) }
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(2, result.totalPlayCount)
        assertEquals(listOf(1L), result.topSongs.map { it.song.id })
    }

    @Test
    fun `all time event range aggregates events across years`() {
        val range = ListeningPeriodRange.allTime(utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2024, 1, 1), listenedMs = 10_000L),
            playEvent(songId = 2, occurredAt = epochMs(2025, 6, 1), listenedMs = 20_000L),
            playEvent(songId = 1, occurredAt = epochMs(2026, 12, 31), listenedMs = 30_000L),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 12, 31)),
        )
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1), song(2)), emptyList(), events)

        assertEquals(3, result.totalPlayCount)
        assertEquals(1, result.totalSkipCount)
        assertEquals(60_000L, result.totalListeningTimeMs)
        assertEquals(listOf(1L, 2L), result.topSongs.map { it.song.id })
    }

    @Test
    fun `monthly analytics ignore imported aggregate stats without events`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val result = ListeningAnalyticsBuilder.build(
            range = range,
            songs = listOf(song(1)),
            stats = listOf(stats(songId = 1, playCount = 999, skipCount = 111)),
            events = emptyList(),
        )

        assertEquals(0, result.totalPlayCount)
        assertEquals(0, result.totalSkipCount)
        assertTrue(result.topSongs.isEmpty())
        assertEquals(ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE, result.emptyState.reason)
    }

    // ── Totals ─────────────────────────────────────────────────────────────────

    @Test
    fun `totalPlayCount and totalSkipCount are counted independently`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 2)),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
        )
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1), song(2)), emptyList(), events)

        assertEquals(2, result.totalPlayCount)
        assertEquals(1, result.totalSkipCount)
        assertTrue(result.hasActivity)
    }

    @Test
    fun `totalListeningTimeMs sums all play event listenedMs`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1), listenedMs = 10_000L),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2), listenedMs = 25_000L),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 3), listenedMs = 40_000L),
        )
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1), song(2)), emptyList(), events)

        assertEquals(75_000L, result.totalListeningTimeMs)
    }

    @Test
    fun `hasActivity is true when only skips exist`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)))
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1)), emptyList(), events)

        assertEquals(0, result.totalPlayCount)
        assertEquals(1, result.totalSkipCount)
        assertTrue(result.hasActivity)
    }

    // ── Per-song summaries ─────────────────────────────────────────────────────

    @Test
    fun `topSongs sorted by play count descending with title tiebreak`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 5)),
        )
        val songs = listOf(
            song(1, title = "Alpha"),
            song(2, title = "Beta"),
            song(3, title = "Gamma"),
        )
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(listOf("Beta", "Gamma", "Alpha"), result.topSongs.map { it.song.title })
        assertEquals(2, result.topSongs[0].playCount)
        assertEquals(2, result.topSongs[1].playCount)
        assertEquals(1, result.topSongs[2].playCount)
    }

    @Test
    fun `top lists are capped at 10 entries`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val songs = (1L..15L).map { song(it, title = "Song $it", artist = "Artist $it", album = "Album $it") }
        val events = (1L..15L).map { playEvent(songId = it, occurredAt = epochMs(2026, 6, it.toInt())) }
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(10, result.topSongs.size)
        assertEquals(10, result.topArtists.size)
        assertEquals(10, result.topAlbums.size)
        assertEquals(10, result.recentlyPlayed.size)
    }

    @Test
    fun `mostSkippedTrack returns song with highest skip count`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            skipEvent(songId = 2, occurredAt = epochMs(2026, 6, 4)),
            skipEvent(songId = 3, occurredAt = epochMs(2026, 6, 5)),
        )
        val songs = listOf(song(1, title = "Played"), song(2, title = "SkipMe"), song(3, title = "OnceSk"))
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals("SkipMe", result.mostSkippedTrack?.song?.title)
        assertEquals(3, result.mostSkippedTrack?.skipCount)
    }

    @Test
    fun `recentlyPlayed sorted by last play event descending`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val t1 = epochMs(2026, 6, 5)
        val t2 = epochMs(2026, 6, 20)
        val t3 = epochMs(2026, 6, 10)
        val events = listOf(
            playEvent(songId = 1, occurredAt = t1),
            playEvent(songId = 2, occurredAt = t2),
            playEvent(songId = 3, occurredAt = t3),
        )
        val songs = listOf(song(1, title = "First"), song(2, title = "Last"), song(3, title = "Middle"))
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(listOf("Last", "Middle", "First"), result.recentlyPlayed.map { it.song.title })
    }

    // ── Orphan events ──────────────────────────────────────────────────────────

    @Test
    fun `orphan play events count in totals but not in per-song lists`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1), listenedMs = 30_000L),
            playEvent(songId = 99, occurredAt = epochMs(2026, 6, 2), listenedMs = 45_000L), // no matching song
        )
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1)), emptyList(), events)

        assertEquals(2, result.totalPlayCount)         // both counted
        assertEquals(75_000L, result.totalListeningTimeMs)
        assertEquals(1, result.tracksPlayedCount)      // only matched song
        assertEquals(1, result.topSongs.size)
    }

    // ── Day-level analytics ────────────────────────────────────────────────────

    @Test
    fun `listeningDaysCount counts distinct calendar days with plays`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 1)),  // same day
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 5)),
            skipEvent(songId = 4, occurredAt = epochMs(2026, 6, 10)), // skip — doesn't count
        )
        val songs = (1L..4L).map { song(it) }
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(2, result.listeningDaysCount)
    }

    @Test
    fun `busiestDay is the day with most plays`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 10)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 10)),
        )
        val songs = (1L..3L).map { song(it) }
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(LocalDate.of(2026, 6, 3), result.busiestDay)
        assertEquals(3, result.busiestDayPlayCount)
    }

    @Test
    fun `averagePlaysPerActiveDay is plays divided by distinct listening days`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 5)),
        )
        val songs = (1L..3L).map { song(it) }
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        // 4 plays across 2 days → 2.0
        assertEquals(2.0, result.averagePlaysPerActiveDay, 0.001)
    }

    @Test
    fun `averagePlaysPerActiveDay is zero when no plays`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(skipEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)))
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1)), emptyList(), events)

        assertEquals(0.0, result.averagePlaysPerActiveDay, 0.0)
    }

    // ── Imported aggregate stats exclusion ─────────────────────────────────────

    @Test
    fun `imported aggregate stats are not used for period play counts`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        // Song 1 has a high all-time playCount from a BlackPlayer import, last played before June.
        val importedStats = listOf(
            TrackStatsEntity(
                songId = 1, contentUri = "content://media/1",
                playCount = 500, skipCount = 20,
                lastPlayedAt = epochMs(2026, 5, 15),
                totalListeningTimeMs = 900_000L,
            )
        )
        // Only song 2 has actual events in June.
        val events = listOf(playEvent(songId = 2, occurredAt = epochMs(2026, 6, 10)))
        val songs = listOf(song(1, title = "ImportHeavy"), song(2, title = "EventOnly"))
        val result = ListeningAnalyticsBuilder.build(range, songs, importedStats, events)

        assertEquals(1, result.totalPlayCount)   // event-backed only
        assertEquals(1, result.tracksPlayedCount)
        assertEquals("EventOnly", result.topSongs[0].song.title)
        assertTrue(result.topSongs.none { it.song.title == "ImportHeavy" })
    }

    @Test
    fun `stats param with no events yields zero period totals even for active songs`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val stats = listOf(
            TrackStatsEntity(
                songId = 1, contentUri = "content://media/1",
                playCount = 200, skipCount = 5, lastPlayedAt = epochMs(2026, 6, 1),
            )
        )
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1)), stats, emptyList())

        assertEquals(0, result.totalPlayCount)
        assertTrue(result.topSongs.isEmpty())
        assertEquals(ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE, result.emptyState.reason)
    }

    // ── Empty state reason codes ────────────────────────────────────────────────

    @Test
    fun `emptyState reason is HAS_ACTIVITY when matched events exist`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(playEvent(songId = 1, occurredAt = epochMs(2026, 6, 10)))
        val result = ListeningAnalyticsBuilder.build(range, listOf(song(1)), emptyList(), events)

        assertEquals(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, result.emptyState.reason)
        assertFalse(result.emptyState.isEmpty)
        assertTrue(result.emptyState.hasEventsInRange)
        assertTrue(result.emptyState.hasMatchedLibraryItems)
    }

    @Test
    fun `emptyState reason is ONLY_ORPHAN_EVENTS when all events have unknown songIds`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 99, occurredAt = epochMs(2026, 6, 5)),  // no song in library
            skipEvent(songId = 98, occurredAt = epochMs(2026, 6, 6)),  // no song in library
        )
        val result = ListeningAnalyticsBuilder.build(range, emptyList(), emptyList(), events)

        assertEquals(ListeningAnalyticsEmptyReason.ONLY_ORPHAN_EVENTS, result.emptyState.reason)
        assertTrue(result.emptyState.isEmpty)
        assertTrue(result.emptyState.hasEventsInRange)
        assertFalse(result.emptyState.hasMatchedLibraryItems)
        assertEquals(2, result.totalPlayCount + result.totalSkipCount) // events still counted
    }

    // ── buildAllTimeAggregateFallback ───────────────────────────────────────────

    @Test
    fun `buildAllTimeAggregateFallback uses stats totals not events`() {
        val stats = listOf(
            TrackStatsEntity(
                songId = 1, contentUri = "content://media/1",
                playCount = 30, skipCount = 5, totalListeningTimeMs = 60_000L,
            ),
            TrackStatsEntity(
                songId = 2, contentUri = "content://media/2",
                playCount = 15, skipCount = 2, totalListeningTimeMs = 30_000L,
            ),
        )
        val songs = listOf(song(1, title = "Heavy"), song(2, title = "Light"))
        val result = ListeningAnalyticsBuilder.buildAllTimeAggregateFallback(songs, stats, zone = utc)

        assertEquals(45, result.totalPlayCount)
        assertEquals(7, result.totalSkipCount)
        assertEquals(90_000L, result.totalListeningTimeMs)
        assertEquals("Heavy", result.topSongs[0].song.title)
        assertEquals(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, result.emptyState.reason)
    }

    @Test
    fun `buildAllTimeAggregateFallback with no activity yields NO_AGGREGATE_ACTIVITY`() {
        val stats = listOf(
            TrackStatsEntity(songId = 1, contentUri = "content://media/1", playCount = 0),
        )
        val result = ListeningAnalyticsBuilder.buildAllTimeAggregateFallback(
            songs = listOf(song(1)), stats = stats, zone = utc,
        )

        assertEquals(ListeningAnalyticsEmptyReason.NO_AGGREGATE_ACTIVITY, result.emptyState.reason)
        assertTrue(result.emptyState.isEmpty)
        assertEquals(0, result.totalPlayCount)
    }

    // ── Artist / album grouping ────────────────────────────────────────────────

    @Test
    fun `songs from same artist are grouped into one artist summary`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
        )
        val songs = listOf(
            song(1, title = "Track A", artist = "Same Artist"),
            song(2, title = "Track B", artist = "Same Artist"),
        )
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(1, result.artistsPlayedCount)
        assertEquals(2, result.topArtists[0].playCount)
        assertEquals(2, result.topArtists[0].songCount)
    }

    @Test
    fun `songs from same album are grouped into one album summary`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
        )
        val songs = listOf(
            song(1, title = "Track 1", album = "Great Album"),
            song(2, title = "Track 2", album = "Great Album"),
        )
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(1, result.albumsPlayedCount)
        assertEquals(3, result.topAlbums[0].playCount)
    }

    @Test
    fun `topArtists sorted by aggregated play count descending`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 5)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 6)),
        )
        val songs = listOf(
            song(1, artist = "Artist C"),
            song(2, artist = "Artist B"),
            song(3, artist = "Artist A"),
        )
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(listOf("Artist A", "Artist B", "Artist C"), result.topArtists.map { it.artistKey })
        assertEquals(listOf(3, 2, 1), result.topArtists.map { it.playCount })
    }

    @Test
    fun `topAlbums sorted by aggregated play count descending`() {
        val range = ListeningPeriodRange.month(2026, 6, utc)
        val events = listOf(
            playEvent(songId = 1, occurredAt = epochMs(2026, 6, 1)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 2)),
            playEvent(songId = 2, occurredAt = epochMs(2026, 6, 3)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 4)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 5)),
            playEvent(songId = 3, occurredAt = epochMs(2026, 6, 6)),
        )
        val songs = listOf(
            song(1, album = "Album C"),
            song(2, album = "Album B"),
            song(3, album = "Album A"),
        )
        val result = ListeningAnalyticsBuilder.build(range, songs, emptyList(), events)

        assertEquals(listOf("Album A", "Album B", "Album C"), result.topAlbums.map { it.albumKey })
        assertEquals(listOf(3, 2, 1), result.topAlbums.map { it.playCount })
    }

    @Test
    fun `all time aggregate fallback uses TrackStatsEntity totals`() {
        val result = ListeningAnalyticsBuilder.buildAllTimeAggregateFallback(
            songs = listOf(song(1), song(2), song(3)),
            stats = listOf(
                stats(songId = 1, playCount = 5, skipCount = 1, totalListeningTimeMs = 50_000L),
                stats(songId = 2, playCount = 8, skipCount = 3, totalListeningTimeMs = 80_000L),
                stats(songId = 99, playCount = 100, skipCount = 100, totalListeningTimeMs = 100_000L),
            ),
            topListLimit = 10,
            zone = utc,
        )

        assertEquals(13, result.totalPlayCount)
        assertEquals(4, result.totalSkipCount)
        assertEquals(130_000L, result.totalListeningTimeMs)
        assertEquals(listOf(2L, 1L), result.topSongs.map { it.song.id })
        assertEquals(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, result.emptyState.reason)
    }

    @Test
    fun `all time aggregate fallback uses effective listening time`() {
        val result = ListeningAnalyticsBuilder.buildAllTimeAggregateFallback(
            songs = listOf(song(1, duration = 60_000L), song(2, duration = 120_000L)),
            stats = listOf(
                stats(songId = 1, playCount = 4, totalListeningTimeMs = 0L),
                stats(songId = 2, playCount = 10, totalListeningTimeMs = 50_000L),
            ),
            topListLimit = 10,
            zone = utc,
        )

        assertEquals(290_000L, result.totalListeningTimeMs)
        assertEquals(240_000L, result.topSongs.first { it.song.id == 1L }.totalListeningTimeMs)
        assertEquals(50_000L, result.topSongs.first { it.song.id == 2L }.totalListeningTimeMs)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun epochMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    private fun song(
        id: Long,
        title: String = "Song $id",
        artist: String = "Artist",
        album: String = "Album",
        duration: Long = 200_000L,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = id,
        duration = duration,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private fun playEvent(
        songId: Long,
        occurredAt: Long,
        listenedMs: Long = 60_000L,
    ) = TrackListenEventEntity(
        songId = songId,
        eventType = TrackListenEventEntity.TYPE_PLAY,
        occurredAt = occurredAt,
        listenedMs = listenedMs,
        durationMs = 200_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private fun skipEvent(
        songId: Long,
        occurredAt: Long,
    ) = TrackListenEventEntity(
        songId = songId,
        eventType = TrackListenEventEntity.TYPE_SKIP,
        occurredAt = occurredAt,
        listenedMs = 0L,
        durationMs = 200_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    private fun stats(
        songId: Long,
        playCount: Int = 0,
        skipCount: Int = 0,
        lastPlayedAt: Long = 0L,
        totalListeningTimeMs: Long = 0L,
    ) = TrackStatsEntity(
        songId = songId,
        contentUri = "content://media/$songId",
        playCount = playCount,
        skipCount = skipCount,
        lastPlayedAt = lastPlayedAt,
        totalListeningTimeMs = totalListeningTimeMs,
    )
}

package com.launchpoint.wavdrop.data.smart

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionBuilderTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun song(
        id: Long,
        title: String = "Song $id",
        duration: Long = 3 * 60_000L,  // 3 minutes default
        dateAdded: Long = 1_000L + id,
    ) = Song(
        id          = id,
        title       = title,
        artist      = "Artist",
        album       = "Album",
        albumId     = 1L,
        duration    = duration,
        uri         = "content://media/external/audio/media/$id",
        dateAdded   = dateAdded,
        trackNumber = 1,
        year        = 2020,
    )

    private fun stats(
        songId: Long,
        playCount: Int    = 0,
        skipCount: Int    = 0,
        lastPlayedAt: Long = 0L,
        isFavorite: Boolean = false,
    ) = TrackStatsEntity(
        songId       = songId,
        contentUri   = "content://media/external/audio/media/$songId",
        playCount    = playCount,
        skipCount    = skipCount,
        lastPlayedAt = lastPlayedAt,
        isFavorite   = isFavorite,
    )

    // ── Empty input ───────────────────────────────────────────────────────────

    @Test fun `build with empty songs returns empty list`() {
        assertTrue(SmartCollectionBuilder.build(emptyList(), emptyList()).isEmpty())
    }

    @Test fun `songsFor with empty songs returns empty list for all types`() {
        for (type in SmartCollectionType.values()) {
            val result = SmartCollectionBuilder.songsFor(type, emptyList(), emptyList())
            assertTrue("Expected empty for $type", result.isEmpty())
        }
    }

    // ── Favorites ─────────────────────────────────────────────────────────────

    @Test fun `favorites returns only songs with isFavorite true`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats  = listOf(
            stats(1, isFavorite = true),
            stats(2, isFavorite = false),
            // song 3 has no stats — not a favorite
        )
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.FAVORITES, songs, stats)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun `favorites with no favorites returns empty`() {
        val songs = listOf(song(1), song(2))
        val stats  = listOf(stats(1, isFavorite = false), stats(2, isFavorite = false))
        assertTrue(SmartCollectionBuilder.songsFor(SmartCollectionType.FAVORITES, songs, stats).isEmpty())
    }

    // ── Most Played ───────────────────────────────────────────────────────────

    @Test fun `most played excludes songs with zero play count`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats  = listOf(stats(1, playCount = 5), stats(2, playCount = 0))
        // song 3 has no stats → playCount 0 → excluded
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_PLAYED, songs, stats)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun `most played sorted by play count descending`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats  = listOf(stats(1, playCount = 3), stats(2, playCount = 10), stats(3, playCount = 1))
        val ids = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_PLAYED, songs, stats)
            .map { it.id }
        assertEquals(listOf(2L, 1L, 3L), ids)
    }

    @Test fun `most played tie broken by id ascending`() {
        val songs = listOf(song(3), song(1), song(2))
        val stats  = listOf(stats(1, playCount = 5), stats(2, playCount = 5), stats(3, playCount = 5))
        val ids = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_PLAYED, songs, stats)
            .map { it.id }
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    // ── Recently Played ───────────────────────────────────────────────────────

    @Test fun `recently played excludes songs with lastPlayedAt zero`() {
        val songs = listOf(song(1), song(2))
        val stats  = listOf(stats(1, lastPlayedAt = 1_000L), stats(2, lastPlayedAt = 0L))
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.RECENTLY_PLAYED, songs, stats)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun `recently played sorted by lastPlayedAt descending`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats  = listOf(
            stats(1, lastPlayedAt = 100L),
            stats(2, lastPlayedAt = 300L),
            stats(3, lastPlayedAt = 200L),
        )
        val ids = SmartCollectionBuilder.songsFor(SmartCollectionType.RECENTLY_PLAYED, songs, stats)
            .map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ids)
    }

    // ── Never Played ──────────────────────────────────────────────────────────

    @Test fun `never played includes songs with no stats`() {
        val songs = listOf(song(1), song(2))
        val stats  = listOf(stats(1, playCount = 5))
        // song 2 has no stats → never played
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.NEVER_PLAYED, songs, stats)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test fun `never played includes songs with play count zero`() {
        val songs = listOf(song(1), song(2))
        val stats  = listOf(stats(1, playCount = 0), stats(2, playCount = 1))
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.NEVER_PLAYED, songs, stats)
        assertEquals(listOf(1L), result.map { it.id })
    }

    // ── Most Skipped ──────────────────────────────────────────────────────────

    @Test fun `most skipped excludes songs with zero skip count`() {
        val songs = listOf(song(1), song(2))
        val stats  = listOf(stats(1, skipCount = 3), stats(2, skipCount = 0))
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_SKIPPED, songs, stats)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun `most skipped sorted by skip count descending`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats  = listOf(stats(1, skipCount = 2), stats(2, skipCount = 8), stats(3, skipCount = 5))
        val ids = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_SKIPPED, songs, stats)
            .map { it.id }
        assertEquals(listOf(2L, 3L, 1L), ids)
    }

    // ── Long Tracks ───────────────────────────────────────────────────────────

    @Test fun `long tracks includes songs at or above 7 minutes`() {
        val exactly7Min  = song(1, duration = 7 * 60 * 1_000L)
        val justOver     = song(2, duration = 7 * 60 * 1_000L + 1)
        val justUnder    = song(3, duration = 7 * 60 * 1_000L - 1)
        val result = SmartCollectionBuilder.songsFor(
            SmartCollectionType.LONG_TRACKS,
            listOf(exactly7Min, justOver, justUnder),
            emptyList(),
        )
        assertEquals(setOf(1L, 2L), result.map { it.id }.toSet())
    }

    @Test fun `long tracks sorted by duration descending`() {
        val songs = listOf(
            song(1, duration = 8 * 60_000L),
            song(2, duration = 10 * 60_000L),
            song(3, duration = 7 * 60_000L),
        )
        val ids = SmartCollectionBuilder.songsFor(SmartCollectionType.LONG_TRACKS, songs, emptyList())
            .map { it.id }
        assertEquals(listOf(2L, 1L, 3L), ids)
    }

    // ── Short Tracks ──────────────────────────────────────────────────────────

    @Test fun `short tracks includes songs at or below 90 seconds`() {
        val exactly90s = song(1, duration = 90_000L)
        val justUnder  = song(2, duration = 89_999L)
        val justOver   = song(3, duration = 90_001L)
        val result = SmartCollectionBuilder.songsFor(
            SmartCollectionType.SHORT_TRACKS,
            listOf(exactly90s, justUnder, justOver),
            emptyList(),
        )
        assertEquals(setOf(1L, 2L), result.map { it.id }.toSet())
    }

    @Test fun `short tracks sorted by duration ascending`() {
        val songs = listOf(
            song(1, duration = 60_000L),
            song(2, duration = 30_000L),
            song(3, duration = 90_000L),
        )
        val ids = SmartCollectionBuilder.songsFor(SmartCollectionType.SHORT_TRACKS, songs, emptyList())
            .map { it.id }
        assertEquals(listOf(2L, 1L, 3L), ids)
    }

    // ── Orphan stats ignored ──────────────────────────────────────────────────

    @Test fun `orphan stats do not affect most played result`() {
        val songs = listOf(song(1))
        val stats  = listOf(
            stats(1, playCount = 2),
            stats(99, playCount = 1_000),  // orphan — song 99 not in library
        )
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_PLAYED, songs, stats)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun `orphan stats do not appear in favorites`() {
        val songs = listOf(song(1))
        val stats  = listOf(
            stats(99, isFavorite = true),  // orphan
        )
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.FAVORITES, songs, stats)
        assertTrue(result.isEmpty())
    }

    // ── Deterministic ordering ────────────────────────────────────────────────

    @Test fun `never played ordering is deterministic across multiple calls`() {
        val songs = (1L..10L).map { song(it) }
        val first  = SmartCollectionBuilder.songsFor(SmartCollectionType.NEVER_PLAYED, songs, emptyList())
        val second = SmartCollectionBuilder.songsFor(SmartCollectionType.NEVER_PLAYED, songs, emptyList())
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test fun `most played ordering is deterministic across multiple calls`() {
        val songs = (1L..5L).map { song(it) }
        val stats  = songs.map { stats(it.id, playCount = (it.id % 3).toInt() + 1) }
        val first  = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_PLAYED, songs, stats)
        val second = SmartCollectionBuilder.songsFor(SmartCollectionType.MOST_PLAYED, songs, stats)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    // ── build summary ─────────────────────────────────────────────────────────

    @Test fun `build omits collections with no matching songs`() {
        // Only one song, no stats → only NEVER_PLAYED and RECENTLY_ADDED can appear
        val songs = listOf(song(1))
        val collections = SmartCollectionBuilder.build(songs, emptyList())
        val types = collections.map { it.type }.toSet()
        assertTrue(SmartCollectionType.FAVORITES !in types)
        assertTrue(SmartCollectionType.MOST_PLAYED !in types)
        assertTrue(SmartCollectionType.NEVER_PLAYED in types)
    }

    @Test fun `build returns correct song counts`() {
        val songs = listOf(song(1), song(2), song(3))
        val stats  = listOf(
            stats(1, isFavorite = true),
            stats(2, isFavorite = true),
        )
        val favCollection = SmartCollectionBuilder.build(songs, stats)
            .first { it.type == SmartCollectionType.FAVORITES }
        assertEquals(2, favCollection.songCount)
    }

    // ── Forgotten Gems ────────────────────────────────────────────────────────

    // Helper: epoch ms clearly older than 60 days
    private val OLD_TS   = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1_000L  // 90 days ago
    private val OLD_TS2  = System.currentTimeMillis() - 75L * 24 * 60 * 60 * 1_000L  // 75 days ago
    // Recent: within 60-day quiet window
    private val RECENT   = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1_000L  // 30 days ago

    @Test fun `forgotten gems excludes songs with playCount below 5`() {
        val songs = listOf(song(1), song(2))
        val s     = listOf(
            stats(1, playCount = 4, lastPlayedAt = OLD_TS),
            stats(2, playCount = 5, lastPlayedAt = OLD_TS),
        )
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.FORGOTTEN_GEMS, songs, s)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test fun `forgotten gems excludes songs played within 60 days`() {
        val songs = listOf(song(1), song(2))
        val s     = listOf(
            stats(1, playCount = 10, lastPlayedAt = RECENT),
            stats(2, playCount = 10, lastPlayedAt = OLD_TS),
        )
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.FORGOTTEN_GEMS, songs, s)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test fun `forgotten gems excludes songs with lastPlayedAt zero`() {
        val songs = listOf(song(1))
        val s     = listOf(stats(1, playCount = 10, lastPlayedAt = 0L))
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.FORGOTTEN_GEMS, songs, s)
        assertTrue(result.isEmpty())
    }

    @Test fun `forgotten gems includes songs meeting all criteria`() {
        val songs = listOf(song(1))
        val s     = listOf(stats(1, playCount = 5, lastPlayedAt = OLD_TS))
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.FORGOTTEN_GEMS, songs, s)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test fun `forgotten gems sorted by playCount descending then lastPlayedAt ascending`() {
        val songs = listOf(song(1), song(2), song(3), song(4))
        val s     = listOf(
            stats(1, playCount = 10, lastPlayedAt = OLD_TS2),  // high plays, less old
            stats(2, playCount = 10, lastPlayedAt = OLD_TS),   // high plays, more old → comes first at same count
            stats(3, playCount = 20, lastPlayedAt = OLD_TS),   // highest plays → first overall
            stats(4, playCount = 5,  lastPlayedAt = OLD_TS),   // lowest plays → last
        )
        val ids = SmartCollectionBuilder.songsFor(SmartCollectionType.FORGOTTEN_GEMS, songs, s)
            .map { it.id }
        assertEquals(listOf(3L, 2L, 1L, 4L), ids)
    }

    @Test fun `forgotten gems caps at 50`() {
        val songs = (1L..60L).map { song(it) }
        val s     = songs.map { stats(it.id, playCount = 10, lastPlayedAt = OLD_TS) }
        val result = SmartCollectionBuilder.songsFor(SmartCollectionType.FORGOTTEN_GEMS, songs, s)
        assertEquals(50, result.size)
    }

    @Test fun `build omits forgotten gems when no songs qualify`() {
        val songs = listOf(song(1))
        val s     = listOf(stats(1, playCount = 3, lastPlayedAt = OLD_TS))  // below min play count
        val collections = SmartCollectionBuilder.build(songs, s)
        assertTrue(collections.none { it.type == SmartCollectionType.FORGOTTEN_GEMS })
    }
}

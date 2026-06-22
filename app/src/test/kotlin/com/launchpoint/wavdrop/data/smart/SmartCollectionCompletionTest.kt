package com.launchpoint.wavdrop.data.smart

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCollectionCompletionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun song(id: Long) = Song(
        id          = id,
        title       = "Song $id",
        artist      = "Artist",
        album       = "Album",
        albumId     = 1L,
        duration    = 3 * 60_000L,
        uri         = "content://media/external/audio/media/$id",
        dateAdded   = 1_000L + id,
        trackNumber = 1,
        year        = 2020,
    )

    private val noStats = emptyList<TrackStatsEntity>()

    private fun completion(
        songId: Long,
        nativePlays: Int = 0,
        nativeSkips: Int = 0,
        validCompletionPlays: Int = nativePlays,
        avgCompletion: Float = 0f,
    ) = SongCompletionSummary(
        songId               = songId,
        nativePlays          = nativePlays,
        nativeSkips          = nativeSkips,
        validCompletionPlays = validCompletionPlays,
        avgCompletion        = avgCompletion,
    )

    private fun alwaysFinish(songs: List<Song>, completions: List<SongCompletionSummary>) =
        SmartCollectionBuilder.songsFor(SmartCollectionType.ALWAYS_FINISH, songs, noStats, completions)

    private fun usuallyAbandon(songs: List<Song>, completions: List<SongCompletionSummary>) =
        SmartCollectionBuilder.songsFor(SmartCollectionType.USUALLY_ABANDON, songs, noStats, completions)

    // ══════════════════════════════════════════════════════════════════════════
    // ALWAYS FINISH
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `always finish excludes songs with fewer than 3 native plays`() {
        val songs = listOf(song(1), song(2))
        val completions = listOf(
            completion(1, nativePlays = 2, avgCompletion = 0.95f),
            completion(2, nativePlays = 3, avgCompletion = 0.90f),
        )
        assertEquals(listOf(2L), alwaysFinish(songs, completions).map { it.id })
    }

    @Test fun `always finish excludes songs with zero plays`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 0, avgCompletion = 1.0f))
        assertTrue(alwaysFinish(songs, completions).isEmpty())
    }

    @Test fun `always finish includes songs at exactly the 0_85 threshold`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 3, avgCompletion = 0.85f))
        assertEquals(listOf(1L), alwaysFinish(songs, completions).map { it.id })
    }

    @Test fun `always finish excludes songs just below the 0_85 threshold`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 3, avgCompletion = 0.849f))
        assertTrue(alwaysFinish(songs, completions).isEmpty())
    }

    @Test fun `always finish accepts avgCompletion of 1_0 (fully played)`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 3, avgCompletion = 1.0f))
        assertEquals(listOf(1L), alwaysFinish(songs, completions).map { it.id })
    }

    @Test fun `always finish excludes songs where avgCompletion is 0_0 (no valid plays)`() {
        val songs = listOf(song(1))
        // nativePlays >= 3 but no valid durationMs events → avgCompletion = 0.0
        val completions = listOf(completion(1, nativePlays = 5, validCompletionPlays = 0, avgCompletion = 0.0f))
        assertTrue(alwaysFinish(songs, completions).isEmpty())
    }

    @Test fun `always finish sorted by avgCompletion desc then nativePlays desc`() {
        val songs = (1L..3L).map { song(it) }
        val completions = listOf(
            completion(1, nativePlays = 5, avgCompletion = 0.90f),
            completion(2, nativePlays = 8, avgCompletion = 0.95f),
            completion(3, nativePlays = 3, avgCompletion = 0.90f),
        )
        assertEquals(listOf(2L, 1L, 3L), alwaysFinish(songs, completions).map { it.id })
    }

    @Test fun `always finish ranks full eligible set before applying 50 cap`() {
        val songs = (55L downTo 1L).map { song(it) }
        val completions = songs.map {
            completion(
                songId = it.id,
                nativePlays = 3,
                avgCompletion = 0.85f + it.id / 1_000f,
            )
        }

        val result = SmartCollectionBuilder.songsResultFor(
            SmartCollectionType.ALWAYS_FINISH,
            songs,
            noStats,
            completions,
        )

        assertEquals(SmartCollectionBuilder.ALWAYS_FINISH_LIMIT, result.songs.size)
        assertEquals(55, result.totalEligibleCount)
        assertEquals(SmartCollectionBuilder.ALWAYS_FINISH_LIMIT, result.visibleLimit)
        assertEquals((55L downTo 6L).toList(), result.songs.map { it.id })
    }

    @Test fun `always finish improved song naturally enters capped visible ranking`() {
        val songs = (1L..51L).map { song(it) }
        val initial = songs.map {
            completion(
                songId = it.id,
                nativePlays = 3,
                avgCompletion = if (it.id == 51L) 0.85f else 0.90f,
            )
        }
        val improved = initial.map {
            if (it.songId == 51L) it.copy(avgCompletion = 1f) else it
        }

        val before = alwaysFinish(songs, initial).map { it.id }
        val after = alwaysFinish(songs, improved).map { it.id }

        assertTrue(51L !in before)
        assertEquals(51L, after.first())
        assertEquals(SmartCollectionBuilder.ALWAYS_FINISH_LIMIT, after.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // USUALLY ABANDON — via repeated native skips
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `usually abandon appears for repeated native skips`() {
        // 0 plays, 5 skips — user always bails before the threshold
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 0, nativeSkips = 5))
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `usually abandon requires at least 3 total attempts (plays + skips)`() {
        val songs = listOf(song(1), song(2))
        val completions = listOf(
            completion(1, nativePlays = 0, nativeSkips = 2),   // 2 attempts — excluded
            completion(2, nativePlays = 0, nativeSkips = 3),   // 3 attempts — included
        )
        assertEquals(listOf(2L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `usually abandon excluded when skip rate is below 0_60`() {
        // 2 skips / 5 attempts = 0.40 skip rate — below 0.60
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 3, nativeSkips = 2, validCompletionPlays = 0, avgCompletion = 0.0f))
        assertTrue(usuallyAbandon(songs, completions).isEmpty())
    }

    @Test fun `usually abandon included when skip rate is exactly 0_60`() {
        // 3 skips / 5 attempts = 0.60 — exactly at threshold
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 2, nativeSkips = 3, validCompletionPlays = 0, avgCompletion = 0.0f))
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `usually abandon included when skip rate is above 0_60`() {
        // 4 skips / 5 attempts = 0.80
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 1, nativeSkips = 4))
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // USUALLY ABANDON — via low-completion native plays
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `usually abandon appears for low-completion native plays`() {
        // User listens past threshold (so PLAY is recorded) but consistently stops early
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 4, nativeSkips = 0, validCompletionPlays = 4, avgCompletion = 0.22f),
        )
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `usually abandon excluded when validCompletionPlays below 3 even with low avg`() {
        // Only 2 valid plays — not enough data for the low-completion branch
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 4, nativeSkips = 0, validCompletionPlays = 2, avgCompletion = 0.15f),
        )
        // 0 skips → skip rate = 0 / 4 < 0.60; validCompletionPlays < 3 → low-completion branch fails
        assertTrue(usuallyAbandon(songs, completions).isEmpty())
    }

    @Test fun `usually abandon excluded when avgCompletion is exactly 0_40`() {
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 4, nativeSkips = 0, validCompletionPlays = 4, avgCompletion = 0.40f),
        )
        assertTrue(usuallyAbandon(songs, completions).isEmpty())
    }

    @Test fun `usually abandon included when avgCompletion just below 0_40`() {
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 4, nativeSkips = 0, validCompletionPlays = 4, avgCompletion = 0.399f),
        )
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // USUALLY ABANDON — mixed: skips + plays both contribute
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `usually abandon OR logic — qualifies via skip rate even without valid plays`() {
        // 4 skips, 1 play with no valid durationMs → skip rate = 0.80, but no completion data
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 1, nativeSkips = 4, validCompletionPlays = 0, avgCompletion = 0.0f),
        )
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `usually abandon OR logic — qualifies via low completion even without high skip rate`() {
        // 4 plays, 1 skip → skip rate = 0.20; but avg completion = 0.18 < 0.40 with 4 valid plays
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 4, nativeSkips = 1, validCompletionPlays = 4, avgCompletion = 0.18f),
        )
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `usually abandon ranks full eligible set before applying 30 cap`() {
        val songs = (40L downTo 1L).map { song(it) }
        val completions = songs.map {
            val skips = 60 + it.id.toInt()
            completion(
                songId = it.id,
                nativePlays = 100 - skips,
                nativeSkips = skips,
                validCompletionPlays = 0,
            )
        }

        val result = SmartCollectionBuilder.songsResultFor(
            SmartCollectionType.USUALLY_ABANDON,
            songs,
            noStats,
            completions,
        )

        assertEquals(SmartCollectionBuilder.USUALLY_ABANDON_LIMIT, result.songs.size)
        assertEquals(40, result.totalEligibleCount)
        assertEquals(SmartCollectionBuilder.USUALLY_ABANDON_LIMIT, result.visibleLimit)
        assertEquals((40L downTo 11L).toList(), result.songs.map { it.id })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Empty and imported-only data
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `always finish returns empty when no completion data`() {
        assertTrue(alwaysFinish(listOf(song(1)), emptyList()).isEmpty())
    }

    @Test fun `usually abandon returns empty when no completion data`() {
        assertTrue(usuallyAbandon(listOf(song(1)), emptyList()).isEmpty())
    }

    @Test fun `imported-only songs have no completion summary and are excluded from both collections`() {
        // BlackPlayer imports write no native events — no SongCompletionSummary row for these songs
        val songs = listOf(song(1))
        val completions = emptyList<SongCompletionSummary>()
        assertTrue(alwaysFinish(songs, completions).isEmpty())
        assertTrue(usuallyAbandon(songs, completions).isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Restore / multi-source coverage
    //
    // TrackListenEventDao.observeCompletionSummaries() now includes events from
    // 'manual_restore' and 'wavdrop_desktop_playback' in addition to
    // 'wavdrop_playback'. The DAO SQL runs in the Android Room layer and cannot
    // be tested as a pure JVM unit test; these tests confirm that the
    // SmartCollectionBuilder processes the resulting SongCompletionSummary rows
    // correctly regardless of which trusted source produced them.
    //
    // BlackPlayer events are never written as individual listen events, so they
    // produce no SongCompletionSummary row and are excluded at the DAO level
    // (tested in "imported-only songs" below).
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `always finish repopulates from manual_restore completion events after backup restore`() {
        // After restore, events tagged manual_restore now produce SongCompletionSummary rows.
        // Builder must accept qualifying summaries regardless of their originating source.
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 3, avgCompletion = 0.90f))
        assertEquals(listOf(1L), alwaysFinish(songs, completions).map { it.id })
    }

    @Test fun `always finish repopulates from wavdrop_desktop_playback completion events`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 5, avgCompletion = 0.92f))
        assertEquals(listOf(1L), alwaysFinish(songs, completions).map { it.id })
    }

    @Test fun `usually abandon repopulates from restored skip evidence after backup restore`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 0, nativeSkips = 5))
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `usually abandon repopulates from restored low-completion plays after backup restore`() {
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 4, nativeSkips = 0, validCompletionPlays = 4, avgCompletion = 0.22f),
        )
        assertEquals(listOf(1L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `native wavdrop_playback events continue to qualify for both collections after source filter change`() {
        // Regression: widening the source filter must not affect native-event behaviour.
        val songs = listOf(song(1), song(2))
        val completions = listOf(
            completion(1, nativePlays = 3, avgCompletion = 0.90f),
            completion(2, nativePlays = 0, nativeSkips = 4),
        )
        assertEquals(listOf(1L), alwaysFinish(songs, completions).map { it.id })
        assertEquals(listOf(2L), usuallyAbandon(songs, completions).map { it.id })
    }

    @Test fun `blackplayer import events produce no completion summary and both collections remain empty`() {
        // BlackPlayer imports are aggregate-only; no individual events are written,
        // so no SongCompletionSummary row is produced for these songs.
        val songs = listOf(song(1))
        val completions = emptyList<SongCompletionSummary>()
        assertTrue(alwaysFinish(songs, completions).isEmpty())
        assertTrue(usuallyAbandon(songs, completions).isEmpty())
    }

    @Test fun `orphan completions for library-absent songs are ignored`() {
        val songs = listOf(song(1))
        val completions = listOf(
            completion(1, nativePlays = 3, avgCompletion = 0.90f),
            completion(99, nativePlays = 10, avgCompletion = 0.99f), // songId 99 not in library
        )
        assertEquals(listOf(1L), alwaysFinish(songs, completions).map { it.id })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mixed library scenarios
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `always finish only includes songs meeting both play count and completion criteria`() {
        val songs = (1L..5L).map { song(it) }
        val completions = listOf(
            completion(1, nativePlays = 5, avgCompletion = 0.95f),  // qualifies
            completion(2, nativePlays = 3, avgCompletion = 0.85f),  // qualifies (boundary)
            completion(3, nativePlays = 2, avgCompletion = 0.92f),  // fails: plays < 3
            completion(4, nativePlays = 4, avgCompletion = 0.70f),  // fails: completion < 0.85
            completion(5, nativePlays = 0, avgCompletion = 0.0f),   // fails: no plays
        )
        assertEquals(setOf(1L, 2L), alwaysFinish(songs, completions).map { it.id }.toSet())
    }

    @Test fun `usually abandon includes songs qualifying via either skip rate or low completion`() {
        val songs = (1L..5L).map { song(it) }
        val completions = listOf(
            completion(1, nativePlays = 0, nativeSkips = 5, avgCompletion = 0.0f),   // skip rate 1.0 → qualifies
            completion(2, nativePlays = 4, nativeSkips = 0, validCompletionPlays = 4, avgCompletion = 0.25f), // low completion → qualifies
            completion(3, nativePlays = 1, nativeSkips = 1, avgCompletion = 0.0f),   // only 2 attempts → excluded
            completion(4, nativePlays = 3, nativeSkips = 0, validCompletionPlays = 3, avgCompletion = 0.55f), // completion ok, skip rate low → excluded
            completion(5, nativePlays = 3, nativeSkips = 0, validCompletionPlays = 2, avgCompletion = 0.10f), // validCompletionPlays < 3, skip rate 0 → excluded
        )
        assertEquals(setOf(1L, 2L), usuallyAbandon(songs, completions).map { it.id }.toSet())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // build() integration
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `build omits always finish when no songs qualify`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 1, avgCompletion = 0.99f))
        val types = SmartCollectionBuilder.build(songs, noStats, completions).map { it.type }
        assertTrue(SmartCollectionType.ALWAYS_FINISH !in types)
    }

    @Test fun `build includes always finish and reports correct count`() {
        val songs = (1L..3L).map { song(it) }
        val completions = listOf(
            completion(1, nativePlays = 3, avgCompletion = 0.90f),
            completion(2, nativePlays = 3, avgCompletion = 0.88f),
            completion(3, nativePlays = 2, avgCompletion = 0.95f), // excluded: plays < 3
        )
        val coll = SmartCollectionBuilder.build(songs, noStats, completions)
            .first { it.type == SmartCollectionType.ALWAYS_FINISH }
        assertEquals(2, coll.songCount)
    }

    @Test fun `build includes usually abandon when songs qualify via skips`() {
        val songs = listOf(song(1))
        val completions = listOf(completion(1, nativePlays = 0, nativeSkips = 5))
        val types = SmartCollectionBuilder.build(songs, noStats, completions).map { it.type }
        assertTrue(SmartCollectionType.USUALLY_ABANDON in types)
    }

    @Test fun `build omits both new types with empty completions`() {
        val songs = listOf(song(1))
        val result = SmartCollectionBuilder.build(songs, noStats)
        assertTrue(result.none { it.type == SmartCollectionType.ALWAYS_FINISH })
        assertTrue(result.none { it.type == SmartCollectionType.USUALLY_ABANDON })
    }
}

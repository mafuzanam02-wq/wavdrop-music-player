package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.model.Song
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** WC-08: the pure song-table diff. Mutation counts are asserted, never wall-clock time. */
class SongSyncPlannerTest {

    private fun song(id: Long) = Song(
        id = id, title = "T$id", artist = "A${id % 9}", album = "B${id % 13}", albumId = id % 13 + 1, duration = 180_000L + id,
        uri = "content://media/external/audio/media/$id", dateAdded = 1_700_000_000L + id, trackNumber = (id % 12).toInt(),
        year = 2000 + (id % 25).toInt(), folderPath = "/Music/F${id % 5}", folderName = "F${id % 5}",
    )

    private fun library(n: Int) = (1L..n).map(::song)
    private fun entities(songs: List<Song>) = songs.map { it.toEntity() }

    // ── unchanged / new / stale / changed / mixed ───────────────────────────────────────────────────────────────────────

    @Test fun anUnchangedLibraryProducesNoSongTableWork() {
        val songs = library(500)
        val plan = SongSyncPlanner.plan(entities(songs), songs)
        assertTrue(plan.entitiesToUpsert.isEmpty())
        assertTrue(plan.staleIds.isEmpty())
        assertTrue(plan.newSongs.isEmpty())
        assertEquals(500, plan.unchangedCount)
        assertFalse(plan.hasSongTableWork)
        assertEquals(songs.map { it.id }.toSet(), plan.liveSongIds)
    }

    @Test fun newOnlyStaleOnlyAndChangedOnly() {
        val base = library(50)
        val newOnly = SongSyncPlanner.plan(entities(base), base + song(100))
        assertEquals(listOf(100L), newOnly.newSongs.map { it.id })
        assertEquals(listOf(100L), newOnly.entitiesToUpsert.map { it.id })
        assertTrue(newOnly.staleIds.isEmpty())

        val staleOnly = SongSyncPlanner.plan(entities(base), base.filter { it.id != 7L })
        assertEquals(setOf(7L), staleOnly.staleIds)
        assertTrue(staleOnly.entitiesToUpsert.isEmpty())
        assertEquals(listOf(7L), staleOnly.staleSongs.map { it.id })

        val changedOnly = SongSyncPlanner.plan(entities(base), base.map { if (it.id == 3L) it.copy(title = "Renamed") else it })
        assertEquals(listOf(3L), changedOnly.changedEntities.map { it.id })
        assertEquals(49, changedOnly.unchangedCount)
        assertTrue(changedOnly.staleIds.isEmpty() && changedOnly.newSongs.isEmpty())
    }

    @Test fun mixedNewChangedStaleAndUnchanged() {
        val base = library(100)
        val scan = base.filter { it.id !in 1L..2L }.map { if (it.id in 10L..12L) it.copy(year = 1999) else it } + song(500) + song(501) + song(502)
        val plan = SongSyncPlanner.plan(entities(base), scan)
        assertEquals(setOf(1L, 2L), plan.staleIds)
        assertEquals(listOf(500L, 501L, 502L), plan.newSongs.map { it.id })
        assertEquals(listOf(10L, 11L, 12L), plan.changedEntities.map { it.id })
        assertEquals(95, plan.unchangedCount)
        assertEquals(listOf(10L, 11L, 12L, 500L, 501L, 502L), plan.entitiesToUpsert.map { it.id })
    }

    // ── every persisted field ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun eachPersistedFieldChangeIsDetectedAsChangedNotNew() {
        val s = song(1)
        val variants = mapOf(
            "title" to s.copy(title = "x"), "artist" to s.copy(artist = "x"), "album" to s.copy(album = "x"),
            "albumId" to s.copy(albumId = s.albumId + 1), "duration" to s.copy(duration = s.duration + 1),
            "uri" to s.copy(uri = s.uri + "x"), "dateAdded" to s.copy(dateAdded = s.dateAdded + 1),
            "trackNumber" to s.copy(trackNumber = s.trackNumber + 1), "year" to s.copy(year = s.year + 1),
            "folderPath" to s.copy(folderPath = "/other"), "folderName" to s.copy(folderName = "other"),
            "folderPath null" to s.copy(folderPath = null), "folderName null" to s.copy(folderName = null),
        )
        for ((field, changed) in variants) {
            val plan = SongSyncPlanner.plan(listOf(s.toEntity()), listOf(changed))
            assertEquals(field, listOf(1L), plan.changedEntities.map { it.id })
            assertEquals(field, changed.toEntity(), plan.entitiesToUpsert.single())
            assertTrue("$field must not be a new song (playlist remap boundary)", plan.newSongs.isEmpty())
            assertTrue(field, plan.staleSongs.isEmpty())
        }
        val fromNull = SongSyncPlanner.plan(listOf(s.copy(folderPath = null, folderName = null).toEntity()), listOf(s))
        assertEquals(listOf(1L), fromNull.changedEntities.map { it.id })
        assertEquals(1, SongSyncPlanner.plan(listOf(s.toEntity()), listOf(s)).unchangedCount)
    }

    @Test fun everyEntityFieldIsCoveredByTheEqualityContract() {
        // If a column is added to SongEntity this fails, forcing the planner/test matrix to be revisited.
        val fields = SongEntity::class.java.declaredFields.filterNot { it.isSynthetic || it.name.startsWith("$") }.map { it.name }.toSet()
        assertEquals(
            setOf("id", "title", "artist", "album", "albumId", "duration", "uri", "dateAdded", "trackNumber", "year", "folderPath", "folderName"),
            fields,
        )
    }

    // ── determinism ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun inputOrderNeverChangesThePlan() {
        val rnd = Random(8)
        val base = library(300)
        val scan = base.filter { it.id % 11L != 0L }.map { if (it.id % 17L == 0L) it.copy(album = "Z") else it } + (1000L..1010L).map(::song)
        val reference = SongSyncPlanner.plan(entities(base), scan)
        repeat(20) {
            val p = SongSyncPlanner.plan(entities(base).shuffled(rnd), scan.shuffled(rnd))
            assertEquals(reference.newSongs, p.newSongs)
            assertEquals(reference.changedEntities, p.changedEntities)
            assertEquals(reference.staleSongs, p.staleSongs)
            assertEquals(reference.entitiesToUpsert, p.entitiesToUpsert)
            assertEquals(reference.staleIds, p.staleIds)
            assertEquals(reference.unchangedCount, p.unchangedCount)
        }
    }

    @Test fun duplicateScannedIdsFailClosedInsteadOfSilentLastWriteWins() {
        try {
            SongSyncPlanner.plan(entities(library(5)), library(5) + song(3).copy(title = "dup"))
            fail("expected DuplicateScannedSongIdException")
        } catch (e: DuplicateScannedSongIdException) {
            assertEquals(setOf(3L), e.duplicateIds)
        }
    }

    // ── scale: counts only ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun largeUnchangedLibrariesWriteAndDeleteNothing() {
        for (n in listOf(1_000, 5_000, 10_000, 25_000)) {
            val songs = library(n)
            val plan = SongSyncPlanner.plan(entities(songs), songs.reversed())
            assertEquals("n=$n upserts (old behaviour: $n)", 0, plan.entitiesToUpsert.size)
            assertEquals("n=$n deletes", 0, plan.staleIds.size)
            assertEquals(n, plan.unchangedCount)
        }
    }

    @Test fun aSparseChangeOn25kLibraryWritesOnlyTheTouchedRows() {
        val base = library(25_000)
        val scan = base.filter { it.id !in setOf(24_000L, 24_001L) } // 2 removed
            .map { if (it.id in 100L..106L) it.copy(title = "edited ${it.id}") else it } // 7 changed
            .plus((90_001L..90_003L).map(::song)) // 3 new
        val plan = SongSyncPlanner.plan(entities(base), scan)
        assertEquals(10, plan.entitiesToUpsert.size) // 7 changed + 3 new, not 25,001
        assertEquals(2, plan.staleIds.size)
        assertEquals(3, plan.newSongs.size)
        assertEquals(7, plan.changedEntities.size)
        assertEquals(24_991, plan.unchangedCount)
        assertTrue("changed rows are never remap candidates", plan.newSongs.none { it.id in 100L..106L })
    }

    // ── guards ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun syncOnlyWritesThroughThePlannerAndNeverWithTheWholeScan() {
        val src = File("src/main/kotlin/com/launchpoint/wavdrop/data/repository/SongRepository.kt").readText().replace("\r\n", "\n")
        assertFalse("sync must not upsert the whole scan", src.contains("dao.upsertAll(found"))
        assertTrue(src.contains("SongSyncPlanner.plan(existingEntities, found)"))
        assertTrue(src.contains("applySongSyncPlan(plan, remapPlan, dao, playlistDao)"))
        assertTrue(src.contains("newSongs = plan.newSongs"))
        assertTrue("identity reconciliation stays unconditional", src.contains("reconcileIdentities(plan.liveSongIds)"))
    }
}

package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-AUDIT-11. A MediaStore content URI is supporting evidence, never identity. An exact URI is accepted only when the backup song's own
 * duration (within the existing 2,000 ms tolerance) AND normalized title corroborate it; a rejected candidate never re-enters through a
 * weaker tier; a stat row with no song descriptor is never attributed by URI alone.
 */
class BackupUriCollisionSafetyTest {

    private fun local(
        id: Long, uri: String = "content://media/external/audio/media/$id", title: String = "Title $id", artist: String = "Artist $id",
        album: String = "Album $id", duration: Long = 180_000L, folder: String? = null,
    ) = Song(id, title, artist, album, 0L, duration, uri, 0L, 0, 0, folder)

    private fun bsong(
        id: Long, uri: String = "content://media/external/audio/media/$id", title: String = "Title $id", artist: String = "Artist $id",
        album: String = "Album $id", duration: Long = 180_000L, folder: String? = null,
    ) = BackupSong(id, uri, title, artist, album, 0L, duration, 0L, 0, 0, folder, null)

    private fun stat(songId: Long, uri: String = "content://media/external/audio/media/$songId", plays: Int = 99, fav: Boolean = true) =
        BackupTrackStats(songId, uri, plays, 0, 1_000L, 9_000_000L, fav, 1_000L)

    private fun backup(songs: List<BackupSong>, stats: List<BackupTrackStats> = songs.map { stat(it.id) }) =
        WavdropBackup("2026-01-01T00:00:00Z", songs, stats, emptyList())

    private fun match(b: WavdropBackup, vararg locals: Song) = WavdropBackupStatsMatcher.match(b, locals.toList())
    private fun resolvedIds(b: WavdropBackup, vararg locals: Song) = WavdropBackupStatsMatcher.resolveBackupSongIds(b, locals.toList())

    // ── 1. accepted ───────────────────────────────────────────────────────────

    @Test fun `1 exact URI with matching metadata is accepted at the URI tier`() {
        val l = local(5)
        val r = match(backup(listOf(bsong(5))), l)
        assertEquals(listOf(l), r.matchedRows.map { it.first })
        assertEquals(1, r.diagnostics.matchedByUri)
    }

    // ── 2-4. collisions are rejected ──────────────────────────────────────────

    @Test fun `2 exact URI with unrelated title and duration is rejected`() {
        val r = match(backup(listOf(bsong(5, title = "Foreign Song", artist = "Foreign", album = "Foreign LP", duration = 310_000L))), local(5, duration = 180_000L))
        assertTrue("collision must not attribute 99 plays and a favorite", r.matchedRows.isEmpty())
        assertEquals(1, r.unmatchedCount)
    }

    @Test fun `3 exact URI with unrelated folder and metadata is rejected even when the duration happens to agree`() {
        val r = match(
            backup(listOf(bsong(5, title = "Other Track", folder = "Download/Other", duration = 180_500L))),
            local(5, title = "Local Track", folder = "Music/Mine", duration = 180_000L),
        )
        assertTrue(r.matchedRows.isEmpty())
        assertEquals(1, r.unmatchedCount)
    }

    @Test fun `4 cross-device style overlapping ids never attribute foreign history`() {
        val ids = listOf(1_000_000_033L, 1_000_000_034L, 1_000_000_035L)
        val backup = backup(ids.map { bsong(it, title = "Foreign $it", artist = "F", album = "F", duration = 250_000L + it % 7) })
        val locals = ids.map { local(it, title = "Mine $it", duration = 120_000L) }
        val r = WavdropBackupStatsMatcher.match(backup, locals)
        assertTrue(r.matchedRows.isEmpty())
        assertEquals(3, r.unmatchedCount)
        assertTrue(WavdropBackupStatsMatcher.resolveBackupSongIds(backup, locals).isEmpty())
    }

    // ── 5. missing descriptor ─────────────────────────────────────────────────

    @Test fun `5 a stat row without a backup song descriptor is never attributed by URI alone`() {
        val l = local(5)
        val b = backup(songs = emptyList(), stats = listOf(stat(5)))
        val r = match(b, l)
        assertTrue(r.matchedRows.isEmpty())
        assertEquals(1, r.unmatchedCount)
    }

    // ── 6-7. legitimate retag / move with corroboration ──────────────────────

    @Test fun `6a retagged album with the same title and duration is accepted`() {
        val l = local(5, album = "Remastered Album")
        assertEquals(listOf(l), match(backup(listOf(bsong(5, title = "Title 5", album = "Old Album"))), l).matchedRows.map { it.first })
    }

    @Test fun `6b changed artist with the same title and duration is accepted`() {
        val l = local(5, artist = "Corrected Artist")
        assertEquals(listOf(l), match(backup(listOf(bsong(5, artist = "Old Artist"))), l).matchedRows.map { it.first })
    }

    @Test fun `7 a file moved to another folder keeps its URI match when title and duration agree`() {
        val l = local(5, folder = "Music/NewHome")
        val r = match(backup(listOf(bsong(5, folder = "Download/OldHome"))), l)
        assertEquals(listOf(l), r.matchedRows.map { it.first })
        assertEquals(1, r.diagnostics.matchedByUri)
    }

    @Test fun `7b tolerant title equality counts (accents and low-value suffix)`() {
        val l = local(5, title = "Café Song")
        assertEquals(1, match(backup(listOf(bsong(5, title = "Cafe Song (Official Audio)"))), l).matchedRows.size)
    }

    // ── 8. a rejected candidate must not re-enter through a weaker tier ──────

    @Test fun `8 a URI collision with identical tags but a contradicting duration is not re-accepted by the tags-only tier`() {
        val l = local(5, title = "Same", artist = "Same", album = "Same", duration = 180_000L)
        val r = match(backup(listOf(bsong(5, title = "Same", artist = "Same", album = "Same", duration = 310_000L))), l)
        assertTrue("the contradicted candidate must stay rejected", r.matchedRows.isEmpty())
        assertEquals(1, r.unmatchedCount)
        assertTrue(resolvedIds(backup(listOf(bsong(5, title = "Same", artist = "Same", album = "Same", duration = 310_000L))), l).isEmpty())
    }

    @Test fun `8b control - without a URI collision the existing tags-only weak tier still matches a changed duration`() {
        val l = local(9, uri = "content://media/external/audio/media/9", title = "Same", artist = "Same", album = "Same", duration = 180_000L)
        val b = backup(listOf(bsong(5, uri = "content://media/external/audio/media/5", title = "Same", artist = "Same", album = "Same", duration = 310_000L)))
        assertEquals("existing fallback tiers are not globally disabled", listOf(l), match(b, l).matchedRows.map { it.first })
    }

    @Test fun `8c an ordinary stale URI resolves to the correct file found by other evidence, never the colliding one`() {
        val colliding = local(5, title = "Unrelated", duration = 100_000L)
        val correct = local(77, uri = "content://media/external/audio/media/77", title = "Foreign Song", artist = "Foreign", album = "LP", duration = 250_000L)
        val b = backup(listOf(bsong(5, title = "Foreign Song", artist = "Foreign", album = "LP", duration = 250_000L)))
        assertEquals(listOf(correct), match(b, colliding, correct).matchedRows.map { it.first })
    }

    // ── 9. duplicates stay unresolved ─────────────────────────────────────────

    @Test fun `9a two current songs sharing one URI that both corroborate are ambiguous, never an arbitrary pick`() {
        val a = local(5, uri = "content://dup", title = "Same"); val b = local(6, uri = "content://dup", title = "Same")
        val r = match(backup(listOf(bsong(1, uri = "content://dup", title = "Same"))), a, b)
        assertTrue(r.matchedRows.isEmpty())
        assertEquals(1, r.unmatchedCount)
    }

    @Test fun `9b identical metadata duplicates with different URIs stay ambiguous`() {
        val a = local(5, title = "Same", artist = "A", album = "B"); val b = local(6, title = "Same", artist = "A", album = "B")
        val r = match(backup(listOf(bsong(99, uri = "content://gone", title = "Same", artist = "A", album = "B"))), a, b)
        assertTrue(r.matchedRows.isEmpty())
    }

    // ── 10. consistency between stat matching and the song-id mapping ───────

    @Test fun `10 stat matching and the song id mapping make the same decision`() {
        val good = local(5); val colliding = local(6, title = "Mine")
        val b = backup(listOf(bsong(5), bsong(6, title = "Foreign", duration = 400_000L)))
        val stats = match(b, good, colliding).matchedRows.map { it.second.songId }.toSet()
        val mapped = resolvedIds(b, good, colliding).keys
        assertEquals(setOf(5L), stats)
        assertEquals(stats, mapped)
    }

    // ── placeholders, boundaries ──────────────────────────────────────────────

    @Test fun `placeholder or blank titles and missing durations are not positive corroboration`() {
        val placeholder = match(backup(listOf(bsong(5, title = "Unknown Title", duration = 180_000L))), local(5, title = "Unknown Title"))
        assertTrue("two placeholder titles prove nothing", placeholder.matchedRows.isEmpty())
        val blank = match(backup(listOf(bsong(6, title = "  "))), local(6, title = ""))
        assertTrue(blank.matchedRows.isEmpty())
        val noDuration = match(backup(listOf(bsong(7, duration = 0L))), local(7, duration = 0L))
        assertTrue("a zero duration is unknown, not agreement", noDuration.matchedRows.isEmpty())
    }

    @Test fun `duration tolerance stays exactly 2000 ms for URI corroboration`() {
        assertEquals(1, match(backup(listOf(bsong(5, duration = 182_000L))), local(5)).matchedRows.size)
        assertTrue(match(backup(listOf(bsong(5, duration = 182_001L))), local(5)).matchedRows.isEmpty())
    }
}

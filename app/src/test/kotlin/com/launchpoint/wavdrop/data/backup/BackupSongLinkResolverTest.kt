package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSongLinkResolverTest {

    private fun song(
        id: Long,
        uri: String = "content://media/$id",
        title: String = "Title $id",
        artist: String = "Artist $id",
        album: String = "Album $id",
    ) = Song(
        id = id, title = title, artist = artist, album = album,
        albumId = 0L, duration = 180_000L, uri = uri, dateAdded = 0L,
        trackNumber = 0, year = 0,
    )

    // ── Tier-matched songId is preferred (reinstall ID change) ────────────────

    @Test
    fun `resolves through tier-matched songId after reinstall ID change`() {
        val current = song(42L)
        val resolver = BackupSongLinkResolver(
            currentSongs     = listOf(current),
            resolvedBySongId = mapOf(1L to current), // backup id 1 → current id 42
        )
        val resolved = resolver.resolve(
            backupSongId = 1L,
            contentUri   = "content://media/OLD/1", // dead URI
            title        = "Wrong", artist = "Wrong", album = "Wrong",
        )
        assertEquals(42L, resolved?.id)
    }

    // ── URI fallback ──────────────────────────────────────────────────────────

    @Test
    fun `a bare URI never resolves a row when songId is not resolved (P5-AUDIT-11)`() {
        // Previously pinned the opposite: a MediaStore URI alone attached rows to whatever file held it. The link resolver has no duration
        // and cannot corroborate, so the URI step was removed; validated songId mapping and unique tags remain.
        val current = song(7L, uri = "content://media/7")
        val resolver = BackupSongLinkResolver(listOf(current), emptyMap())
        assertNull(resolver.resolve(99L, "content://media/7", null, null, null))
    }

    @Test
    fun `tags fallback cannot reintroduce a URI-colliding candidate the matcher rejected`() {
        val colliding = song(7L, uri = "content://media/7", title = "Song", artist = "Band", album = "LP")
        val resolver = BackupSongLinkResolver(listOf(colliding), emptyMap(), mapOf(99L to setOf(7L)))
        assertNull(resolver.resolve(99L, "content://media/7", "Song", "Band", "LP"))
        // A different backup song with the same tags is unaffected by 99's rejection.
        assertEquals(7L, resolver.resolve(100L, null, "Song", "Band", "LP")?.id)
    }

    @Test
    fun `validated songId mapping still wins over rejection and URI`() {
        val current = song(7L)
        val resolver = BackupSongLinkResolver(listOf(current), mapOf(99L to current), mapOf(99L to setOf(7L)))
        assertEquals(7L, resolver.resolve(99L, null, null, null, null)?.id)
    }

    // ── Tags fallback: unique only ────────────────────────────────────────────

    @Test
    fun `falls back to tags when exactly one song carries them`() {
        val current = song(7L, title = "Song", artist = "Band", album = "LP")
        val resolver = BackupSongLinkResolver(listOf(current, song(8L)), emptyMap())
        val resolved = resolver.resolve(99L, "content://dead", "Song", "Band", "LP")
        assertEquals(7L, resolved?.id)
    }

    @Test
    fun `duplicate-tag songs are never guessed at`() {
        val dupA = song(7L, title = "Song", artist = "Band", album = "LP")
        val dupB = song(8L, uri = "content://media/8b", title = "Song", artist = "Band", album = "LP")
        val resolver = BackupSongLinkResolver(listOf(dupA, dupB), emptyMap())
        val resolved = resolver.resolve(99L, "content://dead", "Song", "Band", "LP")
        assertNull("Ambiguous tags must resolve to nothing, not a guess", resolved)
    }

    @Test
    fun `tags comparison is case and whitespace insensitive`() {
        val current = song(7L, title = "Song", artist = "Band", album = "LP")
        val resolver = BackupSongLinkResolver(listOf(current), emptyMap())
        val resolved = resolver.resolve(99L, null, "  SONG ", "band", "lp")
        assertEquals(7L, resolved?.id)
    }

    @Test
    fun `unresolvable row returns null`() {
        val resolver = BackupSongLinkResolver(listOf(song(7L)), emptyMap())
        assertNull(resolver.resolve(99L, "content://dead", "Nope", "Nope", "Nope"))
        assertNull(resolver.resolve(99L, null, null, null, null))
    }

    // ── Lyrics scenario: ID change + duplicate protection (B, C) ─────────────

    @Test
    fun `lyrics attach to the correct song after ID change via the matcher mapping`() {
        // Reinstall: backup song id 1 (dead URI) tier-matches to current id 42.
        val rescanned = song(42L, uri = "content://media/NEW/42", title = "Track", artist = "A", album = "B")
        val backup = WavdropBackup(
            exportedAt = "2026-06-11T00:00:00Z",
            songs = listOf(
                BackupSong(
                    id = 1L, uri = "content://media/OLD/1",
                    title = "Track", artist = "A", album = "B",
                    albumId = 0L, duration = 180_000L, dateAdded = 0L, trackNumber = 0, year = 0,
                ),
            ),
            trackStats = emptyList(), importBaselines = emptyList(),
        )
        val resolved = WavdropBackupStatsMatcher.resolveBackupSongIds(backup, listOf(rescanned))
        val resolver = BackupSongLinkResolver(listOf(rescanned), resolved)

        val target = resolver.resolve(1L, "content://media/OLD/1", "Track", "A", "B")
        assertEquals(42L, target?.id)
    }
}

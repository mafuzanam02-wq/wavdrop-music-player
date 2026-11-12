package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-REL-04B - a stale or reused MediaStore URI must not attach a backup row to a DIFFERENT artist's song.
 *
 * Policy under test (see [WavdropBackupStatsMatcher]): a URI is supporting evidence only. Besides duration and a meaningful tolerant
 * title it must not be CONTRADICTED by a known artist. Two artists contradict each other only when BOTH are known (neither blank nor
 * an "unknown" placeholder) and their tolerant keys differ. An unknown artist neither corroborates nor contradicts: it never turns a
 * URI into a match by itself (title and duration are still required) and it never rejects one. A rejected local song is excluded
 * from every later tier of the same row, so a weaker tier cannot re-accept it.
 *
 * All data is synthetic.
 */
class UriArtistCorroborationTest {

    private fun song(
        id: Long, uri: String, title: String = "Harbor", artist: String = "Artist North", album: String = "Album One",
        duration: Long = 170_000, folderPath: String? = null,
    ) = Song(
        id = id, title = title, artist = artist, album = album, albumId = 0L, duration = duration, uri = uri,
        dateAdded = 0L, trackNumber = 0, year = 0, folderPath = folderPath,
    )

    private fun bSong(
        id: Long, uri: String, title: String = "Harbor", artist: String = "Artist North", album: String = "Album One",
        duration: Long = 170_000, folderPath: String? = null,
    ) = BackupSong(
        id = id, uri = uri, title = title, artist = artist, album = album, albumId = 0L, duration = duration,
        dateAdded = 0L, trackNumber = 0, year = 0, folderPath = folderPath,
    )

    private fun stat(songId: Long, uri: String, plays: Int = 7, skips: Int = 2) = BackupTrackStats(
        songId = songId, contentUri = uri, playCount = plays, skipCount = skips,
        lastPlayedAt = 1_000L, totalListeningTimeMs = 5_000L, isFavorite = false, lastListenedAt = 1_000L,
    )

    private fun backup(songs: List<BackupSong>, stats: List<BackupTrackStats>, events: List<BackupListenEvent> = emptyList()) =
        WavdropBackup(exportedAt = "", songs = songs, trackStats = stats, importBaselines = emptyList(), listenEvents = events)

    private fun event(songId: Long, uri: String, artist: String, at: Long, id: String) = BackupListenEvent(
        songId = songId, contentUri = uri, title = "Harbor", artist = artist, album = "Album One", eventType = "PLAY",
        occurredAt = at, listenedMs = 120_000L, durationMs = 170_000L, source = "wavdrop_playback", eventId = id,
    )

    private val reusedUri = "content://media/500"

    // ── A. accepted ───────────────────────────────────────────────────────────

    @Test
    fun `A - a URI with matching title artist and duration is accepted as an exact match`() {
        val live = song(1, reusedUri)
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(live))

        assertEquals(live, result.matchedRows.single().first)
        assertEquals(1, result.diagnostics.matchedByUri)
    }

    // ── B / C. the unsafe scenario ────────────────────────────────────────────

    @Test
    fun `B - the same URI title and near-equal duration but a different known artist is not an exact match`() {
        val liveOtherArtist = song(1, reusedUri, artist = "Artist South", album = "Album Two", duration = 168_900)
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(liveOtherArtist))

        assertTrue("the stale URI must not attach the statistics to the other artist's song", result.matchedRows.isEmpty())
        assertEquals(1, result.unmatchedCount)
        assertEquals(0, result.diagnostics.matchedByUri)
    }

    @Test
    fun `C - the rejected local song is excluded from every weaker tier and reported as rejected`() {
        // Title+duration (tier 5) would accept this song if it were not excluded: it is the only same-title candidate.
        val liveOtherArtist = song(1, reusedUri, artist = "Artist South", album = "Album Two", duration = 169_500)
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)))

        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, listOf(liveOtherArtist))

        assertTrue(resolution.resolved.isEmpty())
        assertEquals(setOf(1L), resolution.rejectedLocalIds[11L])
        assertTrue(WavdropBackupStatsMatcher.match(b, listOf(liveOtherArtist)).matchedRows.isEmpty())
    }

    // ── D. an independently corroborated alternative is still found ──────────────

    @Test
    fun `D - the true song is still matched by tags while the contradicted URI owner is not reattached`() {
        val uriOwner = song(1, reusedUri, artist = "Artist South", album = "Album Two", duration = 169_000)
        val trueSong = song(2, "content://media/new/2")
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri, plays = 42)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(uriOwner, trueSong))

        assertEquals(trueSong, result.matchedRows.single().first)
        assertEquals(42, result.matchedRows.single().second.playCount)
        assertEquals(1, result.diagnostics.matchedByTagsDuration)
        assertEquals(setOf(1L), WavdropBackupStatsMatcher.resolveBackupSongs(b, listOf(uriOwner, trueSong)).rejectedLocalIds[11L])
    }

    // ── E. empty / unknown / placeholder artist ─────────────────────────────────

    @Test
    fun `E - an unknown artist on either side neither contradicts nor corroborates the URI`() {
        for (placeholder in listOf("", "   ", "Unknown Artist", "unknown artist", "<unknown>", "<UNKNOWN>")) {
            val liveKnown = song(1, reusedUri, artist = "Artist North")
            val backupUnknown = backup(listOf(bSong(11, reusedUri, artist = placeholder)), listOf(stat(11, reusedUri)))
            assertEquals("backup artist '$placeholder'", liveKnown, WavdropBackupStatsMatcher.match(backupUnknown, listOf(liveKnown)).matchedRows.single().first)

            val liveUnknown = song(1, reusedUri, artist = placeholder)
            val backupKnown = backup(listOf(bSong(11, reusedUri, artist = "Artist North")), listOf(stat(11, reusedUri)))
            assertEquals("live artist '$placeholder'", liveUnknown, WavdropBackupStatsMatcher.match(backupKnown, listOf(liveUnknown)).matchedRows.single().first)
        }
    }

    @Test
    fun `E - an unknown artist never overrides contradictory title or duration`() {
        val live = song(1, reusedUri, title = "Different Title", artist = "Unknown Artist")
        val b = backup(listOf(bSong(11, reusedUri, artist = "Unknown Artist")), listOf(stat(11, reusedUri)))

        assertTrue(WavdropBackupStatsMatcher.match(b, listOf(live)).matchedRows.isEmpty())
    }

    // ── F. harmless normalization ────────────────────────────────────────────────

    @Test
    fun `F - artist names differing only by case whitespace accents or apostrophes still corroborate`() {
        val pairs = listOf(
            "Artist North" to "  artist   NORTH ",
            "Beyoncé" to "Beyonce",
            "Guns N' Roses" to "Guns N’ Roses",
            "Sigur Rós" to "sigur ros",
        )
        for ((liveArtist, backupArtist) in pairs) {
            val live = song(1, reusedUri, artist = liveArtist)
            val b = backup(listOf(bSong(11, reusedUri, artist = backupArtist)), listOf(stat(11, reusedUri)))
            assertEquals("$liveArtist vs $backupArtist", live, WavdropBackupStatsMatcher.match(b, listOf(live)).matchedRows.single().first)
        }
    }

    // ── G. existing title / duration rejection is intact ─────────────────────────

    @Test
    fun `G - a contradictory title or duration is still rejected`() {
        val otherTitle = song(1, reusedUri, title = "Another Song")
        val otherDuration = song(2, "content://media/501", duration = 240_000)
        val b1 = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)))
        val b2 = backup(listOf(bSong(12, "content://media/501")), listOf(stat(12, "content://media/501")))

        assertTrue(WavdropBackupStatsMatcher.match(b1, listOf(otherTitle)).matchedRows.isEmpty())
        assertTrue(WavdropBackupStatsMatcher.match(b2, listOf(otherDuration)).matchedRows.isEmpty())
    }

    // ── H. several URI candidates ────────────────────────────────────────────────

    @Test
    fun `H - two live rows sharing a URI that are both corroborated stay ambiguous`() {
        val first = song(1, reusedUri)
        val second = song(2, reusedUri)
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(first, second))

        assertTrue(result.matchedRows.isEmpty())
        assertEquals(1, result.diagnostics.ambiguous)
    }

    @Test
    fun `H - of two live rows sharing a URI only the one whose artist agrees is corroborated`() {
        val sameArtist = song(1, reusedUri)
        val otherArtist = song(2, reusedUri, artist = "Artist South", album = "Album Two")
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(otherArtist, sameArtist))

        assertEquals(sameArtist, result.matchedRows.single().first)
        assertEquals(1, result.diagnostics.matchedByUri)
    }

    // ── I. collisions between backup rows ────────────────────────────────────────

    @Test
    fun `I - two backup rows for one local song still keep only the strongest`() {
        val live = song(1, reusedUri)
        val exact = bSong(11, reusedUri)
        val retagged = bSong(12, "content://media/old/12")
        val b = backup(listOf(exact, retagged), listOf(stat(11, reusedUri, plays = 9), stat(12, "content://media/old/12", plays = 2)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(live))

        assertEquals(9, result.matchedRows.single().second.playCount)
        assertEquals(1, result.diagnostics.collisions)
    }

    // ── J. preservation ──────────────────────────────────────────────────────────

    @Test
    fun `J - rejected statistics and event identities are carried unchanged into the quarantine plan`() {
        val liveOtherArtist = song(1, reusedUri, artist = "Artist South", album = "Album Two", duration = 169_000)
        val events = listOf(
            event(11, reusedUri, "Artist North", at = 1_700_000_000_000L, id = "evt-1"),
            event(11, reusedUri, "Artist North", at = 1_700_000_500_000L, id = "evt-2"),
        )
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri, plays = 42, skips = 5)), events)
        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, listOf(liveOtherArtist))

        val plan = QuarantinePlanner.plan(b, "fp", resolution.resolved.keys, emptySet())

        val candidate = plan.newCandidates.single()
        assertEquals(42, candidate.stats!!.playCount)
        assertEquals(5, candidate.stats!!.skipCount)
        assertEquals(listOf("evt-1", "evt-2"), candidate.events.map { it.first.eventId })
        assertEquals(listOf(1_700_000_000_000L, 1_700_000_500_000L), candidate.events.map { it.first.occurredAt })
    }

    // ── K. event restoration cannot bypass the rejection ─────────────────────────

    @Test
    fun `K - events of a URI-rejected backup song are not attached to the contradicted song`() {
        val liveOtherArtist = song(1, reusedUri, artist = "Artist South", album = "Album Two", duration = 169_000)
        val events = listOf(event(11, reusedUri, "Artist North", at = 1_700_000_000_000L, id = "evt-1"))
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)), events)
        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, listOf(liveOtherArtist))
        val linker = BackupSongLinkResolver(listOf(liveOtherArtist), resolution.resolved, resolution.rejectedLocalIds)

        val plan = ListenEventRestorePlanner.plan(
            events = events,
            resolveSong = { e -> linker.resolve(e.songId, e.contentUri, e.title, e.artist, e.album) },
        )

        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.skippedUnmatched)
    }

    @Test
    fun `K - the link resolver never re-attaches a rejected song even when its tags happen to match the event`() {
        val liveOtherArtist = song(1, reusedUri, artist = "Artist South", album = "Album Two", duration = 169_000)
        val b = backup(listOf(bSong(11, reusedUri)), listOf(stat(11, reusedUri)))
        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, listOf(liveOtherArtist))
        val linker = BackupSongLinkResolver(listOf(liveOtherArtist), resolution.resolved, resolution.rejectedLocalIds)

        assertNull(linker.resolve(11, reusedUri, "Harbor", "Artist South", "Album Two"))
    }

    // ── L. real-world compatibility ──────────────────────────────────────────────

    @Test
    fun `L - a reinstall with new URIs still matches by tags and duration`() {
        val live = song(1, "content://media/new/1")
        val b = backup(listOf(bSong(11, "content://media/old/11")), listOf(stat(11, "content://media/old/11")))

        val result = WavdropBackupStatsMatcher.match(b, listOf(live))

        assertEquals(live, result.matchedRows.single().first)
        assertEquals(1, result.diagnostics.matchedByTagsDuration)
    }

    @Test
    fun `L - a same-device rescan with an unchanged URI and full metadata still matches by URI`() {
        val live = song(1, reusedUri, album = "Album One ", duration = 171_200, folderPath = "Music")
        val b = backup(listOf(bSong(11, reusedUri, duration = 170_000, folderPath = "Music")), listOf(stat(11, reusedUri)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(live))

        assertEquals(1, result.diagnostics.matchedByUri)
    }
}

package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-REL-04A - CHARACTERIZATION of how Recovery/Merge statistics matching behaves when two DIFFERENT live songs share a normalized
 * title and have durations inside [WavdropBackupStatsMatcher.DURATION_TOLERANCE_MS] (observed on the Samsung A15 as two songs titled
 * "Home" whose statistics stayed in pending storage while their listen events were restored).
 *
 * These tests assert CURRENT behavior. They are not a statement that the behavior is the desired end state: where a test pins a
 * behavior that looks questionable it says so. Nothing here changes production code. All fixtures are synthetic; no real listening
 * history is used.
 *
 * Mechanism pinned below:
 *  - [WavdropBackupStatsMatcher] walks its tiers in a fixed order and takes the FIRST tier that has any candidate. If that tier has
 *    more than one distinct candidate the row is AMBIGUOUS and later (stronger-in-information) tiers are never consulted.
 *  - Folder+title+duration (tier 2) is artist-blind and precedes title+artist+album+duration (tier 3), so two songs in one folder
 *    with the same title and near-equal durations collide at tier 2 although tier 3 would identify each uniquely.
 *  - Listen events resolve through [BackupSongLinkResolver], which falls back to title+artist+album UNIQUENESS, so the same two
 *    songs resolve for events but not for statistics.
 */
class StatsMatchingAmbiguityCharacterizationTest {

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun song(
        id: Long, uri: String, title: String, artist: String, album: String, duration: Long, folderPath: String? = "Music",
    ) = Song(
        id = id, title = title, artist = artist, album = album, albumId = 0L, duration = duration, uri = uri,
        dateAdded = 0L, trackNumber = 0, year = 0, folderPath = folderPath,
    )

    private fun bSong(
        id: Long, uri: String, title: String, artist: String, album: String, duration: Long, folderPath: String? = "Music",
    ) = BackupSong(
        id = id, uri = uri, title = title, artist = artist, album = album, albumId = 0L, duration = duration,
        dateAdded = 0L, trackNumber = 0, year = 0, folderPath = folderPath,
    )

    private fun stat(songId: Long, uri: String, plays: Int, skips: Int) = BackupTrackStats(
        songId = songId, contentUri = uri, playCount = plays, skipCount = skips,
        lastPlayedAt = 1_000L, totalListeningTimeMs = 5_000L, isFavorite = false, lastListenedAt = 1_000L,
    )

    private fun event(songId: Long, uri: String, title: String, artist: String, album: String, at: Long, id: String?) = BackupListenEvent(
        songId = songId, contentUri = uri, title = title, artist = artist, album = album, eventType = "PLAY",
        occurredAt = at, listenedMs = 120_000L, durationMs = 170_000L, source = "wavdrop_playback", eventId = id,
    )

    private fun backup(
        songs: List<BackupSong>, stats: List<BackupTrackStats>, events: List<BackupListenEvent> = emptyList(),
    ) = WavdropBackup(
        exportedAt = "", songs = songs, trackStats = stats, importBaselines = emptyList(), listenEvents = events,
    )

    // The colliding pair: same normalized title, different artists/albums, durations 1,320 ms apart, same folder.
    private val liveNorth = song(101, "content://media/101", "Harbor", "Artist North", "Album One", 170_424)
    private val liveSouth = song(102, "content://media/102", "harbor", "Artist South", "Album Two", 169_104)
    private val live = listOf(liveNorth, liveSouth)

    // The same two tracks as they appear in a backup from ANOTHER installation (different ids and URIs, same folder name).
    private fun backupNorth(folder: String? = "Music") = bSong(11, "content://media/old/11", "Harbor", "Artist North", "Album One", 170_424, folder)
    private fun backupSouth(folder: String? = "Music") = bSong(12, "content://media/old/12", "harbor", "Artist South", "Album Two", 169_104, folder)
    private fun bothStats() = listOf(stat(11, "content://media/old/11", 11, 3), stat(12, "content://media/old/12", 5, 9))

    // ── A. reproduction: same normalized title, durations inside tolerance ───────

    @Test
    fun `A - two songs with one normalized title and durations inside tolerance are both ambiguous at the path tier`() {
        val b = backup(listOf(backupNorth(), backupSouth()), bothStats())

        val result = WavdropBackupStatsMatcher.match(b, live)

        assertEquals("1,320 ms apart is inside the 2,000 ms tolerance", true, 170_424 - 169_104 <= WavdropBackupStatsMatcher.DURATION_TOLERANCE_MS)
        assertTrue("neither row may be attached to either song", result.matchedRows.isEmpty())
        assertEquals(2, result.unmatchedCount)
        assertEquals(2, result.diagnostics.ambiguous)
        assertEquals(0, result.diagnostics.matchedByTagsDuration)
    }

    @Test
    fun `A - the ambiguity is not an artifact of row order`() {
        val b = backup(listOf(backupSouth(), backupNorth()), bothStats().reversed())

        val result = WavdropBackupStatsMatcher.match(b, live.reversed())

        assertTrue(result.matchedRows.isEmpty())
        assertEquals(2, result.diagnostics.ambiguous)
    }

    // ── B. a unique artist/album combination distinguishes the songs, but only if the path tier is empty ─

    @Test
    fun `B - the same rows resolve uniquely by tags plus duration when the backup carries no folder path`() {
        val b = backup(listOf(backupNorth(folder = null), backupSouth(folder = null)), bothStats())

        val result = WavdropBackupStatsMatcher.match(b, live)

        assertEquals(2, result.matchedRows.size)
        assertEquals(2, result.diagnostics.matchedByTagsDuration)
        assertEquals(0, result.unmatchedCount)
        val byPlays = result.matchedRows.associate { (s, st) -> st.playCount to s.id }
        assertEquals("11 plays belong to the North song", 101L, byPlays[11])
        assertEquals("5 plays belong to the South song", 102L, byPlays[5])
    }

    // ── E. a different path changes which tier is selected ───────────────────────

    @Test
    fun `E - a backup folder that matches no live folder skips the path tier and the rows resolve by tags plus duration`() {
        val b = backup(listOf(backupNorth("Downloads/Old"), backupSouth("Downloads/Old")), bothStats())

        val result = WavdropBackupStatsMatcher.match(b, live)

        assertEquals(2, result.matchedRows.size)
        assertEquals(2, result.diagnostics.matchedByTagsDuration)
        assertEquals(0, result.diagnostics.ambiguous)
    }

    @Test
    fun `E - distinct live folders let the path tier separate the two songs`() {
        val north = liveNorth.copy(folderPath = "Music/North")
        val south = liveSouth.copy(folderPath = "Music/South")
        val b = backup(listOf(backupNorth("Music/North"), backupSouth("Music/South")), bothStats())

        val result = WavdropBackupStatsMatcher.match(b, listOf(north, south))

        assertEquals(2, result.matchedRows.size)
        assertEquals(2, result.diagnostics.matchedByPath)
    }

    // ── events versus statistics: the asymmetry observed on the device ──────────

    @Test
    fun `statistics matcher resolves neither song while the event link resolver resolves both by unique tags`() {
        val b = backup(listOf(backupNorth(), backupSouth()), bothStats())

        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, live)
        val linkResolver = BackupSongLinkResolver(live, resolution.resolved, resolution.rejectedLocalIds)

        assertTrue("stats-side resolution is empty", resolution.resolved.isEmpty())
        assertEquals(liveNorth, linkResolver.resolve(11, "content://media/old/11", "Harbor", "Artist North", "Album One"))
        assertEquals(liveSouth, linkResolver.resolve(12, "content://media/old/12", "harbor", "Artist South", "Album Two"))
    }

    @Test
    fun `events of the ambiguous songs are restored with identity timestamps and source, and the same history is also quarantined`() {
        val events = listOf(
            event(11, "content://media/old/11", "Harbor", "Artist North", "Album One", at = 1_700_000_000_000L, id = "evt-north-1"),
            event(12, "content://media/old/12", "harbor", "Artist South", "Album Two", at = 1_700_000_100_000L, id = "evt-south-1"),
        )
        val b = backup(listOf(backupNorth(), backupSouth()), bothStats(), events)
        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, live)
        val linkResolver = BackupSongLinkResolver(live, resolution.resolved, resolution.rejectedLocalIds)

        val plan = ListenEventRestorePlanner.plan(
            events = events,
            resolveSong = { e -> linkResolver.resolve(e.songId, e.contentUri, e.title, e.artist, e.album) },
        )

        assertEquals(2, plan.toInsert.size)
        assertEquals(0, plan.skippedUnmatched)
        val north = plan.toInsert.single { it.eventId == "evt-north-1" }
        val south = plan.toInsert.single { it.eventId == "evt-south-1" }
        assertEquals(101L, north.songId)
        assertEquals(102L, south.songId)
        assertEquals(1_700_000_000_000L, north.occurredAt)
        assertEquals(1_700_000_100_000L, south.occurredAt)
        assertEquals(120_000L, north.listenedMs)
        assertEquals(TrackListenEventEntity.SOURCE_MANUAL_RESTORE, north.source)

        // Quarantine is driven by the STATS-side resolution, so both ambiguous tracks are also archived with their events.
        val quarantine = QuarantinePlanner.plan(b, "fp", resolution.resolved.keys, emptySet())
        assertEquals(2, quarantine.newCandidates.size)
        assertEquals(2, quarantine.newEventsCount)
    }

    // ── G. unmatched statistics are preserved, never attached to an arbitrary song ─

    @Test
    fun `G - ambiguous statistics are carried unchanged by the quarantine plan and attached to no live song`() {
        val b = backup(listOf(backupNorth(), backupSouth()), bothStats())
        val match = WavdropBackupStatsMatcher.match(b, live)
        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, live)

        val plan = QuarantinePlanner.plan(b, "fp", resolution.resolved.keys, emptySet())

        assertTrue("no live row is produced", match.matchedRows.isEmpty())
        val byBackupId = plan.newCandidates.associateBy { it.backupSongId }
        val north = byBackupId.getValue(11L)
        val south = byBackupId.getValue(12L)
        assertEquals(11, north.stats!!.playCount)
        assertEquals(3, north.stats!!.skipCount)
        assertEquals(5, south.stats!!.playCount)
        assertEquals(9, south.stats!!.skipCount)
        assertEquals("Harbor", north.title)
        assertEquals("Artist South", south.artist)
        assertEquals(1_000L, north.stats!!.lastPlayedAt)
        assertEquals(5_000L, north.stats!!.totalListeningTimeMs)
    }

    @Test
    fun `G - re-planning the same backup against its own origin keys adds nothing`() {
        val b = backup(listOf(backupNorth(), backupSouth()), bothStats())
        val resolution = WavdropBackupStatsMatcher.resolveBackupSongs(b, live)
        val first = QuarantinePlanner.plan(b, "fp", resolution.resolved.keys, emptySet())

        val again = QuarantinePlanner.plan(b, "fp", resolution.resolved.keys, first.newCandidates.map { it.originKey }.toSet())

        assertTrue(again.newCandidates.isEmpty())
        assertEquals(2, again.alreadyPresentCount)
    }

    // ── C. genuine ambiguity: tags do not identify a single song ─────────────────

    @Test
    fun `C - identical title artist album and near-equal durations stay ambiguous for stats and unresolved for events`() {
        val copyA = song(201, "content://media/201", "Echo", "Same Artist", "Same Album", 200_000)
        val copyB = song(202, "content://media/202", "Echo", "Same Artist", "Same Album", 200_900)
        val bs = bSong(21, "content://media/old/21", "Echo", "Same Artist", "Same Album", 200_400)
        val b = backup(listOf(bs), listOf(stat(21, "content://media/old/21", 4, 1)))

        val match = WavdropBackupStatsMatcher.match(b, listOf(copyA, copyB))
        val linkResolver = BackupSongLinkResolver(listOf(copyA, copyB), emptyMap())

        assertTrue(match.matchedRows.isEmpty())
        assertEquals(1, match.diagnostics.ambiguous)
        assertNull("event resolver also refuses a non-unique tag triple", linkResolver.resolve(21, "", "Echo", "Same Artist", "Same Album"))
    }

    // ── D. near-duplicate releases whose metadata normalizes to the same key ─────

    @Test
    fun `D - albums that differ only by case and whitespace are the same identity key and stay ambiguous`() {
        val release1 = song(301, "content://media/301", "Tide", "Duo", "Deluxe Edition", 180_000, folderPath = null)
        val release2 = song(302, "content://media/302", "Tide", "Duo", "  deluxe edition ", 180_500, folderPath = null)
        val bs = bSong(31, "content://media/old/31", "Tide", "Duo", "Deluxe Edition", 180_200, folderPath = null)
        val b = backup(listOf(bs), listOf(stat(31, "content://media/old/31", 2, 2)))

        val match = WavdropBackupStatsMatcher.match(b, listOf(release1, release2))

        assertTrue(match.matchedRows.isEmpty())
        assertEquals(1, match.diagnostics.ambiguous)
    }

    @Test
    fun `D - durations outside tolerance separate otherwise identical tags`() {
        val short = song(311, "content://media/311", "Tide", "Duo", "Deluxe Edition", 180_000, folderPath = null)
        val long = song(312, "content://media/312", "Tide", "Duo", "Deluxe Edition", 195_000, folderPath = null)
        val bs = bSong(32, "content://media/old/32", "Tide", "Duo", "Deluxe Edition", 195_500, folderPath = null)
        val b = backup(listOf(bs), listOf(stat(32, "content://media/old/32", 2, 2)))

        val match = WavdropBackupStatsMatcher.match(b, listOf(short, long))

        assertEquals(long, match.matchedRows.single().first)
    }

    // ── F. a strong exact match is not overridden by weaker metadata ─────────────

    @Test
    fun `F - a corroborated exact URI wins before the ambiguous path tier is ever consulted`() {
        val b = backup(
            listOf(bSong(11, liveNorth.uri, "Harbor", "Artist North", "Album One", 170_424)),
            listOf(stat(11, liveNorth.uri, 11, 3)),
        )

        val result = WavdropBackupStatsMatcher.match(b, live)

        assertEquals(liveNorth, result.matchedRows.single().first)
        assertEquals(1, result.diagnostics.matchedByUri)
        assertEquals(0, result.diagnostics.ambiguous)
    }

    /**
     * CHARACTERIZED RISK, not endorsed: URI corroboration compares only duration and the TOLERANT TITLE, never artist. A stale URI
     * (MediaStore id reuse after a rescan) that points at a different artist's song with the same title and a duration inside
     * tolerance is therefore accepted as an exact match. Recorded so a future policy slice has a regression anchor.
     */
    @Test
    fun `F - characterized risk - a URI pointing at a same-title different-artist song within tolerance is accepted`() {
        val b = backup(
            listOf(bSong(11, liveSouth.uri, "Harbor", "Artist North", "Album One", 170_424)),
            listOf(stat(11, liveSouth.uri, 11, 3)),
        )

        val result = WavdropBackupStatsMatcher.match(b, live)

        assertEquals("attributed to the South song by URI corroboration", liveSouth, result.matchedRows.single().first)
        assertEquals(1, result.diagnostics.matchedByUri)
    }

    @Test
    fun `F - a URI whose duration contradicts the backup song is rejected and the file is not re-accepted by tags only`() {
        val staleOwner = song(401, "content://media/401", "Other Song", "Other Artist", "Other Album", 300_000)
        val bs = bSong(41, staleOwner.uri, "Harbor", "Artist North", "Album One", 170_424)
        val b = backup(listOf(bs), listOf(stat(41, staleOwner.uri, 1, 1)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(staleOwner))

        assertTrue(result.matchedRows.isEmpty())
        assertEquals(1, result.unmatchedCount)
    }

    // ── context for the candidate-policy comparison ──────────────────────────────

    /**
     * Documented weak tier (tier 5, "artist/album tags changed"): when ONE live song shares the title and has a duration inside
     * tolerance, a different artist does not prevent a match. Pinned so any policy that widens or reorders tiers can be compared
     * against it. It is the reason "continue to a later tier when an earlier one is ambiguous" cannot be done blindly.
     */
    @Test
    fun `title plus duration tier accepts a different artist when it is the only candidate`() {
        val onlyLive = song(501, "content://media/501", "Harbor", "Artist West", "Album Nine", 170_000, folderPath = null)
        val bs = bSong(51, "content://media/old/51", "Harbor", "Artist North", "Album One", 170_500, folderPath = null)
        val b = backup(listOf(bs), listOf(stat(51, "content://media/old/51", 1, 1)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(onlyLive))

        assertNotNull(result.matchedRows.singleOrNull())
        assertEquals(1, result.diagnostics.matchedByTitleDuration)
    }

    // ── H. no regression of collision handling ───────────────────────────────────

    @Test
    fun `H - two backup rows resolving to one local song still keep only the strongest and count the other as a collision`() {
        val only = song(601, "content://media/601", "Solo", "Artist", "Album", 150_000)
        val exact = bSong(61, only.uri, "Solo", "Artist", "Album", 150_000)
        val retagged = bSong(62, "content://media/old/62", "Solo", "Artist", "Album", 150_000, folderPath = null)
        val b = backup(listOf(exact, retagged), listOf(stat(61, only.uri, 9, 1), stat(62, "content://media/old/62", 2, 2)))

        val result = WavdropBackupStatsMatcher.match(b, listOf(only))

        assertEquals(1, result.matchedRows.size)
        assertEquals(9, result.matchedRows.single().second.playCount)
        assertEquals(1, result.diagnostics.collisions)
    }
}

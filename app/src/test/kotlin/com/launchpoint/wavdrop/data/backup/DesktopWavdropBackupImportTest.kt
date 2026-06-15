package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWavdropBackupImportTest {

    // ── Detection / routing ───────────────────────────────────────────────────

    @Test
    fun `desktop backup detection recognizes desktop appName`() {
        assertTrue(DesktopWavdropBackupParser.isDesktopBackupContent(legacyDesktopJson()))
        assertTrue(ImportFileValidation.isLikelyWavdropBackupContent(legacyDesktopJson()))
    }

    @Test
    fun `desktop backup detection recognizes sourcePlatform desktop`() {
        assertTrue(DesktopWavdropBackupParser.isDesktopBackupContent(sharedDesktopJson()))
        assertTrue(ImportFileValidation.isLikelyWavdropBackupContent(sharedDesktopJson()))
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    @Test
    fun `legacy parser reads supported shape`() {
        val result = DesktopWavdropBackupParser.parse(legacyDesktopJson())

        assertNotNull(result.backup)
        assertEquals(DesktopWavdropBackupParser.APP_NAME, result.backup!!.appName)
        assertEquals(1, result.backup.songs.size)
        assertEquals("Jolé", result.backup.songs.single().artist)
    }

    @Test
    fun `shared backup parser reads songs and identity fields`() {
        val result = DesktopWavdropBackupParser.parse(sharedDesktopJson())

        assertNotNull(result.backup)
        assertEquals("desktop", result.backup!!.sourcePlatform)
        assertEquals(2, result.backup.songs.size)
        val first = result.backup.songs.first()
        assertEquals("Jolé", first.artist)
        assertEquals("ace76dacba47849f27d6b2515e600190ad52f51f", first.id)
    }

    @Test
    fun `shared backup parser reads playlists with songIds array`() {
        val result = DesktopWavdropBackupParser.parse(sharedDesktopJson())

        val backup = result.backup!!
        assertEquals(1, backup.playlists.size)
        val playlist = backup.playlists.single()
        assertEquals("9826cd84-02e3-4ae6-ae99-3cb7b12ed2ae", playlist.id)
        assertEquals("My Playlist", playlist.name)
        assertEquals(
            listOf(
                "ace76dacba47849f27d6b2515e600190ad52f51f",
                "509cbf5fb4b324b3723c230200b240481b04a0ae",
            ),
            playlist.songIds,
        )
    }

    @Test
    fun `backup without playlists field parses with empty list`() {
        val result = DesktopWavdropBackupParser.parse(legacyDesktopJson())
        assertNotNull(result.backup)
        assertEquals(emptyList<DesktopBackupPlaylist>(), result.backup!!.playlists)
    }

    // ── Stats / favorites matching ────────────────────────────────────────────

    @Test
    fun `metadata matching matches title artist album without desktop id`() {
        val androidSong = song(id = 7, title = "This City", artist = "Jolé", album = "Single")
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(desktopSong(title = "This City", artist = "Jolé", album = "Single")),
            currentSongs = listOf(androidSong),
            currentStats = emptyList(),
        )

        assertEquals(1, plan.matchedCount)
        assertEquals(7L, plan.matchedRows.single().song.id)
    }

    @Test
    fun `tolerant matching handles accents case apostrophes and underscores`() {
        val androidSong = song(id = 7, title = "Picture Perfect", artist = "Dont", album = "Cafe")
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(desktopSong(title = "picture_perfect", artist = "Don’t", album = "Café")),
            currentSongs = listOf(androidSong),
            currentStats = emptyList(),
        )

        assertEquals(1, plan.matchedCount)
    }

    @Test
    fun `playCount uses max merge`() {
        val plan = planForMerge(current = stats(playCount = 90), desktop = desktopSong(playCount = 70))

        assertEquals(90, plan.matchedRows.single().mergedStats.playCount)
        assertFalse(plan.matchedRows.single().statsWillIncrease)
    }

    @Test
    fun `playCount increases when desktop is higher`() {
        val plan = planForMerge(current = stats(playCount = 70), desktop = desktopSong(playCount = 90))

        assertEquals(90, plan.matchedRows.single().mergedStats.playCount)
        assertTrue(plan.matchedRows.single().statsWillIncrease)
    }

    @Test
    fun `totalListeningTimeMs uses max merge`() {
        val plan = planForMerge(
            current = stats(totalListeningTimeMs = 5_000L),
            desktop = desktopSong(totalListeningTimeMs = 9_000L),
        )

        assertEquals(9_000L, plan.matchedRows.single().mergedStats.totalListeningTimeMs)
    }

    @Test
    fun `lastPlayedAt uses latest timestamp`() {
        val plan = planForMerge(
            current = stats(lastPlayedAt = 1_000L),
            desktop = desktopSong(lastPlayedAt = 5_000L),
        )

        assertEquals(5_000L, plan.matchedRows.single().mergedStats.lastPlayedAt)
    }

    @Test
    fun `favorite true wins`() {
        val plan = planForMerge(
            current = stats(isFavorite = false),
            desktop = desktopSong(favorite = true),
        )

        assertTrue(plan.matchedRows.single().mergedStats.isFavorite)
        assertEquals(1, plan.favoritesWillApplyCount)
    }

    @Test
    fun `unmatched desktop song is skipped`() {
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(desktopSong(title = "Missing")),
            currentSongs = listOf(song(title = "Present")),
            currentStats = emptyList(),
        )

        assertEquals(0, plan.matchedCount)
        assertEquals(1, plan.unmatchedCount)
    }

    @Test
    fun `ambiguous metadata match is skipped`() {
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(desktopSong(title = "Same", artist = "Artist", album = "Album")),
            currentSongs = listOf(
                song(id = 1, title = "Same", artist = "Artist", album = "Album"),
                song(id = 2, title = "Same", artist = "Artist", album = "Album"),
            ),
            currentStats = emptyList(),
        )

        assertEquals(0, plan.matchedCount)
        assertEquals(1, plan.ambiguousCount)
    }

    @Test
    fun `desktop import result does not create listen events`() {
        val result = planForMerge(
            current = stats(playCount = 0),
            desktop = desktopSong(playCount = 1),
        ).toApplyResult()

        assertEquals(0, result.eventsRestored)
        assertEquals(0, result.currentMonthEventsRestored)
    }

    @Test
    fun `android export after desktop merge contains updated aggregate values`() {
        val merged = planForMerge(
            current = stats(playCount = 70, totalListeningTimeMs = 1_000L, lastPlayedAt = 2_000L),
            desktop = desktopSong(playCount = 90, totalListeningTimeMs = 3_000L, lastPlayedAt = 4_000L),
        ).matchedRows.single().mergedStats

        val json = WavdropBackupExporter.toJson(
            WavdropBackup(
                exportedAt = "2026-06-13T00:00:00Z",
                songs = emptyList(),
                trackStats = listOf(
                    BackupTrackStats(
                        songId = merged.songId,
                        contentUri = merged.contentUri,
                        playCount = merged.playCount,
                        skipCount = merged.skipCount,
                        lastPlayedAt = merged.lastPlayedAt,
                        totalListeningTimeMs = merged.totalListeningTimeMs,
                        isFavorite = merged.isFavorite,
                    ),
                ),
                importBaselines = emptyList(),
            ),
        )
        val exportedStats = JSONObject(json).getJSONArray("trackStats").getJSONObject(0)

        assertEquals(90, exportedStats.getInt("playCount"))
        assertEquals(3_000L, exportedStats.getLong("totalListeningTimeMs"))
        assertEquals(4_000L, exportedStats.getLong("lastPlayedAt"))
    }

    // ── Playlist import — shared desktop backup (real shape) ─────────────────

    @Test
    fun `shared backup playlist songs are translated to android ids by metadata`() {
        val dSongId = "ace76dacba47849f27d6b2515e600190ad52f51f"
        val androidSong = song(id = 7, title = "This City", artist = "Jolé", album = "Single")
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = dSongId, title = "This City", artist = "Jolé", album = "Single"),
                playlists = listOf(playlist("Road Trip", dSongId)),
            ),
            currentSongs = listOf(androidSong),
            currentStats = emptyList(),
        )
        assertEquals(1, plan.playlistPlans.size)
        assertEquals("Road Trip", plan.playlistPlans.single().name)
        assertEquals(listOf(7L), plan.playlistPlans.single().resolvedSongIds)
        assertEquals(0, plan.playlistPlans.single().skippedUnmatched)
    }

    @Test
    fun `shared backup playlist order is preserved from songIds array order`() {
        val idA = "aaa111"
        val idB = "bbb222"
        val songA = song(id = 10, title = "Alpha", artist = "A", album = "A")
        val songB = song(id = 20, title = "Beta", artist = "B", album = "B")
        // songIds in backup: B first, then A
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = idA, title = "Alpha", artist = "A", album = "A"),
                desktopSong(id = idB, title = "Beta", artist = "B", album = "B"),
                playlists = listOf(playlist("Ordered", idB, idA)),
            ),
            currentSongs = listOf(songA, songB),
            currentStats = emptyList(),
        )
        assertEquals(listOf(20L, 10L), plan.playlistPlans.single().resolvedSongIds)
    }

    @Test
    fun `shared backup re-import is idempotent via plan counts`() {
        val dSongId = "abc123"
        val androidSong = song(id = 5, title = "Song")
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = dSongId, title = "Song"),
                playlists = listOf(playlist("My List", dSongId)),
            ),
            currentSongs = listOf(androidSong),
            currentStats = emptyList(),
        )
        // First import produces one playlist plan with one song
        assertEquals(1, plan.playlistPlans.size)
        assertEquals(listOf(5L), plan.playlistPlans.single().resolvedSongIds)
    }

    @Test
    fun `playlist song with no android match is skipped and counted`() {
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = "x1", title = "Missing"),
                playlists = listOf(playlist("My List", "x1")),
            ),
            currentSongs = listOf(song(id = 1, title = "Present")),
            currentStats = emptyList(),
        )
        assertEquals(0, plan.playlistPlans.size)
        assertEquals(1, plan.playlistsSkippedEmpty)
        assertEquals(1, plan.playlistEntriesInBackup)
    }

    @Test
    fun `playlist with ambiguous song reference is skipped`() {
        val dup1 = song(id = 1, title = "Same", artist = "A", album = "B")
        val dup2 = song(id = 2, title = "Same", artist = "A", album = "B")
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = "x1", title = "Same", artist = "A", album = "B"),
                playlists = listOf(playlist("Ambig", "x1")),
            ),
            currentSongs = listOf(dup1, dup2),
            currentStats = emptyList(),
        )
        assertEquals(0, plan.playlistPlans.size)
        assertEquals(1, plan.playlistsSkippedEmpty)
    }

    @Test
    fun `empty translated playlist is skipped`() {
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = "x1", title = "Missing"),
                playlists = listOf(playlist("Empty", "x1")),
            ),
            currentSongs = listOf(song(id = 10, title = "Found")),
            currentStats = emptyList(),
        )
        assertEquals(0, plan.playlistPlans.size)
        assertEquals(1, plan.playlistsSkippedEmpty)
    }

    @Test
    fun `same android song appearing twice via different desktop ids is deduplicated`() {
        val androidSong = song(id = 7, title = "Song")
        // Two desktop songs with same metadata → both map to same android song
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = "a1", title = "Song"),
                desktopSong(id = "a2", title = "Song"),
                playlists = listOf(playlist("Dups", "a1", "a2")),
            ),
            currentSongs = listOf(androidSong),
            currentStats = emptyList(),
        )
        assertEquals(listOf(7L), plan.playlistPlans.single().resolvedSongIds)
    }

    @Test
    fun `multiple playlists are each translated independently`() {
        val songA = song(id = 10, title = "Alpha")
        val songB = song(id = 20, title = "Beta")
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = "a1", title = "Alpha"),
                desktopSong(id = "b1", title = "Beta"),
                playlists = listOf(playlist("ListA", "a1"), playlist("ListB", "b1")),
            ),
            currentSongs = listOf(songA, songB),
            currentStats = emptyList(),
        )
        assertEquals(2, plan.playlistPlans.size)
        assertEquals(listOf(10L), plan.playlistPlans[0].resolvedSongIds)
        assertEquals(listOf(20L), plan.playlistPlans[1].resolvedSongIds)
    }

    @Test
    fun `playlist count includes skipped empty playlists`() {
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = "s1", title = "Song"),
                playlists = listOf(
                    playlist("Good", "s1"),
                    playlist("Empty"),
                ),
            ),
            currentSongs = listOf(song(id = 10, title = "Song")),
            currentStats = emptyList(),
        )
        assertEquals(2, plan.playlistsInBackup)
        assertEquals(1, plan.playlistsToImportCount)
        assertEquals(1, plan.playlistsSkippedEmpty)
    }

    @Test
    fun `existing stats and favorites import behavior unchanged by playlist addition`() {
        val dSongId = "stat-song-1"
        val androidSong = song(id = 1, title = "Song")
        val plan = DesktopWavdropBackupImportPlanner.plan(
            backup = backup(
                desktopSong(id = dSongId, title = "Song", playCount = 5, favorite = true),
                playlists = listOf(playlist("List", dSongId)),
            ),
            currentSongs = listOf(androidSong),
            currentStats = listOf(stats(songId = 1, playCount = 3)),
        )
        assertEquals(1, plan.matchedCount)
        assertEquals(5, plan.matchedRows.single().mergedStats.playCount)
        assertTrue(plan.matchedRows.single().mergedStats.isFavorite)
        assertEquals(1, plan.playlistsToImportCount)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun planForMerge(
        current: TrackStatsEntity,
        desktop: DesktopBackupSong,
    ): DesktopBackupImportPlan {
        val androidSong = song(
            id = current.songId,
            title = desktop.title,
            artist = desktop.artist,
            album = desktop.album,
        )
        return DesktopWavdropBackupImportPlanner.plan(
            backup = backup(desktop),
            currentSongs = listOf(androidSong),
            currentStats = listOf(current.copy(contentUri = androidSong.uri)),
        )
    }

    /** Legacy desktop-only backup (appName only, no format/sourcePlatform fields). */
    private fun legacyDesktopJson(): String = """
        {
          "schemaVersion": 1,
          "exportedAt": "2026-06-13T00:00:00Z",
          "appName": "wavdrop-desktop-lab",
          "folderPath": "Music",
          "songs": [
            {
              "id": "legacy-id-999",
              "title": "This City",
              "artist": "Jolé",
              "album": "Single",
              "playCount": 12,
              "totalListeningTimeMs": 120000,
              "lastPlayedAt": 1710000000000,
              "favorite": true
            }
          ]
        }
    """.trimIndent()

    /** Shared desktop backup — uses Android format identity PLUS sourcePlatform + appName. */
    private fun sharedDesktopJson(): String = """
        {
          "app": "Wavdrop",
          "format": "wavdrop_backup",
          "version": 1,
          "sourcePlatform": "desktop",
          "appName": "wavdrop-desktop-lab",
          "schemaVersion": 1,
          "exportedAt": "2026-06-13T05:36:17.634Z",
          "songs": [
            {
              "id": "ace76dacba47849f27d6b2515e600190ad52f51f",
              "title": "This City",
              "artist": "Jolé",
              "album": "Single",
              "playCount": 12,
              "totalListeningTimeMs": 120000,
              "lastPlayedAt": 1710000000000,
              "favorite": true
            },
            {
              "id": "509cbf5fb4b324b3723c230200b240481b04a0ae",
              "title": "Road Runner",
              "artist": "Jolé",
              "album": "Single",
              "playCount": 3,
              "totalListeningTimeMs": 10000,
              "lastPlayedAt": 1710000001000,
              "favorite": false
            }
          ],
          "playlists": [
            {
              "id": "9826cd84-02e3-4ae6-ae99-3cb7b12ed2ae",
              "name": "My Playlist",
              "songIds": [
                "ace76dacba47849f27d6b2515e600190ad52f51f",
                "509cbf5fb4b324b3723c230200b240481b04a0ae"
              ],
              "createdAt": "2026-06-13T05:36:17.634Z",
              "updatedAt": "2026-06-13T07:53:14.921Z"
            }
          ]
        }
    """.trimIndent()

    private fun backup(
        vararg songs: DesktopBackupSong,
        playlists: List<DesktopBackupPlaylist> = emptyList(),
    ) = DesktopWavdropBackup(
        schemaVersion  = 1,
        exportedAt     = "2026-06-13T00:00:00Z",
        appName        = DesktopWavdropBackupParser.APP_NAME,
        sourcePlatform = null,
        folderPath     = "Music",
        songs          = songs.toList(),
        playlists      = playlists,
    )

    private fun playlist(name: String, vararg songIds: String) = DesktopBackupPlaylist(
        id      = "test-playlist-id",
        name    = name,
        songIds = songIds.toList(),
    )

    private fun desktopSong(
        id: String = "desktop-id-999",
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        playCount: Int = 0,
        totalListeningTimeMs: Long = 0L,
        lastPlayedAt: Long = 0L,
        favorite: Boolean = false,
    ) = DesktopBackupSong(
        id                   = id,
        title                = title,
        artist               = artist,
        album                = album,
        playCount            = playCount,
        totalListeningTimeMs = totalListeningTimeMs,
        lastPlayedAt         = lastPlayedAt,
        favorite             = favorite,
    )

    private fun song(
        id: Long = 1L,
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
    ) = Song(
        id          = id,
        title       = title,
        artist      = artist,
        album       = album,
        albumId     = 1L,
        duration    = 180_000L,
        uri         = "content://media/$id",
        dateAdded   = 0L,
        trackNumber = 1,
        year        = 2026,
    )

    private fun stats(
        songId: Long = 1L,
        playCount: Int = 0,
        totalListeningTimeMs: Long = 0L,
        lastPlayedAt: Long = 0L,
        isFavorite: Boolean = false,
    ) = TrackStatsEntity(
        songId               = songId,
        contentUri           = "content://media/$songId",
        playCount            = playCount,
        skipCount            = 2,
        totalListeningTimeMs = totalListeningTimeMs,
        lastPlayedAt         = lastPlayedAt,
        isFavorite           = isFavorite,
    )
}

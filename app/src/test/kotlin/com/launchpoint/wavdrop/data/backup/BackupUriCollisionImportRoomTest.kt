package com.launchpoint.wavdrop.data.backup

import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-AUDIT-11 through the real import paths (Merge and Recovery) on in-memory Room: a backup whose song 5 shares its MediaStore URI with a
 * DIFFERENT local song must attach nothing to that local song, while a legitimately corroborated song (6) still restores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupUriCollisionImportRoomTest {

    private lateinit var db: WavdropDatabase
    private lateinit var recovery: RecoveryRestoreRepository
    private lateinit var merge: WavdropBackupImportRepository

    private val colliding = 5L
    private val good = 6L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
        recovery = RecoveryRestoreRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        merge = WavdropBackupImportRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        runBlocking {
            db.songDao().upsertAll(
                listOf(
                    SongEntity(colliding, "Local Track", "Me", "Mine", 1L, 180_000L, "content://media/5", 1L, 1, 2020, "Music/Mine", "Mine"),
                    SongEntity(good, "Good Song", "Artist", "Album", 1L, 200_000L, "content://media/6", 1L, 1, 2020, "Music/", "Music"),
                ),
            )
            db.trackStatsDao().insertAllForRecovery(listOf(TrackStatsEntity(colliding, "content://media/5", playCount = 2, isFavorite = false)))
            db.trackListenEventDao().insertAll(
                listOf(
                    TrackListenEventEntity(
                        songId = colliding, eventType = TrackListenEventEntity.TYPE_PLAY, occurredAt = 500L, listenedMs = 60_000L,
                        durationMs = 180_000L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = "LOCAL-1",
                    ),
                ),
            )
            val pl = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Mine", createdAt = 1, updatedAt = 1))
            db.playlistDao().insertSongs(listOf(PlaylistSongEntity(pl, colliding, 0)))
        }
    }

    @After fun tearDown() = db.close()

    private fun foreignSong() = BackupSong(
        colliding, "content://media/5", "Foreign Song", "Foreign", "Foreign LP", 9L, 310_000L, 1L, 1, 2019, "Download/Other", "Other",
    )

    private fun goodSong() = BackupSong(good, "content://media/6", "Good Song", "Artist", "Album", 1L, 200_000L, 1L, 1, 2020, "Music/", "Music")

    private fun stats(id: Long, plays: Int, fav: Boolean) =
        BackupTrackStats(id, "content://media/$id", plays, 0, 1_000L, plays * 100_000L, fav, 1_000L)

    private fun event(id: Long, eventId: String, title: String, artist: String, album: String, dur: Long) = BackupListenEvent(
        id, "content://media/$id", title, artist, album, TrackListenEventEntity.TYPE_PLAY, 5_000L + id, 60_000L, dur,
        TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId,
    )

    private fun backup() = RecoveryTestFixtures.v2Backup(
        songs = listOf(foreignSong(), goodSong()),
        stats = listOf(stats(colliding, 99, true), stats(good, 7, true)),
        events = listOf(
            event(colliding, "FOREIGN-1", "Foreign Song", "Foreign", "Foreign LP", 310_000L),
            event(good, "GOOD-1", "Good Song", "Artist", "Album", 200_000L),
        ),
        playlists = listOf(
            BackupPlaylist(
                1, "Imported", 1, 1,
                listOf(
                    BackupPlaylistSong(colliding, "content://media/5", 0, "Foreign Song", "Foreign", "Foreign LP"),
                    BackupPlaylistSong(good, "content://media/6", 1, "Good Song", "Artist", "Album"),
                ),
            ),
        ),
        lyrics = listOf(BackupLyricsOverride(colliding, "content://media/5", "foreign lyrics", 7_000L)),
        baselines = listOf(BackupImportBaseline(colliding, "blackplayer", "k", 50, 3, 8L)),
    )

    private suspend fun assertNothingAttachedToColliding() {
        val s = db.trackStatsDao().getStatsBySongId(colliding)
        assertFalse("foreign favourite must not land on the local song", s?.isFavorite == true)
        assertTrue("foreign play count must not land on the local song", (s?.playCount ?: 0) < 99)
        assertTrue(db.trackListenEventDao().getAllSnapshot().none { it.eventId == "FOREIGN-1" })
        assertTrue(db.lyricsOverrideDao().getAllSnapshot().none { it.songId == colliding && it.lyrics == "foreign lyrics" })
        assertTrue(db.importBaselineDao().getAllImportBaselinesSnapshot().none { it.songId == colliding })
        val playlist = db.playlistDao().getAllPlaylistsSnapshot().firstOrNull { it.name == "Imported" }
        if (playlist != null) assertTrue(db.playlistDao().getSongsForPlaylistSnapshot(playlist.playlistId).none { it.songId == colliding })
    }

    private suspend fun assertGoodSongRestored() {
        assertEquals(7, db.trackStatsDao().getStatsBySongId(good)!!.playCount)
        assertTrue(db.trackListenEventDao().getAllSnapshot().any { it.eventId == "GOOD-1" && it.songId == good })
        val imported = db.playlistDao().getAllPlaylistsSnapshot().first { it.name == "Imported" }
        assertEquals(listOf(good), db.playlistDao().getSongsForPlaylistSnapshot(imported.playlistId).map { it.songId })
    }

    private suspend fun assertUnresolvedPreservedAsPending() {
        assertTrue("pending track row for the unresolved backup song", db.pendingTrackDao().getAllOriginKeys().any { it.endsWith("|$colliding") })
        assertTrue(db.pendingTrackDao().getAllEventFingerprints().isNotEmpty())
        assertTrue(db.pendingTrackDao().getAllLyricsFingerprints().isNotEmpty())
        assertTrue(db.pendingTrackDao().getAllBaselineFingerprints().isNotEmpty())
        assertTrue(db.pendingTrackDao().getAllPlaylistEntryFingerprints().isNotEmpty())
    }

    @Test fun `merge never attaches colliding-URI history to the unrelated local song and keeps local data`() = runBlocking {
        merge.applyImport(backup())
        assertNothingAttachedToColliding()
        assertEquals("local stats untouched", 2, db.trackStatsDao().getStatsBySongId(colliding)!!.playCount)
        assertTrue("local event kept", db.trackListenEventDao().getAllSnapshot().any { it.eventId == "LOCAL-1" })
        assertGoodSongRestored()
        assertUnresolvedPreservedAsPending()
    }

    @Test fun `recovery never attaches colliding-URI history to the unrelated local song`() = runBlocking {
        recovery.applyRecovery(backup())
        assertNothingAttachedToColliding()
        assertGoodSongRestored()
        assertUnresolvedPreservedAsPending()
    }

    @Test fun `a valid retry of the same backup stays idempotent and still attaches nothing to the collision`() = runBlocking {
        merge.applyImport(backup())
        val statsAfterFirst = db.trackStatsDao().getAllStatsSnapshot()
        val eventsAfterFirst = db.trackListenEventDao().getAllSnapshot().size
        val pendingAfterFirst = db.pendingTrackDao().getAllOriginKeys().sorted()
        merge.applyImport(backup())
        assertEquals(statsAfterFirst, db.trackStatsDao().getAllStatsSnapshot())
        assertEquals(eventsAfterFirst, db.trackListenEventDao().getAllSnapshot().size)
        assertEquals(pendingAfterFirst, db.pendingTrackDao().getAllOriginKeys().sorted())
        assertNothingAttachedToColliding()
        assertNull(db.trackStatsDao().getStatsBySongId(colliding)?.takeIf { it.playCount >= 99 })
    }
}

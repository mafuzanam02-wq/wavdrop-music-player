package com.launchpoint.wavdrop.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-A.2 — Room migration instrumentation tests.
 *
 * Uses [MigrationTestHelper] to run migrations against real SQLite on device/emulator
 * and validates that:
 *  - live data survives each migration untouched;
 *  - schema constraints (UNIQUE, FK CASCADE) are enforced;
 *  - new columns are added correctly with NULL defaults for old rows.
 *
 * Test DB name changes per test class so parallel runs don't collide, but
 * MigrationTestHelper tears down the file after each test method automatically.
 */
@RunWith(AndroidJUnit4::class)
class WavdropDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WavdropDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    // ── Helper: insert a minimal song row valid in any schema from v1 onwards ──

    private fun seedSong(db: androidx.sqlite.db.SupportSQLiteDatabase, id: Long = 1L) {
        db.execSQL(
            """
            INSERT INTO songs (id, title, artist, album, albumId, duration, uri, dateAdded, trackNumber, year)
            VALUES ($id, 'Song $id', 'Artist', 'Album', 10, 180000, 'content://media/$id', 0, 1, 2024)
            """.trimIndent()
        )
    }

    // ── v8 → v9 ──────────────────────────────────────────────────────────────

    /**
     * Seeding representative v8 live rows and migrating to v9:
     * - songs, track_stats (with lastListenedAt from v7→v8), track_listen_events,
     *   playlists, playlist_songs, lyrics_overrides, import_baselines.
     * All rows must survive and all six pending tables must be creatable.
     */
    @Test
    fun migration8to9_preservesAllLiveDataAndCreatesPendingTables() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            seedSong(db, 1L)
            db.execSQL(
                """INSERT INTO track_stats
                   (songId, contentUri, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt)
                   VALUES (1, 'content://media/1', 5, 1, 1000, 60000, 0, 900)"""
            )
            db.execSQL(
                """INSERT INTO track_listen_events
                   (songId, eventType, occurredAt, listenedMs, durationMs, source)
                   VALUES (1, 'PLAY', 5000, 30000, 180000, 'wavdrop_playback')"""
            )
            db.execSQL(
                "INSERT INTO playlists (name, createdAt, updatedAt) VALUES ('My Playlist', 1000, 2000)"
            )
            db.execSQL(
                "INSERT INTO playlist_songs (playlistId, songId, position) VALUES (1, 1, 0)"
            )
            db.execSQL(
                """INSERT INTO lyrics_overrides (songId, contentUri, lyrics, updatedAt)
                   VALUES (1, 'content://media/1', 'La la la', 3000)"""
            )
            db.execSQL(
                """INSERT INTO import_baselines
                   (songId, sourceType, sourceKey, lastImportedPlayCount, lastImportedSkipCount, lastImportedAt)
                   VALUES (1, 'BLK', 'k1', 3, 0, 4000)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9).use { db ->
            // Song survived
            db.query("SELECT id, title FROM songs WHERE id = 1").use { c ->
                assertTrue("songs row missing after migration", c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals("Song 1", c.getString(1))
            }
            // Stats survived (including lastListenedAt added in v7→v8)
            db.query("SELECT playCount, lastListenedAt FROM track_stats WHERE songId = 1").use { c ->
                assertTrue("track_stats row missing", c.moveToFirst())
                assertEquals(5, c.getInt(0))
                assertEquals(900L, c.getLong(1))
            }
            // Events survived
            db.query("SELECT occurredAt FROM track_listen_events WHERE songId = 1").use { c ->
                assertTrue("track_listen_events row missing", c.moveToFirst())
                assertEquals(5000L, c.getLong(0))
            }
            // Playlists survived
            db.query("SELECT name FROM playlists WHERE playlistId = 1").use { c ->
                assertTrue("playlists row missing", c.moveToFirst())
                assertEquals("My Playlist", c.getString(0))
            }
            // Lyrics survived
            db.query("SELECT lyrics FROM lyrics_overrides WHERE songId = 1").use { c ->
                assertTrue("lyrics_overrides row missing", c.moveToFirst())
                assertEquals("La la la", c.getString(0))
            }
            // Baselines survived
            db.query("SELECT sourceType FROM import_baselines WHERE songId = 1").use { c ->
                assertTrue("import_baselines row missing", c.moveToFirst())
                assertEquals("BLK", c.getString(0))
            }

            // All six pending tables are reachable — insert into each
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|99', 'fp', '99', 'Ghost', 'A', 'B', 180000, 1, 2024, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|99'")
                .use { c -> c.moveToFirst(); c.getLong(0) }

            db.execSQL(
                """INSERT INTO pending_track_stats
                   (pendingId, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite)
                   VALUES ($pendingId, 3, 0, 1000, 30000, 0)"""
            )
            db.execSQL(
                """INSERT INTO pending_listen_events
                   (pendingId, eventType, occurredAt, listenedMs, durationMs, originFingerprint)
                   VALUES ($pendingId, 'PLAY', 5000, 30000, 180000, 'fp|99|5000|PLAY|30000')"""
            )
            db.execSQL(
                """INSERT INTO pending_lyrics_overrides
                   (pendingId, lyrics, updatedAt, originFingerprint)
                   VALUES ($pendingId, 'Lyrics', 2000, 'fp|99|lyrics')"""
            )
            db.execSQL(
                """INSERT INTO pending_import_baselines
                   (pendingId, sourceType, sourceKey, playCount, originFingerprint)
                   VALUES ($pendingId, 'BLK', 'k99', 3, 'fp|99|BLK|k99')"""
            )
            db.execSQL(
                """INSERT INTO pending_playlist_entries
                   (pendingId, playlistName, position, originFingerprint)
                   VALUES ($pendingId, 'Faves', 0, 'fp|99|pl:Faves|0')"""
            )

            db.query("SELECT COUNT(*) FROM pending_track_stats WHERE pendingId = $pendingId")
                .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM pending_listen_events WHERE pendingId = $pendingId")
                .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM pending_lyrics_overrides WHERE pendingId = $pendingId")
                .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM pending_import_baselines WHERE pendingId = $pendingId")
                .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM pending_playlist_entries WHERE pendingId = $pendingId")
                .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test
    fun migration8to9_uniqueOriginKeyConstraintRejectsDuplicate() {
        helper.createDatabase(TEST_DB, 8).use { }
        helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9).use { db ->
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|99', 'fp', '99', 'Ghost', 'A', 'B', 180000, 1, 2024, 9999, 'PENDING')"""
            )
            try {
                db.execSQL(
                    """INSERT INTO pending_tracks
                       (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                       VALUES ('fp|99', 'fp', '99', 'Ghost Dup', 'A', 'B', 180000, 1, 2024, 9999, 'PENDING')"""
                )
                fail("Expected UNIQUE constraint violation on pending_tracks.originKey")
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Expected — UNIQUE constraint on originKey fired correctly
            }
        }
    }

    @Test
    fun migration8to9_fkCascadeDeletesChildrenWhenParentDeleted() {
        helper.createDatabase(TEST_DB, 8).use { }
        helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|99', 'fp', '99', 'Ghost', 'A', 'B', 180000, 1, 2024, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|99'")
                .use { c -> c.moveToFirst(); c.getLong(0) }

            db.execSQL(
                """INSERT INTO pending_listen_events
                   (pendingId, eventType, occurredAt, listenedMs, durationMs, originFingerprint)
                   VALUES ($pendingId, 'PLAY', 5000, 30000, 180000, 'fp|99|5000|PLAY|30000')"""
            )
            db.execSQL(
                """INSERT INTO pending_playlist_entries
                   (pendingId, playlistName, position, originFingerprint)
                   VALUES ($pendingId, 'Faves', 0, 'fp|99|pl:Faves|0')"""
            )

            db.execSQL("DELETE FROM pending_tracks WHERE pendingId = $pendingId")

            db.query("SELECT COUNT(*) FROM pending_listen_events WHERE pendingId = $pendingId").use { c ->
                c.moveToFirst()
                assertEquals("Pending events must be CASCADE-deleted with parent", 0, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM pending_playlist_entries WHERE pendingId = $pendingId").use { c ->
                c.moveToFirst()
                assertEquals("Pending playlist entries must be CASCADE-deleted with parent", 0, c.getInt(0))
            }
        }
    }

    // ── v9 → v10 ─────────────────────────────────────────────────────────────

    /**
     * A v9 pending playlist entry row survives migration to v10 with all three
     * new provenance columns set to NULL — the correct representation of
     * legacy-ambiguous provenance for pre-v10 records.
     */
    @Test
    fun migration9to10_existingPlaylistEntriesGetNullProvenance() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|99', 'fp', '99', 'Ghost', 'A', 'B', 180000, 1, 2024, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|99'")
                .use { c -> c.moveToFirst(); c.getLong(0) }
            db.execSQL(
                """INSERT INTO pending_playlist_entries
                   (pendingId, playlistName, position, originFingerprint)
                   VALUES ($pendingId, 'Faves', 0, 'fp|99|pl:Faves|0')"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10).use { db ->
            db.query(
                """SELECT playlistName, position, sourcePlaylistId, sourcePlaylistCreatedAt, sourcePlaylistUpdatedAt
                   FROM pending_playlist_entries"""
            ).use { c ->
                assertTrue("Existing pending_playlist_entries row must survive migration", c.moveToFirst())
                assertEquals("Faves", c.getString(0))
                assertEquals(0, c.getInt(1))
                // All three provenance columns must be NULL for migrated v9 rows
                assertTrue(
                    "sourcePlaylistId must be NULL for legacy row",
                    c.isNull(2),
                )
                assertTrue(
                    "sourcePlaylistCreatedAt must be NULL for legacy row",
                    c.isNull(3),
                )
                assertTrue(
                    "sourcePlaylistUpdatedAt must be NULL for legacy row",
                    c.isNull(4),
                )
            }
        }
    }

    @Test
    fun migration9to10_newEntriesAfterMigrationCanStoreFullProvenance() {
        helper.createDatabase(TEST_DB, 9).use { }
        helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10).use { db ->
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|100', 'fp', '100', 'New', 'A', 'B', 180000, 1, 2024, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|100'")
                .use { c -> c.moveToFirst(); c.getLong(0) }

            db.execSQL(
                """INSERT INTO pending_playlist_entries
                   (pendingId, playlistName, position, originFingerprint,
                    sourcePlaylistId, sourcePlaylistCreatedAt, sourcePlaylistUpdatedAt)
                   VALUES ($pendingId, 'Night Drive', 2, 'fp|100|pl:42|2', '42', 1000, 2000)"""
            )

            db.query(
                """SELECT sourcePlaylistId, sourcePlaylistCreatedAt, sourcePlaylistUpdatedAt
                   FROM pending_playlist_entries WHERE pendingId = $pendingId"""
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("42", c.getString(0))
                assertEquals(1000L, c.getLong(1))
                assertEquals(2000L, c.getLong(2))
            }
        }
    }

    // ── v8 → v10 chained ─────────────────────────────────────────────────────

    @Test
    fun migration8to10_chainedMigrationPreservesLiveDataAndSupportsV10Schema() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            seedSong(db, 42L)
            db.execSQL(
                """INSERT INTO track_stats
                   (songId, contentUri, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt)
                   VALUES (42, 'content://media/42', 12, 2, 7000, 120000, 1, 6500)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_8_9, MIGRATION_9_10).use { db ->
            // Live song and stats survived two migrations
            db.query("SELECT title FROM songs WHERE id = 42").use { c ->
                assertTrue("songs row missing after chained migration", c.moveToFirst())
                assertEquals("Song 42", c.getString(0))
            }
            db.query("SELECT playCount, isFavorite FROM track_stats WHERE songId = 42").use { c ->
                assertTrue("track_stats row missing after chained migration", c.moveToFirst())
                assertEquals(12, c.getInt(0))
                assertEquals(1, c.getInt(1))
            }

            // v10 pending_playlist_entries schema accepts provenance columns
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|42', 'fp', '42', 'T', 'A', 'B', 180000, 1, 2025, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|42'")
                .use { c -> c.moveToFirst(); c.getLong(0) }

            db.execSQL(
                """INSERT INTO pending_playlist_entries
                   (pendingId, playlistName, position, originFingerprint,
                    sourcePlaylistId, sourcePlaylistCreatedAt, sourcePlaylistUpdatedAt)
                   VALUES ($pendingId, 'Road Trip', 0, 'fp|42|pl:7|0', '7', 1000, 2000)"""
            )

            db.query(
                "SELECT sourcePlaylistId FROM pending_playlist_entries WHERE pendingId = $pendingId"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("7", c.getString(0))
            }
        }
    }

    // ── v10 → v11 ────────────────────────────────────────────────────────────

    /**
     * Test 17: v10 → v11 migration adds nullable lastListenedAt INTEGER to
     * pending_track_stats. Pre-existing rows must have NULL for the new column.
     */
    @Test
    fun migration10to11_existingPendingStatsGetNullLastListenedAt() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|99', 'fp', '99', 'Ghost', 'A', 'B', 180000, 1, 2024, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|99'")
                .use { c -> c.moveToFirst(); c.getLong(0) }
            db.execSQL(
                """INSERT INTO pending_track_stats
                   (pendingId, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite)
                   VALUES ($pendingId, 3, 0, 1000, 30000, 0)"""
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11).use { db ->
            db.query("SELECT playCount, skipCount, lastListenedAt FROM pending_track_stats").use { c ->
                assertTrue("pending_track_stats row must survive migration", c.moveToFirst())
                assertEquals(3, c.getInt(0))
                assertEquals(0, c.getInt(1))
                // Pre-migration rows have NULL for lastListenedAt (v1 provenance)
                assertTrue(
                    "lastListenedAt must be NULL for pre-migration pending stats row",
                    c.isNull(2),
                )
            }
        }
    }

    /**
     * After v10→v11 migration, new rows can store a non-null lastListenedAt (v2 provenance).
     */
    @Test
    fun migration10to11_newRowsCanStoreLastListenedAt() {
        helper.createDatabase(TEST_DB, 10).use { }

        helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11).use { db ->
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|42', 'fp', '42', 'Song', 'A', 'B', 180000, 1, 2025, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|42'")
                .use { c -> c.moveToFirst(); c.getLong(0) }

            db.execSQL(
                """INSERT INTO pending_track_stats
                   (pendingId, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt)
                   VALUES ($pendingId, 5, 1, 2000, 60000, 0, 1782230100000)"""
            )

            db.query("SELECT lastListenedAt FROM pending_track_stats WHERE pendingId = $pendingId").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1_782_230_100_000L, c.getLong(0))
            }
        }
    }

    /**
     * Test 18: v8 → v11 chained migration preserves live data and supports v11 schema.
     */
    @Test
    fun migration8to11_chainedMigrationPreservesLiveDataAndSupportsV11Schema() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            seedSong(db, 77L)
            db.execSQL(
                """INSERT INTO track_stats
                   (songId, contentUri, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt)
                   VALUES (77, 'content://media/77', 8, 1, 5000, 90000, 0, 4500)"""
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB, 11, true,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        ).use { db ->
            // Live data survived
            db.query("SELECT title FROM songs WHERE id = 77").use { c ->
                assertTrue("songs row missing after chained migration", c.moveToFirst())
                assertEquals("Song 77", c.getString(0))
            }
            db.query("SELECT playCount, lastListenedAt FROM track_stats WHERE songId = 77").use { c ->
                assertTrue("track_stats row missing after chained migration", c.moveToFirst())
                assertEquals(8, c.getInt(0))
                assertEquals(4500L, c.getLong(1))
            }

            // v11 pending_track_stats schema accepts lastListenedAt
            db.execSQL(
                """INSERT INTO pending_tracks
                   (originKey, backupFingerprint, backupSongId, title, artist, album, duration, trackNumber, year, restoredAt, status)
                   VALUES ('fp|77', 'fp', '77', 'Song', 'A', 'B', 180000, 1, 2025, 9999, 'PENDING')"""
            )
            val pendingId = db.query("SELECT pendingId FROM pending_tracks WHERE originKey = 'fp|77'")
                .use { c -> c.moveToFirst(); c.getLong(0) }

            db.execSQL(
                """INSERT INTO pending_track_stats
                   (pendingId, playCount, skipCount, lastPlayedAt, totalListeningTimeMs, isFavorite, lastListenedAt)
                   VALUES ($pendingId, 8, 1, 5000, 90000, 0, 4500)"""
            )

            db.query("SELECT lastListenedAt FROM pending_track_stats WHERE pendingId = $pendingId").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(4500L, c.getLong(0))
            }
        }
    }

    @Test
    fun migration12to13_createsPendingBackupExtensions() {
        helper.createDatabase(TEST_DB, 12).use { }

        helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13).use { db ->
            db.execSQL(
                """INSERT INTO pending_backup_extensions (rootName, rawJson, importedAt)
                   VALUES ('desktopOverlay', '{"schemaVersion":1}', 1782230400000)"""
            )

            db.query("SELECT rawJson, importedAt FROM pending_backup_extensions WHERE rootName = 'desktopOverlay'")
                .use { c ->
                    assertTrue("pending_backup_extensions row missing", c.moveToFirst())
                    assertEquals("""{"schemaVersion":1}""", c.getString(0))
                    assertEquals(1_782_230_400_000L, c.getLong(1))
                }
        }
    }

    companion object {
        private const val TEST_DB = "wavdrop-migration-test.db"
    }
}

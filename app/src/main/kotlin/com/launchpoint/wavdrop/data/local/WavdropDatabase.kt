package com.launchpoint.wavdrop.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PendingBackupExtensionDao
import com.launchpoint.wavdrop.data.local.dao.PendingTrackDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackIdentityDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.PendingBackupExtensionEntity
import com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.PendingLyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingPlaylistEntryEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackStatsEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackIdentityEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity

@Database(
    entities     = [
        SongEntity::class,
        TrackStatsEntity::class,
        ImportBaselineEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        TrackListenEventEntity::class,
        LyricsOverrideEntity::class,
        PendingTrackEntity::class,
        PendingTrackStatsEntity::class,
        PendingListenEventEntity::class,
        PendingLyricsOverrideEntity::class,
        PendingImportBaselineEntity::class,
        PendingPlaylistEntryEntity::class,
        TrackIdentityEntity::class,
        PendingBackupExtensionEntity::class,
    ],
    version      = 13,
    exportSchema = true,
)
abstract class WavdropDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun trackStatsDao(): TrackStatsDao
    abstract fun importBaselineDao(): ImportBaselineDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun trackListenEventDao(): TrackListenEventDao
    abstract fun lyricsOverrideDao(): LyricsOverrideDao
    abstract fun pendingTrackDao(): PendingTrackDao
    abstract fun trackIdentityDao(): TrackIdentityDao
    abstract fun pendingBackupExtensionDao(): PendingBackupExtensionDao
}

/** Add the track_stats table. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_stats (
                songId               INTEGER NOT NULL PRIMARY KEY,
                contentUri           TEXT    NOT NULL,
                playCount            INTEGER NOT NULL DEFAULT 0,
                skipCount            INTEGER NOT NULL DEFAULT 0,
                lastPlayedAt         INTEGER NOT NULL DEFAULT 0,
                totalListeningTimeMs INTEGER NOT NULL DEFAULT 0,
                isFavorite           INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}

/** Add generic import baselines for deduplicating external stats imports. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_baselines (
                songId                INTEGER NOT NULL,
                sourceType            TEXT    NOT NULL,
                sourceKey             TEXT    NOT NULL,
                lastImportedPlayCount INTEGER NOT NULL,
                lastImportedSkipCount INTEGER NOT NULL,
                lastImportedAt        INTEGER NOT NULL,
                PRIMARY KEY(songId, sourceType, sourceKey)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_import_baselines_sourceType_sourceKey
            ON import_baselines(sourceType, sourceKey)
            """.trimIndent()
        )
    }
}

/** Add nullable folder metadata columns for folder browsing. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN folderPath TEXT")
        db.execSQL("ALTER TABLE songs ADD COLUMN folderName TEXT")
    }
}

/** Add playlists and playlist_songs tables. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlists (
                playlistId INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name       TEXT    NOT NULL,
                createdAt  INTEGER NOT NULL,
                updatedAt  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playlist_songs (
                playlistId INTEGER NOT NULL,
                songId     INTEGER NOT NULL,
                position   INTEGER NOT NULL,
                PRIMARY KEY(playlistId, position),
                FOREIGN KEY(playlistId) REFERENCES playlists(playlistId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_songs_playlistId ON playlist_songs(playlistId)"
        )
    }
}

/**
 * Add per-play/per-skip event history table (track_listen_events).
 *
 * History starts from this migration — no backfill from TrackStatsEntity or BlackPlayer imports.
 * BlackPlayer all-time aggregate stats remain aggregate-only.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_listen_events (
                id         INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                songId     INTEGER NOT NULL,
                eventType  TEXT    NOT NULL,
                occurredAt INTEGER NOT NULL,
                listenedMs INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL DEFAULT 0,
                source     TEXT    NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_track_listen_events_occurredAt ON track_listen_events(occurredAt)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_track_listen_events_songId_occurredAt ON track_listen_events(songId, occurredAt)"
        )
    }
}

/**
 * Add lastListenedAt — tracks the last time the user listened to a song for ≥ 5 s.
 * Used for Recently Played (Home + Smart Collection) instead of lastPlayedAt so that
 * the meaningful-play threshold does not gate the Recently Played surface.
 *
 * Backfill: existing rows with lastPlayedAt > 0 are seeded with lastListenedAt = lastPlayedAt
 * so that users keep their current Recently Played history after the upgrade.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE track_stats ADD COLUMN lastListenedAt INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "UPDATE track_stats SET lastListenedAt = lastPlayedAt WHERE lastPlayedAt > 0"
        )
    }
}

/**
 * Add six quarantine tables that preserve backup history for tracks that could
 * not be matched to any local song at restore time. Existing tables and data
 * are untouched.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_tracks (
                pendingId        INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                originKey        TEXT    NOT NULL,
                backupFingerprint TEXT   NOT NULL,
                backupSongId     TEXT    NOT NULL,
                title            TEXT    NOT NULL,
                artist           TEXT    NOT NULL,
                album            TEXT    NOT NULL,
                duration         INTEGER NOT NULL,
                trackNumber      INTEGER NOT NULL,
                year             INTEGER NOT NULL,
                sourceUri        TEXT,
                sourceFolderPath TEXT,
                restoredAt       INTEGER NOT NULL,
                status           TEXT    NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_tracks_originKey ON pending_tracks(originKey)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_track_stats (
                pendingId            INTEGER NOT NULL PRIMARY KEY,
                playCount            INTEGER NOT NULL,
                skipCount            INTEGER NOT NULL,
                lastPlayedAt         INTEGER NOT NULL,
                totalListeningTimeMs INTEGER NOT NULL,
                isFavorite           INTEGER NOT NULL,
                FOREIGN KEY(pendingId) REFERENCES pending_tracks(pendingId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_listen_events (
                id                INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                pendingId         INTEGER NOT NULL,
                eventType         TEXT    NOT NULL,
                occurredAt        INTEGER NOT NULL,
                listenedMs        INTEGER NOT NULL,
                durationMs        INTEGER NOT NULL,
                originFingerprint TEXT    NOT NULL,
                FOREIGN KEY(pendingId) REFERENCES pending_tracks(pendingId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_listen_events_pendingId ON pending_listen_events(pendingId)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_listen_events_originFingerprint ON pending_listen_events(originFingerprint)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_lyrics_overrides (
                pendingId         INTEGER NOT NULL PRIMARY KEY,
                lyrics            TEXT    NOT NULL,
                updatedAt         INTEGER NOT NULL,
                originFingerprint TEXT    NOT NULL,
                FOREIGN KEY(pendingId) REFERENCES pending_tracks(pendingId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_lyrics_overrides_originFingerprint ON pending_lyrics_overrides(originFingerprint)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_import_baselines (
                id                INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                pendingId         INTEGER NOT NULL,
                sourceType        TEXT    NOT NULL,
                sourceKey         TEXT    NOT NULL,
                playCount         INTEGER NOT NULL,
                originFingerprint TEXT    NOT NULL,
                FOREIGN KEY(pendingId) REFERENCES pending_tracks(pendingId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_import_baselines_pendingId ON pending_import_baselines(pendingId)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_import_baselines_originFingerprint ON pending_import_baselines(originFingerprint)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_playlist_entries (
                id                INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                pendingId         INTEGER NOT NULL,
                playlistName      TEXT    NOT NULL,
                position          INTEGER NOT NULL,
                originFingerprint TEXT    NOT NULL,
                FOREIGN KEY(pendingId) REFERENCES pending_tracks(pendingId) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_playlist_entries_pendingId ON pending_playlist_entries(pendingId)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_playlist_entries_originFingerprint ON pending_playlist_entries(originFingerprint)"
        )
    }
}

/**
 * Add playlist source provenance to [pending_playlist_entries].
 *
 * Three nullable columns are appended; existing v9 rows get NULL for all three,
 * which correctly represents legacy-ambiguous provenance (the source backup playlist
 * ID and timestamps were not stored in v9). Do not invent values for old rows.
 *
 * The origin fingerprint scheme for new entries also changes: it now keys on the
 * backup-internal playlist ID rather than the playlist display name, so two playlists
 * with the same name but different IDs produce distinct fingerprints. Pre-v10 rows
 * in the DB retain their old name-based fingerprints and are not retroactively updated.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE pending_playlist_entries ADD COLUMN sourcePlaylistId TEXT"
        )
        db.execSQL(
            "ALTER TABLE pending_playlist_entries ADD COLUMN sourcePlaylistCreatedAt INTEGER"
        )
        db.execSQL(
            "ALTER TABLE pending_playlist_entries ADD COLUMN sourcePlaylistUpdatedAt INTEGER"
        )
    }
}

/**
 * Add [lastListenedAt] to [pending_track_stats].
 *
 * The column is nullable (no NOT NULL, no default). Existing v10 rows correctly
 * receive NULL, which represents "sourced from a v1 backup — field absent".
 * New v2 quarantine rows insert the exact epoch-ms value from the backup.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE pending_track_stats ADD COLUMN lastListenedAt INTEGER"
        )
    }
}

/**
 * P2-B1 Phase 1 — TrackIdentity foundation + eventId column. Additive only.
 *
 *  1. Create [track_identities] — device-local durable identity store. currentSongId is a
 *     soft, nullable reference to songs.id; there is deliberately NO foreign key/cascade so
 *     identities survive a songs-table rebuild on rescan.
 *  2. Add nullable [eventId] to track_listen_events. Existing rows correctly receive NULL
 *     ("legacy event"); there is no backfill. New-event generation is a later phase.
 *
 * Pending tables are untouched. No data is rewritten.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_identities (
                identityUuid   TEXT    NOT NULL PRIMARY KEY,
                currentSongId  INTEGER,
                createdAt      INTEGER NOT NULL,
                lastResolvedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_track_identities_currentSongId ON track_identities(currentSongId)"
        )
        db.execSQL("ALTER TABLE track_listen_events ADD COLUMN eventId TEXT")
    }
}

/**
 * Preserve raw, non-integrity extension roots from verified backups so newer portable data can
 * survive Android import/re-export even before Android understands every nested field.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_backup_extensions (
                rootName   TEXT    NOT NULL PRIMARY KEY,
                rawJson    TEXT    NOT NULL,
                importedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/** Add app-managed unsynced lyric overrides. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS lyrics_overrides (
                songId     INTEGER NOT NULL PRIMARY KEY,
                contentUri TEXT    NOT NULL,
                lyrics     TEXT    NOT NULL,
                updatedAt  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_lyrics_overrides_contentUri ON lyrics_overrides(contentUri)"
        )
    }
}

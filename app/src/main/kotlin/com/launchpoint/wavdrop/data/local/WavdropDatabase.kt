package com.launchpoint.wavdrop.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
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
    ],
    version      = 7,
    exportSchema = true,
)
abstract class WavdropDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun trackStatsDao(): TrackStatsDao
    abstract fun importBaselineDao(): ImportBaselineDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun trackListenEventDao(): TrackListenEventDao
    abstract fun lyricsOverrideDao(): LyricsOverrideDao
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

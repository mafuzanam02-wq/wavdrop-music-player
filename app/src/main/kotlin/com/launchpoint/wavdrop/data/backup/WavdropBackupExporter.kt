package com.launchpoint.wavdrop.data.backup

import org.json.JSONArray
import org.json.JSONObject

object WavdropBackupExporter {

    fun toJson(backup: WavdropBackup): String = JSONObject().apply {
        put("app", "Wavdrop")
        put("format", "wavdrop_backup")
        put("version", 1)
        put("exportedAt", backup.exportedAt)
        put("packageName", "com.launchpoint.wavdrop")
        put("database", "wavdrop.db")
        put("songs", songsArray(backup.songs))
        put("trackStats", trackStatsArray(backup.trackStats))
        put("importBaselines", baselinesArray(backup.importBaselines))
        put("lyricsOverrides", lyricsOverridesArray(backup.lyricsOverrides))
    }.toString(2)

    private fun songsArray(songs: List<BackupSong>): JSONArray = JSONArray().apply {
        songs.forEach { s ->
            put(JSONObject().apply {
                put("id",          s.id)
                put("uri",         s.uri)
                put("title",       s.title)
                put("artist",      s.artist)
                put("album",       s.album)
                put("albumId",     s.albumId)
                put("duration",    s.duration)
                put("dateAdded",   s.dateAdded)
                put("trackNumber", s.trackNumber)
                put("year",        s.year)
            })
        }
    }

    private fun trackStatsArray(stats: List<BackupTrackStats>): JSONArray = JSONArray().apply {
        stats.forEach { s ->
            put(JSONObject().apply {
                put("songId",               s.songId)
                put("contentUri",           s.contentUri)
                put("playCount",            s.playCount)
                put("skipCount",            s.skipCount)
                put("lastPlayedAt",         s.lastPlayedAt)
                put("totalListeningTimeMs", s.totalListeningTimeMs)
                put("isFavorite",           s.isFavorite)
            })
        }
    }

    private fun baselinesArray(baselines: List<BackupImportBaseline>): JSONArray = JSONArray().apply {
        baselines.forEach { b ->
            put(JSONObject().apply {
                put("songId",                 b.songId)
                put("sourceType",             b.sourceType)
                put("sourceKey",              b.sourceKey)
                put("lastImportedPlayCount",  b.lastImportedPlayCount)
                put("lastImportedSkipCount",  b.lastImportedSkipCount)
                put("lastImportedAt",         b.lastImportedAt)
            })
        }
    }

    private fun lyricsOverridesArray(overrides: List<BackupLyricsOverride>): JSONArray = JSONArray().apply {
        overrides.forEach { o ->
            put(JSONObject().apply {
                put("songId",     o.songId)
                put("contentUri", o.contentUri)
                put("lyrics",     o.lyrics)
                put("updatedAt",  o.updatedAt)
            })
        }
    }
}

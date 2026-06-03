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
        backup.preferences?.let { put("preferences", preferencesObject(it)) }
        put("playlists", playlistsArray(backup.playlists))
        put("listenEvents", listenEventsArray(backup.listenEvents))
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

    private fun playlistsArray(playlists: List<BackupPlaylist>): JSONArray = JSONArray().apply {
        playlists.forEach { p ->
            put(JSONObject().apply {
                put("id",        p.id)
                put("name",      p.name)
                put("createdAt", p.createdAt)
                put("updatedAt", p.updatedAt)
                put("songs",     playlistSongsArray(p.songs))
            })
        }
    }

    private fun playlistSongsArray(songs: List<BackupPlaylistSong>): JSONArray = JSONArray().apply {
        songs.forEach { s ->
            put(JSONObject().apply {
                put("songId",     s.songId)
                put("contentUri", s.contentUri)
                put("position",   s.position)
                put("title",      s.title)
                put("artist",     s.artist)
                put("album",      s.album)
            })
        }
    }

    private fun listenEventsArray(events: List<BackupListenEvent>): JSONArray = JSONArray().apply {
        events.forEach { e ->
            put(JSONObject().apply {
                put("songId",     e.songId)
                put("contentUri", e.contentUri)
                put("title",      e.title)
                put("artist",     e.artist)
                put("album",      e.album)
                put("eventType",  e.eventType)
                put("occurredAt", e.occurredAt)
                put("listenedMs", e.listenedMs)
                put("durationMs", e.durationMs)
                put("source",     e.source)
            })
        }
    }

    private fun preferencesObject(prefs: BackupPreferences): JSONObject = JSONObject().apply {
        prefs.startupDestination?.let  { put("startupDestination",  it) }
        prefs.mostPlayedPeriod?.let    { put("mostPlayedPeriod",    it) }
        prefs.mostPlayedLimit?.let     { put("mostPlayedLimit",     it) }
        prefs.homeVisibleSections?.let { sections ->
            put("homeVisibleSections", JSONArray().apply { sections.forEach { put(it) } })
        }
    }
}

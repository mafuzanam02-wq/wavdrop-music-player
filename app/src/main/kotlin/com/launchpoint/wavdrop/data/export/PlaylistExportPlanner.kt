package com.launchpoint.wavdrop.data.export

import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.model.Song

enum class UnresolvedReason { SongRowMissing, NoFilePath, UnusablePath }

data class UnresolvedEntry(val position: Int, val songId: Long, val reason: UnresolvedReason)

data class PlannedEntry(val position: Int, val songId: Long, val entry: M3u8Entry)

/** Everything the export will write ([entries]) plus everything it will not ([unresolved]). */
data class ExportPlan(
    val playlistId: Long,
    val playlistName: String,
    val entries: List<PlannedEntry>,
    val unresolved: List<UnresolvedEntry>,
) {
    val resolvedCount: Int get() = entries.size
    val unresolvedCount: Int get() = unresolved.size
    val totalCount: Int get() = entries.size + unresolved.size
    val suggestedFileName: String get() = PlaylistExportPlanner.fileNameFor(playlistName)
}

object PlaylistExportPlanner {
    private val illegalNameChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun plan(
        playlistId: Long,
        playlistName: String,
        rows: List<PlaylistSongEntity>,
        songs: Map<Long, Song>,
        paths: Map<Long, String>,
    ): ExportPlan {
        val entries = ArrayList<PlannedEntry>(rows.size)
        val unresolved = ArrayList<UnresolvedEntry>()
        for (row in rows.sortedBy { it.position }) {
            val song = songs[row.songId]
            if (song == null) {
                unresolved += UnresolvedEntry(row.position, row.songId, UnresolvedReason.SongRowMissing)
                continue
            }
            val path = paths[row.songId]
            when {
                path == null -> unresolved += UnresolvedEntry(row.position, row.songId, UnresolvedReason.NoFilePath)
                !M3u8Formatter.isSerializablePath(path) ->
                    unresolved += UnresolvedEntry(row.position, row.songId, UnresolvedReason.UnusablePath)
                else -> entries += PlannedEntry(
                    row.position, row.songId,
                    M3u8Entry(path, song.duration, song.displayArtist, song.displayTitle),
                )
            }
        }
        return ExportPlan(playlistId, playlistName, entries, unresolved)
    }

    fun fileNameFor(playlistName: String): String {
        val base = playlistName.replace(illegalNameChars, "_").trim().trim('.').take(100).trim()
        return (if (base.isEmpty()) "playlist" else base) + ".m3u8"
    }
}

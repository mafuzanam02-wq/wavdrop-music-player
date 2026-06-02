package com.launchpoint.wavdrop.data.library

import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.model.Song

object FolderGrouper {
    const val UNKNOWN_FOLDER = "Unknown Folder"

    fun groupSongsByFolder(songs: List<Song>): List<FolderSummary> =
        songs
            .groupBy { folderKey(it) }
            .map { (key, group) ->
                FolderSummary(
                    folderKey = key,
                    displayName = displayNameForGroup(key, group),
                    songCount = group.size,
                    totalDurationMs = group.sumOf { it.duration },
                )
            }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.folderKey.lowercase() }))

    fun songsForFolder(songs: List<Song>, folderKey: String): List<Song> =
        songs
            .filter { folderKey(it) == folderKey }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))

    fun folderKey(song: Song): String =
        song.folderPath
            ?.trim()
            ?.trim('/', '\\')
            ?.ifBlank { null }
            ?: UNKNOWN_FOLDER

    private fun displayNameForGroup(folderKey: String, songs: List<Song>): String {
        if (folderKey == UNKNOWN_FOLDER) return UNKNOWN_FOLDER
        return songs
            .asSequence()
            .mapNotNull { it.folderName?.trim()?.ifBlank { null } }
            .firstOrNull()
            ?: folderKey.substringAfterLast('/').substringAfterLast('\\').ifBlank { UNKNOWN_FOLDER }
    }
}

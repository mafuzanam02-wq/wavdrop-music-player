package com.launchpoint.wavdrop.data.mediastore

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRules
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scanSongs(settings: LibraryScanSettings = LibraryScanSettings()): List<Song> {
        val normalizedSettings = LibraryScanSettingsRules.normalize(settings)
        val minimumDurationMs = LibraryScanSettingsRules.minimumDurationMs(normalizedSettings)
        val songs = mutableListOf<Song>()

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        // Exclude non-music (podcasts, ringtones) and clips shorter than the configured threshold.
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
                "AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(minimumDurationMs.toString())

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val trackCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                -1
            }
            @Suppress("DEPRECATION")
            val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            } else {
                -1
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val folderInfo = readFolderInfo(
                    relativePath = cursor.getNullableString(relativePathCol),
                    dataPath = cursor.getNullableString(dataCol),
                )
                songs += Song(
                    id          = id,
                    title       = cursor.getString(titleCol) ?: "Unknown Title",
                    artist      = cursor.getString(artistCol) ?: "Unknown Artist",
                    album       = cursor.getString(albumCol) ?: "Unknown Album",
                    albumId     = cursor.getLong(albumIdCol),
                    duration    = cursor.getLong(durationCol),
                    uri         = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                    ).toString(),
                    dateAdded   = cursor.getLong(dateCol),
                    trackNumber = cursor.getInt(trackCol),
                    year        = cursor.getInt(yearCol),
                    folderPath  = folderInfo?.path,
                    folderName  = folderInfo?.name,
                )
            }
        }

        return LibraryScanSettingsRules.filterSongsForScanSettings(
            songs = songs,
            settings = normalizedSettings,
        )
    }

    private fun readFolderInfo(relativePath: String?, dataPath: String?): FolderInfo? {
        val relative = relativePath
            ?.trim()
            ?.trim('/', '\\')
            ?.takeIf { it.isNotBlank() }
        if (relative != null) {
            return FolderInfo(
                path = relative,
                name = relative.substringAfterLast('/').substringAfterLast('\\').ifBlank { relative },
            )
        }

        val parent = dataPath
            ?.substringBeforeLast('/', missingDelimiterValue = "")
            ?.trim()
            ?.trim('/', '\\')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return FolderInfo(
            path = parent,
            name = parent.substringAfterLast('/').substringAfterLast('\\').ifBlank { parent },
        )
    }

    private fun android.database.Cursor.getNullableString(columnIndex: Int): String? =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex) else null

    private data class FolderInfo(
        val path: String,
        val name: String,
    )
}

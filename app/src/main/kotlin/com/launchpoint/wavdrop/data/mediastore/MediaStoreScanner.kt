package com.launchpoint.wavdrop.data.mediastore

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.launchpoint.wavdrop.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scanSongs(): List<Song> {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
        )

        // Exclude non-music (podcasts, ringtones) and clips shorter than 30 s
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
                "AND ${MediaStore.Audio.Media.DURATION} >= 30000"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
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
                )
            }
        }

        return songs
    }
}

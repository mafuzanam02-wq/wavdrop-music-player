package com.launchpoint.wavdrop.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.launchpoint.wavdrop.data.model.Song

internal const val WAVDROP_ALBUM_ID_EXTRA = "wavdrop_album_id"

/** Builds the canonical Media3 representation used by normal and resumed playback. */
internal fun Song.toPlaybackMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .setMediaId(id.toString())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(displayTitle)
                .setArtist(displayArtist)
                .setAlbumTitle(album)
                .setExtras(Bundle().apply { putLong(WAVDROP_ALBUM_ID_EXTRA, albumId) })
                .build()
        )
        .build()

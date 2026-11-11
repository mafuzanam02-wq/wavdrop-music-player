package com.launchpoint.wavdrop.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.Song

internal const val WAVDROP_ALBUM_ID_EXTRA = "wavdrop_album_id"

/**
 * The album-art reference published to system media surfaces (notification, lock screen), or null when the song has no album id.
 *
 * It is the SAME `content://media/external/audio/albumart/<albumId>` reference the in-app screens already render
 * ([ArtworkResolver]). It is only a reference: Media3's session bitmap loader (a size-limited `DataSourceBitmapLoader` created by the
 * session builder) resolves it asynchronously in this app's own process, which already holds the media permission, so no new
 * permission or URI grant is needed, nothing is decoded here and playback never waits for it. A reference whose image does not exist
 * simply fails to load and the surfaces fall back to their no-artwork presentation. It never takes part in queue/occurrence identity
 * (the media id remains the song id).
 */
internal fun Song.artworkUriOrNull(): Uri? =
    ArtworkResolver.albumArtworkUri(albumId)?.let(Uri::parse)

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
                .setArtworkUri(artworkUriOrNull())
                .setExtras(Bundle().apply { putLong(WAVDROP_ALBUM_ID_EXTRA, albumId) })
                .build()
        )
        .build()

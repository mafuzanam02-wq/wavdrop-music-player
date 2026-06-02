package com.launchpoint.wavdrop.data.artwork

object ArtworkResolver {

    fun albumArtworkUri(albumId: Long?): String? {
        if (albumId == null || albumId <= 0L) return null
        return "content://media/external/audio/albumart/$albumId"
    }
}

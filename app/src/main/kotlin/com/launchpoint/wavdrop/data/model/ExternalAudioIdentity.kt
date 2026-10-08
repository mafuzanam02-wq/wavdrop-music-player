package com.launchpoint.wavdrop.data.model

/**
 * The identity of the SYNTHETIC song that external ACTION_VIEW playback creates. An externally opened file is deliberately not
 * part of the WavDrop library, so this id never refers to a library song and must never be persisted as one (for example as a
 * playlist entry). [com.launchpoint.wavdrop.playback.PlayerController] builds the external song with [SONG_ID]; everything else
 * asks this object instead of repeating the sentinel.
 */
object ExternalAudioIdentity {
    const val SONG_ID: Long = Long.MIN_VALUE

    fun isExternalAudioId(songId: Long): Boolean = songId == SONG_ID

    fun isExternalAudio(song: Song): Boolean = isExternalAudioId(song.id)
}

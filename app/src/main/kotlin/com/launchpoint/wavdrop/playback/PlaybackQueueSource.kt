package com.launchpoint.wavdrop.playback

/**
 * Provenance of the active logical playback queue. Only what playlist row highlighting needs to
 * prove is modelled: a queue is either known to have been started from one playlist, or its
 * source is unidentified. It is set by queue-replacing operations and never inferred from song IDs.
 * Not persisted: after session restore the source is [Other], so nothing is guessed.
 */
sealed interface PlaybackQueueSource {
    data class Playlist(val playlistId: Long) : PlaybackQueueSource

    data object Other : PlaybackQueueSource
}

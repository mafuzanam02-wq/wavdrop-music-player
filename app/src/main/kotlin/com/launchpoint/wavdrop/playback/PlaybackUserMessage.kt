package com.launchpoint.wavdrop.playback

/**
 * Concise, transient user-facing messages emitted by [PlayerController] (Phase 8).
 *
 * Kept as a UI-free enum so the playback layer never hard-codes display strings, exposes raw
 * exception classes, content URIs, file paths, or Media3 error codes. The UI maps each value to a
 * localized string. At most one message is emitted per bad-media recovery episode.
 */
enum class PlaybackUserMessage {
    /** A track could not be played and playback automatically skipped past it. */
    BAD_TRACK_SKIPPED,

    /** Recovery walked the queue and found nothing else playable; playback stopped. */
    QUEUE_EXHAUSTED,
}

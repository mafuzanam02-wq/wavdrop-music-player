package com.launchpoint.wavdrop.data.repository

/**
 * Minimal write interface used by the playback layer to record play and skip events.
 * Implemented by [StatsRepository]; the separation allows the playback package to depend
 * on this interface rather than the full repository, and makes StatsTracker unit-testable.
 */
interface PlayEventWriter {
    suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long = 0L)
    suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long = 0L)

    /**
     * Records that the user listened to [songId] for at least 5 accumulated seconds.
     * Updates [TrackStatsEntity.lastListenedAt] only — does not increment playCount,
     * does not write a PLAY or SKIP event, and does not affect analytics.
     * Used exclusively by the Recently Played surface (Home + Smart Collection).
     */
    suspend fun recordListenStart(songId: Long, contentUri: String)
}

package com.launchpoint.wavdrop.data.repository

/**
 * Minimal write interface used by the playback layer to record play and skip events.
 * Implemented by [StatsRepository]; the separation allows the playback package to depend
 * on this interface rather than the full repository, and makes StatsTracker unit-testable.
 */
interface PlayEventWriter {
    suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long = 0L)
    suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long = 0L)
}

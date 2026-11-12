package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import com.launchpoint.wavdrop.data.repository.StatsWriteResult

/**
 * P5-STATS-19C - TEST SUPPORT ONLY. A convenience base for the many playback-test fakes that only need to observe or ignore writes.
 *
 * Production [PlayEventWriter] has no default implementations (so a real writer can never silently inherit a non-idempotent write).
 * This class makes the fakes' choice explicit instead: each occurrence method forwards to a simple, overridable hook and reports
 * [StatsWriteResult.APPLIED]. It models NO idempotency - fakes that need to test it (or retry behaviour) implement [PlayEventWriter]
 * directly or use the real repository.
 */
open class HookedPlayEventWriter : PlayEventWriter {
    open suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) {}
    open suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) {}
    open suspend fun recordListenStart(songId: Long, contentUri: String) {}

    final override suspend fun recordPlayOccurrence(
        occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long,
    ): StatsWriteResult {
        recordPlay(songId, contentUri, listenedMs, durationMs)
        return StatsWriteResult.APPLIED
    }

    final override suspend fun recordSkipOccurrence(
        occurrence: PlayOccurrence, songId: Long, contentUri: String, durationMs: Long,
    ): StatsWriteResult {
        recordSkip(songId, contentUri, durationMs)
        return StatsWriteResult.APPLIED
    }

    final override suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String) {
        recordListenStart(songId, contentUri)
    }
}

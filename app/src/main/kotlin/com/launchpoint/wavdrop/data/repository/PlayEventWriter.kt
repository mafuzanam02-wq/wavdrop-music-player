package com.launchpoint.wavdrop.data.repository

/**
 * P5-STATS-19B - identity of ONE logical PLAY or SKIP occurrence.
 *
 * Created once, by the caller, at the moment the occurrence is detected. Every later attempt to persist the same occurrence must pass
 * the SAME value, so the writer can recognise it. [occurredAtMs] is wall-clock epoch milliseconds captured at detection time (never
 * inside a delayed attempt), and is what the stored event and the aggregate timestamps use.
 */
data class PlayOccurrence(val eventId: String, val occurredAtMs: Long) {
    init { require(eventId.isNotBlank()) { "eventId must not be blank" } }
}

/** Outcome of persisting an occurrence. Both values mean the occurrence is now durably recorded exactly once. */
enum class StatsWriteResult {
    /** This call inserted the event and updated the aggregate. */
    APPLIED,

    /** An equivalent event with this id was already stored; nothing was changed. */
    ALREADY_APPLIED,
}

/**
 * The same event id is already stored with a different payload (song, type, time, listened or duration). Nothing was modified. This is a
 * data-integrity violation, not a transient failure, so it must never be retried blindly.
 */
class StatsEventConflictException(message: String) : IllegalStateException(message)

/**
 * Minimal write interface used by the playback layer to record play, skip and listen-start statistics.
 * Implemented by [StatsRepository]; the separation allows the playback package to depend
 * on this interface rather than the full repository, and makes StatsTracker unit-testable.
 *
 * P5-STATS-19C - CONTRACT. Every method is idempotent per occurrence, because the caller may repeat the exact same call after an
 * uncertain outcome (e.g. a commit whose acknowledgement was lost). There are deliberately NO default implementations and no
 * non-idempotent entry points: an implementation cannot silently inherit a write that would count a repeated call twice.
 *
 *  - An equivalent event already stored under the same id => return [StatsWriteResult.ALREADY_APPLIED], change nothing.
 *  - The same id stored with a different payload => throw [StatsEventConflictException], change nothing.
 *  - Otherwise apply the event and its aggregate update atomically and return [StatsWriteResult.APPLIED].
 */
interface PlayEventWriter {
    /** Idempotent PLAY keyed by [occurrence]: applied at most once however often it is repeated. */
    suspend fun recordPlayOccurrence(
        occurrence: PlayOccurrence,
        songId: Long,
        contentUri: String,
        listenedMs: Long,
        durationMs: Long,
    ): StatsWriteResult

    /** Idempotent SKIP keyed by [occurrence]: applied at most once however often it is repeated. */
    suspend fun recordSkipOccurrence(
        occurrence: PlayOccurrence,
        songId: Long,
        contentUri: String,
        durationMs: Long,
    ): StatsWriteResult

    /**
     * Records that the user listened to [songId] for at least 5 accumulated seconds, at the time [occurredAtMs] captured at detection.
     * Updates `lastListenedAt` only (it never moves backwards) - does not increment playCount, does not write a PLAY or SKIP event, and
     * does not affect analytics. Naturally idempotent. Used exclusively by the Recently Played surface (Home + Smart Collection).
     */
    suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String)
}

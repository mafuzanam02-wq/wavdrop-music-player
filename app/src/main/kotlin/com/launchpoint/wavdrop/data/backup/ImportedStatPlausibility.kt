package com.launchpoint.wavdrop.data.backup

/**
 * Shared plausibility policy for imported *portable* track statistics (WD-05).
 *
 * Imported stats merge into local stats with MAX semantics, so an absurd or
 * overflow-adjacent value (e.g. a manually edited backup with a near-`Int.MAX_VALUE`
 * play count, or a timestamp far in the future) would permanently dominate local
 * data — MAX merges can never lower it again — corrupting Insights, Wrapped,
 * Most Played sorting, skip analytics, and listening-time displays.
 *
 * Policy: **reject or quarantine** implausible rows rather than silently clamping.
 * Clamping would launder malformed input into apparently valid data and hide that
 * the backup was tampered with or corrupt.
 *
 * These bounds validate portable magnitudes only. They do NOT touch:
 *  - Android playback stat generation,
 *  - listen-event thresholds (PLAY requires listenedMs > 0, SKIP requires
 *    listenedMs == 0),
 *  - the integrity fingerprint (validation always runs *after* integrity
 *    verification; no sealed value is mutated).
 *
 * All duration constants use Long arithmetic to avoid Int overflow.
 */
object ImportedStatPlausibility {

    /** A single play/skip counter cannot exceed this. ~10M plays is already absurd. */
    const val MAX_PLAY_COUNT: Int = 10_000_000
    const val MAX_SKIP_COUNT: Int = 10_000_000

    /**
     * Upper bound on cumulative listening time: 100 years in milliseconds.
     * Computed with Long arithmetic (365-day years; leap days are irrelevant at
     * this magnitude — the bound only needs to be "obviously impossible").
     */
    const val MAX_TOTAL_LISTENING_TIME_MS: Long =
        100L * 365L * 24L * 60L * 60L * 1_000L // 3_153_600_000_000

    /**
     * Clock-skew allowance for timestamps. A backup produced on a device whose
     * clock runs slightly ahead is still legitimate; 7 days comfortably covers
     * that without letting a year-2200 timestamp through.
     */
    const val CLOCK_SKEW_ALLOWANCE_MS: Long = 7L * 24L * 60L * 60L * 1_000L // 604_800_000

    fun isPlausiblePlayCount(value: Int): Boolean = value in 0..MAX_PLAY_COUNT

    fun isPlausibleSkipCount(value: Int): Boolean = value in 0..MAX_SKIP_COUNT

    fun isPlausibleTotalListeningTimeMs(value: Long): Boolean =
        value in 0L..MAX_TOTAL_LISTENING_TIME_MS

    /**
     * A timestamp is plausible when it is non-negative (0 = "never", allowed
     * wherever it is currently valid) and no further in the future than now plus
     * the clock-skew allowance.
     */
    fun isPlausibleTimestamp(value: Long, nowMs: Long): Boolean =
        value in 0L..(nowMs + CLOCK_SKEW_ALLOWANCE_MS)

    /**
     * True when every portable magnitude of a track-stats row is within bounds.
     *
     * @param lastListenedAt null is accepted (v1 backups have no such field);
     *   when present it is bounded like any other timestamp.
     */
    fun isPlausibleTrackStats(
        playCount: Int,
        skipCount: Int,
        totalListeningTimeMs: Long,
        lastPlayedAt: Long,
        lastListenedAt: Long?,
        nowMs: Long,
    ): Boolean =
        isPlausiblePlayCount(playCount) &&
            isPlausibleSkipCount(skipCount) &&
            isPlausibleTotalListeningTimeMs(totalListeningTimeMs) &&
            isPlausibleTimestamp(lastPlayedAt, nowMs) &&
            (lastListenedAt == null || isPlausibleTimestamp(lastListenedAt, nowMs))
}

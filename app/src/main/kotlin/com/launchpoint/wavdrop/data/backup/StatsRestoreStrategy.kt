package com.launchpoint.wavdrop.data.backup

/**
 * Pure MAX-reconciliation strategy for Wavdrop backup merge restore (P1-B and later).
 *
 * Merge Restore semantics (contract §8.2):
 *   - Neither local nor backup is the sole source of truth.
 *   - Every field is set to MAX(local, backup), so stats can only increase.
 *   - Idempotent: merging the same backup twice yields zero deltas the second time.
 *
 * The actual DB write is [TrackStatsDao.mergeMaxStats]. This object exists to
 * compute reporting counts without touching the database.
 *
 * The caller is responsible for computing [effectiveBackupLastListenedAt] before
 * calling [computeEffect]:
 *   - v1 backup: use [BackupTrackStats.lastPlayedAt] as the effective value
 *     (conservative fallback — v1 has no separate lastListenedAt field).
 *   - v2 backup: use [BackupTrackStats.lastListenedAt] (exact, non-null).
 *
 * This distinction is preserved across the parser → preview → apply → DAO call chain
 * and is never inferred from the value being null vs 0.
 *
 * Note: [StatsImportMerger] handles BlackPlayer import (incremental deltas, no
 * lastPlayedAt in its effect). Wavdrop backup merge uses a separate MAX effect
 * that includes lastPlayedAt, lastListenedAt, and the retained-local dimension.
 */
object StatsRestoreStrategy {

    data class MergeEffect(
        /** True when at least one backup value was higher than local and will raise it. */
        val anyIncreased: Boolean,
        /** True when at least one local value was higher than backup and will be kept. */
        val anyRetainedLocal: Boolean,
    ) {
        /** True when any field differs between local and backup (either direction). */
        val anyChanged: Boolean get() = anyIncreased || anyRetainedLocal
    }

    /**
     * Computes the merge effect of applying backup stats to current stats with MAX semantics.
     * The actual DB update is performed by [TrackStatsDao.mergeMaxStats].
     *
     * @param effectiveBackupLastListenedAt The effective lastListenedAt from the backup,
     *   computed by the caller based on the source format version:
     *   v1 → [BackupTrackStats.lastPlayedAt]; v2 → [BackupTrackStats.lastListenedAt].
     */
    fun computeEffect(
        currentPlayCount: Int,
        currentSkipCount: Int,
        currentListeningTimeMs: Long,
        currentLastPlayedAt: Long,
        currentLastListenedAt: Long = 0L,
        backupPlayCount: Int,
        backupSkipCount: Int,
        backupListeningTimeMs: Long,
        backupLastPlayedAt: Long,
        effectiveBackupLastListenedAt: Long = 0L,
    ): MergeEffect = MergeEffect(
        anyIncreased =
            backupPlayCount                    > currentPlayCount       ||
            backupSkipCount                    > currentSkipCount       ||
            backupListeningTimeMs              > currentListeningTimeMs ||
            backupLastPlayedAt                 > currentLastPlayedAt    ||
            effectiveBackupLastListenedAt      > currentLastListenedAt,
        anyRetainedLocal =
            currentPlayCount       > backupPlayCount            ||
            currentSkipCount       > backupSkipCount            ||
            currentListeningTimeMs > backupListeningTimeMs      ||
            currentLastPlayedAt    > backupLastPlayedAt         ||
            currentLastListenedAt  > effectiveBackupLastListenedAt,
    )
}

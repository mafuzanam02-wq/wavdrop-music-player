package com.launchpoint.wavdrop.data.backup

/**
 * Pure restore-mode strategy for Wavdrop backup restore.
 *
 * Unlike [StatsImportMerger] (MAX-reconciliation, used by BlackPlayer *import*),
 * a Wavdrop backup *restore* treats the backup as the source of truth: when a
 * track is confidently matched, aggregate stats are set to exactly the backup
 * values — even if local values are higher. Exact set is naturally idempotent:
 * restoring the same backup twice produces the same values.
 */
object StatsRestoreStrategy {

    data class RestoreEffect(
        /** True when applying the backup values changes at least one stored field. */
        val anyChanged: Boolean,
    )

    /**
     * Computes whether setting the backup values would change the current row.
     * The actual DB update is performed by TrackStatsDao.restoreExactStats; this
     * exists to calculate reporting counts without touching the database.
     */
    fun computeEffect(
        currentPlayCount: Int,
        currentSkipCount: Int,
        currentListeningTimeMs: Long,
        currentLastPlayedAt: Long,
        backupPlayCount: Int,
        backupSkipCount: Int,
        backupListeningTimeMs: Long,
        backupLastPlayedAt: Long,
    ): RestoreEffect = RestoreEffect(
        anyChanged = currentPlayCount != backupPlayCount ||
            currentSkipCount != backupSkipCount ||
            currentListeningTimeMs != backupListeningTimeMs ||
            currentLastPlayedAt != backupLastPlayedAt,
    )
}

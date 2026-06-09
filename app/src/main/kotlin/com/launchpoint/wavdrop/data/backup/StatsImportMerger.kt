package com.launchpoint.wavdrop.data.backup

/**
 * Shared, pure MAX-reconciliation strategy used by both Wavdrop backup restore and
 * BlackPlayer import.
 *
 * Strategy: treat imported totals as target totals, not increments.
 *   newPlayCount         = MAX(current, imported)
 *   newSkipCount         = MAX(current, imported)
 *   newTotalListeningMs  = MAX(current, imported)
 *
 * This is idempotent by definition: calling merge with the same imported values twice
 * produces zero deltas the second time. It also preserves local stats that are higher
 * than the import (never reduces local counters).
 */
object StatsImportMerger {

    data class MergeEffect(
        /** How much playCount will increase (0 if imported <= current). */
        val playDelta: Long,
        /** How much skipCount will increase (0 if imported <= current). */
        val skipDelta: Long,
        /** How much totalListeningTimeMs will increase (0 if imported <= current). */
        val listeningTimeDelta: Long,
    ) {
        val anyUpdated: Boolean
            get() = playDelta > 0L || skipDelta > 0L || listeningTimeDelta > 0L
    }

    /**
     * Computes the net effect of merging imported stats into current stats with MAX semantics.
     * The actual DB update is performed by [TrackStatsDao.mergeMaxStats]; this function exists
     * to calculate reporting deltas without touching the database.
     */
    fun computeEffect(
        currentPlayCount: Int,
        currentSkipCount: Int,
        currentListeningTimeMs: Long,
        importedPlayCount: Int,
        importedSkipCount: Int,
        importedListeningTimeMs: Long,
    ): MergeEffect = MergeEffect(
        playDelta          = maxOf(0, importedPlayCount - currentPlayCount).toLong(),
        skipDelta          = maxOf(0, importedSkipCount - currentSkipCount).toLong(),
        listeningTimeDelta = maxOf(0L, importedListeningTimeMs - currentListeningTimeMs),
    )
}

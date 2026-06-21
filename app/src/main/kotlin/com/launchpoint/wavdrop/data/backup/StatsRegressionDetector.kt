package com.launchpoint.wavdrop.data.backup

/**
 * Pure function: detects whether applying the backup would overwrite local stats
 * that are strictly higher than the backup values.
 *
 * Called before the restore is applied (preview phase) to decide whether to show
 * a "newer local activity" warning. Has no side effects and does not touch the DB.
 */
object StatsRegressionDetector {

    data class MatchedStatsPair(
        val backupPlayCount: Int,
        val backupSkipCount: Int,
        val backupListeningTimeMs: Long,
        val backupLastPlayedAt: Long,
        val localPlayCount: Int,
        val localSkipCount: Int,
        val localListeningTimeMs: Long,
        val localLastPlayedAt: Long,
    )

    data class StatsRegressionSummary(val affectedSongs: Int) {
        val hasRegression: Boolean get() = affectedSongs > 0

        fun regressionWarning(): String? {
            if (!hasRegression) return null
            val count = affectedSongs
            return "This backup may overwrite newer listening activity for " +
                "$count song${if (count == 1) "" else "s"} already on this device."
        }
    }

    fun detect(pairs: List<MatchedStatsPair>): StatsRegressionSummary {
        val affected = pairs.count { p ->
            p.localPlayCount       > p.backupPlayCount       ||
            p.localSkipCount       > p.backupSkipCount       ||
            p.localListeningTimeMs > p.backupListeningTimeMs ||
            p.localLastPlayedAt    > p.backupLastPlayedAt
        }
        return StatsRegressionSummary(affectedSongs = affected)
    }
}

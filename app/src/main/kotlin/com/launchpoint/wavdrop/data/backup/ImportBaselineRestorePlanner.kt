package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity

/**
 * Pure planner for restoring import baselines from a Wavdrop backup.
 *
 * Baselines record what a previous BlackPlayer import contributed, keyed by
 * (songId, sourceType, sourceKey). Losing them on restore does not corrupt stats
 * (MAX-merge import is idempotent without them) but it breaks historical tracking
 * and inflates the "plays imported" numbers reported by a future re-import.
 *
 * Rules:
 *  - Song identity is resolved by the caller via the shared tier matcher, so
 *    baselines follow their track to the NEW local song id after a reinstall.
 *  - sourceType/sourceKey are preserved exactly — they identify the import source,
 *    not the restore.
 *  - When a local baseline already exists for the same key, the one with the newer
 *    lastImportedAt wins: a backup never regresses a baseline written by an import
 *    that happened after the backup was taken.
 *  - Idempotent: re-planning against the result of a previous restore upserts nothing.
 */
object ImportBaselineRestorePlanner {

    data class Plan(
        val toUpsert: List<ImportBaselineEntity>,
        val baselinesInBackup: Int,
        val restored: Int,
        val skippedUnmatched: Int,
        val skippedExistingNewer: Int,
    )

    fun plan(
        baselines: List<BackupImportBaseline>,
        resolveSongId: (Long) -> Long?,
        existing: List<ImportBaselineEntity>,
    ): Plan {
        val existingByKey = existing.associateBy { Triple(it.songId, it.sourceType, it.sourceKey) }

        val toUpsert = mutableListOf<ImportBaselineEntity>()
        var skippedUnmatched = 0
        var skippedExistingNewer = 0

        for (baseline in baselines) {
            val newSongId = resolveSongId(baseline.songId)
            if (newSongId == null) {
                skippedUnmatched++
                continue
            }
            val candidate = ImportBaselineEntity(
                songId                = newSongId,
                sourceType            = baseline.sourceType,
                sourceKey             = baseline.sourceKey,
                lastImportedPlayCount = baseline.lastImportedPlayCount,
                lastImportedSkipCount = baseline.lastImportedSkipCount,
                lastImportedAt        = baseline.lastImportedAt,
            )
            val current = existingByKey[Triple(newSongId, baseline.sourceType, baseline.sourceKey)]
            if (current != null && current.lastImportedAt >= candidate.lastImportedAt) {
                skippedExistingNewer++
                continue
            }
            toUpsert += candidate
        }

        return Plan(
            toUpsert             = toUpsert,
            baselinesInBackup    = baselines.size,
            restored             = toUpsert.size,
            skippedUnmatched     = skippedUnmatched,
            skippedExistingNewer = skippedExistingNewer,
        )
    }
}

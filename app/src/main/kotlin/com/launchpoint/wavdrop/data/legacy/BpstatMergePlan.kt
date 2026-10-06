package com.launchpoint.wavdrop.data.legacy

import com.launchpoint.wavdrop.data.backup.StatsImportMerger

/**
 * What applying ONE matched .bpstat row to WavDrop's stats means, as pure data (the repository only executes it).
 *
 * A .bpstat file supplies exactly one trustworthy counter for WavDrop: its main play count (field 1). Field 2 is a PERIOD
 * play count, not skips, so it is deliberately absent from every value below:
 *  - [importedSkipCount] is always 0, so the DAO's `MAX(skipCount, 0)` leaves the local skip count untouched (never raised,
 *    replaced or decremented; a previously mis-imported value stays as it is);
 *  - [importedPlayCount] is the main play count only (MAX-merged; the period count is never added to it);
 *  - no listening time, no `lastListenedAt` and no listen events are produced.
 * The baseline written for historical tracking records the play count and a skip baseline of 0 (no skip evidence).
 */
internal data class BpstatMergePlan(
    val importedPlayCount: Int,
    val importedSkipCount: Int,
    val importedListeningTimeMs: Long,
    val importedLastPlayedAt: Long,
    val importedLastListenedAt: Long,
    val baselinePlayCount: Int,
    val baselineSkipCount: Int,
    val effect: StatsImportMerger.MergeEffect,
)

internal fun planBpstatMerge(
    currentPlayCount: Int,
    currentSkipCount: Int,
    currentListeningTimeMs: Long,
    row: BlackPlayerStatImportRow,
): BpstatMergePlan = BpstatMergePlan(
    importedPlayCount = row.playCount,
    importedSkipCount = 0,
    importedListeningTimeMs = 0L,
    importedLastPlayedAt = row.lastPlayedMs,
    importedLastListenedAt = 0L,
    baselinePlayCount = row.playCount,
    baselineSkipCount = 0,
    effect = StatsImportMerger.computeEffect(
        currentPlayCount = currentPlayCount,
        currentSkipCount = currentSkipCount,
        currentListeningTimeMs = currentListeningTimeMs,
        importedPlayCount = row.playCount,
        importedSkipCount = 0,
        importedListeningTimeMs = 0L,
    ),
)

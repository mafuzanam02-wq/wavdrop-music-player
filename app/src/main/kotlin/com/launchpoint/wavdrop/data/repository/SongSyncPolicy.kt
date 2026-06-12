package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings

/**
 * Pure (no Android/Room deps) sync-decision rules so they can be unit-tested without an
 * instrumented environment.
 */
object SongSyncPolicy {

    /**
     * Returns true when an empty scan result should preserve the existing library rather than
     * wiping it. Specifically: selected-folder mode with at least one configured folder. An
     * empty result here almost certainly means path/permission mismatch, not "no music exists".
     */
    fun shouldPreserveOnEmptyScan(settings: LibraryScanSettings): Boolean =
        settings.scanMode == LibraryScanMode.SELECTED_FOLDERS &&
            settings.selectedFolderUris.isNotEmpty()

    /**
     * Returns the IDs present in [currentIds] but absent from [activeIds] — i.e. rows that
     * should be deleted because they were not found in the latest scan.
     */
    fun computeStaleIds(currentIds: Set<Long>, activeIds: Set<Long>): Set<Long> =
        currentIds - activeIds
}

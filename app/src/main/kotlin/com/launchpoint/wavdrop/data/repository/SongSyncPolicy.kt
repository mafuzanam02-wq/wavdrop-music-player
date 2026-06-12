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
     * clearing it. The rule is universal across scan modes: if the database already contains
     * songs, a zero-result scan is treated as a transient failure (permission revoked, storage
     * unmounted, MediaStore indexing delay) rather than evidence that no music exists.
     *
     * The [settings] parameter is used only to produce the correct user-facing [emptyPreservedReason];
     * the preserve decision itself depends solely on [existingSongCount].
     */
    fun shouldPreserveOnEmptyScan(
        @Suppress("UNUSED_PARAMETER") settings: LibraryScanSettings,
        existingSongCount: Int,
    ): Boolean = existingSongCount > 0

    /**
     * Returns a user-facing explanation for why an empty scan was preserved.
     * Callers should only invoke this after [shouldPreserveOnEmptyScan] returns true.
     */
    fun emptyPreservedReason(settings: LibraryScanSettings): String =
        when (settings.scanMode) {
            LibraryScanMode.SELECTED_FOLDERS ->
                "Selected folder scan returned no songs. " +
                "Your selected folder may have moved or its access may have changed. " +
                "Check your folder selection and rescan."
            LibraryScanMode.WHOLE_DEVICE ->
                "Library scan returned no songs. " +
                "Your device storage or media permission may need attention. " +
                "Existing songs were kept."
        }

    /**
     * Returns the IDs present in [currentIds] but absent from [activeIds] — i.e. rows that
     * should be deleted because they were not found in the latest scan.
     */
    fun computeStaleIds(currentIds: Set<Long>, activeIds: Set<Long>): Set<Long> =
        currentIds - activeIds
}

package com.launchpoint.wavdrop.data.backup

/**
 * How a Wavdrop (Android) backup is applied. Always an explicit user choice: it is never inferred from a clean install, an empty
 * database, the button wording, the backup's age or its source installation.
 */
enum class BackupRestoreMode {
    /**
     * Local state and backup state coexist: stats MAX(local, backup), favourites set-true-only, events union/dedup, playlists
     * add-missing, newer lyrics win, unmatched data quarantined. Settings are not changed. This is the default.
     */
    MERGE,

    /**
     * The selected backup becomes authoritative for WavDrop-owned recoverable state, mapped conservatively onto the songs
     * currently in the device library. Destructive, so it can only run after a VERIFIED pre-restore safety snapshot exists.
     */
    RECOVERY,
}

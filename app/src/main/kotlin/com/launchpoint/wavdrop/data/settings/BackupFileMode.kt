package com.launchpoint.wavdrop.data.settings

enum class BackupFileMode {
    /** Each export suggests a filename that includes the current date. */
    DATED,

    /** Each export suggests a stable filename so the previous Wavdrop backup file can be replaced. */
    REPLACE_PREVIOUS,
}

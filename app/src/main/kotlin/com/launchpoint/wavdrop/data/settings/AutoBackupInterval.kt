package com.launchpoint.wavdrop.data.settings

enum class AutoBackupInterval {
    OFF,
    DAILY,
    WEEKLY,
    MONTHLY;

    fun toMillis(): Long = when (this) {
        OFF     -> Long.MAX_VALUE
        DAILY   -> 24L * 60 * 60 * 1_000
        WEEKLY  -> 7L  * 24 * 60 * 60 * 1_000
        MONTHLY -> 30L * 24 * 60 * 60 * 1_000
    }
}

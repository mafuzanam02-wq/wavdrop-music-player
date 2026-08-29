package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.settings.AutoBackupInterval

internal object AutoBackupDueRules {
    fun shouldRun(
        interval: AutoBackupInterval,
        lastBackupAtMillis: Long,
        nowMillis: Long,
    ): Boolean =
        interval != AutoBackupInterval.OFF &&
            nowMillis - lastBackupAtMillis >= interval.toMillis()

    fun successfulBackupTimestamp(
        result: AutoBackupRepository.Result,
        nowMillis: Long,
    ): Long? =
        if (result is AutoBackupRepository.Result.Success) nowMillis else null
}

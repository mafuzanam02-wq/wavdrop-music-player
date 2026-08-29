package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupDueRulesTest {
    @Test
    fun `off interval is never due`() {
        assertFalse(
            AutoBackupDueRules.shouldRun(
                interval = AutoBackupInterval.OFF,
                lastBackupAtMillis = 0L,
                nowMillis = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `enabled interval is not due before elapsed interval`() {
        assertFalse(
            AutoBackupDueRules.shouldRun(
                interval = AutoBackupInterval.DAILY,
                lastBackupAtMillis = 1_000L,
                nowMillis = 1_000L + AutoBackupInterval.DAILY.toMillis() - 1L,
            ),
        )
    }

    @Test
    fun `enabled interval is due once elapsed interval is reached`() {
        assertTrue(
            AutoBackupDueRules.shouldRun(
                interval = AutoBackupInterval.WEEKLY,
                lastBackupAtMillis = 1_000L,
                nowMillis = 1_000L + AutoBackupInterval.WEEKLY.toMillis(),
            ),
        )
    }

    @Test
    fun `last backup timestamp updates only after success`() {
        assertEquals(
            123L,
            AutoBackupDueRules.successfulBackupTimestamp(
                result = AutoBackupRepository.Result.Success,
                nowMillis = 123L,
            ),
        )
        assertNull(
            AutoBackupDueRules.successfulBackupTimestamp(
                result = AutoBackupRepository.Result.Failure("write failed"),
                nowMillis = 123L,
            ),
        )
        assertNull(
            AutoBackupDueRules.successfulBackupTimestamp(
                result = AutoBackupRepository.Result.FolderUnavailable("folder unavailable"),
                nowMillis = 123L,
            ),
        )
    }
}

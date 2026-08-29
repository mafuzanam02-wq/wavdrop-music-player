package com.launchpoint.wavdrop.ui.screen.settings

import com.launchpoint.wavdrop.data.settings.AutoBackupCheckResult
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truthful Automatic-Backup Wording.
 *
 * Auto-backup in Wavdrop is scheduled through WorkManager; [AutoBackupRepository.runIfDue]
 * still owns the due check and backup write. These tests verify that all status labels
 * exposed in the UI accurately reflect that model.
 *
 * Additionally, the "last backup" timestamp is only updated after a full write +
 * read-back + [BackupSaveValidator.isSavedBackupValid] check. The label must say
 * "Last verified backup", not "Last successful backup".
 *
 * [toAutoBackupStatusText] and [toStatusLabel] are pure functions (internal); no DB,
 * no coroutines, no Compose — direct unit tests only.
 */
class AutoBackupWordingTest {

    // ── AutoBackupCheckResult status text ────────────────────────────────────

    @Test
    fun `SUCCESS result text is Backup saved — not Backup successful`() {
        assertEquals("Backup saved", AutoBackupCheckResult.SUCCESS.toAutoBackupStatusText())
    }

    @Test
    fun `NOT_DUE result text is Not due yet`() {
        assertEquals("Not due yet", AutoBackupCheckResult.NOT_DUE.toAutoBackupStatusText())
    }

    @Test
    fun `FOLDER_UNAVAILABLE result text is Folder unavailable`() {
        assertEquals("Folder unavailable", AutoBackupCheckResult.FOLDER_UNAVAILABLE.toAutoBackupStatusText())
    }

    @Test
    fun `FAILURE result text is Failed`() {
        assertEquals("Failed", AutoBackupCheckResult.FAILURE.toAutoBackupStatusText())
    }

    // ── AutoBackupInterval status labels ─────────────────────────────────────

    @Test
    fun `DAILY interval label is Daily`() {
        val label = AutoBackupInterval.DAILY.toStatusLabel()
        assertEquals("Daily", label)
    }

    @Test
    fun `WEEKLY interval label is Weekly`() {
        val label = AutoBackupInterval.WEEKLY.toStatusLabel()
        assertEquals("Weekly", label)
    }

    @Test
    fun `MONTHLY interval label is Monthly`() {
        val label = AutoBackupInterval.MONTHLY.toStatusLabel()
        assertEquals("Monthly", label)
    }
}

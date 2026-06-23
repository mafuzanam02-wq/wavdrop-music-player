package com.launchpoint.wavdrop.ui.screen.settings

import com.launchpoint.wavdrop.data.settings.AutoBackupCheckResult
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-C: Truthful Automatic-Backup Wording.
 *
 * Auto-backup in Wavdrop is app-open-triggered only — [AutoBackupRepository.runIfDue]
 * is called once per app session from the root navigation layer, never by a background
 * scheduler. These tests verify that all status labels exposed in the UI accurately
 * reflect that trigger model.
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

    // ── AutoBackupInterval status labels (on-open trigger qualification) ─────

    @Test
    fun `DAILY interval label qualifies trigger as on open`() {
        // "Daily" alone implies background scheduling — the label must include "on open"
        // to truthfully reflect that the check only runs when the user opens Wavdrop.
        val label = AutoBackupInterval.DAILY.toStatusLabel()
        assertEquals("Daily, on open", label)
    }

    @Test
    fun `WEEKLY interval label qualifies trigger as on open`() {
        val label = AutoBackupInterval.WEEKLY.toStatusLabel()
        assertEquals("Weekly, on open", label)
    }

    @Test
    fun `MONTHLY interval label qualifies trigger as on open`() {
        val label = AutoBackupInterval.MONTHLY.toStatusLabel()
        assertEquals("Monthly, on open", label)
    }
}

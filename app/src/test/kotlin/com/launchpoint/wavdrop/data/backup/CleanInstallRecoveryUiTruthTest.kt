package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanInstallRecoveryUiTruthTest {

    @Test
    fun `recovery UI distinguishes waiting scan and completion states`() {
        assertEquals(
            "Music folders need to be reselected",
            CleanInstallRecoveryUiText.FOLDERS_NEED_RESELECTION,
        )
        assertEquals("Library scan in progress…", CleanInstallRecoveryUiText.SCAN_IN_PROGRESS)
        assertEquals("Settings restored", CleanInstallRecoveryUiText.SETTINGS_RESTORED)
        assertEquals(
            "History merged with current library",
            CleanInstallRecoveryUiText.HISTORY_MERGED,
        )
        assertEquals(
            "History preserved for later matching",
            CleanInstallRecoveryUiText.HISTORY_PRESERVED,
        )
        assertTrue(CleanInstallRecoveryUiText.NOT_RESTORED.isNotBlank())
    }
}

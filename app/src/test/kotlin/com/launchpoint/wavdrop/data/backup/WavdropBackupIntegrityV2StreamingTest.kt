package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The streaming fingerprint must be bit-for-bit the fingerprint the old whole-string implementation produced. */
class WavdropBackupIntegrityV2StreamingTest {

    private fun assertSame(backup: WavdropBackup) =
        assertEquals(ReferenceFingerprintV2.fingerprint(backup), WavdropBackupIntegrityV2.fingerprint(backup))

    @Test fun `identical to the reference implementation across fixtures`() {
        assertSame(RecoveryTestFixtures.v2Backup())
        assertSame(WdbkTestSupport.emptyBackup())
        assertSame(WdbkTestSupport.fullBackup(eventCount = 0))
        assertSame(WdbkTestSupport.fullBackup(eventCount = 1_500))
        assertSame(WdbkTestSupport.fullBackup(overlay = false))
    }

    @Test fun `identical for non-ASCII, astral and control characters`() {
        val songs = listOf(
            RecoveryTestFixtures.backupSong(1L, title = "日本語 🎵 emoji \u0000 nul \u001F unit \u001E rec \n nl", artist = "Ünïcödé"),
            RecoveryTestFixtures.backupSong(2L, title = "lone \uD800 surrogate", artist = "x\uDC00"),
        )
        assertSame(RecoveryTestFixtures.v2Backup(songs = songs, stats = listOf(RecoveryTestFixtures.backupStats(1L, 3, 0, true))))
    }

    @Test fun `identical when preferences are absent present or all-null`() {
        assertSame(RecoveryTestFixtures.v2Backup(preferences = null))
        assertSame(RecoveryTestFixtures.v2Backup(preferences = RecoveryTestFixtures.emptyPrefs()))
        assertSame(WdbkTestSupport.fullBackup())
    }
}

package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.wdbk.WdbkLimits
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Content sniffing: the unified reader never trusts file name, extension or MIME type. */
class WavdropBackupDocumentReaderTest {

    private fun read(bytes: ByteArray, limits: WdbkLimits = WdbkLimits.DEFAULT) =
        WavdropBackupDocumentReader.read(ByteArrayInputStream(bytes), limits, WdbkTestSupport.NOW + 1_000_000L)

    private fun read(text: String) = read(text.toByteArray())

    private fun wavdrop(r: BackupDocumentReadResult) = r as BackupDocumentReadResult.Wavdrop

    @Test fun `valid V2 JSON is accepted and verified`() {
        val r = wavdrop(read(RecoveryTestFixtures.v2Json(WdbkTestSupport.fullBackup())))
        assertEquals(BackupContainerKind.LEGACY_JSON, r.container)
        assertNull(r.result.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.result.integrityStatus)
        assertEquals(BackupFormatVersion.V2, r.result.backup!!.sourceVersion)
        assertNotNull("legacy Recovery re-validates from the original text", r.legacyText)
    }

    @Test fun `valid V1 JSON is accepted through the legacy parser as V1`() {
        val r = wavdrop(read(RecoveryTestFixtures.v1Json()))
        assertEquals(BackupContainerKind.LEGACY_JSON, r.container)
        assertNull(r.result.error)
        assertEquals(BackupFormatVersion.V1, r.result.backup!!.sourceVersion)
        assertEquals(WavdropBackupParser.parse(RecoveryTestFixtures.v1Json()), r.result)
    }

    @Test fun `legacy JSON is decoded by content whatever it is named - there is no name input at all`() {
        // The reader API takes only a stream: a ".txt"/".wdbk"/MIME-less provider name cannot influence the decision.
        val json = RecoveryTestFixtures.v2Json()
        assertNull(wavdrop(read(json)).result.error)
    }

    @Test fun `valid WDBK is accepted whatever its display name`() {
        val backup = WdbkTestSupport.fullBackup()
        val r = wavdrop(read(WdbkTestSupport.write(backup).first))
        assertEquals(BackupContainerKind.WDBK, r.container)
        assertNull(r.result.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.result.integrityStatus)
        assertNull("the WDBK container is not held in memory for Recovery", r.legacyText)
    }

    @Test fun `decoded legacy v2 and decoded WDBK are the same model`() {
        val backup = WdbkTestSupport.fullBackup()
        val old = wavdrop(read(WavdropBackupExporterV2.toJson(backup))).result.backup
        val new = wavdrop(read(WdbkTestSupport.write(backup).first)).result.backup
        assertEquals(old, new)
    }

    @Test fun `plain invalid JSON in a wdbk-named file is rejected`() {
        val r = read("""{"hello":"world"}""")
        assertEquals(BackupDocumentReadResult.Rejected(BackupRejectReason.NOT_A_BACKUP), r)
    }

    @Test fun `random text and binary are rejected`() {
        assertEquals(BackupDocumentReadResult.Rejected(BackupRejectReason.NOT_A_BACKUP), read("just some text"))
        assertEquals(BackupDocumentReadResult.Rejected(BackupRejectReason.NOT_A_BACKUP), read(ByteArray(500) { (it * 7).toByte() }))
    }

    @Test fun `a fake zip fails safely with no backup`() {
        val fake = "PK\u0003\u0004 definitely not really a zip".toByteArray()
        val r = wavdrop(read(fake))
        assertEquals(BackupContainerKind.WDBK, r.container)
        assertNull(r.result.backup)
        assertEquals(BackupIntegrityStatus.INVALID, r.result.integrityStatus)
    }

    @Test fun `an ordinary zip that is not a WDBK is rejected`() {
        val zip = WdbkTestSupport.zip(listOf("readme.txt" to "hi".toByteArray()))
        assertNull(wavdrop(read(zip)).result.backup)
    }

    @Test fun `empty input is rejected as empty`() {
        assertEquals(BackupDocumentReadResult.Rejected(BackupRejectReason.EMPTY), read(ByteArray(0)))
        assertEquals(BackupDocumentReadResult.Rejected(BackupRejectReason.EMPTY), read("   \n  "))
    }

    @Test fun `a future legacy logical version keeps the existing newer-version wording`() {
        val future = RecoveryTestFixtures.v2Json().replaceFirst("\"version\": 2", "\"version\": 3")
        val r = wavdrop(read(future))
        assertEquals(WavdropBackupParser.NEWER_VERSION_ERROR, r.result.error)
        assertNull(r.result.backup)
    }

    @Test fun `a tampered legacy v2 file still fails the existing integrity check`() {
        val r = wavdrop(read(RecoveryTestFixtures.tampered(RecoveryTestFixtures.v2Json())))
        assertTrue(r.result.error!!.startsWith("Backup integrity check failed"))
    }

    @Test fun `desktop JSON is routed to the desktop parser not the android one`() {
        val desktop = """{"schemaVersion":1,"exportedAt":"2026-06-13T00:00:00Z","appName":"wavdrop-desktop-lab","songs":[]}"""
        assertTrue(DesktopWavdropBackupParser.isDesktopBackupContent(desktop))
        val r = read(desktop)
        assertTrue(r is BackupDocumentReadResult.DesktopJson)
        assertEquals(desktop, (r as BackupDocumentReadResult.DesktopJson).text)
    }

    @Test fun `legacy JSON over the input cap is rejected as too large and never parsed`() {
        val big = " ".repeat(2_000) + RecoveryTestFixtures.v2Json()
        val r = read(big.toByteArray(), WdbkLimits(maxContainerBytes = 1_000))
        assertEquals(BackupDocumentReadResult.Rejected(BackupRejectReason.TOO_LARGE), r)
    }

    @Test fun `WDBK over the outer cap is rejected with the shared too-large message`() {
        val bytes = WdbkTestSupport.write(WdbkTestSupport.fullBackup()).first
        val r = wavdrop(read(bytes, WdbkLimits(maxContainerBytes = bytes.size - 1L)))
        assertEquals(BackupInputReader.TOO_LARGE_MESSAGE, r.result.error)
    }
}

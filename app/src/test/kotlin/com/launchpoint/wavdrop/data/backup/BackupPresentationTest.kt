package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.util.Locale

/** P5-REL-05: format-warning and export-date rules, driven by backups decoded through the real production reader. */
class BackupPresentationTest {

    private var savedLocale: Locale = Locale.getDefault()
    @Before fun pinLocale() { savedLocale = Locale.getDefault(); Locale.setDefault(Locale.ENGLISH) }
    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

    private val utc = ZoneId.of("UTC")

    private fun read(bytes: ByteArray) =
        WavdropBackupDocumentReader.read(ByteArrayInputStream(bytes), com.launchpoint.wavdrop.data.backup.wdbk.WdbkLimits.DEFAULT, WdbkTestSupport.NOW + 1_000_000L)

    private fun decoded(bytes: ByteArray): WavdropBackup =
        ((read(bytes) as BackupDocumentReadResult.Wavdrop).result.backup)!!

    private fun wdbk() = decoded(WdbkTestSupport.write(WdbkTestSupport.fullBackup()).first)
    private fun v2() = decoded(RecoveryTestFixtures.v2Json().toByteArray())
    private fun v1() = decoded(RecoveryTestFixtures.v1Json().toByteArray())
    private fun bare(exportedAt: String = "", ms: Long? = null, sha: String? = null, ver: BackupFormatVersion = BackupFormatVersion.V2) =
        WavdropBackup(exportedAt, emptyList(), emptyList(), emptyList(), payloadSha256 = sha, sourceVersion = ver, exportedAtMs = ms)

    // ── Defect A: format warning ─────────────────────────────────────────────

    @Test fun `A valid WDBK has no older-format warning`() {
        val b = wdbk()
        assertEquals(BackupFormatVersion.V2, b.sourceVersion)
        assertNull(b.payloadSha256)
        assertNull(BackupPresentation.legacyFormatWarning(b))
    }

    @Test fun `B valid v2 JSON without payloadSha256 has no older-format warning`() {
        val b = v2()
        assertNull(b.payloadSha256)
        assertNull(BackupPresentation.legacyFormatWarning(b))
    }

    @Test fun `C genuine v1 keeps a compatibility warning and never invents integrity`() {
        val b = v1()
        assertEquals(BackupFormatVersion.V1, b.sourceVersion)
        assertNotNull(b.payloadSha256)
        assertEquals(BackupPresentation.LEGACY_SIGNED_WARNING, BackupPresentation.legacyFormatWarning(b))
        assertEquals(BackupPresentation.LEGACY_UNSIGNED_WARNING, BackupPresentation.legacyFormatWarning(bare(ver = BackupFormatVersion.V1, sha = null)))
        assertTrue(!BackupPresentation.LEGACY_SIGNED_WARNING.contains("integrity protection"))
    }

    @Test fun `D corrupted WDBK and tampered v2 are still rejected and yield no backup`() {
        val bytes = WdbkTestSupport.write(WdbkTestSupport.fullBackup()).first
        val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        val r = read(flipped)
        assertTrue(r is BackupDocumentReadResult.Rejected || (r as BackupDocumentReadResult.Wavdrop).result.backup == null)
        val tampered = RecoveryTestFixtures.v2Json().replace(Regex("\"exportedAt\"\\s*:\\s*${WdbkTestSupport.NOW}"), "\"exportedAt\": ${WdbkTestSupport.NOW + 1}")
        assertTrue(tampered != RecoveryTestFixtures.v2Json())
        val t = read(tampered.toByteArray()) as BackupDocumentReadResult.Wavdrop
        assertNull(t.result.backup)
        assertNotNull(t.result.error)
    }

    @Test fun `E empty history backups keep their v2 identity and gain no format warning`() {
        assertNull(BackupPresentation.legacyFormatWarning(bare(ms = 1_000L)))
    }

    // ── Defect B: export date ────────────────────────────────────────────────

    private val ms20260611 = 1_781_173_800_000L // 2026-06-11T10:30:00Z

    @Test fun `F exportedAtMs formats as the real export date`() {
        assertEquals("11 Jun 2026, 10:30", BackupPresentation.formatExportDate(bare(ms = ms20260611), utc))
        assertEquals(ms20260611, BackupPresentation.exportTimestampMillis(bare(ms = ms20260611)))
    }

    @Test fun `F real WDBK and v2 JSON decode to their embedded export time`() {
        assertEquals(WdbkTestSupport.NOW, BackupPresentation.exportTimestampMillis(wdbk()))
        assertEquals(WdbkTestSupport.NOW, BackupPresentation.exportTimestampMillis(v2()))
        assertTrue(BackupPresentation.formatExportDate(wdbk(), utc) != "Unknown")
    }

    @Test fun `G legacy ISO exportedAt formats correctly`() {
        assertEquals("11 Jun 2026, 10:00", BackupPresentation.formatExportDate(v1(), utc))
        assertEquals("11 Jun 2026, 10:30", BackupPresentation.formatDesktopExportDate("2026-06-11T10:30:00Z", utc))
    }

    @Test fun `H when both are present exportedAtMs wins`() {
        val b = bare(exportedAt = "2020-01-01T00:00:00Z", ms = ms20260611)
        assertEquals(ms20260611, BackupPresentation.exportTimestampMillis(b))
    }

    @Test fun `I missing or malformed timestamps are Unknown and never throw`() {
        assertEquals("Unknown", BackupPresentation.formatExportDate(bare(), utc))
        assertEquals("Unknown", BackupPresentation.formatExportDate(bare(exportedAt = "not a date"), utc))
        assertEquals("Unknown", BackupPresentation.formatExportDate(bare(ms = 0L), utc))
        assertEquals("Unknown", BackupPresentation.formatExportDate(bare(ms = -5L), utc))
        BackupPresentation.formatExportDate(bare(ms = Long.MAX_VALUE), utc) // extreme value must not throw
        assertEquals("Unknown", BackupPresentation.formatDesktopExportDate("  ", utc))
        assertEquals("garbage", BackupPresentation.formatDesktopExportDate("garbage", utc))
        // invalid ms falls back to a valid legacy string, not to Unknown
        assertEquals(ms20260611, BackupPresentation.exportTimestampMillis(bare(exportedAt = "2026-06-11T10:30:00Z", ms = 0L)))
    }

    @Test fun `J the export time never depends on file time`() {
        // exportTimestampMillis takes only the backup: with no embedded time it is null, so a caller must flag any file-time fallback.
        assertNull(BackupPresentation.exportTimestampMillis(bare()))
        assertEquals(ms20260611, BackupPresentation.exportTimestampMillis(bare(ms = ms20260611)))
    }

    @Test fun `K timezone boundaries move the calendar day only by the zone offset`() {
        val b = bare(ms = 1_781_220_600_000L) // 2026-06-11T23:30:00Z
        assertEquals("11 Jun 2026, 23:30", BackupPresentation.formatExportDate(b, utc))
        assertEquals("12 Jun 2026, 08:30", BackupPresentation.formatExportDate(b, ZoneId.of("Asia/Tokyo")))
        assertEquals("11 Jun 2026, 19:30", BackupPresentation.formatExportDate(b, ZoneId.of("America/New_York")))
        // seconds-vs-millis confusion would land in 1970
        assertTrue(BackupPresentation.formatExportDate(b, utc).contains("2026"))
    }
}

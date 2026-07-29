package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.legacy.BlackPlayerStatParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * WD-02 — backup input-size cap.
 *
 * The size-gated public entry point (`readBackupText`) needs a real Android
 * `Context`/`ContentResolver`; its two pure building blocks — the declared-size
 * gate and the bounded byte reader — are unit-tested directly here with an
 * injectable byte limit so no multi-MiB allocation is needed. Round-trip tests
 * confirm the bounded reader reproduces valid backup content byte-for-byte
 * (including multibyte UTF-8) so files under the cap parse exactly as before.
 */
class BackupInputReaderTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun v2Backup() = WavdropBackup(
        exportedAt           = "",
        exportedAtMs         = 1_782_230_400_000L,
        backupId             = "00000000-0000-0000-0000-000000000001",
        sourceInstallationId = "00000000-0000-0000-0000-000000000002",
        sourceVersion        = BackupFormatVersion.V2,
        songs                = listOf(
            // Includes multibyte UTF-8 (é, ñ) to prove byte-bounded decode fidelity.
            BackupSong(3316L, "content://media/3316", "Jolé Cañón", "Doors",
                "Other Voices", 42L, 180_000L, 1_000_000L, 1, 1971),
        ),
        trackStats           = listOf(
            BackupTrackStats(3316L, "content://media/3316", 10, 1,
                lastPlayedAt = 1_782_230_000_000L,
                totalListeningTimeMs = 180_000L,
                isFavorite = false,
                lastListenedAt = 1_782_230_100_000L),
        ),
        importBaselines      = emptyList(),
    )

    private fun v2Json(): String = WavdropBackupExporterV2.toJson(v2Backup())

    private fun desktopOverlayV2Json(): String =
        JSONObject(v2Json()).apply {
            put(
                "desktopOverlay",
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("producer", JSONObject().put("platform", "desktop")),
            )
        }.toString(2)

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    // ── 1. Small valid Android V2 backup still reads and parses ────────────────

    @Test
    fun `bounded read reproduces a valid v2 backup and it parses`() {
        val json = v2Json()
        val read = BackupInputReader.readBounded(stream(json))

        assertEquals("Bounded read must be byte-identical", json, read)
        val result = WavdropBackupParser.parse(read)
        assertNotNull("v2 parse failed: ${result.error}", result.backup)
        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
        assertEquals("Jolé Cañón", result.backup!!.songs.single().title)
    }

    // ── 2. Small valid desktopOverlay backup still reads and parses ────────────

    @Test
    fun `bounded read reproduces a desktopOverlay v2 backup and it parses`() {
        val json = desktopOverlayV2Json()
        val read = BackupInputReader.readBounded(stream(json))

        assertEquals(json, read)
        val result = WavdropBackupParser.parse(read)
        assertNotNull("desktop-overlay v2 parse failed: ${result.error}", result.backup)
        assertEquals(1, result.backup!!.desktopOverlay!!.schemaVersion)
    }

    // ── 3. Declared size above the limit is rejected before any read ───────────

    @Test
    fun `declared size above the limit is flagged for rejection`() {
        assertTrue(
            BackupInputReader.declaredSizeExceedsLimit(BackupInputReader.MAX_BACKUP_INPUT_BYTES + 1),
        )
    }

    @Test
    fun `declared size at the limit is accepted`() {
        assertFalse(
            BackupInputReader.declaredSizeExceedsLimit(BackupInputReader.MAX_BACKUP_INPUT_BYTES),
        )
    }

    // ── 4. Unknown-size stream exceeding the limit is rejected during read ─────

    @Test
    fun `bounded read rejects a stream that exceeds the byte limit`() {
        val payload = "x".repeat(17)
        assertThrows(BackupInputReader.InputTooLargeException::class.java) {
            BackupInputReader.readBounded(stream(payload), maxBytes = 16)
        }
    }

    // ── 5. Exact-limit input is accepted ───────────────────────────────────────

    @Test
    fun `bounded read accepts input exactly at the byte limit`() {
        val payload = "x".repeat(16)
        val read = BackupInputReader.readBounded(stream(payload), maxBytes = 16)
        assertEquals(payload, read)
    }

    // ── 6. One byte over the limit is rejected ─────────────────────────────────

    @Test
    fun `bounded read rejects input one byte over the limit`() {
        val payload = "x".repeat(17)
        assertThrows(BackupInputReader.InputTooLargeException::class.java) {
            BackupInputReader.readBounded(stream(payload), maxBytes = 16)
        }
    }

    // ── 7. Unknown size / cancellation-safe behaviors ─────────────────────────
    //
    // Unknown declared size (provider reports nothing) must never be treated as
    // "too large" — the bounded read is the guard for those streams. Picker
    // cancellation is handled upstream (a null Uri never reaches the reader);
    // an empty stream still yields "" so existing blank-file handling is intact.

    @Test
    fun `unknown declared size is not rejected by the size gate`() {
        assertFalse(BackupInputReader.declaredSizeExceedsLimit(null))
    }

    @Test
    fun `bounded read of an empty stream yields empty string`() {
        assertEquals("", BackupInputReader.readBounded(stream("")))
    }

    // ── .bpstat importer shares the same bounded reader (generic message) ──────

    @Test
    fun `bounded read reproduces a valid bpstat row and it parses`() {
        val bpstat =
            "148;2;23;Wilfred;Everything We Need;/storage/emulated/0/Music/example.mp3;1759066940607;1779810940727"
        val read = BackupInputReader.readBounded(stream(bpstat))

        assertEquals(bpstat, read)
        val result = BlackPlayerStatParser.parse(read)
        assertEquals(1, result.validRows.size)
        assertEquals(148, result.validRows.single().playCount)
    }

    @Test
    fun `import too-large message is generic, not backup-specific`() {
        assertTrue(
            BackupInputReader.IMPORT_TOO_LARGE_MESSAGE.startsWith("Import file is too large"),
        )
        assertFalse(BackupInputReader.IMPORT_TOO_LARGE_MESSAGE.contains("Backup"))
    }

    @Test
    fun `bounded read rejects an over-limit bpstat-style stream`() {
        // Same primitive guards .bpstat: one byte over the cap is rejected.
        val payload = "1;0;t;a;al;/p;1;2".repeat(4) // > 16 bytes
        assertThrows(BackupInputReader.InputTooLargeException::class.java) {
            BackupInputReader.readBounded(stream(payload), maxBytes = 16)
        }
    }

    // ── Multibyte boundary: a byte cap must count bytes, not characters ────────

    @Test
    fun `multibyte content is measured in bytes, not characters`() {
        // "€" is 3 UTF-8 bytes; three of them = 9 bytes > 8-byte cap → rejected,
        // even though it is only 3 characters.
        val payload = "€€€"
        assertThrows(BackupInputReader.InputTooLargeException::class.java) {
            BackupInputReader.readBounded(stream(payload), maxBytes = 8)
        }
    }
}

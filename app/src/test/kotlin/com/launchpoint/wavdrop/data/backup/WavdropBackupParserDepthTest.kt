package com.launchpoint.wavdrop.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WD-01 — JSON nesting-depth hardening for the hand-written Backup parser.
 *
 * The parser recurses through parseValue/parseObject/parseArray. Without a
 * depth limit, a crafted backup with thousands of nested arrays/objects would
 * overflow the stack and terminate the process before an import preview appears.
 *
 * Depth semantics: the top-level value sits at depth 1; each nested object or
 * array adds one level. Up to MAX_JSON_NESTING_DEPTH (128) levels are accepted;
 * the first level beyond that is rejected deterministically with a normal
 * parser failure — never a StackOverflowError.
 */
class WavdropBackupParserDepthTest {

    private companion object {
        const val MAX_DEPTH = 128
        const val DEPTH_ERROR = "Backup JSON is nested too deeply"

        // A depth an order of magnitude past the limit — enough to prove the guard
        // rejects before recursion can exhaust the stack, without the memory/runtime
        // cost (and CI flakiness) of a six-figure generator. The parser rejects at
        // depth 129, so any input beyond that exercises the identical guard path;
        // 2,048 is simply a comfortable margin over MAX_DEPTH.
        const val STRESS_DEPTH = 2_048
    }

    // ── Fixtures reused from the v2 test shape ────────────────────────────────

    private fun v2Backup() = WavdropBackup(
        exportedAt           = "",
        exportedAtMs         = 1_782_230_400_000L,
        backupId             = "00000000-0000-0000-0000-000000000001",
        sourceInstallationId = "00000000-0000-0000-0000-000000000002",
        sourceVersion        = BackupFormatVersion.V2,
        songs                = listOf(
            BackupSong(3316L, "content://media/3316", "Ghost Song", "Doors",
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

    private fun desktopOverlayJson(): JSONObject =
        JSONObject()
            .put("schemaVersion", 1)
            .put("producer", JSONObject().put("platform", "desktop"))

    /** Builds `depth` nested arrays: e.g. depth=3 → "[[[ ]]]". */
    private fun nestedArrays(depth: Int): String = "[".repeat(depth) + "]".repeat(depth)

    /** Builds `depth` nested single-key objects around a leaf. */
    private fun nestedObjects(depth: Int, leaf: String = "0"): String =
        "{\"a\":".repeat(depth) + leaf + "}".repeat(depth)

    /** Alternates object/array wrappers `depth` levels deep around a leaf. */
    private fun mixedNesting(depth: Int): String {
        val open = StringBuilder()
        val close = StringBuilder()
        for (level in 0 until depth) {
            if (level % 2 == 0) { open.append("{\"a\":"); close.insert(0, "}") }
            else { open.append("["); close.insert(0, "]") }
        }
        return open.toString() + "0" + close.toString()
    }

    // ── 1. Normal Android V2 fixture still parses ─────────────────────────────

    @Test
    fun `normal v2 fixture parses`() {
        val result = WavdropBackupParser.parse(v2Json())
        assertNotNull("v2 parse failed: ${result.error}", result.backup)
        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
    }

    // ── 2. Normal Desktop-overlay V2 fixture still parses ─────────────────────

    @Test
    fun `normal desktop-overlay v2 fixture parses`() {
        val json = JSONObject(v2Json()).apply {
            put("desktopOverlay", desktopOverlayJson())
        }.toString(2)

        val result = WavdropBackupParser.parse(json)
        assertNotNull("desktop-overlay v2 parse failed: ${result.error}", result.backup)
        assertEquals(BackupIntegrityStatus.VERIFIED, result.integrityStatus)
        assertEquals(1, result.backup!!.desktopOverlay!!.schemaVersion)
    }

    // ── 3. Nested objects below the limit parse (no depth failure) ────────────

    @Test
    fun `nested objects at the limit do not trigger a depth failure`() {
        // At exactly MAX_DEPTH the reader accepts the structure; the failure that
        // follows is a schema/format failure, never the depth failure.
        val result = WavdropBackupParser.parse(nestedObjects(MAX_DEPTH))
        assertNull(result.backup)
        assertNotEquals(DEPTH_ERROR, result.error)
    }

    // ── 4. Nested arrays below the limit parse (no depth failure) ─────────────

    @Test
    fun `nested arrays at the limit do not trigger a depth failure`() {
        val result = WavdropBackupParser.parse(nestedArrays(MAX_DEPTH))
        assertNull(result.backup)
        // A top-level array is well-formed JSON but not an object → "Malformed JSON".
        assertNotEquals(DEPTH_ERROR, result.error)
    }

    // ── 5. Object nesting above the limit is rejected cleanly ─────────────────

    @Test
    fun `object nesting above the limit is rejected with depth error`() {
        val result = WavdropBackupParser.parse(nestedObjects(MAX_DEPTH + 1))
        assertNull(result.backup)
        assertEquals(DEPTH_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 6. Array nesting above the limit is rejected cleanly ──────────────────

    @Test
    fun `array nesting above the limit is rejected with depth error`() {
        val result = WavdropBackupParser.parse(nestedArrays(MAX_DEPTH + 1))
        assertNull(result.backup)
        assertEquals(DEPTH_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 7. Mixed object/array nesting above the limit is rejected cleanly ─────

    @Test
    fun `mixed nesting above the limit is rejected with depth error`() {
        val result = WavdropBackupParser.parse(mixedNesting(MAX_DEPTH + 1))
        assertNull(result.backup)
        assertEquals(DEPTH_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── 8. Bounded over-limit stress does not produce StackOverflowError ────────

    @Test
    fun `array nesting far beyond the limit returns a normal failure, not StackOverflowError`() {
        val result = WavdropBackupParser.parse(nestedArrays(STRESS_DEPTH))
        assertNull(result.backup)
        assertEquals(DEPTH_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    @Test
    fun `object nesting far beyond the limit returns a normal failure, not StackOverflowError`() {
        val result = WavdropBackupParser.parse(nestedObjects(STRESS_DEPTH))
        assertNull(result.backup)
        assertEquals(DEPTH_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    @Test
    fun `mixed nesting far beyond the limit returns a normal failure, not StackOverflowError`() {
        val result = WavdropBackupParser.parse(mixedNesting(STRESS_DEPTH))
        assertNull(result.backup)
        assertEquals(DEPTH_ERROR, result.error)
        assertEquals(BackupIntegrityStatus.INVALID, result.integrityStatus)
    }

    // ── Legitimate shallow nesting is unaffected ──────────────────────────────

    @Test
    fun `shallow nesting well within the limit is accepted by the reader`() {
        val result = WavdropBackupParser.parse(nestedArrays(4))
        // Well-formed but not a backup object → not a depth failure.
        assertTrue(
            "Shallow nesting must not be rejected for depth: ${result.error}",
            result.error != DEPTH_ERROR,
        )
    }
}

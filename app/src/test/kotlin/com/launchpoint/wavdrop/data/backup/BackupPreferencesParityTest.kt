package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Backup-eligibility parity guard (WU-08).
 *
 * Every field on [BackupPreferences] must be BOTH exported (v1 + v2) AND parsed. A new
 * user-facing setting added to the model but not wired into the exporter/parser silently
 * drops out of users' backups; the per-field round-trip test only covers fields its fixture
 * happens to set. This reflection guard covers *all* current and future fields, so the
 * omission fails here in CI instead of on a user's restore.
 *
 * Uses Java reflection only (no kotlin-reflect dependency).
 */
class BackupPreferencesParityTest {

    private fun preferenceFields(): List<Field> =
        BackupPreferences::class.java.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }

    private fun jsonSentinelFor(field: Field): String = when (field.type) {
        java.lang.String::class.java -> "\"S_${field.name}\""
        java.lang.Boolean::class.java -> "true"
        java.lang.Integer::class.java -> "7"
        java.util.List::class.java -> "[\"X\",\"Y\"]"
        else -> throw AssertionError(
            "Unhandled BackupPreferences field type for '${field.name}': ${field.type}. " +
                "Extend jsonSentinelFor so the parity guard covers it.",
        )
    }

    /** A backup JSON whose preferences block sets EVERY declared field to a sentinel. */
    private fun allFieldsBackupJson(): String {
        val body = preferenceFields().joinToString(",\n") { "\"${it.name}\": ${jsonSentinelFor(it)}" }
        return """
            {
              "app": "Wavdrop",
              "format": "wavdrop_backup",
              "version": 1,
              "exportedAt": "2026-06-01T10:00:00Z",
              "songs": [],
              "trackStats": [],
              "importBaselines": [],
              "preferences": { $body }
            }
        """.trimIndent()
    }

    @Test
    fun `parser populates every BackupPreferences field`() {
        val result = WavdropBackupParser.parse(allFieldsBackupJson())
        assertNull(result.error)
        val prefs = requireNotNull(result.backup?.preferences)
        val missing = preferenceFields().filter { it.get(prefs) == null }.map { it.name }
        assertTrue(
            "WavdropBackupParser does not read these BackupPreferences fields: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `v1 and v2 exporters round-trip every BackupPreferences field`() {
        // Build a fully-populated instance via the parser (proven complete by the test above),
        // so this test needs no reflective data-class construction.
        val full = requireNotNull(WavdropBackupParser.parse(allFieldsBackupJson()).backup?.preferences)
        val backup = WavdropBackup(
            exportedAt = "2026-06-01T10:00:00Z",
            songs = emptyList(),
            trackStats = emptyList(),
            importBaselines = emptyList(),
            preferences = full,
            // v2 export mandates these header fields (WavdropBackupExporterV2 requireNotNull-s them,
            // just as WavdropBackupRepository always sets them for a real export). The fixture must
            // supply them or toJson throws before the preference round-trip is ever exercised. They
            // are v2-only and absent from v1 output, so v1 parity is unaffected.
            backupId = "test-backup-id",
            sourceInstallationId = "test-installation-id",
            exportedAtMs = 1_717_236_000_000L,
        )
        val v1 = requireNotNull(WavdropBackupParser.parse(WavdropBackupExporter.toJson(backup)).backup?.preferences)
        val v2 = requireNotNull(WavdropBackupParser.parse(WavdropBackupExporterV2.toJson(backup)).backup?.preferences)
        // If either exporter omits a field, the round-tripped value differs from the source.
        assertEquals("v1 exporter dropped a preference field", full, v1)
        assertEquals("v2 exporter dropped a preference field", full, v2)
    }
}

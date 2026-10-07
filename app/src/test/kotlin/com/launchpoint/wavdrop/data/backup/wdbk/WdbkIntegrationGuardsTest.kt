package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupVerificationRepository
import com.launchpoint.wavdrop.data.backup.ImportFileValidation
import com.launchpoint.wavdrop.ui.screen.settings.BackupPickerTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Behavioural checks for file naming/picker policy plus structural guards for architecture properties that are not
 * otherwise observable on the JVM (SAF/Compose wiring, "Merge/Recovery stay container-unaware").
 */
class WdbkIntegrationGuardsTest {

    private val root = File("src/main/kotlin/com/launchpoint/wavdrop")
    private fun src(path: String) = File(root, path).readText()
    private fun code(path: String) = src(path).lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")

    // ── Backup Verification discovery ─────────────────────────────────────────

    @Test fun `verification discovers wdbk and legacy json backups but never temp or unrelated files`() {
        val ok = listOf(
            "wavdrop-backup.wdbk", "wavdrop-backup-2026-10-07.wdbk", "wavdrop-backup.json", "wavdrop-backup-2026-06-01.json",
            "WAVDROP-BACKUP.WDBK",
        )
        val not = listOf(
            "wavdrop-backup.wdbk.tmp", "wavdrop-backup.json.tmp", "wavdrop-backup-2026-10-07.wdbk.tmp",
            "other.wdbk", "wavdrop-backup.zip", "wavdrop-backup.txt", "pre-recovery-latest.json", "", null,
        )
        ok.forEach { assertTrue(it, BackupVerificationRepository.isBackupFileName(it)) }
        not.forEach { assertFalse(it ?: "null", BackupVerificationRepository.isBackupFileName(it)) }
    }

    @Test fun `verification reads through the unified content-sniffing reader not the extension`() {
        val s = code("data/backup/BackupVerificationRepository.kt")
        assertTrue("WavdropBackupDocumentReader.readUri" in s)
        assertFalse("readBackupText" in s)
        assertFalse("ZipInputStream" in s)
    }

    // ── Picker and naming policy ──────────────────────────────────────────────

    @Test fun `new backups are suggested as wdbk with a provider-safe MIME`() {
        val s = code("ui/screen/settings/SettingsBackupScreen.kt")
        assertTrue("wavdrop-backup-\${LocalDate.now()}.wdbk" in s)
        assertTrue("\"wavdrop-backup.wdbk\"" in s)
        assertFalse(".json" in s)
        assertFalse("application/json" in s)
        assertTrue("CreateDocument(BackupPickerTypes.CREATE_BACKUP_MIME)" in s)
        assertTrue("BackupPickerTypes.OPEN_BACKUP_MIME_TYPES" in s)
        assertEquals("application/octet-stream", BackupPickerTypes.CREATE_BACKUP_MIME)
    }

    @Test fun `the restore picker is not narrowed and validation is by content`() {
        assertArrayEquals(arrayOf("*/*"), BackupPickerTypes.OPEN_BACKUP_MIME_TYPES)
        val vm = code("ui/screen/backupimport/BackupImportPreviewViewModel.kt")
        assertTrue("WavdropBackupDocumentReader.readUri" in vm)
        assertFalse("isLikelyWavdropBackupFileName" in vm)
        assertFalse("displayName" in vm)
    }

    @Test fun `restore copy no longer says JSON-only and fixed-name copy says wdbk`() {
        assertEquals("Choose a WavDrop backup file.", ImportFileValidation.WAVDROP_WRONG_FILE_MESSAGE)
        val s = src("ui/screen/settings/SettingsBackupScreen.kt")
        assertTrue("Always saves as wavdrop-backup.wdbk" in s)
        assertTrue("Choose a WavDrop backup file to preview and restore." in s)
        assertFalse("backup JSON file" in s)
    }

    @Test fun `ImportFileValidation name hint accepts both extensions but is not an authority`() {
        assertTrue(ImportFileValidation.isLikelyWavdropBackupFileName("x.WDBK"))
        assertTrue(ImportFileValidation.isLikelyWavdropBackupFileName("x.json"))
        assertFalse(ImportFileValidation.isLikelyWavdropBackupFileName("x.txt"))
    }

    @Test fun `automatic backup names and flow`() {
        val s = src("data/backup/AutoBackupRepository.kt")
        assertTrue("\"wavdrop-backup-\${LocalDate.now()}.wdbk\"" in s)
        assertTrue("\"wavdrop-backup.wdbk\"" in s)
        assertTrue("saveToFolderAndVerify" in s)
        assertTrue("buildWdbkExportSnapshot()" in s)
        assertFalse("buildBackupJson" in code("data/backup/AutoBackupRepository.kt"))
    }

    // ── Architecture boundary ─────────────────────────────────────────────────

    @Test fun `manual export uses the paged WDBK model and the container saver, not the monolithic JSON string`() {
        val repo = code("data/backup/WavdropBackupRepository.kt")
        val exportFn = repo.substringAfter("suspend fun exportToUri").substringBefore("suspend fun buildWdbkExportSnapshot")
        assertTrue("WdbkBackupSaver().saveAndVerify" in exportFn)
        assertTrue("buildWdbkExportSnapshot()" in exportFn)
        assertFalse("buildBackupJson" in exportFn)
        val wdbkModel = repo.substringAfter("suspend fun buildWdbkExportSnapshot").substringBefore("suspend fun buildBackupJson")
        assertTrue("CanonicalEventStream" in wdbkModel)
        assertTrue("getExportPage" in wdbkModel)
        assertTrue("getMaxId" in wdbkModel)
        assertFalse("getAllSnapshot" in wdbkModel)
    }

    @Test fun `the WDBK writer and reader never build one whole-backup JSON string`() {
        val w = code("data/backup/wdbk/WdbkWriter.kt")
        for (forbidden in listOf("WavdropBackupExporterV2.toJson", "buildBackupJson", "getAllSnapshot", ".toString(2)", "ByteArrayOutputStream")) {
            assertFalse("writer must not use $forbidden", forbidden in w)
        }
        val saver = code("data/backup/wdbk/WdbkBackupSaver.kt")
        assertFalse("ByteArrayOutputStream" in saver) // the archive is streamed, never assembled in memory
        assertFalse("readText" in code("data/backup/wdbk/WdbkReader.kt"))
    }

    @Test fun `merge and recovery repositories stay container-unaware`() {
        for (f in listOf(
            "data/backup/WavdropBackupImportRepository.kt", "data/backup/RecoveryRestoreRepository.kt",
            "data/backup/RecoveryRestorePlanner.kt", "data/backup/ListenEventRestorePlanner.kt",
            "data/backup/PlaylistEntryRestorePlanner.kt", "data/backup/WavdropMergePreviewAnalyzer.kt",
            "data/backup/StatsImportMerger.kt", "data/backup/DesktopOverlayRestorePlanner.kt",
            "data/backup/RecoveryPreferenceApplier.kt",
        )) {
            val text = src(f).lowercase()
            assertFalse("$f must not know about the container", "wdbk" in text || "zipinputstream" in text)
        }
    }

    @Test fun `UI code never inspects ZIP entries`() {
        val ui = File(root, "ui").walkTopDown().filter { it.isFile && it.extension == "kt" }
        for (f in ui) assertFalse("${f.name} must not touch java.util.zip", "java.util.zip" in f.readText())
    }

    @Test fun `the recovery safety snapshot intentionally stays on verified legacy v2 JSON in WDBK-1`() {
        val s = src("data/backup/RecoverySafetySnapshot.kt")
        assertTrue("pre-recovery-latest.json" in s)
        assertTrue("backupRepository.buildBackupJson()" in s)
        assertFalse("wdbk" in s.lowercase())
        assertTrue("fun restore(rawBackupJson: String)" in src("data/backup/RecoveryRestoreOrchestrator.kt"))
    }

    @Test fun `legacy parser and exporter remain`() {
        assertTrue("object WavdropBackupParser" in src("data/backup/WavdropBackupParser.kt"))
        assertTrue("object WavdropBackupExporterV2" in src("data/backup/WavdropBackupExporterV2.kt"))
        assertTrue("suspend fun buildBackupJson" in src("data/backup/WavdropBackupRepository.kt"))
    }

    @Test fun `no network permission and no new codec dependencies`() {
        assertFalse("INTERNET" in File("src/main/AndroidManifest.xml").readText())
        val gradle = File("build.gradle.kts").readText().lowercase()
        for (dep in listOf("zstd", "protobuf", "cbor", "commons-compress", "zip4j")) assertFalse(dep, dep in gradle)
    }

    @Test fun `room schema is untouched by WDBK-1`() {
        val db = src("data/local/WavdropDatabase.kt")
        val version = Regex("""version\s*=\s*(\d+)""").find(db)!!.groupValues[1]
        val newest = File("schemas/com.launchpoint.wavdrop.data.local.WavdropDatabase").listFiles()!!
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals(newest, version.toInt())
    }
}

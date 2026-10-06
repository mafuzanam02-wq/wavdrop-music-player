package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.ui.screen.backupimport.BackupImportUiState
import com.launchpoint.wavdrop.ui.screen.backupimport.RecoveryRestoreCopy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules + structural guards (the project has no Compose/ViewModel test harness) for Recovery Restore. */
class RecoveryRestoreRulesTest {

    // ── Eligibility ───────────────────────────────────────────────────────────

    private fun parse(json: String) = WavdropBackupParser.parse(json)

    @Test fun `verified v2 is eligible`() {
        assertTrue(RecoveryEligibility.evaluate(parse(RecoveryTestFixtures.v2Json())) is RecoveryEligibility.Result.Eligible)
    }

    @Test fun `v1 is blocked but still parses for Merge`() {
        val parsed = parse(RecoveryTestFixtures.v1Json())
        assertNotNull("v1 stays importable for Merge", parsed.backup)
        val blocked = RecoveryEligibility.evaluate(parsed) as RecoveryEligibility.Result.Blocked
        assertEquals(RecoveryEligibility.Reason.LEGACY_V1, blocked.reason)
    }

    @Test fun `invalid v2 is blocked`() {
        val blocked = RecoveryEligibility.evaluate(parse(RecoveryTestFixtures.tampered(RecoveryTestFixtures.v2Json())))
        assertEquals(RecoveryEligibility.Reason.INVALID, (blocked as RecoveryEligibility.Result.Blocked).reason)
    }

    @Test fun `a v1 file that carries a v1 checksum is still not eligible`() {
        val v1 = parse(RecoveryTestFixtures.v1Json())
        val forcedVerified = v1.copy(integrityStatus = BackupIntegrityStatus.VERIFIED)
        assertTrue(RecoveryEligibility.evaluate(forcedVerified) is RecoveryEligibility.Result.Blocked)
    }

    @Test fun `recovery eligibility does not depend on whether Merge would change anything`() {
        // An empty-payload verified backup has nothing mergeable but is a legitimate Recovery source.
        val empty = RecoveryTestFixtures.v2Json(RecoveryTestFixtures.v2Backup(songs = emptyList(), stats = emptyList()))
        assertTrue(RecoveryEligibility.evaluate(parse(empty)) is RecoveryEligibility.Result.Eligible)
    }

    // ── Planner (pure) ────────────────────────────────────────────────────────

    private fun song(id: Long, title: String = "Song $id") = Song(
        id = id, title = title, artist = "Artist", album = "Album", albumId = 1L, duration = 180_000L + id,
        uri = "content://media/$id", dateAdded = 1_000L, trackNumber = 1, year = 2020, folderPath = "Music/", folderName = "Music",
    )

    @Test fun `plan copies backup values exactly and never fabricates events from counters`() {
        val b = RecoveryTestFixtures.v2Backup(stats = listOf(RecoveryTestFixtures.backupStats(1, 40, 5, false)))
        val plan = RecoveryRestorePlanner.plan(b, listOf(song(1)))
        val s = plan.stats.single()
        assertEquals(40, s.playCount); assertEquals(5, s.skipCount); assertFalse(s.isFavorite)
        assertTrue("40 plays must not become 40 events", plan.eventPlan.toInsert.isEmpty())
    }

    @Test fun `weak matches are not broadened - an unrelated song is not matched`() {
        val b = RecoveryTestFixtures.v2Backup(songs = listOf(RecoveryTestFixtures.backupSong(5, "Totally Different")),
            stats = listOf(RecoveryTestFixtures.backupStats(5, 3, 0, true)))
        val plan = RecoveryRestorePlanner.plan(b, listOf(song(1)))
        assertTrue(plan.stats.isEmpty())
        assertEquals(1, plan.unmatchedStatsRows)
    }

    @Test fun `two spellings of one playlist name fold into the first, and different backup songs collapsing onto one song are dropped`() {
        val p1 = BackupPlaylist(1, "Mix", 1, 1, listOf(RecoveryTestFixtures.backupPlaylistSong(1, 0)))
        val p2 = BackupPlaylist(2, "mix", 2, 2, listOf(RecoveryTestFixtures.backupPlaylistSong(1, 0)))
        val plan = RecoveryRestorePlanner.plan(RecoveryTestFixtures.v2Backup(playlists = listOf(p1, p2)), listOf(song(1)))
        assertEquals(1, plan.playlists.size)
        assertEquals("Mix", plan.playlists.single().name)
        assertEquals(listOf(1L, 1L), plan.playlists.single().songIds) // same backup song id: a genuine duplicate entry is preserved
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    @Test fun `unspecified preferences resolve to defaults so Recovery is authoritative over a changed device`() {
        val resolved = RecoveryPreferenceDefaults.resolve(RecoveryTestFixtures.emptyPrefs())
        val exempt = setOf("scanMode", "selectedFolderUris")
        for (f in BackupPreferences::class.java.declaredFields) {
            if (f.name in exempt || f.isSynthetic) continue
            f.isAccessible = true
            assertNotNull("${f.name} must resolve to a default", f.get(resolved))
        }
    }

    @Test fun `device-specific folder state is never restored by recovery`() {
        val withFolders = RecoveryTestFixtures.emptyPrefs().copy(scanMode = "SELECTED_FOLDERS", selectedFolderUris = listOf("content://tree/x"))
        val resolved = RecoveryPreferenceDefaults.resolve(withFolders)
        assertNull(resolved.scanMode); assertNull(resolved.selectedFolderUris)
    }

    @Test fun `explicit backup preferences win over defaults`() {
        val resolved = RecoveryPreferenceDefaults.resolve(RecoveryTestFixtures.emptyPrefs().copy(themeMode = "DARK", compactMode = true))
        assertEquals("DARK", resolved.themeMode); assertEquals(true, resolved.compactMode)
    }

    // ── Structure / UI contract ───────────────────────────────────────────────

    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText()

    /** Source without comment lines, so guards assert on code, not on documentation that names what it forbids. */
    private fun code(path: String) = src(path).lines().filterNot { l -> l.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }.joinToString("\n")

    @Test fun `the default mode is Merge and a fresh preview never carries Recovery`() {
        assertEquals(BackupRestoreMode.MERGE, BackupRestoreMode.values().first())
        val state = BackupImportUiState.Preview("f", 2, "d", 0, 0, 0, 0, false, 0, 0)
        assertEquals(BackupRestoreMode.MERGE, state.selectedRestoreMode)
        val vm = src("ui/screen/backupimport/BackupImportPreviewViewModel.kt")
        assertTrue("every import resets the mode", "selectedRestoreMode   = BackupRestoreMode.MERGE" in vm)
        assertFalse("mode is never persisted", "SharedPreferences" in vm || "dataStore" in vm.lowercase())
    }

    @Test fun `recovery can only be selected for an eligible, offered, non-desktop backup`() {
        val vm = src("ui/screen/backupimport/BackupImportPreviewViewModel.kt")
        assertTrue("mode == BackupRestoreMode.RECOVERY && !preview.recoveryEligible" in vm)
        assertTrue("!preview.recoveryOffered || !preview.recoveryEligible || preview.isDesktopBackup" in vm)
        assertTrue("val recoveryOffered = !cleanInstallRecovery" in vm) // clean-install flow is not redefined
        // the desktop branch of readAndParse returns before any recovery field is set
        val desktopBranch = vm.substringAfter("isDesktopBackup      = true").substringBefore("setStage(\"Parsing backup data…\")")
        assertFalse("recoveryOffered" in desktopBranch)
    }

    @Test fun `the ViewModel routes recovery only through the orchestrator and never calls the destructive DAOs`() {
        val vm = src("ui/screen/backupimport/BackupImportPreviewViewModel.kt")
        assertTrue("recoveryOrchestrator.restore(raw)" in vm)
        assertFalse("ForRecovery" in vm)
        assertFalse("applyRecovery(" in vm.replace("private fun applyRecovery(", "").replace("applyRecovery(preview)", ""))
    }

    @Test fun `destructive recovery DAO methods are only used by the recovery repository`() {
        val root = File("src/main/kotlin/com/launchpoint/wavdrop")
        val users = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\w+ForRecovery\(""").containsMatchIn(it.readText()) }
            .map { it.name }.toSet()
        // DAO interface files declare them; RecoveryRestoreRepository is the only caller.
        val declared = setOf("TrackStatsDao.kt", "TrackListenEventDao.kt", "LyricsOverrideDao.kt", "ImportBaselineDao.kt", "PlaylistDao.kt", "PendingBackupExtensionDao.kt")
        assertEquals(declared + "RecoveryRestoreRepository.kt", users)
    }

    @Test fun `recovery never deletes songs or audio files`() {
        val repo = code("data/backup/RecoveryRestoreRepository.kt") + code("data/backup/RecoveryRestorePlanner.kt") +
            code("data/backup/RecoveryRestoreOrchestrator.kt") + code("data/backup/RecoverySafetySnapshot.kt")
        for (forbidden in listOf("songDao.delete", "songDao.upsert", "MediaStore", "contentResolver.delete", "trackIdentityDao", "File(\"/", "externalStorage")) {
            assertFalse("recovery must not use $forbidden", forbidden in repo)
        }
        assertFalse("INTERNET" in File("src/main/AndroidManifest.xml").readText())
    }

    @Test fun `the snapshot uses the shared serializer and the existing exporter and validator, with no second mutex`() {
        val s = src("data/backup/RecoverySafetySnapshot.kt")
        assertTrue("serializer.withSerializedBackup" in s)
        assertTrue("backupRepository.buildBackupJson()" in s)
        assertTrue("BackupSaveValidator.isSavedBackupValid" in s)
        assertFalse("Mutex()" in s)
        assertEquals("only the restore-ownership lock may add a Mutex", 1, Regex("Mutex\\(\\)").findAll(src("data/backup/RecoveryRestoreOrchestrator.kt")).count())
    }

    @Test fun `the orchestrator sequences snapshot before apply and applies inside the backup serializer`() {
        val o = src("data/backup/RecoveryRestoreOrchestrator.kt")
        val body = o.substringAfter("private suspend fun restoreOwned")
        assertTrue(body.indexOf("RecoveryEligibility.evaluate") < body.indexOf("snapshots.createVerifiedSnapshot()"))
        assertTrue(body.indexOf("snapshots.createVerifiedSnapshot()") < body.indexOf("database.applyRecovery"))
        assertTrue(body.indexOf("database.applyRecovery") < body.indexOf("preferences.applyRecoveryPreferences"))
        assertTrue("backupExecutionSerializer.withSerializedBackup" in body)
        assertTrue("tryRun" in o)
    }

    @Test fun `the whole destructive database change is one transaction`() {
        val r = code("data/backup/RecoveryRestoreRepository.kt")
        assertEquals(1, Regex("""withTransaction""").findAll(r.replace("import androidx.room.withTransaction", "")).count())
        assertTrue(r.indexOf("RecoveryRestorePlanner.plan") < r.indexOf("deleteAllTrackStatsForRecovery()"))
    }

    @Test fun `recovery and merge use different code paths for stats - no MAX in recovery`() {
        val r = code("data/backup/RecoveryRestoreRepository.kt") + code("data/backup/RecoveryRestorePlanner.kt")
        assertFalse("mergeMaxStats" in r)
        assertFalse("MAX(" in r)
        // and Merge itself still uses it, untouched
        assertTrue("trackStatsDao.mergeMaxStats(" in src("data/backup/WavdropBackupImportRepository.kt"))
        assertTrue("trackStatsDao.setFavorite(song.id, true)" in src("data/backup/WavdropBackupImportRepository.kt"))
    }

    @Test fun `clean-install recovery keeps resetting folders by default`() {
        val s = src("data/backup/CleanInstallPreferenceRestorer.kt")
        assertTrue("folderPolicy: FolderPolicy = FolderPolicy.CLEAN_INSTALL_RESET" in s)
        assertTrue("preferenceRestorer.restore(backup.preferences)" in src("ui/screen/backupimport/BackupImportPreviewViewModel.kt"))
        assertTrue("KEEP_DEVICE_FOLDERS" in src("data/backup/RecoveryPreferenceApplier.kt"))
    }

    @Test fun `the screen shows the mode choice, the warning, a stronger confirmation and the blocked state`() {
        val ui = code("ui/screen/backupimport/BackupImportPreviewScreen.kt")
        assertTrue("RestoreModeOption(" in ui && "BackupRestoreMode.RECOVERY" in ui && "BackupRestoreMode.MERGE" in ui)
        assertTrue("RecoveryRestoreCopy.RECOVERY_WARNING" in ui)
        assertTrue("ConfirmRecoveryDialog(" in ui && "RecoveryRestoreCopy.CONFIRM_BUTTON" in ui)
        assertTrue("state.recoveryEligible" in ui) // recovery button gated by eligibility, not hasMergeableData
        assertTrue("RecoveryBlockedContent(" in ui && "Use Merge Restore instead" in ui)
        assertFalse("continue anyway" in ui.lowercase())
        assertTrue("RecoveryRestoreCopy.PARTIAL_TITLE" in ui)
        assertTrue("isRecovery -> \"Recovery complete\"" in ui)
    }

    @Test fun `copy explains the safety backup and that audio files are untouched`() {
        assertTrue("verified safety backup" in RecoveryRestoreCopy.RECOVERY_WARNING)
        assertTrue("Audio files are not changed" in RecoveryRestoreCopy.RECOVERY_WARNING)
        assertTrue("safety backup" in RecoveryRestoreCopy.CONFIRM_BODY && "Audio files are not changed" in RecoveryRestoreCopy.CONFIRM_BODY)
        assertTrue("Nothing was changed" in RecoveryRestoreCopy.SNAPSHOT_FAILED_BODY)
        assertEquals("Keep what's on this device and add compatible data from the backup.", RecoveryRestoreCopy.MODE_MERGE_BODY)
        assertTrue(RecoveryRestoreCopy.MODE_RECOVERY_BODY.startsWith("Replace WavDrop's recoverable state with this backup"))
        assertFalse("restores your music files" in RecoveryRestoreCopy.RECOVERY_WARNING)
    }

    @Test fun `no schema, format or version change`() {
        assertTrue("version      = 13" in src("data/local/WavdropDatabase.kt"))
        assertEquals(2, WavdropBackupParser.SUPPORTED_VERSION)
        val gradle = File("build.gradle.kts").readText()
        assertTrue("versionCode = 10" in gradle && "versionName = \"0.1.0-beta10\"" in gradle)
    }
}

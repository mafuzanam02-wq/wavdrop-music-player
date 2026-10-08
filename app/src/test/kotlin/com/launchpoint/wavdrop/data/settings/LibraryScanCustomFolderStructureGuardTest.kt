package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CFE-1 structural guards (source-text, same style as the SE-1 guard): one path authority, a confirmation before excluding,
 * no Unknown Folder action, settings management from persisted settings, no second scanner / delete / MediaStore write, and
 * nothing in backups, schema, permissions or dependencies.
 */
class LibraryScanCustomFolderStructureGuardTest {

    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText()
    private fun kotlinFiles(dir: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$dir").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun code(text: String) = text.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")

    private val details = src("ui/screen/folders/FolderDetailsScreen.kt")
    private val detailsVm = src("ui/screen/folders/FolderDetailsViewModel.kt")
    private val settingsScreen = src("ui/screen/settings/SettingsLibraryScreen.kt")
    private val settingsVm = src("ui/screen/settings/SettingsViewModel.kt")

    // ── Folder Details ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `25 - Folder Details offers Exclude this folder only for a real path and always behind a confirmation`() {
        assertTrue(details.contains("""Text("Exclude this folder")"""))
        assertTrue("the menu item only exists when the folder can be excluded", details.contains("if (onExcludeFolder != null)"))
        assertTrue("the action is offered only for excludable folders", details.contains("onExcludeFolder = if (state.canExcludeFolder)"))
        assertTrue("canExclude is derived from the path authority, never from a name", detailsVm.contains("LibraryScanSettingsRules.canonicalCustomFolderPath(folderKey) != null"))
        // the menu item opens the dialog; only the dialog's confirm button persists
        val menuItem = details.substringAfter("""Text("Exclude this folder")""").take(120)
        assertTrue(menuItem.contains("onExcludeFolder()") && !menuItem.contains("excludeThisFolder"))
        val dialog = details.substringAfter("if (confirmExclude && state.canExcludeFolder)").substringBefore("addToPlaylistSong?.let")
        assertTrue(dialog.contains("viewModel.excludeThisFolder"))
        assertTrue(dialog.contains("Exclude this folder?"))
        assertTrue(dialog.contains("Exclude folder") && dialog.contains("Cancel"))
        assertEquals("exactly one persist call, inside the confirm button", 1, Regex("""excludeThisFolder""").findAll(details).count())
    }

    @Test fun `the confirmation says files and history are kept and it is not a delete`() {
        val dialog = details.substringAfter("if (confirmExclude && state.canExcludeFolder)").substringBefore("addToPlaylistSong?.let")
        assertTrue(dialog.contains("hide this folder and its subfolders from WavDrop the next time you rescan your library"))
        assertTrue(dialog.contains("Your audio files will stay on your device, and your listening history and statistics will be kept."))
        assertTrue(dialog.contains("Folder excluded. Rescan your library to apply the change."))
        for (forbidden in listOf("Delete", "Erase", "Remove files")) assertFalse("dialog must not say $forbidden", dialog.contains(forbidden))
        assertTrue("shows the folder path", dialog.contains("state.folderKey"))
    }

    @Test fun `the exclude action is folder-level, not in song rows, and Unknown Folder never reaches it`() {
        val rowBlock = details.substringAfter("SongRowWithOverflow(").substringBefore("modifier         = Modifier.fillMaxWidth()")
        assertFalse(rowBlock.contains("xclude"))
        assertFalse("no UNKNOWN_FOLDER persisted by the screen", details.contains("addCustomFolderExclusion"))
        assertTrue(detailsVm.contains("if (!canExcludeFolder) { onResult(false); return }"))
    }

    @Test fun `Folder Details never scans, deletes, mutates songs or touches MediaStore`() {
        val c = code(detailsVm) + "\n" + code(details)
        for (forbidden in listOf("sync(", "scanSongs", "MediaStore", "ContentResolver", "createDeleteRequest", "deleteAll", "songDao", "deleteSong", "File(")) {
            assertFalse("Folder Details must not use $forbidden", c.contains(forbidden))
        }
        assertFalse("no matching logic in the view model", detailsVm.contains("startsWith") || detailsVm.contains("isExcludedByCustomFolder"))
        assertTrue("persistence goes through the settings repository", detailsVm.contains("scanSettingsRepository.addCustomFolderExclusion(folderKey)"))
    }

    // ── Settings ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `26 - Settings Library shows custom excluded folders from persisted settings with an empty state and a Remove action`() {
        val section = settingsScreen.substringAfter("""SectionHeader("Custom excluded folders")""").substringBefore("""SectionHeader("Filters & Folders")""")
        assertTrue(section.contains("No custom folders excluded."))
        assertTrue("rendered from persisted scan settings, not the live library", section.contains("scanSettings.customExcludedFolderPaths"))
        assertTrue(section.contains("viewModel.removeCustomFolderExclusion(folderPath)"))
        assertTrue(section.contains("apply the next time you rescan"))
        assertFalse("removal does not rescan", section.contains("rescan()") || section.contains("rescanLibrary"))
        assertTrue("the preset switches are still there, separate", settingsScreen.contains("""SectionHeader("Exclude folders")""") && settingsScreen.contains("Exclude Downloads"))
        val fn = settingsVm.substringAfter("fun removeCustomFolderExclusion").substringBefore("fun setStartupDestination")
        assertTrue(fn.contains("scanSettingsRepository.removeCustomFolderExclusion(folderPath)"))
        assertFalse(fn.contains("rescan") || fn.contains("sync"))
        assertFalse("no free-text entry, wildcard or regex in settings", section.contains("TextField") || section.contains("Regex"))
    }

    // ── one path authority ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `only the scan rules implement custom folder matching and the classifier stays the preset authority`() {
        val users = kotlinFiles("").filter { it.readText().contains("LibraryScanFolderClassifier") }.map { it.name }.toSet()
        assertEquals(setOf("LibraryScanFolderClassifier.kt", "LibraryScanSettingsRules.kt"), users)
        val classifier = src("data/settings/LibraryScanFolderClassifier.kt")
        assertFalse("the classifier holds no user-defined folder state", classifier.contains("customExcluded") || classifier.contains("isExcludedByCustomFolder"))
        val matchers = kotlinFiles("").filter { it.readText().contains("isExcludedByCustomFolder") }.map { it.name }.toSet()
        assertEquals(setOf("LibraryScanSettingsRules.kt"), matchers)
        val scanner = src("data/mediastore/MediaStoreScanner.kt")
        assertTrue(scanner.contains("LibraryScanSettingsRules.evaluateScanSettings("))
        assertFalse("no custom path rule in the MediaStore query", scanner.contains("customExcluded"))
    }

    @Test fun `custom folder identity never form-decodes a raw folder path`() {
        val r = src("data/settings/LibraryScanSettingsRules.kt")
        val custom = r.substringAfter("fun canonicalCustomFolderPath").substringBefore("fun clampMinimumTrackDurationSeconds")
        assertFalse("no URLDecoder in the custom path code", custom.contains("URLDecoder") || custom.contains("decode("))
        val calls = Regex("""LibraryScanFolderClassifier\.segments\([^)]*\)""").findAll(custom).map { it.value }.toList()
        assertEquals(3, calls.size)
        assertTrue("every custom call opts out of decoding: $calls", calls.all { it.contains("decodeEncodedText = false") })
        assertTrue("the SE-1 preset callers keep the default decoding", src("data/settings/LibraryScanFolderClassifier.kt").contains("decodeEncodedText: Boolean = true"))
    }

    @Test fun `the final eligibility rule applies presets and custom folders and the evidence is neutral to which one acted`() {
        val r = src("data/settings/LibraryScanSettingsRules.kt")
        val allowed = r.substringAfter("fun isSongAllowedByScanSettings").substringBefore("fun matchesSelectedFolder")
        assertTrue(allowed.contains("!isExcludedByPreset(song, normalized)") && allowed.contains("!isExcludedByCustomFolder(song, normalized)"))
        val evaluate = r.substringAfter("fun evaluateScanSettings").substringBefore("/** Stage 1")
        assertTrue(evaluate.indexOf("isSongAllowedBeforeExplicitExclusions") < evaluate.indexOf("isExcludedByPreset") && evaluate.contains("isExcludedByCustomSegments"))
        assertFalse("the old preset-only evidence name is gone", kotlinFiles("").any { it.readText().contains("eligibleBeforePresetExclusionsCount") })
        val policy = src("data/repository/SongSyncPolicy.kt")
        assertTrue(policy.contains("LibraryScanSettingsRules.hasAnyExplicitExclusion(settings)"))
        assertFalse("the disposition no longer looks at presets only", code(policy).contains("settings.excludedPresetFolders"))
        assertTrue("the sync still never deletes history", src("data/repository/SongRepository.kt").let { !it.contains("customExcluded") })
    }

    // ── what CFE-1 deliberately does not touch ──────────────────────────────────────────────────────────────────────────

    @Test fun `custom exclusions are device-local and never enter any backup model`() {
        for (f in kotlinFiles("data/backup")) {
            val t = f.readText()
            assertFalse("${f.name} must not carry custom exclusions", t.contains("customExcluded") || t.contains("CustomExcluded") || t.contains("library_custom_excluded_folder_paths"))
        }
    }

    @Test fun `no schema, permission or dependency change and no path logging`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE") || manifest.contains("INTERNET"))
        val rules = src("data/settings/LibraryScanSettingsRules.kt")
        assertFalse(rules.contains("Log."))
        val db = src("data/local/WavdropDatabase.kt")
        val version = Regex("""version\s*=\s*(\d+)""").find(db)!!.groupValues[1].toInt()
        val newest = File("schemas/com.launchpoint.wavdrop.data.local.WavdropDatabase").listFiles()!!
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals("no Room schema change", newest, version)
        assertTrue("a dedicated DataStore key, additive", src("data/settings/LibraryScanSettingsRepository.kt").contains("""stringSetPreferencesKey("library_custom_excluded_folder_paths")"""))
    }
}

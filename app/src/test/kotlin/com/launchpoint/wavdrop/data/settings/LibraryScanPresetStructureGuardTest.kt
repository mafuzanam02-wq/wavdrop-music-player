package com.launchpoint.wavdrop.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SE-1: structural facts about where the rule lives, how the UI binds to it, and what is deliberately NOT touched
 * (backups, schema, permissions). The filtering behaviour itself is covered by the behavioural tests.
 */
class LibraryScanPresetStructureGuardTest {

    private val main = File("src/main/kotlin/com/launchpoint/wavdrop")
    private fun src(path: String) = File(main, path).readText()
    private fun kotlinFiles(dir: String) = File(main, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ── UI ──────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the library screen has exactly five exclusion switches bound to the right enum and view model action`() {
        val s = src("ui/screen/settings/SettingsLibraryScreen.kt")
        assertTrue(s.contains("SectionHeader(\"Exclude folders\")"))
        val expected = mapOf(
            "Exclude Telegram" to "TELEGRAM", "Exclude Signal" to "SIGNAL", "Exclude Messenger" to "MESSENGER",
            "Exclude Downloads" to "DOWNLOADS", "Exclude Recordings" to "RECORDINGS",
        )
        for ((title, enumName) in expected) {
            val block = s.substringAfter("title           = \"$title\"").substringBefore("item {")
            assertTrue("$title binds the checked state to $enumName", block.contains("LibraryScanExclusion.$enumName in scanSettings.excludedPresetFolders"))
            assertTrue("$title calls the view model with $enumName", block.contains("viewModel.setPresetScanExclusion(LibraryScanExclusion.$enumName, it)"))
        }
        assertEquals("no sixth switch", 5, Regex("""setPresetScanExclusion\(""").findAll(s).count())
        assertTrue(s.contains("Hide audio stored in Telegram folders."))
        assertTrue(s.contains("Hide audio stored in Signal folders."))
        assertTrue(s.contains("Hide audio stored in Messenger folders."))
        assertTrue(s.contains("Hide audio stored in your Downloads folder."))
        assertTrue(s.contains("Hide audio stored in recording folders."))
    }

    @Test fun `the existing whatsapp control is kept and still routes to its own setter`() {
        val s = src("ui/screen/settings/SettingsLibraryScreen.kt")
        assertTrue(s.contains("title   = \"Include WhatsApp voice notes\""))
        assertTrue(s.contains("onCheckedChange = viewModel::setIncludeWhatsAppVoiceNotes"))
    }

    @Test fun `the UI holds no path rules and a toggle does not launch a scan`() {
        val s = src("ui/screen/settings/SettingsLibraryScreen.kt")
        for (forbidden in listOf("LibraryScanFolderClassifier", "Download/", "\"download", "org.telegram", "securesms", "com.facebook")) {
            assertFalse("the screen must not contain $forbidden", s.contains(forbidden))
        }
        val vm = src("ui/screen/settings/SettingsViewModel.kt")
        val fn = vm.substringAfter("fun setPresetScanExclusion").substringBefore("fun addSelectedFolderUri")
        assertTrue(fn.contains("scanSettingsRepository.setPresetExclusion(exclusion, excluded)"))
        assertFalse("applies at the next rescan, never a background scan", fn.contains("rescan") || fn.contains("sync") || fn.contains("scan("))
        assertFalse(vm.contains("LibraryScanFolderClassifier"))
    }

    // ── one rule, one place ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `only the scan rules consult the classifier and the scanner still applies the single Kotlin filter`() {
        val users = kotlinFiles("").filter { it.readText().contains("LibraryScanFolderClassifier") }.map { it.name }.toSet()
        assertEquals(setOf("LibraryScanFolderClassifier.kt", "LibraryScanSettingsRules.kt"), users)
        val scanner = src("data/mediastore/MediaStoreScanner.kt")
        assertTrue(scanner.contains("LibraryScanSettingsRules.evaluateScanSettings("))
        for (forbidden in listOf("LIKE", "Download", "Telegram", "Recordings", "Messenger", "Signal")) {
            assertFalse("no path rule in the MediaStore query: $forbidden", scanner.contains(forbidden))
        }
    }

    @Test fun `the exclusion precedence is documented in the rule where it is applied`() {
        val r = src("data/settings/LibraryScanSettingsRules.kt")
        val stage1 = r.substringAfter("fun isSongAllowedBeforePresetExclusions").substringBefore("fun isSongAllowedByScanSettings")
        assertTrue("stage 1 = duration, WhatsApp, scan mode", stage1.indexOf("minimumDurationMs") in 0 until stage1.indexOf("includeWhatsAppVoiceNotes") &&
            stage1.indexOf("includeWhatsAppVoiceNotes") < stage1.indexOf("when (normalized.scanMode)"))
        assertFalse("stage 1 never consults the preset exclusion", stage1.contains("isExcludedByPreset"))
        val allowed = r.substringAfter("fun isSongAllowedByScanSettings").substringBefore("fun matchesSelectedFolder")
        assertTrue("the public rule is stage 1 AND NOT preset", allowed.contains("isSongAllowedBeforePresetExclusions(song, normalized) && !isExcludedByPreset(song, normalized)"))
        assertTrue(r.contains("outranks everything, including an explicitly selected folder"))
        val evaluate = r.substringAfter("fun evaluateScanSettings").substringBefore("/** Stage 1")
        assertTrue("the evidence count is taken between the two stages", evaluate.indexOf("isSongAllowedBeforePresetExclusions") < evaluate.indexOf("isExcludedByPreset") && evaluate.contains("eligible.size"))
    }

    // ── what SE-1 deliberately does not touch ───────────────────────────────────────────────────────────────────────────

    @Test fun `exclusions are device-local and never enter the backup model`() {
        for (f in kotlinFiles("data/backup")) {
            val t = f.readText()
            assertFalse("${f.name} must not carry preset exclusions", t.contains("excludedPresetFolders") || t.contains("LibraryScanExclusion"))
        }
        assertFalse(src("data/backup/WavdropBackup.kt").contains("xclusion"))
    }

    @Test fun `no schema permission or logging of folder paths was added`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("INTERNET"))
        val classifier = src("data/settings/LibraryScanFolderClassifier.kt")
        assertFalse(classifier.contains("Log."))
        assertFalse(src("data/settings/LibraryScanSettingsRules.kt").contains("Log."))
        val db = src("data/local/WavdropDatabase.kt")
        val version = Regex("""version\s*=\s*(\d+)""").find(db)!!.groupValues[1].toInt()
        val newest = File("schemas/com.launchpoint.wavdrop.data.local.WavdropDatabase").listFiles()!!
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertEquals("no Room schema change", newest, version)
    }

    @Test fun `the sync lifecycle deletes nothing but vanished songs - no history cleanup was added`() {
        val repo = src("data/repository/SongRepository.kt")
        assertFalse(repo.contains("excludedPresetFolders"))
        val policy = src("data/repository/SongSyncPolicy.kt")
        assertTrue(policy.contains("fun computeStaleIds"))
    }
}

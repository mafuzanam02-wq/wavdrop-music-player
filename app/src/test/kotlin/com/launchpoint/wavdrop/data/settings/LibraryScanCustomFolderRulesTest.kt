package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.EmptyScanDisposition
import com.launchpoint.wavdrop.data.repository.SongSyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CFE-1: custom folder exclusions as pure rules. One path authority ([LibraryScanSettingsRules]); whole-segment matching;
 * the exclusion is the folder AND its descendants; explicit exclusions outrank selected folders; the pre-exclusion evidence
 * count is shared by presets and custom folders.
 */
class LibraryScanCustomFolderRulesTest {

    private fun song(id: Long, folderPath: String?, durationMs: Long = 120_000L) = Song(
        id = id, title = "Song $id", artist = "A", album = "B", albumId = 0L, duration = durationMs, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020, folderPath = folderPath, folderName = folderPath?.substringAfterLast('/'),
    )

    private fun settings(
        vararg custom: String,
        presets: Set<LibraryScanExclusion> = emptySet(),
        mode: LibraryScanMode = LibraryScanMode.WHOLE_DEVICE,
        folders: List<String> = emptyList(),
    ) = LibraryScanSettings(
        scanMode = mode, selectedFolderUris = folders, minimumTrackDurationSeconds = 1,
        excludedPresetFolders = presets, customExcludedFolderPaths = custom.toSet(),
    )

    private fun excluded(folder: String?, vararg custom: String) =
        LibraryScanSettingsRules.isExcludedByCustomFolder(song(1, folder), settings(*custom))

    // ── 1. model ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `1 - custom exclusions are empty by default and change nothing`() {
        assertEquals(emptySet<String>(), LibraryScanSettings().customExcludedFolderPaths)
        assertEquals(emptySet<String>(), LibraryScanSettingsRules.normalize(LibraryScanSettings()).customExcludedFolderPaths)
        val songs = listOf(song(1, "Music/Rock"), song(2, "Music/Podcasts/S1"), song(3, null))
        assertEquals(listOf(1L, 2L, 3L), LibraryScanSettingsRules.filterSongsForScanSettings(songs, LibraryScanSettings(minimumTrackDurationSeconds = 1)).map { it.id })
        assertFalse(LibraryScanSettingsRules.hasAnyExplicitExclusion(LibraryScanSettings()))
    }

    // ── 2/3. canonicalization, duplicates, invalid input ───────────────────────────────────────────────────────────────

    @Test fun `canonical path handles separators, trailing slashes, duplicate separators and storage prefixes`() {
        val canonical = "Music/Podcasts"
        for (raw in listOf(
            "Music/Podcasts", "Music/Podcasts/", "/Music/Podcasts/", "Music\\Podcasts\\", "Music//Podcasts",
            "/storage/emulated/0/Music/Podcasts", "storage/emulated/0/Music/Podcasts/", "/sdcard/Music/Podcasts", "  Music/Podcasts  ",
            "/storage/ABCD-1234/Music/Podcasts", "/storage/self/primary/Music/Podcasts/", "/mnt/sdcard/Music/Podcasts",
        )) assertEquals("canonical form of '$raw'", canonical, LibraryScanSettingsRules.canonicalCustomFolderPath(raw))
        assertEquals("original letter case is kept for display", "My Music/DJ Sets", LibraryScanSettingsRules.canonicalCustomFolderPath("\\My Music\\DJ Sets\\"))
    }

    @Test fun `blank, unknown folder and prefix-only paths are not valid custom exclusions`() {
        for (raw in listOf(null, "", "   ", "/", "\\", "//", FolderGrouper.UNKNOWN_FOLDER, " unknown folder ", "/storage/emulated/0", "/storage/emulated/0/", "sdcard")) {
            assertNull("'$raw' must be rejected", LibraryScanSettingsRules.canonicalCustomFolderPath(raw))
        }
        assertEquals(emptySet<String>(), LibraryScanSettingsRules.normalizeCustomFolderExclusions(listOf("", "  ", FolderGrouper.UNKNOWN_FOLDER, "/")))
        assertEquals(emptySet<String>(), LibraryScanSettingsRules.normalizeCustomFolderExclusions(null))
    }

    // ── CFE-1 correction: raw folder paths are literal, never form/percent decoded ───────────────────────────────────────

    @Test fun `A - plus signs survive and C++ is not C`() {
        assertEquals("Music/C++", LibraryScanSettingsRules.canonicalCustomFolderPath("Music/C++"))
        assertEquals("Music/C++", LibraryScanSettingsRules.canonicalCustomFolderPath("/storage/emulated/0/Music/C++/"))
        assertTrue(excluded("Music/C++", "Music/C++"))
        assertTrue("descendants", excluded("Music/C++/Live", "Music/C++"))
        assertTrue("storage-prefixed song path", excluded("/storage/emulated/0/Music/C++/Live", "Music/C++"))
        assertFalse("Music/C is a different folder", excluded("Music/C", "Music/C++"))
        assertFalse("Music/C+ is a different folder", excluded("Music/C+", "Music/C++"))
        assertFalse("and the other way around: excluding Music/C leaves Music/C++", excluded("Music/C++", "Music/C"))
        assertFalse("'+' is not a space", excluded("Music/C  ", "Music/C++") || excluded("Music/A B", "Music/A+B"))
        assertEquals("a plus is not decoded into a space", "Music/A+B", LibraryScanSettingsRules.canonicalCustomFolderPath("Music/A+B"))
    }

    @Test fun `B - literal percent text survives as one folder name`() {
        assertEquals("Music/A%2FB", LibraryScanSettingsRules.canonicalCustomFolderPath("Music/A%2FB"))
        assertEquals("Music/100%", LibraryScanSettingsRules.canonicalCustomFolderPath("Music/100%"))
        assertEquals("Music/%ZZ", LibraryScanSettingsRules.canonicalCustomFolderPath("Music/%ZZ"))
        assertTrue(excluded("Music/A%2FB", "Music/A%2FB"))
        assertTrue("descendants of the literal folder", excluded("Music/A%2FB/Live", "Music/A%2FB"))
        assertFalse("Music/A/B is a different (nested) folder", excluded("Music/A/B", "Music/A%2FB"))
        assertFalse("and excluding A/B leaves the literal A%2FB folder", excluded("Music/A%2FB", "Music/A/B"))
    }

    @Test fun `C - percent-encoded-looking separators are never treated as path separators for custom exclusions`() {
        for (raw in listOf("Music%2FPodcasts", "Music%5CPodcasts", "Music%2fPodcasts")) {
            val canonical = LibraryScanSettingsRules.canonicalCustomFolderPath(raw)
            assertEquals("kept as one literal segment: $raw", raw, canonical)
            assertFalse("must not exclude Music/Podcasts", excluded("Music/Podcasts", raw))
            assertFalse("must not exclude descendants of Music/Podcasts", excluded("Music/Podcasts/Season 1", raw))
        }
        assertFalse("excluding Music/Podcasts does not touch the literal %2F look-alike", excluded("Music%2FPodcasts", "Music/Podcasts"))
        assertEquals(1, LibraryScanSettingsRules.normalizeCustomFolderExclusions(listOf("Music%2FPodcasts")).size)
        assertEquals("distinct folders are never collapsed", 2, LibraryScanSettingsRules.normalizeCustomFolderExclusions(listOf("Music%2FPodcasts", "Music/Podcasts")).size)
    }

    @Test fun `D and E - ordinary storage prefixes and backslashes still normalize structurally, volume-agnostic`() {
        assertEquals("Music/Podcasts", LibraryScanSettingsRules.canonicalCustomFolderPath("/storage/emulated/0/Music/Podcasts/"))
        assertEquals("Music/Podcasts", LibraryScanSettingsRules.canonicalCustomFolderPath("/storage/ABCD-1234/Music/Podcasts"))
        assertEquals("Music/Podcasts/Season 1", LibraryScanSettingsRules.canonicalCustomFolderPath("\\storage\\emulated\\0\\Music\\Podcasts\\Season 1\\"))
        assertTrue("backslash song path", excluded("Music\\C++\\Live", "Music/C++"))
        assertTrue("an SD-card song path matches the same relative path (documented volume-agnostic model)", excluded("/storage/ABCD-1234/Music/Podcasts/S1", "/storage/emulated/0/Music/Podcasts"))
    }

    @Test fun `the preset classifier keeps its existing decoding behaviour`() {
        assertEquals(LibraryScanExclusion.DOWNLOADS, LibraryScanFolderClassifier.classifyPath("%44ownload/Mixes"))
        assertEquals("custom matching never uses that decoding", false, excluded("%44ownload/Mixes", "Download/Mixes"))
        assertEquals(listOf("music", "a b"), LibraryScanFolderClassifier.segments("Music/A+B"))
        assertEquals(listOf("music", "a+b"), LibraryScanFolderClassifier.segments("Music/A+B", decodeEncodedText = false))
    }

    @Test fun `3 - duplicate spellings collapse to one entry in a deterministic order`() {
        val a = LibraryScanSettingsRules.normalizeCustomFolderExclusions(listOf("Music/Podcasts", "music/podcasts", "MUSIC\\PODCASTS/", "/storage/emulated/0/Music/Podcasts"))
        assertEquals(1, a.size)
        val b = LibraryScanSettingsRules.normalizeCustomFolderExclusions(listOf("/storage/emulated/0/Music/Podcasts", "MUSIC\\PODCASTS/", "music/podcasts", "Music/Podcasts"))
        assertEquals("input order never changes the result", a.toList(), b.toList())
        val sorted = LibraryScanSettingsRules.normalizeCustomFolderExclusions(listOf("Zeta", "alpha", "Beta"))
        assertEquals(listOf("alpha", "Beta", "Zeta"), sorted.toList())
    }

    // ── 4/5. mutation API ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `4 - adding one exclusion preserves presets and every other setting`() {
        val base = LibraryScanSettings(
            scanMode = LibraryScanMode.SELECTED_FOLDERS, selectedFolderUris = listOf("content://tree/primary:Music"),
            minimumTrackDurationSeconds = 20, includeWhatsAppVoiceNotes = true,
            excludedPresetFolders = setOf(LibraryScanExclusion.DOWNLOADS), customExcludedFolderPaths = setOf("Music/Live"),
        )
        val added = LibraryScanSettingsRules.withCustomFolderExclusion(base, "Music/Podcasts")
        assertEquals(setOf("Music/Live", "Music/Podcasts"), added.customExcludedFolderPaths)
        assertEquals("every other setting preserved", LibraryScanSettingsRules.normalize(base), added.copy(customExcludedFolderPaths = setOf("Music/Live")))
        assertEquals("duplicate add is idempotent", added, LibraryScanSettingsRules.withCustomFolderExclusion(added, "/MUSIC/podcasts/"))
        assertEquals("an invalid path changes nothing", LibraryScanSettingsRules.normalize(base), LibraryScanSettingsRules.withCustomFolderExclusion(base, FolderGrouper.UNKNOWN_FOLDER))
    }

    @Test fun `5 - removing one exclusion preserves the others and every other setting`() {
        val base = LibraryScanSettings(
            minimumTrackDurationSeconds = 20, excludedPresetFolders = setOf(LibraryScanExclusion.TELEGRAM),
            customExcludedFolderPaths = setOf("Music/Podcasts", "Music/Live"),
        )
        val removed = LibraryScanSettingsRules.withoutCustomFolderExclusion(base, "MUSIC\\podcasts\\")
        assertEquals(setOf("Music/Live"), removed.customExcludedFolderPaths)
        assertEquals(setOf(LibraryScanExclusion.TELEGRAM), removed.excludedPresetFolders)
        assertEquals(20, removed.minimumTrackDurationSeconds)
        assertEquals("removing an absent folder is a no-op", removed, LibraryScanSettingsRules.withoutCustomFolderExclusion(removed, "Music/Absent"))
        assertEquals(emptySet<String>(), LibraryScanSettingsRules.withoutCustomFolderExclusion(removed, "Music/Live").customExcludedFolderPaths)
    }

    @Test fun `custom and preset exclusions are independent in both directions`() {
        val both = LibraryScanSettingsRules.withCustomFolderExclusion(
            LibraryScanSettingsRules.withPresetExclusion(LibraryScanSettings(), LibraryScanExclusion.DOWNLOADS, true), "Download/My Music",
        )
        val customRemoved = LibraryScanSettingsRules.withoutCustomFolderExclusion(both, "Download/My Music")
        assertEquals("removing the custom exclusion keeps the Downloads preset", setOf(LibraryScanExclusion.DOWNLOADS), customRemoved.excludedPresetFolders)
        val presetRemoved = LibraryScanSettingsRules.withPresetExclusion(both, LibraryScanExclusion.DOWNLOADS, false)
        assertEquals("disabling the preset keeps the custom exclusion", setOf("Download/My Music"), presetRemoved.customExcludedFolderPaths)
    }

    // ── 6-8. matching ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `6 - the exact folder matches`() {
        assertTrue(excluded("Music/Podcasts", "Music/Podcasts"))
        assertTrue(excluded("Music/Podcasts/", "Music/Podcasts"))
        assertTrue(excluded("Music/DJ Sets", "Music/DJ Sets"))
    }

    @Test fun `7 - every descendant folder matches`() {
        assertTrue(excluded("Music/Podcasts/Season 1", "Music/Podcasts"))
        assertTrue(excluded("Music/Podcasts/Season 2/Episodes", "Music/Podcasts"))
        assertTrue(excluded("Music/DJ Sets/2026", "Music/DJ Sets"))
    }

    @Test fun `8 - look-alike siblings and parents never match`() {
        for (folder in listOf("Music/Podcasts Archive", "Music/My Podcasts", "Music/Podcast", "Music/Podcasts-old", "Music/Podcasts2/Season 1", "Podcasts", "Other/Music/Podcasts")) {
            assertFalse("'$folder' must not match Music/Podcasts", excluded(folder, "Music/Podcasts"))
        }
        assertFalse(excluded("Music/DJ Sets Backup", "Music/DJ Sets"))
        assertFalse("the parent of an excluded folder stays", excluded("Music", "Music/Podcasts"))
        assertFalse("a sibling stays", excluded("Music/Rock", "Music/Podcasts"))
    }

    @Test fun `9 and 10 - slash, backslash, case, storage-prefix and trailing separator normalization apply on both sides`() {
        assertTrue(excluded("music\\PODCASTS\\Season 1\\", "Music/Podcasts"))
        assertTrue(excluded("/storage/emulated/0/Music/Podcasts/Season 1", "Music/Podcasts"))
        assertTrue(excluded("Music/Podcasts", "/sdcard/MUSIC/PODCASTS/"))
        assertTrue(excluded("Music//Podcasts//S1", "Music\\Podcasts"))
        assertTrue(excluded("MUSIC/podcasts", "music/PODCASTS"))
    }

    @Test fun `11 - a blank or null song folder path never matches`() {
        assertFalse(excluded(null, "Music/Podcasts"))
        assertFalse(excluded("", "Music/Podcasts"))
        assertFalse(excluded("   ", "Music/Podcasts"))
        assertFalse(excluded("/", "Music/Podcasts"))
        assertFalse(excluded(FolderGrouper.UNKNOWN_FOLDER, "Music/Podcasts"))
    }

    @Test fun `malformed persisted entries are ignored and never crash matching`() {
        val s = LibraryScanSettings(customExcludedFolderPaths = setOf("", " ", "%", "%ZZ", "////", "Music/Podcasts"))
        val n = LibraryScanSettingsRules.normalize(s)
        assertTrue("Music/Podcasts" in n.customExcludedFolderPaths)
        assertFalse(n.customExcludedFolderPaths.any { it.isBlank() || it == "/" })
        assertTrue(LibraryScanSettingsRules.isExcludedByCustomFolder(song(1, "Music/Podcasts/x"), s))
        assertFalse(LibraryScanSettingsRules.isExcludedByCustomFolder(song(2, "Music/Rock"), s))
    }

    // ── 13-16. the one eligibility rule ─────────────────────────────────────────────────────────────────────────────────

    private val rock = song(1, "Music/Rock")
    private val podcasts = song(2, "Music/Podcasts")
    private val season = song(3, "Music/Podcasts/Season 1")
    private val archive = song(4, "Music/Podcasts Archive")
    private val downloads = song(5, "Download/Mixes")
    private val all = listOf(rock, podcasts, season, archive, downloads)

    private fun ids(s: LibraryScanSettings) = LibraryScanSettingsRules.filterSongsForScanSettings(all, s).map { it.id }

    @Test fun `13 - preset-only exclusion still works unchanged`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(settings(presets = setOf(LibraryScanExclusion.DOWNLOADS))))
    }

    @Test fun `14 - custom-only exclusion works`() {
        assertEquals(listOf(1L, 4L, 5L), ids(settings("Music/Podcasts")))
    }

    @Test fun `15 - preset and custom exclusions are a union`() {
        assertEquals(listOf(1L, 4L), ids(settings("Music/Podcasts", presets = setOf(LibraryScanExclusion.DOWNLOADS))))
    }

    @Test fun `overlapping preset and custom exclusions are valid`() {
        val nested = song(6, "Download/My Music")
        val s = settings("Download/My Music", presets = setOf(LibraryScanExclusion.DOWNLOADS))
        assertTrue(LibraryScanSettingsRules.isExcludedByPreset(nested, s))
        assertTrue(LibraryScanSettingsRules.isExcludedByCustomFolder(nested, s))
        assertFalse(LibraryScanSettingsRules.isSongAllowedByScanSettings(nested, s))
        assertTrue("with the preset off, the custom exclusion alone still excludes it", !LibraryScanSettingsRules.isSongAllowedByScanSettings(nested, s.copy(excludedPresetFolders = emptySet())))
        assertTrue("with the custom exclusion off, the preset alone still excludes it", !LibraryScanSettingsRules.isSongAllowedByScanSettings(nested, s.copy(customExcludedFolderPaths = emptySet())))
    }

    @Test fun `16 - a selected parent folder is overridden by a custom exclusion inside it`() {
        val s = settings("Music/Podcasts", mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://com.android.externalstorage.documents/tree/primary:Music"))
        assertEquals("Music songs stay, Music/Podcasts and descendants go", listOf(1L, 4L), ids(s))
        assertEquals("the two settings stay independent", listOf("content://com.android.externalstorage.documents/tree/primary:Music"), LibraryScanSettingsRules.normalize(s).selectedFolderUris)
    }

    @Test fun `a selected folder equal to a custom-excluded folder yields nothing and is not silently edited`() {
        val s = settings("Music/Podcasts", mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://com.android.externalstorage.documents/tree/primary:Music/Podcasts"))
        assertEquals(emptyList<Long>(), ids(s))
        assertEquals(1, LibraryScanSettingsRules.normalize(s).selectedFolderUris.size)
    }

    // ── 17-21. definitive-empty evidence ────────────────────────────────────────────────────────────────────────────────

    private fun evaluate(songs: List<Song>, s: LibraryScanSettings) = LibraryScanSettingsRules.evaluateScanSettings(songs, s)
    private fun disposition(songs: List<Song>, s: LibraryScanSettings, existing: Int = 5): EmptyScanDisposition {
        val e = evaluate(songs, s)
        assertTrue("these cases expect an empty result", e.songs.isEmpty())
        return SongSyncPolicy.emptyScanDisposition(s, existing, e.eligibleBeforeExplicitExclusionsCount)
    }

    @Test fun `the evidence count is taken before BOTH preset and custom exclusions`() {
        val e = evaluate(all, settings("Music/Podcasts", presets = setOf(LibraryScanExclusion.DOWNLOADS)))
        assertEquals(all.size, e.eligibleBeforeExplicitExclusionsCount)
        assertEquals(listOf(1L, 4L), e.songs.map { it.id })
        assertEquals("no exclusion -> the count equals the result", all.size, evaluate(all, settings()).let { it.eligibleBeforeExplicitExclusionsCount.also { _ -> assertEquals(all, it.songs) } })
    }

    @Test fun `17 - a custom exclusion that empties the library is a definitive empty`() {
        val onlyPodcasts = listOf(podcasts, season)
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, disposition(onlyPodcasts, settings("Music/Podcasts")))
    }

    @Test fun `18 - a preset exclusion that empties the library is still a definitive empty`() {
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, disposition(listOf(downloads), settings(presets = setOf(LibraryScanExclusion.DOWNLOADS))))
    }

    @Test fun `19 - presets plus custom folders that together empty the library are a definitive empty`() {
        val songs = listOf(downloads, podcasts)
        val s = settings("Music/Podcasts", presets = setOf(LibraryScanExclusion.DOWNLOADS))
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, disposition(songs, s))
        // each rule alone would not have emptied it
        assertTrue(evaluate(songs, settings("Music/Podcasts")).songs.isNotEmpty())
        assertTrue(evaluate(songs, settings(presets = setOf(LibraryScanExclusion.DOWNLOADS))).songs.isNotEmpty())
    }

    @Test fun `selected folder equal to the custom exclusion is a definitive empty when tracks existed before the explicit stage`() {
        val s = settings("Music/Podcasts", mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://com.android.externalstorage.documents/tree/primary:Music/Podcasts"))
        val e = evaluate(all, s)
        assertEquals(listOf(2, 3).size, e.eligibleBeforeExplicitExclusionsCount)
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, SongSyncPolicy.emptyScanDisposition(s, 5, e.eligibleBeforeExplicitExclusionsCount))
    }

    @Test fun `20 - ambiguous zeros stay preserved with a custom exclusion active`() {
        val s = settings("Music/Podcasts")
        assertEquals("nothing came back from MediaStore", EmptyScanDisposition.PRESERVE_AMBIGUOUS, SongSyncPolicy.emptyScanDisposition(s, 5, 0))
        // everything fails the duration rule before the explicit stage -> evidence is 0
        val tooShort = listOf(song(9, "Music/Podcasts", durationMs = 500L))
        val strict = s.copy(minimumTrackDurationSeconds = 30)
        assertEquals(0, evaluate(tooShort, strict).eligibleBeforeExplicitExclusionsCount)
        assertEquals(EmptyScanDisposition.PRESERVE_AMBIGUOUS, SongSyncPolicy.emptyScanDisposition(strict, 5, 0))
        // a selected folder that matches nothing -> nothing eligible -> ambiguous
        val nowhere = s.copy(scanMode = LibraryScanMode.SELECTED_FOLDERS, selectedFolderUris = listOf("content://tree/primary:Nowhere"))
        assertEquals(0, evaluate(all, nowhere).eligibleBeforeExplicitExclusionsCount)
        assertEquals(EmptyScanDisposition.PRESERVE_AMBIGUOUS, SongSyncPolicy.emptyScanDisposition(nowhere, 5, 0))
        // WhatsApp-only library with voice notes hidden
        val voice = song(10, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/2024")
        assertEquals(0, evaluate(listOf(voice), s).eligibleBeforeExplicitExclusionsCount)
        // no explicit exclusion at all: eligible songs but empty cannot happen; evidence without any exclusion stays ambiguous
        assertEquals(EmptyScanDisposition.PRESERVE_AMBIGUOUS, SongSyncPolicy.emptyScanDisposition(settings(), 5, 3))
        // an empty table has nothing to preserve
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, SongSyncPolicy.emptyScanDisposition(s, 0, 0))
    }

    @Test fun `a custom exclusion that matches nothing in the library does not turn an ambiguous zero into a definitive one`() {
        val s = settings("Music/Nowhere")
        assertEquals(EmptyScanDisposition.PRESERVE_AMBIGUOUS, SongSyncPolicy.emptyScanDisposition(s, 5, 0))
    }
}

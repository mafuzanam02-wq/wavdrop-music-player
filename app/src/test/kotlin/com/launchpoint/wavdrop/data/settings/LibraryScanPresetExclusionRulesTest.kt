package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.EmptyScanDisposition
import com.launchpoint.wavdrop.data.repository.SongSyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SE-1: the preset exclusion model and its place in the ONE scan-eligibility rule
 * (duration AND WhatsApp AND preset exclusion AND scan mode). The pre-existing rule tests are in LibraryScanSettingsRulesTest and
 * are unchanged.
 */
class LibraryScanPresetExclusionRulesTest {

    private fun song(id: Long, folderPath: String?, durationMs: Long = 120_000L, folderName: String? = null) = Song(
        id = id, title = "Song $id", artist = "A", album = "B", albumId = 0L, duration = durationMs, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020, folderPath = folderPath, folderName = folderName,
    )

    private val music = song(1, "Music/Rock")
    private val downloads = song(2, "Download/Mixes")
    private val recordings = song(3, "Recordings/Voice")
    private val telegram = song(4, "Telegram/Telegram Audio")
    private val signal = song(5, "Android/media/org.thoughtcrime.securesms/Signal")
    private val messenger = song(6, "Android/media/com.facebook.orca/audio")
    private val all = listOf(music, downloads, recordings, telegram, signal, messenger)

    private fun settings(
        vararg excluded: LibraryScanExclusion,
        mode: LibraryScanMode = LibraryScanMode.WHOLE_DEVICE,
        folders: List<String> = emptyList(),
        minSeconds: Int = 1,
        whatsApp: Boolean = false,
    ) = LibraryScanSettings(
        scanMode = mode, selectedFolderUris = folders, minimumTrackDurationSeconds = minSeconds,
        includeWhatsAppVoiceNotes = whatsApp, excludedPresetFolders = excluded.toSet(),
    )

    private fun ids(songs: List<Song>) = songs.map { it.id }
    private fun filter(s: LibraryScanSettings) = ids(LibraryScanSettingsRules.filterSongsForScanSettings(all, s))

    // ── model ───────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the default exclusion set is empty and the library is unchanged`() {
        assertEquals(emptySet<LibraryScanExclusion>(), LibraryScanSettings().excludedPresetFolders)
        assertEquals(emptySet<LibraryScanExclusion>(), LibraryScanSettingsRules.normalize(LibraryScanSettings()).excludedPresetFolders)
        assertEquals("nothing is excluded on upgrade", listOf(1L, 2L, 3L, 4L, 5L, 6L), filter(settings()))
    }

    @Test fun `exactly the five approved presets exist and the names are the persisted form`() {
        assertEquals(listOf("TELEGRAM", "SIGNAL", "MESSENGER", "DOWNLOADS", "RECORDINGS"), LibraryScanExclusion.entries.map { it.name })
    }

    @Test fun `one exclusion can be enabled and disabled without touching the others`() {
        val base = LibraryScanSettings(scanMode = LibraryScanMode.SELECTED_FOLDERS, selectedFolderUris = listOf("content://m"), minimumTrackDurationSeconds = 17, includeWhatsAppVoiceNotes = true)
        val on = LibraryScanSettingsRules.withPresetExclusion(base, LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(setOf(LibraryScanExclusion.DOWNLOADS), on.excludedPresetFolders)
        assertEquals("every other setting preserved", base, on.copy(excludedPresetFolders = emptySet()))
        val both = LibraryScanSettingsRules.withPresetExclusion(on, LibraryScanExclusion.TELEGRAM, true)
        assertEquals(setOf(LibraryScanExclusion.TELEGRAM, LibraryScanExclusion.DOWNLOADS), both.excludedPresetFolders)
        val off = LibraryScanSettingsRules.withPresetExclusion(both, LibraryScanExclusion.DOWNLOADS, false)
        assertEquals(setOf(LibraryScanExclusion.TELEGRAM), off.excludedPresetFolders)
        assertEquals(emptySet<LibraryScanExclusion>(), LibraryScanSettingsRules.withPresetExclusion(off, LibraryScanExclusion.TELEGRAM, false).excludedPresetFolders)
    }

    @Test fun `enabling twice keeps one entry and disabling something that is not enabled is a no-op`() {
        var s = LibraryScanSettings()
        repeat(3) { s = LibraryScanSettingsRules.withPresetExclusion(s, LibraryScanExclusion.SIGNAL, true) }
        assertEquals(setOf(LibraryScanExclusion.SIGNAL), s.excludedPresetFolders)
        assertEquals(1, s.excludedPresetFolders.size)
        assertEquals(s, LibraryScanSettingsRules.withPresetExclusion(s, LibraryScanExclusion.RECORDINGS, false))
    }

    @Test fun `normalization is deterministic - declaration order whatever the insertion order`() {
        val a = LibraryScanSettingsRules.normalize(LibraryScanSettings(excludedPresetFolders = linkedSetOf(LibraryScanExclusion.RECORDINGS, LibraryScanExclusion.TELEGRAM)))
        val b = LibraryScanSettingsRules.normalize(LibraryScanSettings(excludedPresetFolders = linkedSetOf(LibraryScanExclusion.TELEGRAM, LibraryScanExclusion.RECORDINGS)))
        assertEquals(listOf(LibraryScanExclusion.TELEGRAM, LibraryScanExclusion.RECORDINGS), a.excludedPresetFolders.toList())
        assertEquals(a.excludedPresetFolders.toList(), b.excludedPresetFolders.toList())
        assertEquals(a, b)
    }

    @Test fun `persisted names parse known values and ignore unknown future or blank text`() {
        assertEquals(emptySet<LibraryScanExclusion>(), LibraryScanSettingsRules.parsePresetExclusions(null))
        assertEquals(emptySet<LibraryScanExclusion>(), LibraryScanSettingsRules.parsePresetExclusions(emptySet()))
        assertEquals(
            setOf(LibraryScanExclusion.DOWNLOADS, LibraryScanExclusion.SIGNAL),
            LibraryScanSettingsRules.parsePresetExclusions(listOf("SIGNAL", "FUTURE_APP", "", "downloads", "DOWNLOADS", "DOWNLOADS ")),
        )
        assertEquals("names are exact: case and spacing variants are unknown", emptySet<LibraryScanExclusion>(), LibraryScanSettingsRules.parsePresetExclusions(listOf("signal", " SIGNAL")))
    }

    // ── filtering across scan modes ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun `whole device with one exclusion removes only that category`() {
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), filter(settings(LibraryScanExclusion.DOWNLOADS)))
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L), filter(settings(LibraryScanExclusion.RECORDINGS)))
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L), filter(settings(LibraryScanExclusion.TELEGRAM)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), filter(settings(LibraryScanExclusion.SIGNAL)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), filter(settings(LibraryScanExclusion.MESSENGER)))
    }

    @Test fun `whole device with all five keeps only ordinary music`() {
        assertEquals(listOf(1L), filter(settings(*LibraryScanExclusion.entries.toTypedArray())))
    }

    @Test fun `ordinary look-alike folders survive every exclusion`() {
        val lookAlikes = listOf(
            song(10, "Music/My Downloads"), song(11, "Music/Live Recordings"), song(12, "Music/Telegram Tribute"),
            song(13, "Music/Signal Fire"), song(14, "Music/The Messengers"), song(15, "Music/Messenger Band"),
        )
        val kept = LibraryScanSettingsRules.filterSongsForScanSettings(lookAlikes, settings(*LibraryScanExclusion.entries.toTypedArray()))
        assertEquals(lookAlikes.map { it.id }, ids(kept))
    }

    @Test fun `selected folders - a matching preset exclusion wins over the explicit selection`() {
        val s = settings(LibraryScanExclusion.DOWNLOADS, mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://tree/primary:Download"))
        assertTrue("selected and NOT excluded -> allowed", LibraryScanSettingsRules.isSongAllowedByScanSettings(downloads, s.copy(excludedPresetFolders = emptySet())))
        assertFalse("selected AND excluded -> the exclusion wins", LibraryScanSettingsRules.isSongAllowedByScanSettings(downloads, s))
        assertEquals(emptyList<Long>(), filter(s))
    }

    @Test fun `selected folders - an unrelated exclusion changes nothing`() {
        val selected = listOf("content://tree/primary:Download", "content://tree/primary:Music")
        val s = settings(LibraryScanExclusion.TELEGRAM, mode = LibraryScanMode.SELECTED_FOLDERS, folders = selected)
        assertEquals(listOf(1L, 2L), filter(s))
        assertEquals(filter(s.copy(excludedPresetFolders = emptySet())), filter(s))
    }

    @Test fun `selected folders - a folder outside the selection stays out whether or not it is excluded`() {
        val s = settings(mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://tree/primary:Music"))
        assertEquals(listOf(1L), filter(s))
    }

    @Test fun `minimum duration is independent of exclusions`() {
        val short = song(20, "Music/Rock", durationMs = 10_000L)
        val shortDownload = song(21, "Download", durationMs = 10_000L)
        val s = settings(LibraryScanExclusion.RECORDINGS, minSeconds = 30)
        assertEquals(emptyList<Long>(), ids(LibraryScanSettingsRules.filterSongsForScanSettings(listOf(short, shortDownload), s)))
        val s2 = settings(LibraryScanExclusion.DOWNLOADS, minSeconds = 5)
        assertEquals(listOf(20L), ids(LibraryScanSettingsRules.filterSongsForScanSettings(listOf(short, shortDownload), s2)))
        assertEquals("the threshold is not changed by exclusions", 30_000L, LibraryScanSettingsRules.minimumDurationMs(s))
    }

    @Test fun `the whatsapp rule and the new exclusions apply together without replacing each other`() {
        val voice = song(30, "WhatsApp/Media/WhatsApp Voice Notes")
        val songs = listOf(voice, downloads, music)
        // default: voice notes excluded, downloads included
        assertEquals(listOf(2L, 1L), ids(LibraryScanSettingsRules.filterSongsForScanSettings(songs, settings())))
        // include voice notes AND exclude downloads
        assertEquals(listOf(30L, 1L), ids(LibraryScanSettingsRules.filterSongsForScanSettings(songs, settings(LibraryScanExclusion.DOWNLOADS, whatsApp = true))))
        // excluding a preset never re-includes voice notes
        assertEquals(listOf(1L), ids(LibraryScanSettingsRules.filterSongsForScanSettings(songs, settings(LibraryScanExclusion.DOWNLOADS))))
        assertFalse("the WhatsApp setting default is unchanged", LibraryScanSettings().includeWhatsAppVoiceNotes)
    }

    @Test fun `isExcludedByPreset is false for everything while the set is empty`() {
        all.forEach { assertFalse(LibraryScanSettingsRules.isExcludedByPreset(it, LibraryScanSettings())) }
        assertTrue(LibraryScanSettingsRules.isExcludedByPreset(downloads, settings(LibraryScanExclusion.DOWNLOADS)))
        assertFalse(LibraryScanSettingsRules.isExcludedByPreset(music, settings(*LibraryScanExclusion.entries.toTypedArray())))
    }

    // ── sync lifecycle (scan eligibility only; history is not touched here) ─────────────────────────────────────────────

    @Test fun `enabling an exclusion makes the song stale for sync and turning it off brings it back through the normal scan`() {
        val currentIds = all.map { it.id }.toSet()
        val withExclusion = ids(LibraryScanSettingsRules.filterSongsForScanSettings(all, settings(LibraryScanExclusion.DOWNLOADS))).toSet()
        assertEquals("only the excluded song disappears from the live library", setOf(2L), SongSyncPolicy.computeStaleIds(currentIds, withExclusion))
        val afterReenable = ids(LibraryScanSettingsRules.filterSongsForScanSettings(all, settings())).toSet()
        assertEquals("nothing is stale after re-enabling; the song is found again by the ordinary scan", emptySet<Long>(), SongSyncPolicy.computeStaleIds(withExclusion + 2L, afterReenable))
        assertTrue(2L in afterReenable)
    }

    @Test fun `the ambiguous-empty reason keeps its original wording and never mentions exclusions`() {
        val s = settings(LibraryScanExclusion.DOWNLOADS)
        assertTrue(SongSyncPolicy.shouldPreserveOnEmptyScan(s, existingSongCount = 10))
        assertEquals(SongSyncPolicy.emptyPreservedReason(settings()), SongSyncPolicy.emptyPreservedReason(s))
        assertFalse(SongSyncPolicy.emptyPreservedReason(s).contains("exclu"))
    }

    // ── staged evaluation: the pre-preset count is AFTER duration/WhatsApp/scan mode and BEFORE the preset exclusion ─────

    private fun evaluate(songs: List<Song>, s: LibraryScanSettings) = LibraryScanSettingsRules.evaluateScanSettings(songs, s)

    @Test fun `with no exclusions the evidence equals the final list`() {
        val e = evaluate(all, settings())
        assertEquals(all.size, e.eligibleBeforePresetExclusionsCount)
        assertEquals(all, e.songs)
    }

    @Test fun `the preset stage removes songs but leaves the pre-preset count untouched`() {
        val e = evaluate(all, settings(LibraryScanExclusion.DOWNLOADS))
        assertEquals(6, e.eligibleBeforePresetExclusionsCount)
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), ids(e.songs))
    }

    @Test fun `the count is taken after the minimum duration`() {
        val short = song(10, "Download/x", durationMs = 5_000L)
        val e = evaluate(listOf(short, song(11, "Download/y", durationMs = 20_000L)), settings(LibraryScanExclusion.DOWNLOADS, minSeconds = 60))
        assertEquals("both are under 60s, so nothing was eligible before the preset stage", 0, e.eligibleBeforePresetExclusionsCount)
        assertTrue(e.songs.isEmpty())
        val e2 = evaluate(listOf(short, downloads), settings(LibraryScanExclusion.DOWNLOADS, minSeconds = 10))
        assertEquals("only the long song counted", 1, e2.eligibleBeforePresetExclusionsCount)
    }

    @Test fun `the count is taken after the whatsapp rule`() {
        val voice = song(20, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/2024")
        val e = evaluate(listOf(voice), settings(LibraryScanExclusion.DOWNLOADS))
        assertEquals(0, e.eligibleBeforePresetExclusionsCount)
        assertEquals(1, evaluate(listOf(voice), settings(LibraryScanExclusion.DOWNLOADS, whatsApp = true)).eligibleBeforePresetExclusionsCount)
    }

    @Test fun `the count is taken after the scan mode`() {
        val sel = settings(LibraryScanExclusion.DOWNLOADS, mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://tree/primary:Music"))
        val e = evaluate(listOf(downloads, music), sel)
        assertEquals("the Download song is outside the selected folder, so it never counted", 1, e.eligibleBeforePresetExclusionsCount)
        assertEquals(listOf(1L), ids(e.songs))
    }

    @Test fun `a selected folder that the exclusion covers leaves evidence above zero and a final zero`() {
        val sel = settings(LibraryScanExclusion.DOWNLOADS, mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://tree/primary:Download"))
        val e = evaluate(listOf(downloads), sel)
        assertEquals(1, e.eligibleBeforePresetExclusionsCount)
        assertTrue(e.songs.isEmpty())
    }

    @Test fun `a selected folder that matches nothing has zero evidence`() {
        val sel = settings(LibraryScanExclusion.DOWNLOADS, mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://tree/primary:Nowhere"))
        val e = evaluate(all, sel)
        assertEquals(0, e.eligibleBeforePresetExclusionsCount)
        assertTrue(e.songs.isEmpty())
    }

    @Test fun `the two stages compose to exactly the public rule for every song and setting`() {
        val combos = listOf(
            settings(), settings(LibraryScanExclusion.DOWNLOADS), settings(*LibraryScanExclusion.entries.toTypedArray()),
            settings(LibraryScanExclusion.DOWNLOADS, whatsApp = true, minSeconds = 130),
            settings(LibraryScanExclusion.DOWNLOADS, mode = LibraryScanMode.SELECTED_FOLDERS, folders = listOf("content://tree/primary:Download")),
        )
        for (s in combos) for (song in all) {
            val staged = LibraryScanSettingsRules.isSongAllowedBeforePresetExclusions(song, s) && !LibraryScanSettingsRules.isExcludedByPreset(song, s)
            assertEquals(staged, LibraryScanSettingsRules.isSongAllowedByScanSettings(song, s))
        }
    }

    // ── empty-scan disposition (pure) ───────────────────────────────────────────────────────────────────────────────────

    private fun disposition(s: LibraryScanSettings, existing: Int, eligible: Int) =
        SongSyncPolicy.emptyScanDisposition(s, existing, eligible)

    @Test fun `explicit exclusion emptied an existing library is definitive`() {
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, disposition(settings(LibraryScanExclusion.DOWNLOADS), existing = 100, eligible = 100))
    }

    @Test fun `zero evidence stays ambiguous even with exclusions enabled`() {
        assertEquals(EmptyScanDisposition.PRESERVE_AMBIGUOUS, disposition(settings(LibraryScanExclusion.DOWNLOADS), existing = 100, eligible = 0))
    }

    @Test fun `evidence without any enabled exclusion is never definitive`() {
        assertEquals(EmptyScanDisposition.PRESERVE_AMBIGUOUS, disposition(settings(), existing = 100, eligible = 5))
    }

    @Test fun `an already empty library has nothing to preserve`() {
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, disposition(settings(), existing = 0, eligible = 0))
        assertEquals(EmptyScanDisposition.APPLY_DEFINITIVE_EMPTY, disposition(settings(LibraryScanExclusion.DOWNLOADS), existing = 0, eligible = 3))
    }
}

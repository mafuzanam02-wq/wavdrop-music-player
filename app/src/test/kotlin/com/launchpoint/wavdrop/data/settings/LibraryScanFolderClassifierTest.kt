package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.LibraryScanExclusion.DOWNLOADS
import com.launchpoint.wavdrop.data.settings.LibraryScanExclusion.MESSENGER
import com.launchpoint.wavdrop.data.settings.LibraryScanExclusion.RECORDINGS
import com.launchpoint.wavdrop.data.settings.LibraryScanExclusion.SIGNAL
import com.launchpoint.wavdrop.data.settings.LibraryScanExclusion.TELEGRAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SE-1: the exact, documented path-matching rules. Classification is by whole path SEGMENT after a shared-storage prefix is
 * removed, never by substring, and only for the structural roots the policy names.
 */
class LibraryScanFolderClassifierTest {

    private fun song(folderPath: String?, folderName: String? = null) = Song(
        id = 1L, title = "T", artist = "A", album = "B", albumId = 0L, duration = 60_000L, uri = "content://media/1",
        dateAdded = 0L, trackNumber = 0, year = 2020, folderPath = folderPath, folderName = folderName,
    )

    private fun classify(path: String?) = LibraryScanFolderClassifier.classify(song(path))
    private fun assertMatches(expected: LibraryScanExclusion, vararg paths: String) =
        paths.forEach { assertEquals("path `$it`", expected, classify(it)) }
    private fun assertOrdinary(vararg paths: String?) =
        paths.forEach { assertNull("path `$it` must be ordinary music", classify(it)) }

    // ── DOWNLOADS ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `downloads matches the shared storage Download root and its descendants`() {
        assertMatches(
            DOWNLOADS,
            "Download", "Download/", "Downloads", "Downloads/", "Download/song.mp3", "Downloads/song.mp3",
            "Download/Mixes", "Downloads/Audio", "Download/Mixes/2024/Live",
            "storage/emulated/0/Download", "storage/emulated/0/Downloads", "storage/emulated/0/Download/Music/song.mp3",
            "/storage/emulated/0/Download", "/storage/emulated/10/Download/Audio",
            "/sdcard/Download", "sdcard/Download/x", "/mnt/sdcard/Download", "/storage/self/primary/Download/",
            "/storage/1A2B-3C4D/Download", "storage/1a2b-3c4d/Downloads/Audio",
        )
    }

    @Test fun `downloads does not match nested user folders that merely contain the word`() {
        assertOrdinary(
            "Music/Downloads Collection", "Music/My Downloads", "Downloadable Music", "Music/Download", "Music/Downloads",
            "Music/Download/Mixes", "My Download", "Downloaded", "Podcasts/Download", "Movies/Download",
            "storage/emulated/0/Music/Download", "Music/Downloads/Download",
        )
    }

    // ── RECORDINGS ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `recordings matches only the supported structural recording roots`() {
        assertMatches(
            RECORDINGS,
            "Recordings", "Recordings/", "Recordings/Voice Recorder", "recordings/call", "Recorder", "Recorder/", "Recorder/2024",
            "storage/emulated/0/Recordings", "/storage/emulated/0/Recorder/Notes", "/sdcard/Recordings/x",
        )
    }

    @Test fun `recordings does not match music folders that merely contain the word`() {
        assertOrdinary(
            "Music/Live Recordings", "Music/Recordings Deluxe", "Music/Recordings", "Music/Recorder", "Recordings Deluxe",
            "Live Recordings", "My Recorder", "Recorders", "Music/Studio Recordings/2020", "Voice Recorder", "Sound Recorder",
        )
    }

    // ── TELEGRAM ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `telegram matches the legacy root and the Android app media roots`() {
        assertMatches(
            TELEGRAM,
            "Telegram", "Telegram/", "Telegram/Telegram Audio", "Telegram/Telegram Voice/", "telegram/telegram audio",
            "storage/emulated/0/Telegram/Telegram Audio",
            "Android/media/org.telegram.messenger/Telegram/Telegram Audio", "Android/media/org.telegram.messenger/Telegram/Telegram Voice/",
            "Android/data/org.telegram.messenger/files/Telegram/Telegram Audio",
            "/storage/emulated/0/Android/media/org.telegram.messenger.web/Telegram/Telegram Audio",
            "Android/media/org.thunderdog.challegram/Telegram X",
        )
    }

    @Test fun `telegram does not match music folders that merely contain the word`() {
        assertOrdinary(
            "Music/Telegram Tribute", "Music/My Telegram Songs", "Music/Telegram", "Telegram Tribute", "My Telegram",
            "Android/media/org.telegram.fake/Telegram", "Android/media/com.example.telegram/x", "Android/obb/org.telegram.messenger/x",
        )
    }

    // ── SIGNAL ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `signal matches the Signal application media roots`() {
        assertMatches(
            SIGNAL,
            "Android/media/org.thoughtcrime.securesms", "Android/media/org.thoughtcrime.securesms/Signal/Signal Audio",
            "Android/data/org.thoughtcrime.securesms/files/Signal", "/storage/emulated/0/Android/media/org.thoughtcrime.securesms/x",
            "ANDROID\\MEDIA\\ORG.THOUGHTCRIME.SECURESMS\\Signal",
        )
    }

    @Test fun `signal does not match music folders that merely contain the word`() {
        assertOrdinary(
            "Music/Signal Fire", "Music/Signal", "Signal", "Signal Fire", "Music/Signal/Audio", "Android/media/org.thoughtcrime.securesmsX/a",
            "Android/media/Signal/a",
        )
    }

    // ── MESSENGER ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `messenger matches the Messenger application media roots`() {
        assertMatches(
            MESSENGER,
            "Android/media/com.facebook.orca", "Android/media/com.facebook.orca/Messenger/Audio", "Android/data/com.facebook.orca/files/audio_clips",
            "storage/emulated/0/Android/media/com.facebook.mlite/x",
        )
    }

    @Test fun `messenger does not match music folders that merely contain the word`() {
        assertOrdinary(
            "Music/The Messengers", "Music/Messenger Band", "Messenger", "Music/Messenger", "Messenger Band", "Android/media/com.facebook.katana/x",
            "Android/media/com.facebook.orcaX/x",
        )
    }

    // ── normalization shapes ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `mixed case backslashes trailing slashes and redundant separators are tolerated`() {
        assertMatches(DOWNLOADS, "DOWNLOAD", "dOwNlOaDs\\Audio\\", "storage\\emulated\\0\\Download\\Mixes", "//Download//Mixes//", " Download ")
        assertMatches(TELEGRAM, "TELEGRAM\\Telegram Audio\\", "android/MEDIA/ORG.TELEGRAM.MESSENGER/Telegram")
    }

    @Test fun `url encoded paths are decoded before matching`() {
        assertMatches(DOWNLOADS, "storage%2Femulated%2F0%2FDownload%2FMixes", "Download%2FMixes")
        assertMatches(TELEGRAM, "Android%2Fmedia%2Forg.telegram.messenger%2FTelegram%2FTelegram%20Audio")
        assertOrdinary("Music%2FMy%20Downloads", "Music%2FTelegram%20Tribute")
        assertOrdinary("100%", "%ZZ/Music") // malformed encoding never throws
    }

    @Test fun `null blank and folder-name-only songs are ordinary music`() {
        assertOrdinary(null, "", "   ", "/", "\\")
        // A name alone carries no structure (it could be any nested user folder), so it is never classified.
        for (name in listOf("Download", "Downloads", "Telegram", "Recordings", "Messenger", "Signal")) {
            assertNull(name, LibraryScanFolderClassifier.classify(song(folderPath = null, folderName = name)))
        }
    }

    @Test fun `the storage prefix is removed only in its supported shapes`() {
        assertOrdinary("storage/emulated/x/Download", "storage/Download", "data/Download", "mnt/Download", "emulated/0/Download", "storage/0/Download")
    }

    @Test fun `categories are mutually exclusive and one path maps to at most one category`() {
        val paths = listOf("Download/a", "Recordings/a", "Telegram/a", "Android/media/org.thoughtcrime.securesms/a", "Android/media/com.facebook.orca/a")
        for (p in paths) {
            val matched = LibraryScanExclusion.entries.filter { LibraryScanFolderClassifier.matches(it, song(p)) }
            assertEquals("path $p matched $matched", 1, matched.size)
            assertEquals(matched.single(), classify(p))
        }
    }

    @Test fun `every preset has at least one positive structural example so none is dead`() {
        val examples = mapOf(
            TELEGRAM to "Telegram/Telegram Audio", SIGNAL to "Android/media/org.thoughtcrime.securesms/x", MESSENGER to "Android/media/com.facebook.orca/x",
            DOWNLOADS to "Download", RECORDINGS to "Recordings",
        )
        assertEquals(LibraryScanExclusion.entries.toSet(), examples.keys)
        examples.forEach { (e, p) -> assertEquals(e, classify(p)) }
    }
}

package com.launchpoint.wavdrop.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanException
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanResult
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanner
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.LibraryScanExclusion
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRules
import com.launchpoint.wavdrop.ui.scan.LibraryScanUiState
import com.launchpoint.wavdrop.ui.scan.toScanUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SE-1 correction: the REAL [SongRepository.sync] over real Room, with a scanner that feeds a raw MediaStore-like list through the
 * REAL scan rules (so the pre-preset evidence is computed by production code, not hand-written). Proves a zero result caused only
 * by the explicit preset exclusion is applied (Success(0)), while every ambiguous empty still preserves the library (WB-02).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongRepositoryPresetExclusionSyncRoomTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: WavdropDatabase
    private lateinit var settingsRepo: LibraryScanSettingsRepository
    private lateinit var repo: SongRepository

    /** What "MediaStore" currently holds, before any scan rule. Null makes the scan fail. */
    private var device: List<Song>? = emptyList()

    private inner class FakeScanner : MediaStoreScanner(RuntimeEnvironment.getApplication()) {
        override fun scanSongs(settings: LibraryScanSettings): MediaStoreScanResult {
            val raw = device ?: throw MediaStoreScanException(SecurityException("permission revoked"))
            val e = LibraryScanSettingsRules.evaluateScanSettings(raw, settings)
            return MediaStoreScanResult(e.songs, e.eligibleBeforeExplicitExclusionsCount)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
        settingsRepo = LibraryScanSettingsRepository(
            PreferenceDataStoreFactory.create(produceFile = { temporaryFolder.newFile("scan-${System.nanoTime()}.preferences_pb") }),
        )
        repo = SongRepository(db, db.songDao(), db.playlistDao(), FakeScanner(), settingsRepo, db.trackIdentityDao())
    }

    @After
    fun tearDown() = db.close()

    private fun song(id: Long, folder: String) = Song(
        id = id, title = "Song $id", artist = "Artist $id", album = "Album", albumId = 1L, duration = 180_000L,
        uri = "content://media/external/audio/media/$id", dateAdded = 1_700_000_000L + id, trackNumber = 1, year = 2020,
        folderPath = folder, folderName = folder.substringAfterLast('/'),
    )

    private val d1 = song(1, "Download/Mixes")
    private val d2 = song(2, "Download")
    private val m1 = song(3, "Music/Rock")

    private fun liveIds() = runBlocking { db.songDao().getAllSongsSnapshot().map { it.id }.sorted() }

    private fun seed(songs: List<Song>) = runBlocking {
        device = songs
        assertTrue(repo.sync() is LibrarySyncResult.Success)
    }

    // ── A. whole device, only Downloads songs ───────────────────────────────────────────────────────────────────────────

    @Test fun `A - exclusion off keeps the songs, Exclude Downloads empties the library through Success(0)`() = runBlocking {
        seed(listOf(d1, d2))
        assertEquals(listOf(1L, 2L), liveIds())

        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        val result = repo.sync()

        assertEquals(LibrarySyncResult.Success(0), result)
        assertEquals(emptyList<Long>(), liveIds())
        assertEquals("rendered as ordinary completion, not a warning", LibraryScanUiState.Complete, result.toScanUiState())
    }

    // ── B. mixed library ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `B - mixed library keeps Music and drops Downloads`() = runBlocking {
        seed(listOf(d1, d2, m1))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(LibrarySyncResult.Success(1), repo.sync())
        assertEquals(listOf(3L), liveIds())
    }

    // ── C. selected folder conflict ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun `C - selected folder equal to an excluded folder is definitively emptied`() = runBlocking {
        settingsRepo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        settingsRepo.addSelectedFolderUri("content://com.android.externalstorage.documents/tree/primary:Download")
        seed(listOf(d1, d2, m1))
        assertEquals(listOf(1L, 2L), liveIds())

        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(LibrarySyncResult.Success(0), repo.sync())
        assertEquals(emptyList<Long>(), liveIds())
    }

    // ── D. selected folder genuinely matches nothing ────────────────────────────────────────────────────────────────────

    @Test fun `D - a selected folder that matches nothing preserves the library even with an exclusion on`() = runBlocking {
        seed(listOf(d1, m1))
        settingsRepo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        settingsRepo.addSelectedFolderUri("content://com.android.externalstorage.documents/tree/primary:Nowhere")
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)

        val result = repo.sync()

        assertTrue(result is LibrarySyncResult.EmptyPreserved)
        assertEquals(listOf(1L, 3L), liveIds())
        assertTrue(result.toScanUiState() is LibraryScanUiState.Warning)
    }

    // ── E. WhatsApp-only library ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `E - a whatsapp-only library with voice notes hidden is still preserved, exclusions do not make it definitive`() = runBlocking {
        val voice = song(9, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/2024")
        // seed while voice notes are included, then hide them and exclude Downloads
        settingsRepo.setIncludeWhatsAppVoiceNotes(true)
        seed(listOf(voice))
        settingsRepo.setIncludeWhatsAppVoiceNotes(false)
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)

        val result = repo.sync()

        assertTrue(result is LibrarySyncResult.EmptyPreserved)
        assertEquals(listOf(9L), liveIds())
    }

    // ── F. MediaStore failure ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `F - a MediaStore failure preserves the library even when exclusions are on`() = runBlocking {
        seed(listOf(d1, m1))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        device = null

        val result = repo.sync()

        assertTrue(result is LibrarySyncResult.Failed)
        assertEquals(listOf(1L, 3L), liveIds())
        assertTrue(result.toScanUiState() is LibraryScanUiState.Error)
    }

    @Test fun `an empty device with exclusions on and nothing eligible still preserves`() = runBlocking {
        seed(listOf(d1, m1))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        device = emptyList()
        assertTrue(repo.sync() is LibrarySyncResult.EmptyPreserved)
        assertEquals(listOf(1L, 3L), liveIds())
    }

    // ── G. table already empty ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `G - an already empty table with a definitive empty is Success(0) with no problem`() = runBlocking {
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        device = listOf(d1, d2)
        assertEquals(LibrarySyncResult.Success(0), repo.sync())
        assertEquals(emptyList<Long>(), liveIds())
    }

    // ── H. history and identity ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `H - a definitive empty deletes no stats, history or identity rows and only clears currentSongId`() = runBlocking {
        seed(listOf(d1, d2))
        db.trackStatsDao().insertIfAbsent(TrackStatsEntity(songId = 1L, contentUri = "u1", playCount = 7))
        db.trackListenEventDao().insert(
            TrackListenEventEntity(songId = 1L, eventType = "PLAY", occurredAt = 5L, listenedMs = 1L, durationMs = 2L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK),
        )
        val before = db.trackIdentityDao().getAllSnapshot()
        assertEquals(2, before.size)
        assertTrue(before.all { it.currentSongId != null })

        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(LibrarySyncResult.Success(0), repo.sync())

        assertEquals(7, db.trackStatsDao().getAllStatsSnapshot().first { it.songId == 1L }.playCount)
        assertEquals(1, db.trackListenEventDao().getAllSnapshot().size)
        val after = db.trackIdentityDao().getAllSnapshot()
        assertEquals("identity rows retained", before.map { it.identityUuid }.toSet(), after.map { it.identityUuid }.toSet())
        assertTrue("bindings cleared by the ordinary reconciliation", after.all { it.currentSongId == null })
        assertNull(db.trackIdentityDao().getByCurrentSongId(1L).singleOrNull())
    }

    // ── I. disable and rescan ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `I - disabling the exclusion and rescanning brings the songs back through the ordinary scan`() = runBlocking {
        seed(listOf(d1, d2, m1))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(LibrarySyncResult.Success(1), repo.sync())
        assertEquals(listOf(3L), liveIds())

        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, false)
        assertEquals(LibrarySyncResult.Success(3), repo.sync())
        assertEquals(listOf(1L, 2L, 3L), liveIds())
    }

    @Test fun `emptying then restoring after a definitive empty works from an empty table`() = runBlocking {
        seed(listOf(d1))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(LibrarySyncResult.Success(0), repo.sync())
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, false)
        assertEquals(LibrarySyncResult.Success(1), repo.sync())
        assertEquals(listOf(1L), liveIds())
    }
}

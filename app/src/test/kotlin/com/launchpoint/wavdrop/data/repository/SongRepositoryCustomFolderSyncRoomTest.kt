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
import kotlinx.coroutines.flow.first
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
 * CFE-1: the REAL [SongRepository.sync] over real Room, fed by a scanner that runs the raw MediaStore-like list through the
 * REAL scan rules. Proves custom folder exclusions remove only live song rows, keep stats / listen events / identity rows,
 * give a legitimate empty library only when explicit exclusions caused it, never weaken preserve-on-empty (WB-02), and are
 * restored by the ordinary scan when removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongRepositoryCustomFolderSyncRoomTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: WavdropDatabase
    private lateinit var settingsRepo: LibraryScanSettingsRepository
    private lateinit var repo: SongRepository

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

    private val rock = song(1, "Music/Rock")
    private val podcast = song(2, "Music/Podcasts")
    private val season = song(3, "Music/Podcasts/Season 1")
    private val archive = song(4, "Music/Podcasts Archive")
    private val download = song(5, "Download/Mixes")

    private fun liveIds() = runBlocking { db.songDao().getAllSongsSnapshot().map { it.id }.sorted() }

    private fun seed(songs: List<Song>) = runBlocking {
        device = songs
        assertTrue(repo.sync() is LibrarySyncResult.Success)
    }

    @Test fun `a custom exclusion removes that folder and its descendants on the next scan and keeps look-alikes`() = runBlocking {
        seed(listOf(rock, podcast, season, archive))
        assertEquals(listOf(1L, 2L, 3L, 4L), liveIds())

        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        assertEquals("nothing changes until a rescan", listOf(1L, 2L, 3L, 4L), liveIds())

        assertEquals(LibrarySyncResult.Success(2), repo.sync())
        assertEquals(listOf(1L, 4L), liveIds())
    }

    @Test fun `custom-only exclusion that removes every eligible song is a definitive Success(0)`() = runBlocking {
        seed(listOf(podcast, season))
        settingsRepo.addCustomFolderExclusion("Music/Podcasts")

        val result = repo.sync()

        assertEquals(LibrarySyncResult.Success(0), result)
        assertEquals(emptyList<Long>(), liveIds())
        assertEquals(LibraryScanUiState.Complete, result.toScanUiState())
    }

    @Test fun `presets plus custom folders that together empty the library are a definitive Success(0)`() = runBlocking {
        seed(listOf(download, podcast))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        assertEquals(LibrarySyncResult.Success(0), repo.sync())
        assertEquals(emptyList<Long>(), liveIds())
    }

    @Test fun `selected folder equal to the custom exclusion is a definitive empty and the selected folder is kept`() = runBlocking {
        settingsRepo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        settingsRepo.addSelectedFolderUri("content://com.android.externalstorage.documents/tree/primary:Music/Podcasts")
        seed(listOf(podcast, season, rock))
        assertEquals(listOf(2L, 3L), liveIds())

        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        assertEquals(LibrarySyncResult.Success(0), repo.sync())
        assertEquals(emptyList<Long>(), liveIds())
        assertEquals("the selected folder is never silently edited", listOf("content://com.android.externalstorage.documents/tree/primary:Music/Podcasts"), settingsRepo.settings.first().selectedFolderUris)
    }

    @Test fun `selected parent folder with a custom exclusion inside keeps the rest`() = runBlocking {
        settingsRepo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        settingsRepo.addSelectedFolderUri("content://com.android.externalstorage.documents/tree/primary:Music")
        seed(listOf(rock, podcast, season, archive, download))
        assertEquals(listOf(1L, 2L, 3L, 4L), liveIds())

        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        assertEquals(LibrarySyncResult.Success(2), repo.sync())
        assertEquals(listOf(1L, 4L), liveIds())
    }

    @Test fun `ambiguous empties stay preserved with a custom exclusion active`() = runBlocking {
        seed(listOf(rock, podcast))
        settingsRepo.addCustomFolderExclusion("Music/Podcasts")

        device = emptyList() // MediaStore returned nothing
        assertTrue(repo.sync() is LibrarySyncResult.EmptyPreserved)
        assertEquals(listOf(1L, 2L), liveIds())

        device = listOf(rock, podcast)
        settingsRepo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        settingsRepo.addSelectedFolderUri("content://com.android.externalstorage.documents/tree/primary:Nowhere")
        assertTrue("selected folder matching nothing", repo.sync() is LibrarySyncResult.EmptyPreserved)
        assertEquals(listOf(1L, 2L), liveIds())
    }

    @Test fun `a MediaStore failure preserves the library with a custom exclusion active`() = runBlocking {
        seed(listOf(rock, podcast))
        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        device = null

        val result = repo.sync()

        assertTrue(result is LibrarySyncResult.Failed)
        assertEquals(listOf(1L, 2L), liveIds())
        assertTrue(result.toScanUiState() is LibraryScanUiState.Error)
    }

    @Test fun `stats, listen events and identity rows survive a custom exclusion while the binding clears`() = runBlocking {
        seed(listOf(rock, podcast, season))
        db.trackStatsDao().insertIfAbsent(TrackStatsEntity(songId = 2L, contentUri = "u2", playCount = 7))
        db.trackListenEventDao().insert(
            TrackListenEventEntity(songId = 2L, eventType = "PLAY", occurredAt = 5L, listenedMs = 1L, durationMs = 2L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK),
        )
        val before = db.trackIdentityDao().getAllSnapshot()
        assertEquals(3, before.size)

        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        assertEquals(LibrarySyncResult.Success(1), repo.sync())

        assertEquals(listOf(1L), liveIds())
        assertEquals("stats kept", 7, db.trackStatsDao().getAllStatsSnapshot().first { it.songId == 2L }.playCount)
        assertEquals("listen events kept", 1, db.trackListenEventDao().getAllSnapshot().size)
        val after = db.trackIdentityDao().getAllSnapshot()
        assertEquals("identity rows retained", before.map { it.identityUuid }.toSet(), after.map { it.identityUuid }.toSet())
        assertEquals("only the excluded songs lose their binding", 1, after.count { it.currentSongId != null })
        assertNull(db.trackIdentityDao().getByCurrentSongId(2L).singleOrNull())
        assertEquals(1, db.trackIdentityDao().getByCurrentSongId(1L).size)
    }

    @Test fun `literal folder names are honoured by the real sync - C++ is excluded and C is kept, A%2FB is not A slash B`() = runBlocking {
        val cpp = song(11, "Music/C++")
        val cppLive = song(12, "Music/C++/Live")
        val c = song(13, "Music/C")
        val literal = song(14, "Music/A%2FB")
        val nested = song(15, "Music/A/B")
        seed(listOf(cpp, cppLive, c, literal, nested))

        settingsRepo.addCustomFolderExclusion("Music/C++")
        settingsRepo.addCustomFolderExclusion("Music/A%2FB")
        assertEquals(LibrarySyncResult.Success(2), repo.sync())
        assertEquals(listOf(13L, 15L), liveIds())
    }

    @Test fun `removing the exclusion and scanning again restores the songs through the ordinary sync`() = runBlocking {
        seed(listOf(rock, podcast, season))
        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        assertEquals(LibrarySyncResult.Success(1), repo.sync())
        assertEquals(listOf(1L), liveIds())

        settingsRepo.removeCustomFolderExclusion("Music/Podcasts")
        assertEquals("settings only: nothing is restored before a scan", listOf(1L), liveIds())

        assertEquals(LibrarySyncResult.Success(3), repo.sync())
        assertEquals(listOf(1L, 2L, 3L), liveIds())
    }

    @Test fun `restoring from a definitively empty library works through the ordinary scan`() = runBlocking {
        seed(listOf(podcast))
        settingsRepo.addCustomFolderExclusion("Music/Podcasts")
        assertEquals(LibrarySyncResult.Success(0), repo.sync())
        settingsRepo.removeCustomFolderExclusion("Music/Podcasts")
        assertEquals(LibrarySyncResult.Success(1), repo.sync())
        assertEquals(listOf(2L), liveIds())
    }

    @Test fun `removing the custom exclusion keeps the overlapping preset exclusion in force`() = runBlocking {
        val nested = song(6, "Download/My Music")
        seed(listOf(rock, download, nested))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        settingsRepo.addCustomFolderExclusion("Download/My Music")
        assertEquals(LibrarySyncResult.Success(1), repo.sync())

        settingsRepo.removeCustomFolderExclusion("Download/My Music")
        assertEquals("the Downloads preset still hides both Download songs", LibrarySyncResult.Success(1), repo.sync())
        assertEquals(listOf(1L), liveIds())
    }

    @Test fun `users who never use custom exclusions see identical SE-1 behaviour`() = runBlocking {
        seed(listOf(rock, podcast, download))
        settingsRepo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(LibrarySyncResult.Success(2), repo.sync())
        assertEquals(listOf(1L, 2L), liveIds())
    }
}

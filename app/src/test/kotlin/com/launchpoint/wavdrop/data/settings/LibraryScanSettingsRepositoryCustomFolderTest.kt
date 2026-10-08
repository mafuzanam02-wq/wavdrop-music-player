package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.launchpoint.wavdrop.data.library.FolderGrouper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CFE-1: persistence of the custom folder exclusions through the real DataStore-backed repository. */
class LibraryScanSettingsRepositoryCustomFolderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = stringSetPreferencesKey("library_custom_excluded_folder_paths")

    private fun dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { temporaryFolder.newFile("custom-${System.nanoTime()}.preferences_pb") })

    @Test fun `1 - a fresh install and an upgrade from SE-1 have zero custom exclusions and unchanged settings`() = runBlocking {
        val store = dataStore()
        // an SE-1 era store: presets and a selected folder, no CFE-1 key at all
        store.edit {
            it[stringSetPreferencesKey("library_excluded_preset_folders")] = setOf("DOWNLOADS")
            it[stringSetPreferencesKey("library_selected_folder_uris")] = setOf("content://tree/primary:Music")
        }
        val settings = LibraryScanSettingsRepository(store).settings.first()
        assertEquals(emptySet<String>(), settings.customExcludedFolderPaths)
        assertEquals(setOf(LibraryScanExclusion.DOWNLOADS), settings.excludedPresetFolders)
        assertEquals(listOf("content://tree/primary:Music"), settings.selectedFolderUris)
        assertEquals(emptySet<String>(), LibraryScanSettingsRepository(dataStore()).settings.first().customExcludedFolderPaths)
    }

    @Test fun `2 - a round trip stores canonical paths under the dedicated key and a second repository sees them`() = runBlocking {
        val store = dataStore()
        val repo = LibraryScanSettingsRepository(store)
        assertTrue(repo.addCustomFolderExclusion("/storage/emulated/0/Music\\Podcasts/"))
        assertTrue(repo.addCustomFolderExclusion("Music/DJ Sets"))
        assertEquals(setOf("Music/Podcasts", "Music/DJ Sets"), store.data.first()[key])
        assertEquals(listOf("Music/DJ Sets", "Music/Podcasts"), LibraryScanSettingsRepository(store).settings.first().customExcludedFolderPaths.toList())
    }

    @Test fun `3 - duplicate adds in different spellings keep one entry`() = runBlocking {
        val repo = LibraryScanSettingsRepository(dataStore())
        repo.addCustomFolderExclusion("Music/Podcasts")
        repo.addCustomFolderExclusion("music/podcasts/")
        repo.addCustomFolderExclusion("MUSIC\\PODCASTS")
        assertEquals(1, repo.settings.first().customExcludedFolderPaths.size)
    }

    @Test fun `4 - adding one exclusion preserves presets and every other setting`() = runBlocking {
        val repo = LibraryScanSettingsRepository(dataStore())
        repo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        repo.addSelectedFolderUri("content://tree/primary:Music")
        repo.setMinimumTrackDurationSeconds(25)
        repo.setIncludeWhatsAppVoiceNotes(true)
        repo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        val before = repo.settings.first()

        repo.addCustomFolderExclusion("Music/Podcasts")

        val after = repo.settings.first()
        assertEquals(setOf("Music/Podcasts"), after.customExcludedFolderPaths)
        assertEquals(before, after.copy(customExcludedFolderPaths = emptySet()))
    }

    @Test fun `5 - removing one preserves the other exclusions and settings, and the last removal drops the key`() = runBlocking {
        val store = dataStore()
        val repo = LibraryScanSettingsRepository(store)
        repo.setPresetExclusion(LibraryScanExclusion.TELEGRAM, true)
        repo.addCustomFolderExclusion("Music/Podcasts")
        repo.addCustomFolderExclusion("Music/Live")

        repo.removeCustomFolderExclusion("MUSIC/podcasts/")
        assertEquals(setOf("Music/Live"), repo.settings.first().customExcludedFolderPaths)
        assertEquals(setOf(LibraryScanExclusion.TELEGRAM), repo.settings.first().excludedPresetFolders)

        repo.removeCustomFolderExclusion("Music/Live")
        assertEquals(emptySet<String>(), repo.settings.first().customExcludedFolderPaths)
        assertNull(store.data.first()[key])
        repo.removeCustomFolderExclusion("Music/Absent")
        assertEquals("removing an absent folder is harmless", emptySet<String>(), repo.settings.first().customExcludedFolderPaths)
    }

    @Test fun `custom exclusions and presets never overwrite each other`() = runBlocking {
        val repo = LibraryScanSettingsRepository(dataStore())
        repo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        repo.addCustomFolderExclusion("Download/My Music")
        repo.removeCustomFolderExclusion("Download/My Music")
        assertEquals(setOf(LibraryScanExclusion.DOWNLOADS), repo.settings.first().excludedPresetFolders)
        repo.addCustomFolderExclusion("Download/My Music")
        repo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, false)
        assertEquals(setOf("Download/My Music"), repo.settings.first().customExcludedFolderPaths)
    }

    @Test fun `12 - Unknown Folder and blank paths cannot be persisted through the product seam`() = runBlocking {
        val store = dataStore()
        val repo = LibraryScanSettingsRepository(store)
        for (bad in listOf(FolderGrouper.UNKNOWN_FOLDER, "", "   ", "/", "\\", "/storage/emulated/0")) {
            assertFalse("'$bad' must be rejected", repo.addCustomFolderExclusion(bad))
        }
        assertNull("nothing was written", store.data.first()[key])
        assertEquals(emptySet<String>(), repo.settings.first().customExcludedFolderPaths)
    }

    @Test fun `unknown, blank and malformed persisted values are ignored and duplicates collapse on read`() = runBlocking {
        val store = dataStore()
        store.edit { it[key] = setOf("", "  ", "/", FolderGrouper.UNKNOWN_FOLDER, "Music/Podcasts", "music/podcasts", "/storage/emulated/0/Music/Podcasts/") }
        val read = LibraryScanSettingsRepository(store).settings.first().customExcludedFolderPaths
        assertEquals(1, read.size)
        assertEquals("music/podcasts", read.single().lowercase())
    }

    @Test fun `literal plus and percent folder names round-trip unchanged through DataStore`() = runBlocking {
        val store = dataStore()
        val repo = LibraryScanSettingsRepository(store)
        assertTrue(repo.addCustomFolderExclusion("Music/C++"))
        assertTrue(repo.addCustomFolderExclusion("/storage/emulated/0/Music/A%2FB/"))
        assertEquals(setOf("Music/C++", "Music/A%2FB"), store.data.first()[key])
        assertEquals(setOf("Music/A%2FB", "Music/C++"), LibraryScanSettingsRepository(store).settings.first().customExcludedFolderPaths)
        repo.removeCustomFolderExclusion("music/c++")
        assertEquals(setOf("Music/A%2FB"), repo.settings.first().customExcludedFolderPaths)
    }

    @Test fun `custom exclusions are device-local scan configuration in their own key`() = runBlocking {
        val store = dataStore()
        LibraryScanSettingsRepository(store).addCustomFolderExclusion("Music/Podcasts")
        val names = store.data.first().asMap().keys.map { it.name }
        assertTrue("library_custom_excluded_folder_paths" in names)
        assertFalse("the preset key is not overloaded", "library_excluded_preset_folders" in names)
    }
}

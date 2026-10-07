package com.launchpoint.wavdrop.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

/** SE-1: persistence of the preset exclusions through the real DataStore-backed repository. */
class LibraryScanSettingsRepositoryPresetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = stringSetPreferencesKey("library_excluded_preset_folders")

    private fun dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { temporaryFolder.newFile("scan-${System.nanoTime()}.preferences_pb") })

    @Test fun `a fresh install has no exclusions and the unchanged defaults`() = runBlocking {
        val settings = LibraryScanSettingsRepository(dataStore()).settings.first()
        assertEquals(emptySet<LibraryScanExclusion>(), settings.excludedPresetFolders)
        assertEquals(LibraryScanMode.WHOLE_DEVICE, settings.scanMode)
        assertEquals(false, settings.includeWhatsAppVoiceNotes)
        assertEquals(LibraryScanSettingsRules.DEFAULT_MINIMUM_TRACK_DURATION_SECONDS, settings.minimumTrackDurationSeconds)
    }

    @Test fun `one exclusion is saved and read back`() = runBlocking {
        val repo = LibraryScanSettingsRepository(dataStore())
        repo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(setOf(LibraryScanExclusion.DOWNLOADS), repo.settings.first().excludedPresetFolders)
    }

    @Test fun `several exclusions are saved as stable enum names and a second repository sees them`() = runBlocking {
        val store = dataStore()
        LibraryScanSettingsRepository(store).apply {
            setPresetExclusion(LibraryScanExclusion.RECORDINGS, true)
            setPresetExclusion(LibraryScanExclusion.TELEGRAM, true)
            setPresetExclusion(LibraryScanExclusion.RECORDINGS, true) // duplicate enable
        }
        assertEquals(setOf("RECORDINGS", "TELEGRAM"), store.data.first()[key])
        // reconstruction: a brand-new repository over the same persisted data
        val reread = LibraryScanSettingsRepository(store).settings.first()
        assertEquals(setOf(LibraryScanExclusion.TELEGRAM, LibraryScanExclusion.RECORDINGS), reread.excludedPresetFolders)
        assertEquals(listOf(LibraryScanExclusion.TELEGRAM, LibraryScanExclusion.RECORDINGS), reread.excludedPresetFolders.toList())
    }

    @Test fun `disabling one leaves the others and disabling the last removes the stored key`() = runBlocking {
        val store = dataStore()
        val repo = LibraryScanSettingsRepository(store)
        LibraryScanExclusion.entries.forEach { repo.setPresetExclusion(it, true) }
        assertEquals(LibraryScanExclusion.entries.toSet(), repo.settings.first().excludedPresetFolders)
        repo.setPresetExclusion(LibraryScanExclusion.SIGNAL, false)
        assertEquals(LibraryScanExclusion.entries.toSet() - LibraryScanExclusion.SIGNAL, repo.settings.first().excludedPresetFolders)
        LibraryScanExclusion.entries.forEach { repo.setPresetExclusion(it, false) }
        assertEquals(emptySet<LibraryScanExclusion>(), repo.settings.first().excludedPresetFolders)
        assertNull(store.data.first()[key])
    }

    @Test fun `unknown or future stored values are ignored and dropped on the next write`() = runBlocking {
        val store = dataStore()
        store.edit { it[key] = setOf("DOWNLOADS", "WECHAT_FUTURE", "", "downloads") }
        val repo = LibraryScanSettingsRepository(store)
        assertEquals(setOf(LibraryScanExclusion.DOWNLOADS), repo.settings.first().excludedPresetFolders)
        repo.setPresetExclusion(LibraryScanExclusion.SIGNAL, true)
        assertEquals(setOf("DOWNLOADS", "SIGNAL"), store.data.first()[key])
    }

    @Test fun `a stored set of only unknown values reads as empty`() = runBlocking {
        val store = dataStore()
        store.edit { it[key] = setOf("NOT_A_PRESET") }
        assertEquals(emptySet<LibraryScanExclusion>(), LibraryScanSettingsRepository(store).settings.first().excludedPresetFolders)
    }

    @Test fun `toggling an exclusion never overwrites the other scan settings`() = runBlocking {
        val repo = LibraryScanSettingsRepository(dataStore())
        repo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        repo.setSelectedFolderUris(listOf("content://tree/primary:Music", "content://tree/primary:Download"))
        repo.setMinimumTrackDurationSeconds(17)
        repo.setIncludeWhatsAppVoiceNotes(true)
        val before = repo.settings.first()

        repo.setPresetExclusion(LibraryScanExclusion.MESSENGER, true)
        repo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        repo.setPresetExclusion(LibraryScanExclusion.MESSENGER, false)

        val after = repo.settings.first()
        assertEquals(before.scanMode, after.scanMode)
        assertEquals(before.selectedFolderUris, after.selectedFolderUris)
        assertEquals(before.minimumTrackDurationSeconds, after.minimumTrackDurationSeconds)
        assertEquals(before.includeWhatsAppVoiceNotes, after.includeWhatsAppVoiceNotes)
        assertEquals(setOf(LibraryScanExclusion.DOWNLOADS), after.excludedPresetFolders)
    }

    @Test fun `the other setters never disturb the exclusions`() = runBlocking {
        val repo = LibraryScanSettingsRepository(dataStore())
        repo.setPresetExclusion(LibraryScanExclusion.TELEGRAM, true)
        repo.setScanMode(LibraryScanMode.SELECTED_FOLDERS)
        repo.addSelectedFolderUri("content://tree/primary:Music")
        repo.removeSelectedFolderUri("content://tree/primary:Music")
        repo.setMinimumTrackDurationSeconds(5)
        repo.setIncludeWhatsAppVoiceNotes(true)
        assertEquals(setOf(LibraryScanExclusion.TELEGRAM), repo.settings.first().excludedPresetFolders)
    }

    @Test fun `concurrent toggles do not lose each other`() = runBlocking {
        val repo = LibraryScanSettingsRepository(dataStore())
        kotlinx.coroutines.coroutineScope {
            LibraryScanExclusion.entries.map { e -> launch { repo.setPresetExclusion(e, true) } }
        }
        assertEquals(LibraryScanExclusion.entries.toSet(), repo.settings.first().excludedPresetFolders)
    }

    @Test fun `the whatsapp key is independent of the exclusion key`() = runBlocking {
        val store = dataStore()
        val repo = LibraryScanSettingsRepository(store)
        repo.setIncludeWhatsAppVoiceNotes(false)
        repo.setPresetExclusion(LibraryScanExclusion.DOWNLOADS, true)
        assertEquals(false, repo.settings.first().includeWhatsAppVoiceNotes)
        assertTrue(store.data.first().asMap().keys.map { it.name }.containsAll(listOf("library_include_whatsapp_voice_notes", "library_excluded_preset_folders")))
    }
}

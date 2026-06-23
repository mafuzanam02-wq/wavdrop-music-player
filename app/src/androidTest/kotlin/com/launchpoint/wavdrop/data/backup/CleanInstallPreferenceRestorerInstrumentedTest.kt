package com.launchpoint.wavdrop.data.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.launchpoint.wavdrop.data.settings.AccentColor
import com.launchpoint.wavdrop.data.settings.AppIconAliasManager
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.data.settings.ThemeMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanInstallPreferenceRestorerInstrumentedTest {
    private lateinit var file: File
    private lateinit var appSettings: AppSettingsRepository
    private lateinit var homeSettings: HomeLayoutSettingsRepository
    private lateinit var scanSettings: LibraryScanSettingsRepository
    private lateinit var restorer: CleanInstallPreferenceRestorer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "recovery-${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create { file }
        appSettings = AppSettingsRepository(dataStore)
        homeSettings = HomeLayoutSettingsRepository(dataStore)
        scanSettings = LibraryScanSettingsRepository(dataStore)
        restorer = CleanInstallPreferenceRestorer(
            appSettings = appSettings,
            homeSettings = homeSettings,
            scanSettings = scanSettings,
            resumeSettings = ResumeBehaviorSettingsRepository(dataStore),
            iconManager = AppIconAliasManager(context),
        )
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun portablePreferencesRestoreAndRemainAfterDefaultReads() = runBlocking {
        val preferences = basePreferences().copy(
            themeMode = ThemeMode.DARK.name,
            accentColor = AccentColor.DEEP_TEAL.name,
            compactMode = true,
            homeVisibleSections = listOf(HomeSectionId.RECENTLY_PLAYED.name),
        )

        val first = restorer.restore(preferences)
        val second = restorer.restore(preferences)

        assertTrue(first.restored)
        assertTrue(second.restored)
        assertEquals(ThemeMode.DARK, appSettings.themeMode.first())
        assertEquals(AccentColor.DEEP_TEAL, appSettings.accentColor.first())
        assertTrue(appSettings.compactMode.first())
        assertEquals(
            setOf(HomeSectionId.RECENTLY_PLAYED, HomeSectionId.MOST_PLAYED),
            homeSettings.settings.first().visibleSections,
        )
    }

    @Test
    fun selectedFolderUrisAreDiscardedAndReselectionFlagIsSet() = runBlocking {
        val result = restorer.restore(
            basePreferences().copy(
                scanMode = LibraryScanMode.SELECTED_FOLDERS.name,
                selectedFolderUris = listOf("content://old-install/tree/Music"),
            ),
        )

        val settings = scanSettings.settings.first()
        assertTrue(result.needsFolderReselection)
        assertEquals(LibraryScanMode.SELECTED_FOLDERS, settings.scanMode)
        assertTrue(settings.selectedFolderUris.isEmpty())
        assertTrue(appSettings.needsFolderReselectionAfterRestore.first())
    }

    @Test
    fun autoBackupDestinationIsNotReplacedByRecovery() = runBlocking {
        appSettings.setAutoBackupFolderUri("content://this-device/tree/Backups")

        restorer.restore(basePreferences().copy(autoBackupInterval = "DAILY"))

        assertEquals(
            "content://this-device/tree/Backups",
            appSettings.autoBackupFolderUri.first(),
        )
        assertFalse(appSettings.needsAutoBackupFolderSelectionAfterRestore.first())
    }

    private fun basePreferences() = BackupPreferences(
        startupDestination = null,
        mostPlayedPeriod = null,
        mostPlayedLimit = null,
        homeVisibleSections = null,
        scanMode = null,
        selectedFolderUris = null,
        minimumTrackDurationSeconds = null,
    )
}

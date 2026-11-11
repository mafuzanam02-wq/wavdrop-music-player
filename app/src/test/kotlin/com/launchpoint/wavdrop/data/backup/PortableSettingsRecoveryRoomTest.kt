package com.launchpoint.wavdrop.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.settings.AppIconAliasManager
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.HomeLayoutSettingsRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.data.settings.PreviousButtonBehavior
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettingsRepository
import com.launchpoint.wavdrop.playback.CrossfadeRules
import com.launchpoint.wavdrop.playback.TransportFadeRules
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-AUDIT-08. Real repository export, real DataStore, real restorer / Recovery applier / Merge repository.
 *
 * Semantics under test: a CURRENT backup always carries all four settings (defaults included), so Recovery on an existing install
 * resets them exactly; a LEGACY backup (field absent) must leave the device's newer values alone; Merge never applies settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PortableSettingsRecoveryRoomTest {

    private class Device(val file: File, val store: DataStore<Preferences>) {
        val app = AppSettingsRepository(store)
        val home = HomeLayoutSettingsRepository(store)
        val scan = LibraryScanSettingsRepository(store)
        val resume = ResumeBehaviorSettingsRepository(store)
    }

    private class NoopGateway : AutoBackupWorkManagerGateway {
        override fun enqueueUniquePeriodicWork(uniqueWorkName: String, existingWorkPolicy: androidx.work.ExistingPeriodicWorkPolicy, request: androidx.work.PeriodicWorkRequest) = Unit
        override fun cancelUniqueWork(uniqueWorkName: String) = Unit
    }

    private val files = mutableListOf<File>()
    private lateinit var db: WavdropDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close(); files.forEach { it.delete() } }

    private fun device(): Device {
        val f = File(RuntimeEnvironment.getApplication().cacheDir, "p5a08-${UUID.randomUUID()}.preferences_pb").also { files += it }
        return Device(f, PreferenceDataStoreFactory.create { f })
    }

    private fun restorer(d: Device) = CleanInstallPreferenceRestorer(
        d.app, d.home, d.scan, d.resume, AppIconAliasManager(RuntimeEnvironment.getApplication()),
    )

    private fun applier(d: Device) = RecoveryPreferenceApplierImpl(
        restorer(d), AutoBackupWorkScheduler(d.app, NoopGateway()),
    )

    private fun exporter(d: Device) = WavdropBackupRepository(
        RuntimeEnvironment.getApplication(), d.store, db.songDao(), db.trackStatsDao(), db.importBaselineDao(), db.lyricsOverrideDao(),
        db.playlistDao(), db.trackListenEventDao(), db.pendingBackupExtensionDao(), db.pendingTrackDao(), d.app, d.home, d.scan, d.resume,
    )

    private suspend fun setNonDefault(d: Device) {
        d.home.setHomeSmartCollections(listOf(SmartCollectionType.LONG_TRACKS, SmartCollectionType.FAVORITES, SmartCollectionType.MOST_PLAYED))
        d.app.setCrossfadeDurationMs(6000)
        d.app.setTransportFadeEnabled(false)
        d.app.setTransportFadeDurationMs(650)
        d.app.setPreviousButtonBehavior(PreviousButtonBehavior.PREVIOUS_TRACK)
    }

    private data class Snapshot(
        val home: List<SmartCollectionType>, val crossfade: Long, val fadeEnabled: Boolean, val fadeMs: Long, val previous: PreviousButtonBehavior,
    )

    private suspend fun snap(d: Device) = Snapshot(
        d.home.settings.first().homeSmartCollections, d.app.crossfadeDurationMs.first(), d.app.transportFadeEnabled.first(),
        d.app.transportFadeDurationMs.first(), d.app.previousButtonBehavior.first(),
    )

    private val defaults = Snapshot(
        listOf(SmartCollectionType.ALWAYS_FINISH, SmartCollectionType.FORGOTTEN_GEMS, SmartCollectionType.USUALLY_ABANDON),
        CrossfadeRules.OFF_MS, TransportFadeRules.DEFAULT_ENABLED, TransportFadeRules.DEFAULT_DURATION_MS, PreviousButtonBehavior.DEFAULT,
    )

    private fun exportedPrefs(d: Device): BackupPreferences = runBlocking {
        val r = WavdropBackupParser.parse(exporter(d).buildBackupJson())
        assertNull(r.error, r.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, r.integrityStatus)
        requireNotNull(r.backup?.preferences)
    }

    // ── Export from the real repository ───────────────────────────────────────

    @Test fun `real export carries all four non-default settings`() = runBlocking {
        val a = device(); setNonDefault(a)
        val p = exportedPrefs(a)
        assertEquals(listOf("LONG_TRACKS", "FAVORITES", "MOST_PLAYED"), p.homeSmartCollections)
        assertEquals(6000, p.crossfadeDurationMs)
        assertEquals(false, p.transportFadeEnabled)
        assertEquals(650, p.transportFadeDurationMs)
        assertEquals("PREVIOUS_TRACK", p.previousButtonBehavior)
    }

    @Test fun `real export of default settings still writes them explicitly`() = runBlocking {
        val p = exportedPrefs(device())
        assertEquals(listOf("ALWAYS_FINISH", "FORGOTTEN_GEMS", "USUALLY_ABANDON"), p.homeSmartCollections)
        assertEquals(0, p.crossfadeDurationMs)
        assertEquals(false, p.transportFadeEnabled)
        assertEquals(300, p.transportFadeDurationMs)
        assertEquals("RESTART_CURRENT", p.previousButtonBehavior)
    }

    @Test fun `real export keeps Home visible sections independent of the Smart Collection selection`() = runBlocking {
        val a = device(); setNonDefault(a)
        a.home.setVisibleSections(setOf(com.launchpoint.wavdrop.data.settings.HomeSectionId.entries.first()))
        val p = exportedPrefs(a)
        assertEquals(listOf(com.launchpoint.wavdrop.data.settings.HomeSectionId.entries.first().name), p.homeVisibleSections)
        assertEquals(listOf("LONG_TRACKS", "FAVORITES", "MOST_PLAYED"), p.homeSmartCollections)
    }

    // ── Recovery on an existing install ───────────────────────────────────────

    @Test fun `recovery restores all four settings onto a different device state`() = runBlocking {
        val source = device(); setNonDefault(source)
        val prefs = exportedPrefs(source)
        val target = device()
        target.home.setHomeSmartCollections(listOf(SmartCollectionType.NEVER_PLAYED, SmartCollectionType.RECENTLY_ADDED, SmartCollectionType.SHORT_TRACKS))
        target.app.setCrossfadeDurationMs(2000); target.app.setTransportFadeEnabled(true); target.app.setTransportFadeDurationMs(200)

        val result = applier(target).applyRecoveryPreferences(prefs)

        assertTrue(result is RecoveryPreferenceResult.Applied)
        assertEquals(snap(source), snap(target))
    }

    @Test fun `recovery of a current default backup resets non-default device values`() = runBlocking {
        val prefs = exportedPrefs(device()) // all four defaults, written explicitly
        val target = device(); setNonDefault(target)
        applier(target).applyRecoveryPreferences(prefs)
        assertEquals(defaults, snap(target))
    }

    @Test fun `recovery of a legacy backup leaves the device's newer settings untouched`() = runBlocking {
        val legacy = requireNotNull(WavdropBackupParser.parse(RecoveryTestFixtures.v2Json(
            RecoveryTestFixtures.v2Backup(preferences = BackupPreferences(
                startupDestination = "ALBUMS", mostPlayedPeriod = null, mostPlayedLimit = null, homeVisibleSections = null,
                scanMode = null, selectedFolderUris = null, minimumTrackDurationSeconds = null,
            )),
        )).backup?.preferences)
        assertNull(legacy.homeSmartCollections); assertNull(legacy.crossfadeDurationMs)
        val target = device(); setNonDefault(target)
        val before = snap(target)
        val result = applier(target).applyRecoveryPreferences(legacy)
        assertTrue(result is RecoveryPreferenceResult.Applied)
        assertEquals("a backup that predates the fields must not overwrite them with defaults", before, snap(target))
    }

    @Test fun `clean install recovery applies the settings`() = runBlocking {
        val source = device(); setNonDefault(source)
        val fresh = device()
        val r = restorer(fresh).restore(exportedPrefs(source))
        assertTrue(r.restored)
        assertEquals(snap(source), snap(fresh))
    }

    @Test fun `restore never turns an unsupported or invalid value into an invalid state`() = runBlocking {
        val target = device(); setNonDefault(target)
        val before = snap(target)
        restorer(target).restore(
            BackupPreferences(
                startupDestination = null, mostPlayedPeriod = null, mostPlayedLimit = null, homeVisibleSections = null,
                scanMode = null, selectedFolderUris = null, minimumTrackDurationSeconds = null,
                homeSmartCollections = listOf("BOGUS", "FAVORITES", "favorites", "FAVORITES", "NEVER_PLAYED"),
                crossfadeDurationMs = 99_999, transportFadeDurationMs = 5, previousButtonBehavior = "FUTURE_MODE",
            ),
            CleanInstallPreferenceRestorer.FolderPolicy.KEEP_DEVICE_FOLDERS,
        )
        val after = snap(target)
        assertEquals("unique, valid, exactly three, valid ids keep their order", 3, after.home.toSet().size)
        assertEquals(listOf(SmartCollectionType.FAVORITES, SmartCollectionType.NEVER_PLAYED), after.home.take(2))
        assertEquals(CrossfadeRules.MAX_DURATION_MS, after.crossfade)
        assertEquals(TransportFadeRules.MIN_DURATION_MS, after.fadeMs)
        assertEquals("an unrecognized enum value is ignored, not defaulted", before.previous, after.previous)
        assertEquals("fade enabled was absent so it is untouched", before.fadeEnabled, after.fadeEnabled)
    }

    @Test fun `duration normalization matches the existing rules`() = runBlocking {
        val t = device()
        suspend fun crossfade(raw: Int) = restorer(t).restore(BackupPreferences(startupDestination = null, mostPlayedPeriod = null, mostPlayedLimit = null, homeVisibleSections = null, scanMode = null, selectedFolderUris = null, minimumTrackDurationSeconds = null, crossfadeDurationMs = raw),
            CleanInstallPreferenceRestorer.FolderPolicy.KEEP_DEVICE_FOLDERS).let { t.app.crossfadeDurationMs.first() }
        assertEquals(CrossfadeRules.OFF_MS, crossfade(0))
        assertEquals(CrossfadeRules.OFF_MS, crossfade(-5))
        assertEquals(CrossfadeRules.MIN_ENABLED_DURATION_MS, crossfade(500))
        assertEquals(7000L, crossfade(7000))
    }

    // ── Merge ─────────────────────────────────────────────────────────────────

    @Test fun `merge imports the backup but never applies the four settings`() = runBlocking {
        val source = device(); setNonDefault(source)
        val prefs = exportedPrefs(source)
        val target = device()
        val before = snap(target)
        val merge = WavdropBackupImportRepository(
            db, db.songDao(), db.trackStatsDao(), db.lyricsOverrideDao(), db.importBaselineDao(), db.playlistDao(),
            db.trackListenEventDao(), db.pendingTrackDao(), db.pendingBackupExtensionDao(),
        )
        val result = merge.applyImport(RecoveryTestFixtures.v2Backup(preferences = prefs))
        assertTrue(result.preferencesSkipped)
        assertEquals(before, snap(target))
        assertEquals(defaults, snap(target))
    }
}

package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class AppSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val startupDestination: Flow<StartupDestination> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[STARTUP_DESTINATION_KEY]?.toStartupDestination()
                ?: StartupDestination.SONGS
        }

    suspend fun setStartupDestination(destination: StartupDestination) {
        dataStore.edit { preferences ->
            preferences[STARTUP_DESTINATION_KEY] = destination.name
        }
    }

    val mostPlayedPeriod: Flow<MostPlayedPeriod> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[MOST_PLAYED_PERIOD_KEY]?.toMostPlayedPeriod()
                ?: MostPlayedPeriod.ALL_TIME
        }

    val mostPlayedDisplayLimit: Flow<MostPlayedDisplayLimit> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[MOST_PLAYED_LIMIT_KEY]?.toMostPlayedDisplayLimit()
                ?: MostPlayedDisplayLimit.TOP_25
        }

    suspend fun setMostPlayedPeriod(period: MostPlayedPeriod) {
        dataStore.edit { preferences ->
            preferences[MOST_PLAYED_PERIOD_KEY] = period.name
        }
    }

    suspend fun setMostPlayedDisplayLimit(limit: MostPlayedDisplayLimit) {
        dataStore.edit { preferences ->
            preferences[MOST_PLAYED_LIMIT_KEY] = limit.name
        }
    }

    val appIconChoice: Flow<AppIconChoice> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[APP_ICON_CHOICE_KEY]?.toAppIconChoice()
                ?: AppIconChoice.MIDNIGHT_VIOLET
        }

    suspend fun setAppIconChoice(choice: AppIconChoice) {
        dataStore.edit { preferences ->
            preferences[APP_ICON_CHOICE_KEY] = choice.name
        }
    }

    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[THEME_MODE_KEY]?.toThemeMode() ?: ThemeMode.SYSTEM
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode.name
        }
    }

    val accentColor: Flow<AccentColor> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[ACCENT_COLOR_KEY]?.toAccentColor() ?: AccentColor.MIDNIGHT_VIOLET
        }

    suspend fun setAccentColor(color: AccentColor) {
        dataStore.edit { preferences ->
            preferences[ACCENT_COLOR_KEY] = color.name
        }
    }

    val compactMode: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[COMPACT_MODE_KEY] ?: false }

    suspend fun setCompactMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[COMPACT_MODE_KEY] = enabled
        }
    }

    val hasCompletedOnboarding: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[HAS_COMPLETED_ONBOARDING_KEY] ?: false }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAS_COMPLETED_ONBOARDING_KEY] = completed
        }
    }

    // Versioned onboarding completion. Migration: if new key is absent but the old boolean
    // is true, return 1 so existing users are not shown onboarding again.
    val lastCompletedOnboardingVersion: Flow<Int> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[LAST_COMPLETED_ONBOARDING_VERSION_KEY]
                ?: if (preferences[HAS_COMPLETED_ONBOARDING_KEY] == true) 1 else 0
        }

    suspend fun setLastCompletedOnboardingVersion(version: Int) {
        dataStore.edit { preferences ->
            preferences[LAST_COMPLETED_ONBOARDING_VERSION_KEY] = version
            // Keep old boolean in sync so changelog gating (which reads the boolean) still works.
            if (version > 0) preferences[HAS_COMPLETED_ONBOARDING_KEY] = true
        }
    }

    val lastSeenChangelogVersion: Flow<Int> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[LAST_SEEN_CHANGELOG_VERSION_KEY] ?: 0 }

    suspend fun setLastSeenChangelogVersion(versionCode: Int) {
        dataStore.edit { preferences ->
            preferences[LAST_SEEN_CHANGELOG_VERSION_KEY] = versionCode
        }
    }

    // Persisted SAF tree URI for auto-backup. Null means no folder has been selected yet.
    val autoBackupFolderUri: Flow<String?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[AUTO_BACKUP_FOLDER_URI_KEY] }

    suspend fun setAutoBackupFolderUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri != null) preferences[AUTO_BACKUP_FOLDER_URI_KEY] = uri
            else preferences.remove(AUTO_BACKUP_FOLDER_URI_KEY)
        }
    }

    val autoBackupInterval: Flow<AutoBackupInterval> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[AUTO_BACKUP_INTERVAL_KEY]?.toAutoBackupInterval() ?: AutoBackupInterval.OFF
        }

    suspend fun setAutoBackupInterval(interval: AutoBackupInterval) {
        dataStore.edit { preferences ->
            preferences[AUTO_BACKUP_INTERVAL_KEY] = interval.name
        }
    }

    val lastAutoBackupAtMillis: Flow<Long> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[LAST_AUTO_BACKUP_AT_MILLIS_KEY] ?: 0L }

    suspend fun setLastAutoBackupAtMillis(millis: Long) {
        dataStore.edit { preferences ->
            preferences[LAST_AUTO_BACKUP_AT_MILLIS_KEY] = millis
        }
    }

    val backupFileMode: Flow<BackupFileMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[BACKUP_FILE_MODE_KEY]?.toBackupFileMode() ?: BackupFileMode.DATED
        }

    suspend fun setBackupFileMode(mode: BackupFileMode) {
        dataStore.edit { preferences ->
            preferences[BACKUP_FILE_MODE_KEY] = mode.name
        }
    }

    val notificationControlsSetting: Flow<NotificationControlsSetting> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[NOTIFICATION_CONTROLS_KEY]
                ?.let { runCatching { NotificationControlsSetting.valueOf(it) }.getOrNull() }
                ?: NotificationControlsSetting.STANDARD
        }

    suspend fun setNotificationControlsSetting(setting: NotificationControlsSetting) {
        dataStore.edit { preferences ->
            preferences[NOTIFICATION_CONTROLS_KEY] = setting.name
        }
    }

    private fun String.toAutoBackupInterval(): AutoBackupInterval? =
        runCatching { AutoBackupInterval.valueOf(this) }.getOrNull()

    private fun String.toBackupFileMode(): BackupFileMode? =
        runCatching { BackupFileMode.valueOf(this) }.getOrNull()

    private fun String.toStartupDestination(): StartupDestination? =
        runCatching { StartupDestination.valueOf(this) }.getOrNull()

    private fun String.toMostPlayedPeriod(): MostPlayedPeriod? =
        runCatching { MostPlayedPeriod.valueOf(this) }.getOrNull()

    private fun String.toMostPlayedDisplayLimit(): MostPlayedDisplayLimit? =
        runCatching { MostPlayedDisplayLimit.valueOf(this) }.getOrNull()

    private fun String.toAppIconChoice(): AppIconChoice? =
        runCatching { AppIconChoice.valueOf(this) }.getOrNull()

    private fun String.toThemeMode(): ThemeMode? =
        runCatching { ThemeMode.valueOf(this) }.getOrNull()

    private fun String.toAccentColor(): AccentColor? =
        runCatching { AccentColor.valueOf(this) }.getOrNull()

    private companion object {
        val STARTUP_DESTINATION_KEY = stringPreferencesKey("startup_destination")
        val MOST_PLAYED_PERIOD_KEY  = stringPreferencesKey("most_played_period")
        val MOST_PLAYED_LIMIT_KEY   = stringPreferencesKey("most_played_limit")
        val APP_ICON_CHOICE_KEY          = stringPreferencesKey("app_icon_choice")
        val THEME_MODE_KEY               = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR_KEY             = stringPreferencesKey("accent_color")
        val COMPACT_MODE_KEY             = booleanPreferencesKey("compact_mode")
        val HAS_COMPLETED_ONBOARDING_KEY            = booleanPreferencesKey("has_completed_onboarding")
        val LAST_COMPLETED_ONBOARDING_VERSION_KEY   = intPreferencesKey("last_completed_onboarding_version")
        val AUTO_BACKUP_FOLDER_URI_KEY              = stringPreferencesKey("auto_backup_folder_uri")
        val AUTO_BACKUP_INTERVAL_KEY                = stringPreferencesKey("auto_backup_interval")
        val LAST_AUTO_BACKUP_AT_MILLIS_KEY          = longPreferencesKey("last_auto_backup_at_millis")
        val BACKUP_FILE_MODE_KEY                    = stringPreferencesKey("backup_file_mode")
        val NOTIFICATION_CONTROLS_KEY               = stringPreferencesKey("notification_controls")
        val LAST_SEEN_CHANGELOG_VERSION_KEY         = intPreferencesKey("last_seen_changelog_version")
    }
}

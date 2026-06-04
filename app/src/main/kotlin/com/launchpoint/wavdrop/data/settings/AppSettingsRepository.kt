package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val APP_ICON_CHOICE_KEY     = stringPreferencesKey("app_icon_choice")
        val THEME_MODE_KEY          = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR_KEY        = stringPreferencesKey("accent_color")
        val COMPACT_MODE_KEY        = booleanPreferencesKey("compact_mode")
        val HAS_COMPLETED_ONBOARDING_KEY = booleanPreferencesKey("has_completed_onboarding")
    }
}

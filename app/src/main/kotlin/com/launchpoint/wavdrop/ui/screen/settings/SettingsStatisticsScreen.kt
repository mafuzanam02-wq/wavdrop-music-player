package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStatisticsScreen(
    onNavigateBack: () -> Unit,
    onStatisticsClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMonthlyReportsClick: () -> Unit,
    onWrappedClick: () -> Unit,
    showBackArrow: Boolean = true,
    onHomeClick: () -> Unit = {},
    onSongsClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onInsightsClick: () -> Unit = {},
    onNowPlayingClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val showMilestoneCelebrations by viewModel.showMilestoneCelebrations.collectAsStateWithLifecycle()
    val wrappedUseArtworkBackgrounds by viewModel.wrappedUseArtworkBackgrounds.collectAsStateWithLifecycle()
    val wrappedBackgroundIntensity by viewModel.wrappedBackgroundIntensity.collectAsStateWithLifecycle()
    val wrappedFallbackTheme by viewModel.wrappedFallbackTheme.collectAsStateWithLifecycle()
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports & Insights") },
                navigationIcon = {
                    if (showBackArrow) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(
                    nowPlaying                 = nowPlaying,
                    onOpenNowPlaying           = onNowPlayingClick,
                    onTogglePlayPause          = playbackVm::togglePlayPause,
                    onPrevious                 = playbackVm::skipToPrevious,
                    onNext                     = playbackVm::skipToNext,
                    onToggleShuffle            = playbackVm::toggleShuffle,
                    onCycleRepeatMode          = playbackVm::cycleRepeatMode,
                    applyNavigationBarsPadding = false,
                )
                PrimaryNavigationBar(
                    selected        = PrimaryDestination.INSIGHTS,
                    onHomeClick     = onHomeClick,
                    onSongsClick    = onSongsClick,
                    onLibraryClick  = onLibraryClick,
                    onInsightsClick = onInsightsClick,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item {
                ClickableSettingsRow(
                    title    = "Statistics Dashboard",
                    subtitle = "View listening totals, most played tracks, recent plays, and skips.",
                    onClick  = onStatisticsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Listening Reports",
                    subtitle = "See top songs, artists, albums, habits, and recent activity.",
                    onClick  = onReportsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Monthly Reports",
                    subtitle = "Browse listening activity grouped by calendar month.",
                    onClick  = onMonthlyReportsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Wrapped",
                    subtitle = "Review monthly and yearly listening recaps.",
                    onClick  = onWrappedClick,
                )
            }
            item { SectionDivider() }
            item { SectionHeader("Wrapped") }
            item {
                ToggleSettingsRow(
                    title           = "Show milestone celebrations",
                    subtitle        = "Display milestone summaries inside yearly Wrapped recaps.",
                    checked         = showMilestoneCelebrations,
                    onCheckedChange = viewModel::setShowMilestoneCelebrations,
                )
            }
            item {
                ToggleSettingsRow(
                    title           = "Use artwork backgrounds in Wrapped",
                    subtitle        = "Use your music artwork as atmospheric card backgrounds when available.",
                    checked         = wrappedUseArtworkBackgrounds,
                    onCheckedChange = viewModel::setWrappedUseArtworkBackgrounds,
                )
            }
            item { SectionHeader("Wrapped background intensity") }
            item {
                SettingsMessageRow(
                    message = "Control how bold Wrapped backgrounds feel.",
                )
            }
            WrappedBackgroundIntensity.entries.forEach { intensity ->
                item {
                    ScanModeRow(
                        title    = intensity.displayName,
                        subtitle = intensity.description,
                        selected = wrappedBackgroundIntensity == intensity,
                        onClick  = { viewModel.setWrappedBackgroundIntensity(intensity) },
                    )
                }
            }
            item { SectionHeader("Wrapped fallback theme") }
            item {
                SettingsMessageRow(
                    message = "Choose the visual mood used when artwork is unavailable.",
                )
            }
            WrappedFallbackTheme.entries.forEach { theme ->
                item {
                    ScanModeRow(
                        title    = theme.displayName,
                        subtitle = theme.description,
                        selected = wrappedFallbackTheme == theme,
                        onClick  = { viewModel.setWrappedFallbackTheme(theme) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

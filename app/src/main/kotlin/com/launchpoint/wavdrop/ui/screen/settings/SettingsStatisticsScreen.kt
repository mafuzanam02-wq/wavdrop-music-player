package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
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
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InsightsDestinationCard(
                        title    = "Statistics",
                        subtitle = "Plays, listening time, streaks",
                        icon     = Icons.Default.Insights,
                        onClick  = onStatisticsClick,
                        modifier = Modifier.weight(1f),
                    )
                    InsightsDestinationCard(
                        title    = "Reports",
                        subtitle = "Top songs, artists, albums",
                        icon     = Icons.Default.History,
                        onClick  = onReportsClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InsightsDestinationCard(
                        title    = "Monthly",
                        subtitle = "Month-by-month listening",
                        icon     = Icons.Default.DateRange,
                        onClick  = onMonthlyReportsClick,
                        modifier = Modifier.weight(1f),
                    )
                    InsightsDestinationCard(
                        title    = "Wrapped",
                        subtitle = "Monthly and yearly recaps",
                        icon     = Icons.Default.AutoAwesome,
                        onClick  = onWrappedClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun InsightsDestinationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .semantics { role = Role.Button }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(28.dp),
            )
            Text(
                text  = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}

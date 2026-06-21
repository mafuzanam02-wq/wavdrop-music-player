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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar
import com.launchpoint.wavdrop.ui.screen.settings.InsightsHubUiState.Content
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsFormatters
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
    onSmartCollectionClick: (SmartCollectionType) -> Unit = {},
    onHomeClick: () -> Unit = {},
    onSongsClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onInsightsClick: () -> Unit = {},
    onNowPlayingClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
    insightsVm: InsightsViewModel = hiltViewModel(),
) {
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val hubState   by insightsVm.uiState.collectAsStateWithLifecycle()

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
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector        = Icons.Default.Search,
                            contentDescription = "Search",
                        )
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

            // ── Hero Summary ──────────────────────────────────────────────────
            item {
                InsightsHubSummaryRow(hubState = hubState)
            }

            if (hubState is Content) {
                val content = hubState as Content

                item { Spacer(Modifier.height(24.dp)) }

                // ── Rediscover ────────────────────────────────────────────────
                item { InsightsSectionHeader("Rediscover") }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    RediscoverCard(
                        forgottenGemsCount  = content.forgottenGemsCount,
                        recentlyPlayedCount = content.recentlyPlayedCount,
                        neverPlayedCount    = content.neverPlayedCount,
                        onCollectionClick   = onSmartCollectionClick,
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                // ── This Month ────────────────────────────────────────────────
                item { InsightsSectionHeader("This Month") }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    InsightsThisMonthCard(
                        playCount     = content.thisMonthPlayCount,
                        listeningMs   = content.thisMonthListeningTimeMs,
                        topTrackTitle = content.thisMonthTopTrackTitle,
                        onViewMonthly = onMonthlyReportsClick,
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }

                // ── Listening Habits ──────────────────────────────────────────
                item { InsightsSectionHeader("Listening Habits") }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InsightsStatCard(
                            label    = "Top Day",
                            value    = StatisticsFormatters.formatDayOfWeekShort(content.mostActiveDayOfWeek),
                            icon     = Icons.Default.DateRange,
                            modifier = Modifier.weight(1f),
                        )
                        InsightsStatCard(
                            label    = "Top Hour",
                            value    = formatHour(content.mostActiveHour),
                            icon     = Icons.Default.Timer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            } else {
                item { Spacer(Modifier.height(16.dp)) }
            }

            // ── More Insights ─────────────────────────────────────────────────
            item { InsightsSectionHeader("More Insights") }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InsightsDestinationCard(
                        title    = "Statistics",
                        subtitle = "Totals, streaks, and listening history",
                        icon     = Icons.Default.Insights,
                        onClick  = onStatisticsClick,
                        modifier = Modifier.weight(1f),
                    )
                    InsightsDestinationCard(
                        title    = "Reports",
                        subtitle = "Ranked songs, artists, and albums",
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

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun InsightsSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

// ── Summary row ───────────────────────────────────────────────────────────────

@Composable
private fun InsightsHubSummaryRow(
    hubState: InsightsHubUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InsightsStatCard(
            label    = "Total Plays",
            value    = when (hubState) {
                is Content                 -> formatPlays(hubState.totalPlayCount)
                InsightsHubUiState.Empty   -> "0"
                InsightsHubUiState.Loading -> "—"
            },
            icon     = Icons.Default.MusicNote,
            modifier = Modifier.weight(1f),
        )
        InsightsStatCard(
            label    = "Listening",
            value    = when (hubState) {
                is Content                 -> StatisticsFormatters.formatDurationSummary(hubState.totalListeningTimeMs)
                InsightsHubUiState.Empty   -> "0m"
                InsightsHubUiState.Loading -> "—"
            },
            icon     = Icons.Default.Timer,
            modifier = Modifier.weight(1f),
        )
        InsightsStatCard(
            label    = "Streak",
            value    = when (hubState) {
                is Content                 -> StatisticsFormatters.formatStreakDays(hubState.currentStreakDays)
                InsightsHubUiState.Empty   -> "—"
                InsightsHubUiState.Loading -> "—"
            },
            icon     = Icons.Default.Star,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InsightsStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(20.dp),
            )
            Text(
                text     = value,
                style    = MaterialTheme.typography.titleMedium,
                color    = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Rediscover ────────────────────────────────────────────────────────────────

@Composable
private fun RediscoverCard(
    forgottenGemsCount: Int,
    recentlyPlayedCount: Int,
    neverPlayedCount: Int,
    onCollectionClick: (SmartCollectionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            InsightsCollectionRow(
                label    = "Forgotten Gems",
                subtitle = "Songs you used to love",
                icon     = Icons.Default.History,
                count    = forgottenGemsCount,
                onClick  = { onCollectionClick(SmartCollectionType.FORGOTTEN_GEMS) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InsightsCollectionRow(
                label    = "Recently Played",
                subtitle = "Your latest listening activity",
                icon     = Icons.Default.Schedule,
                count    = recentlyPlayedCount,
                onClick  = { onCollectionClick(SmartCollectionType.RECENTLY_PLAYED) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            InsightsCollectionRow(
                label    = "Never Played",
                subtitle = "Tracks waiting to be discovered",
                icon     = Icons.Default.MusicNote,
                count    = neverPlayedCount,
                onClick  = { onCollectionClick(SmartCollectionType.NEVER_PLAYED) },
            )
        }
    }
}

@Composable
private fun InsightsCollectionRow(
    label: String,
    subtitle: String,
    icon: ImageVector,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            text  = "$count songs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier           = Modifier.size(20.dp),
        )
    }
}

// ── This Month ────────────────────────────────────────────────────────────────

@Composable
private fun InsightsThisMonthCard(
    playCount: Int?,
    listeningMs: Long?,
    topTrackTitle: String?,
    onViewMonthly: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
            if (playCount != null && listeningMs != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = formatPlays(playCount),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text  = "Plays",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = StatisticsFormatters.formatDurationSummary(listeningMs),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text  = "Listening",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                if (topTrackTitle != null) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector        = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.primary,
                            modifier           = Modifier.size(16.dp),
                        )
                        Column {
                            Text(
                                text     = topTrackTitle,
                                style    = MaterialTheme.typography.bodyMedium,
                                color    = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text  = "Top track this month",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick  = onViewMonthly,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("View monthly report")
                }
            } else {
                Text(
                    text  = "Your listening activity for this month will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── Destination cards ─────────────────────────────────────────────────────────

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

// ── Local formatters ──────────────────────────────────────────────────────────

private fun formatPlays(count: Int): String = count.toString()

private fun formatHour(hour: Int?): String = when {
    hour == null -> "—"
    hour == 0    -> "12 AM"
    hour < 12    -> "$hour AM"
    hour == 12   -> "12 PM"
    else         -> "${hour - 12} PM"
}

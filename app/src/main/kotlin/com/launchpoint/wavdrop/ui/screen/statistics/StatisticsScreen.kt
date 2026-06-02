package com.launchpoint.wavdrop.ui.screen.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.StatsDashboardSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            StatisticsUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            StatisticsUiState.Empty -> EmptyContent(Modifier.padding(innerPadding))
            is StatisticsUiState.Content -> DashboardContent(
                summary = state.summary,
                onTrackDetailsClick = onTrackDetailsClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Loading statistics...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No listening statistics yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Play music to start building your Wavdrop history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DashboardContent(
    summary: StatsDashboardSummary,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            OverviewSection(summary = summary)
        }

        statsSection(
            title = "Most Played",
            sectionKey = "most_played",
            songs = summary.mostPlayedSongs,
            emptyMessage = "No plays recorded yet.",
            metric = { "${it.playCount} plays" },
            onTrackDetailsClick = onTrackDetailsClick,
        )

        statsSection(
            title = "Recently Played",
            sectionKey = "recently_played",
            songs = summary.recentlyPlayedSongs,
            emptyMessage = "No recent plays yet.",
            metric = { StatisticsFormatters.formatLastPlayed(it.lastPlayedAt) },
            onTrackDetailsClick = onTrackDetailsClick,
        )

        statsSection(
            title = "Most Skipped",
            sectionKey = "most_skipped",
            songs = summary.mostSkippedSongs,
            emptyMessage = "No skips recorded yet.",
            metric = { "${it.skipCount} skips" },
            onTrackDetailsClick = onTrackDetailsClick,
        )
    }
}

@Composable
private fun OverviewSection(summary: StatsDashboardSummary) {
    SectionHeader("Overview")
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverviewCard(
                label = "Songs",
                value = summary.totalSongs.toString(),
                icon = Icons.Default.MusicNote,
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Played Tracks",
                value = summary.totalPlayedTracks.toString(),
                icon = Icons.Default.History,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverviewCard(
                label = "Total Plays",
                value = summary.totalPlayCount.toString(),
                icon = Icons.Default.MusicNote,
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Total Skips",
                value = summary.totalSkipCount.toString(),
                icon = Icons.Default.SkipNext,
                modifier = Modifier.weight(1f),
            )
        }
        OverviewCard(
            label = "Listening Time",
            value = StatisticsFormatters.formatDurationSummary(summary.totalListeningTimeMs),
            icon = Icons.Default.Timer,
        )
    }
}

@Composable
private fun OverviewCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statsSection(
    title: String,
    sectionKey: String,
    songs: List<SongStatsSummary>,
    emptyMessage: String,
    metric: (SongStatsSummary) -> String,
    onTrackDetailsClick: (Long) -> Unit,
) {
    item { SectionHeader(title) }
    if (songs.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    // Prefix with sectionKey so the same song ID in multiple sections (e.g. Most Played +
    // Recently Played) doesn't produce duplicate LazyColumn keys and crash Compose.
    items(songs, key = { "${sectionKey}_${it.song.id}" }) { summary ->
        SongStatsRow(
            summary = summary,
            metric = metric(summary),
            onClick = { onTrackDetailsClick(summary.song.id) },
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun EmptySectionRow(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SongStatsRow(
    summary: SongStatsSummary,
    metric: String,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary.song.artist.ifBlank { "Unknown Artist" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = metric,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
        )
    }
}

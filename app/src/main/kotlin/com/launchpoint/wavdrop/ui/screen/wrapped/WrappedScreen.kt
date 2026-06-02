package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsFormatters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrappedScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    viewModel: WrappedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wrapped") },
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
            WrappedUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            WrappedUiState.Empty -> EmptyContent(Modifier.padding(innerPadding))
            is WrappedUiState.Content -> WrappedContent(
                state = state,
                onSelectYear = viewModel::selectYear,
                onTrackDetailsClick = onTrackDetailsClick,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Loading Wrapped...",
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
                text = "No event-backed Wrapped yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Play music in Wavdrop to build yearly listening history. Imported aggregate counts are not used for Wrapped.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WrappedContent(
    state: WrappedUiState.Content,
    onSelectYear: (Int) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val years = state.availableYears
    val selectedIndex = years.indexOf(state.selectedYear)
    val wrapped = state.wrapped

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            YearSelector(
                year = state.selectedYear,
                hasPrevious = selectedIndex >= 0 && selectedIndex < years.lastIndex,
                hasNext = selectedIndex > 0,
                onPrevious = { if (selectedIndex >= 0 && selectedIndex < years.lastIndex) onSelectYear(years[selectedIndex + 1]) },
                onNext = { if (selectedIndex > 0) onSelectYear(years[selectedIndex - 1]) },
            )
        }

        if (wrapped.emptyState.isEmpty) {
            item { EmptyYearNotice(wrapped.year) }
        }

        item { SectionHeader("${wrapped.year} Overview") }
        item { Overview(wrapped) }

        item { SectionHeader("Highlights") }
        item {
            HighlightRows(
                wrapped = wrapped,
                onTrackDetailsClick = onTrackDetailsClick,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
            )
        }

        songSection(
            title = "Recent Plays",
            songs = wrapped.recentlyPlayed,
            emptyMessage = "Recent plays will appear here.",
            metric = { StatisticsFormatters.formatLastPlayed(it.lastPlayedAt) },
            onTrackDetailsClick = onTrackDetailsClick,
        )
    }
}

@Composable
private fun YearSelector(
    year: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevious, enabled = hasPrevious) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous year",
                tint = if (hasPrevious) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            )
        }
        Text(
            text = year.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next year",
                tint = if (hasNext) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            )
        }
    }
}

@Composable
private fun EmptyYearNotice(year: Int) {
    Text(
        text = "No matched plays were found for $year. Event history may point to tracks that are no longer in the library.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Overview(wrapped: WrappedSummary) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardRow {
            OverviewCard(
                label = "Total Plays",
                value = wrapped.totalPlayCount.toString(),
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Listening Time",
                value = StatisticsFormatters.formatDurationSummary(wrapped.totalListeningTimeMs),
                modifier = Modifier.weight(1f),
            )
        }
        CardRow {
            OverviewCard(
                label = "Listening Days",
                value = wrapped.listeningDaysCount.toString(),
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Busiest Day",
                value = formatBusiestDay(wrapped.busiestDay, wrapped.busiestDayPlayCount),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CardRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun OverviewCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HighlightRows(
    wrapped: WrappedSummary,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    Column {
        wrapped.mostPlayedSong?.let { song ->
            ReportRow(
                title = "Top song",
                subtitle = song.song.title,
                metric = "${song.playCount} plays",
                onClick = { onTrackDetailsClick(song.song.id) },
            )
            SectionDivider()
        } ?: EmptySectionRow("Top song will appear as you listen.")

        wrapped.mostPlayedArtist?.let { artist ->
            ReportRow(
                title = "Top artist",
                subtitle = artist.artistKey,
                metric = "${artist.playCount} plays",
                onClick = { onArtistClick(artist.artistKey) },
            )
            SectionDivider()
        }

        wrapped.mostPlayedAlbum?.let { album ->
            ReportRow(
                title = "Top album",
                subtitle = album.albumKey,
                metric = "${album.playCount} plays",
                onClick = { onAlbumClick(album.albumKey) },
            )
            SectionDivider()
        }

        wrapped.mostSkippedTrack?.let { track ->
            ReportRow(
                title = "Most skipped track",
                subtitle = track.song.title,
                metric = "${track.skipCount} skips",
                onClick = { onTrackDetailsClick(track.song.id) },
            )
        } ?: EmptySectionRow("No skips recorded for this year.")
    }
}

private fun LazyListScope.songSection(
    title: String,
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
    items(songs, key = { "wrapped_recent_${it.song.id}_${it.lastPlayedAt}" }) { summary ->
        ReportRow(
            title = summary.song.title,
            subtitle = summary.song.artist.ifBlank { "Unknown Artist" },
            metric = metric(summary),
            onClick = { onTrackDetailsClick(summary.song.id) },
        )
        SectionDivider()
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
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun ReportRow(
    title: String,
    subtitle: String,
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
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatBusiestDay(day: LocalDate?, playCount: Int): String =
    day?.let { "${BUSIEST_DAY_FORMATTER.format(it)} - $playCount plays" } ?: "None"

private val BUSIEST_DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)

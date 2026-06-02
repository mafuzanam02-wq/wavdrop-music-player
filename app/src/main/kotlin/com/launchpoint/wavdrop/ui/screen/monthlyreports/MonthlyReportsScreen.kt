package com.launchpoint.wavdrop.ui.screen.monthlyreports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
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
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.MonthlyReportSummary
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsFormatters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyReportsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    viewModel: MonthlyReportsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monthly Reports") },
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
            MonthlyReportsUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            MonthlyReportsUiState.NoData -> NoDataContent(Modifier.padding(innerPadding))
            is MonthlyReportsUiState.Content -> MonthlyContent(
                state = state,
                onSelectMonth = viewModel::selectMonth,
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
            text = "Loading monthly reports...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun NoDataContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No event-backed monthly history yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Play music in Wavdrop to build accurate monthly reports. Imported aggregate counts are kept out of monthly history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MonthlyContent(
    state: MonthlyReportsUiState.Content,
    onSelectMonth: (MonthYear) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = state.report
    val months = state.availableMonths
    val selectedIndex = months.indexOf(state.selectedMonth)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            MonthSelector(
                label = state.selectedMonth.toDisplayLabel(),
                hasPrevious = selectedIndex >= 0 && selectedIndex < months.lastIndex,
                hasNext = selectedIndex > 0,
                onPrevious = { if (selectedIndex >= 0 && selectedIndex < months.lastIndex) onSelectMonth(months[selectedIndex + 1]) },
                onNext = { if (selectedIndex > 0) onSelectMonth(months[selectedIndex - 1]) },
            )
        }

        item { AccuracyBanner(report) }

        item { SectionHeader("Overview") }
        item { OverviewCards(report = report) }

        monthSongSection(
            title = "Top Songs",
            subtitle = "Ranked by event-backed plays this month",
            sectionKey = "top_songs",
            songs = report.topSongs,
            emptyMessage = "No plays recorded for this month.",
            metric = { "${it.playCount} plays this month" },
            onTrackDetailsClick = onTrackDetailsClick,
        )

        monthArtistSection(
            title = "Top Artists",
            subtitle = "Ranked by event-backed plays this month",
            artists = report.topArtists,
            emptyMessage = "No artist data for this month.",
            metric = { "${it.playCount} plays this month" },
            onArtistClick = onArtistClick,
        )

        monthAlbumSection(
            title = "Top Albums",
            subtitle = "Ranked by event-backed plays this month",
            albums = report.topAlbums,
            emptyMessage = "No album data for this month.",
            metric = { "${it.playCount} plays this month" },
            onAlbumClick = onAlbumClick,
        )

        item { SectionHeader("Habits") }
        item {
            HabitsSection(
                report = report,
                onTrackDetailsClick = onTrackDetailsClick,
            )
        }

        item { SectionHeader("Recently Played in ${state.selectedMonth.toDisplayLabel()}") }
        monthSongRows(
            sectionKey = "recent_songs",
            songs = report.recentlyPlayedInMonth,
            emptyMessage = "No recent plays for this month.",
            metric = { StatisticsFormatters.formatLastPlayed(it.lastPlayedAt) },
            onTrackDetailsClick = onTrackDetailsClick,
        )
    }
}

@Composable
private fun MonthSelector(
    label: String,
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
                contentDescription = "Previous month",
                tint = if (hasPrevious) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                },
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next month",
                tint = if (hasNext) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                },
            )
        }
    }
}

@Composable
private fun AccuracyBanner(report: MonthlyReportSummary, modifier: Modifier = Modifier) {
    val (icon, tint, message) = when (report.emptyStateReason) {
        ListeningAnalyticsEmptyReason.HAS_ACTIVITY -> Triple(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            "Accurate event history. Counts reflect Wavdrop plays and skips recorded this month.",
        )
        ListeningAnalyticsEmptyReason.ONLY_ORPHAN_EVENTS -> Triple(
            Icons.Filled.Info,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            "Event history exists for this month, but the tracks are no longer in the current library.",
        )
        ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE -> Triple(
            Icons.Filled.Info,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            "No event-backed listening was recorded for this month. Imported aggregate counts are excluded.",
        )
        ListeningAnalyticsEmptyReason.NO_AGGREGATE_ACTIVITY -> Triple(
            Icons.Filled.Info,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            "No monthly event history is available.",
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp).padding(top = 1.dp),
        )
        Text(
            text = "$message Status: ${emptyStateLabel(report.emptyStateReason)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun OverviewCards(report: MonthlyReportSummary) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverviewRow(
            leftLabel = "Plays",
            leftValue = report.totalPlayCount.toString(),
            rightLabel = "Skips",
            rightValue = report.totalSkipCount.toString(),
        )
        OverviewRow(
            leftLabel = "Listening Time",
            leftValue = StatisticsFormatters.formatDurationSummary(report.totalListeningTimeMs),
            rightLabel = "Tracks",
            rightValue = report.activeSongCount.toString(),
        )
        OverviewRow(
            leftLabel = "Artists",
            leftValue = report.activeArtistCount.toString(),
            rightLabel = "Albums",
            rightValue = report.activeAlbumCount.toString(),
        )
        OverviewRow(
            leftLabel = "Listening Days",
            leftValue = report.listeningDaysCount.toString(),
            rightLabel = "Busiest Day",
            rightValue = formatBusiestDay(report.busiestDay),
        )
        OverviewRow(
            leftLabel = "Busiest Plays",
            leftValue = report.busiestDayPlayCount.toString(),
            rightLabel = "Avg / Day",
            rightValue = formatAveragePlays(report.averagePlaysPerActiveDay),
        )
    }
}

@Composable
private fun OverviewRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverviewCard(
            label = leftLabel,
            value = leftValue,
            modifier = Modifier.weight(1f),
        )
        OverviewCard(
            label = rightLabel,
            value = rightValue,
            modifier = Modifier.weight(1f),
        )
    }
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HabitsSection(
    report: MonthlyReportSummary,
    onTrackDetailsClick: (Long) -> Unit,
) {
    Column {
        report.mostSkippedTrack?.let { track ->
            ReportRow(
                title = "Most skipped track",
                subtitle = track.song.title,
                metric = "${track.skipCount} skips this month",
                onClick = { onTrackDetailsClick(track.song.id) },
            )
        } ?: EmptySectionRow("No skips recorded for this month.")
    }
}

private fun LazyListScope.monthSongSection(
    title: String,
    subtitle: String,
    sectionKey: String,
    songs: List<SongStatsSummary>,
    emptyMessage: String,
    metric: (SongStatsSummary) -> String,
    onTrackDetailsClick: (Long) -> Unit,
) {
    item { SectionHeader(title, subtitle) }
    monthSongRows(
        sectionKey = sectionKey,
        songs = songs,
        emptyMessage = emptyMessage,
        metric = metric,
        onTrackDetailsClick = onTrackDetailsClick,
    )
}

private fun LazyListScope.monthSongRows(
    sectionKey: String,
    songs: List<SongStatsSummary>,
    emptyMessage: String,
    metric: (SongStatsSummary) -> String,
    onTrackDetailsClick: (Long) -> Unit,
) {
    if (songs.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    // Prefix with sectionKey: the same song can appear in both topSongs and recentlyPlayedInMonth
    // inside the same LazyColumn, which would produce duplicate "song_${id}" keys and crash Compose.
    items(songs, key = { "${sectionKey}_${it.song.id}" }) { summary ->
        ReportRow(
            title = summary.song.title,
            subtitle = summary.song.artist.ifBlank { "Unknown Artist" },
            metric = metric(summary),
            onClick = { onTrackDetailsClick(summary.song.id) },
        )
        SectionDivider()
    }
}

private fun LazyListScope.monthArtistSection(
    title: String,
    subtitle: String,
    artists: List<ArtistReportSummary>,
    emptyMessage: String,
    metric: (ArtistReportSummary) -> String,
    onArtistClick: (String) -> Unit,
) {
    item { SectionHeader(title, subtitle) }
    if (artists.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    items(artists, key = { "artist_${it.artistKey}" }) { artist ->
        ReportRow(
            title = artist.artistKey,
            subtitle = "${artist.songCount} songs",
            metric = metric(artist),
            onClick = { onArtistClick(artist.artistKey) },
        )
        SectionDivider()
    }
}

private fun LazyListScope.monthAlbumSection(
    title: String,
    subtitle: String,
    albums: List<AlbumReportSummary>,
    emptyMessage: String,
    metric: (AlbumReportSummary) -> String,
    onAlbumClick: (String) -> Unit,
) {
    item { SectionHeader(title, subtitle) }
    if (albums.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    items(albums, key = { "album_${it.albumKey}" }) { album ->
        ReportRow(
            title = album.albumKey,
            subtitle = album.artist.ifBlank { "Unknown Artist" },
            metric = metric(album),
            onClick = { onAlbumClick(album.albumKey) },
        )
        SectionDivider()
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun EmptySectionRow(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun ReportRow(
    title: String,
    subtitle: String,
    metric: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
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

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

private fun formatBusiestDay(day: LocalDate?): String =
    day?.format(BUSIEST_DAY_FORMATTER) ?: "None"

private fun formatAveragePlays(value: Double): String =
    if (value <= 0.0) "0.0" else String.format(Locale.US, "%.1f", value)

private fun emptyStateLabel(reason: ListeningAnalyticsEmptyReason): String = when (reason) {
    ListeningAnalyticsEmptyReason.HAS_ACTIVITY -> "has activity"
    ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE -> "no events in range"
    ListeningAnalyticsEmptyReason.ONLY_ORPHAN_EVENTS -> "only orphan events"
    ListeningAnalyticsEmptyReason.NO_AGGREGATE_ACTIVITY -> "no aggregate activity"
}

private val BUSIEST_DAY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)

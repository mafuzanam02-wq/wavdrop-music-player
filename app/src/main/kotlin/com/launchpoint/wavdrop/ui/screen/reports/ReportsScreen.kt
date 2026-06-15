package com.launchpoint.wavdrop.ui.screen.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.ListeningReportSummary
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsFormatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports") },
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
            ReportsUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            ReportsUiState.Empty -> EmptyContent(Modifier.padding(innerPadding))
            is ReportsUiState.Content -> ReportsContent(
                report = state.report,
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
            text = "Loading reports...",
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
                text = "No listening reports yet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Play music in Wavdrop to build listening reports from your local history.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ReportsContent(
    report: ListeningReportSummary,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { ListeningOverview(report = report) }

        songReportSection(
            title = "Top Songs",
            songs = report.topSongs,
            emptyMessage = "No top songs yet. Play music to rank tracks by listening activity.",
            metric = { "${it.playCount} plays" },
            onTrackDetailsClick = onTrackDetailsClick,
        )

        artistReportSection(
            title = "Top Artists",
            artists = report.topArtists,
            emptyMessage = "No top artists yet. Play songs to build artist activity.",
            metric = { "${it.playCount} plays" },
            onArtistClick = onArtistClick,
        )

        albumReportSection(
            title = "Top Albums",
            albums = report.topAlbums,
            emptyMessage = "No top albums yet. Play songs to build album activity.",
            metric = { "${it.playCount} plays" },
            onAlbumClick = onAlbumClick,
        )

        item { SectionHeader("Listening Habits") }
        item {
            HabitsSection(
                report = report,
                onTrackDetailsClick = onTrackDetailsClick,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
            )
        }

        item { SectionHeader("Recent Activity") }
        songReportRows(
            songs = report.recentlyPlayedSongs,
            emptyMessage = "No recent plays yet. Start playback to fill this section.",
            metric = { StatisticsFormatters.formatLastPlayed(it.lastPlayedAt) },
            onTrackDetailsClick = onTrackDetailsClick,
        )
        artistReportRows(
            artists = report.recentlyActiveArtists,
            emptyMessage = "No recently active artists yet. Play songs to update this list.",
            metric = { StatisticsFormatters.formatLastPlayed(it.lastPlayedAt) },
            onArtistClick = onArtistClick,
        )
    }
}

@Composable
private fun ListeningOverview(report: ListeningReportSummary) {
    SectionHeader("Listening Overview")
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReportCardRow {
            OverviewCard(
                label = "Listening Time",
                value = StatisticsFormatters.formatDurationSummary(report.totalListeningTimeMs),
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Total Plays",
                value = report.totalPlayCount.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        ReportCardRow {
            OverviewCard(
                label = "Total Skips",
                value = report.totalSkipCount.toString(),
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Tracks Played",
                value = report.tracksPlayed.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        ReportCardRow {
            OverviewCard(
                label = "Artists Played",
                value = report.artistsPlayed.toString(),
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Albums Played",
                value = report.albumsPlayed.toString(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReportCardRow(content: @Composable RowScope.() -> Unit) {
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
private fun HabitsSection(
    report: ListeningReportSummary,
    onTrackDetailsClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
) {
    Column {
        report.mostPlayedTrack?.let { track ->
            ReportRow(
                title = "Most played track",
                subtitle = track.song.displayTitle,
                metric = "${track.playCount} plays",
                onClick = { onTrackDetailsClick(track.song.id) },
            )
        } ?: EmptySectionRow("No most-played track yet. Play music to choose one.")

        report.mostPlayedArtist?.let { artist ->
            ReportRow(
                title = "Most played artist",
                subtitle = artist.artistKey,
                metric = "${artist.playCount} plays",
                onClick = { onArtistClick(artist.artistKey) },
            )
        }

        report.mostPlayedAlbum?.let { album ->
            ReportRow(
                title = "Most played album",
                subtitle = album.albumKey,
                metric = "${album.playCount} plays",
                onClick = { onAlbumClick(album.albumKey) },
            )
        }

        report.mostSkippedTrack?.let { track ->
            ReportRow(
                title = "Most skipped track",
                subtitle = track.song.displayTitle,
                metric = "${track.skipCount} skips",
                onClick = { onTrackDetailsClick(track.song.id) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.songReportSection(
    title: String,
    songs: List<SongStatsSummary>,
    emptyMessage: String,
    metric: (SongStatsSummary) -> String,
    onTrackDetailsClick: (Long) -> Unit,
) {
    item { SectionHeader(title) }
    songReportRows(
        songs = songs,
        emptyMessage = emptyMessage,
        metric = metric,
        onTrackDetailsClick = onTrackDetailsClick,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.songReportRows(
    songs: List<SongStatsSummary>,
    emptyMessage: String,
    metric: (SongStatsSummary) -> String,
    onTrackDetailsClick: (Long) -> Unit,
) {
    if (songs.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    items(songs, key = { "song_${it.song.id}_${metric(it)}" }) { summary ->
        ReportRow(
            title = summary.song.displayTitle,
            subtitle = summary.song.displayArtist.ifBlank { "Unknown Artist" },
            metric = metric(summary),
            onClick = { onTrackDetailsClick(summary.song.id) },
        )
        SectionDivider()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.artistReportSection(
    title: String,
    artists: List<ArtistReportSummary>,
    emptyMessage: String,
    metric: (ArtistReportSummary) -> String,
    onArtistClick: (String) -> Unit,
) {
    item { SectionHeader(title) }
    artistReportRows(
        artists = artists,
        emptyMessage = emptyMessage,
        metric = metric,
        onArtistClick = onArtistClick,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.artistReportRows(
    artists: List<ArtistReportSummary>,
    emptyMessage: String,
    metric: (ArtistReportSummary) -> String,
    onArtistClick: (String) -> Unit,
) {
    if (artists.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    items(artists, key = { "artist_${it.artistKey}_${metric(it)}" }) { artist ->
        ReportRow(
            title = artist.artistKey,
            subtitle = "${artist.songCount} songs - ${artist.albumCount} albums",
            metric = metric(artist),
            onClick = { onArtistClick(artist.artistKey) },
        )
        SectionDivider()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumReportSection(
    title: String,
    albums: List<AlbumReportSummary>,
    emptyMessage: String,
    metric: (AlbumReportSummary) -> String,
    onAlbumClick: (String) -> Unit,
) {
    item { SectionHeader(title) }
    if (albums.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    items(albums, key = { "album_${it.albumKey}_${metric(it)}" }) { album ->
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
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
        )
    }
}

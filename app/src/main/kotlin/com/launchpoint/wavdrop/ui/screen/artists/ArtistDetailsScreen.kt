package com.launchpoint.wavdrop.ui.screen.artists

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.launchpoint.wavdrop.data.model.ArtistAlbumInsight
import com.launchpoint.wavdrop.data.model.ArtistInsightsSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.SongRowWithOverflow
import com.launchpoint.wavdrop.ui.screen.statistics.StatisticsFormatters
import com.launchpoint.wavdrop.ui.viewmodel.PlaylistActionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onAlbumClick: (String) -> Unit = {},
    viewModel: ArtistDetailsViewModel = hiltViewModel(),
    playlistVm: PlaylistActionsViewModel = hiltViewModel(),
) {
    val state             by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists         by playlistVm.playlists.collectAsStateWithLifecycle()
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    val snackbarHostState  = remember { SnackbarHostState() }
    val coroutineScope     = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.artistName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = artistMeta(state.songCount, state.albumCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
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
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                OverviewSection(summary = state.insights)
            }

            if (state.insights.totalPlayCount == 0) {
                item {
                    LowDataMessage()
                }
            }

            artistSongStatsSection(
                title = "Top Songs",
                songs = state.insights.topSongs,
                emptyMessage = "Top songs will appear as you listen.",
                metric = { "${it.playCount} plays" },
                onSongClick = viewModel::playSong,
            )

            artistAlbumSection(
                title = "Top Albums",
                albums = state.insights.topAlbums,
                emptyMessage = "Top albums will appear as you listen.",
                onAlbumClick = onAlbumClick,
            )

            artistSongStatsSection(
                title = "Recent Activity",
                songs = state.insights.recentActivity,
                emptyMessage = "Recent plays will appear here.",
                metric = { StatisticsFormatters.formatLastPlayed(it.lastPlayedAt) },
                onSongClick = viewModel::playSong,
            )

            item { SectionHeader("Songs") }
            if (state.songs.isEmpty()) {
                item { EmptySectionRow("No songs found for this artist.") }
            } else {
                items(state.songs, key = { it.id }) { song ->
                    val isFavorite = song.id in state.favoriteSongIds
                    SongRowWithOverflow(
                        song             = song,
                        isCurrent        = song.id == state.currentSongId,
                        isFavorite       = isFavorite,
                        onPlay           = { viewModel.playSong(song) },
                        onPlayNext       = { viewModel.playNext(song) },
                        onAddToQueue     = { viewModel.addToQueue(song) },
                        onToggleFavorite = {
                            viewModel.toggleFavorite(song.id)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (isFavorite) "Removed from Favorites" else "Added to Favorites",
                                )
                            }
                        },
                        onAddToPlaylist  = { addToPlaylistSong = song },
                        onTrackDetails   = { onTrackDetailsClick(song.id) },
                        modifier         = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider(
                        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            playlists        = playlists,
            onSelectPlaylist = { playlistId ->
                playlistVm.addSongToPlaylist(song.id, playlistId)
                addToPlaylistSong = null
            },
            onCreateAndAdd   = { name ->
                playlistVm.createPlaylistAndAddSong(name, song.id)
                addToPlaylistSong = null
            },
            onDismiss        = { addToPlaylistSong = null },
        )
    }
}

@Composable
private fun OverviewSection(summary: ArtistInsightsSummary) {
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
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Albums",
                value = summary.totalAlbums.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverviewCard(
                label = "Plays",
                value = summary.totalPlayCount.toString(),
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Skips",
                value = summary.totalSkipCount.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverviewCard(
                label = "Listening Time",
                value = StatisticsFormatters.formatDurationSummary(summary.totalListeningTimeMs),
                modifier = Modifier.weight(1f),
            )
            OverviewCard(
                label = "Last Played",
                value = StatisticsFormatters.formatLastPlayed(summary.lastPlayedAt),
                modifier = Modifier.weight(1f),
            )
        }
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
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LowDataMessage() {
    Text(
        text = "Insights will appear as you listen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.artistSongStatsSection(
    title: String,
    songs: List<SongStatsSummary>,
    emptyMessage: String,
    metric: (SongStatsSummary) -> String,
    onSongClick: (com.launchpoint.wavdrop.data.model.Song) -> Unit,
) {
    item { SectionHeader(title) }
    if (songs.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    items(songs, key = { "${title}_${it.song.id}" }) { summary ->
        ArtistSongInsightRow(
            summary = summary,
            metric = metric(summary),
            onClick = { onSongClick(summary.song) },
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.artistAlbumSection(
    title: String,
    albums: List<ArtistAlbumInsight>,
    emptyMessage: String,
    onAlbumClick: (String) -> Unit,
) {
    item { SectionHeader(title) }
    if (albums.isEmpty()) {
        item { EmptySectionRow(emptyMessage) }
        return
    }
    items(albums, key = { it.albumKey }) { album ->
        ArtistAlbumInsightRow(
            album = album,
            onClick = { onAlbumClick(album.albumKey) },
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            thickness = 0.5.dp,
        )
    }
}

@Composable
private fun ArtistSongInsightRow(
    summary: SongStatsSummary,
    metric: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightRow(
        title = summary.song.title,
        subtitle = summary.song.album.ifBlank { "Unknown Album" },
        metric = metric,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ArtistAlbumInsightRow(
    album: ArtistAlbumInsight,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightRow(
        title = album.albumKey,
        subtitle = "${album.songCount} songs",
        metric = "${album.playCount} plays",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun InsightRow(
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

private fun artistMeta(songCount: Int, albumCount: Int): String =
    buildString {
        append("$songCount songs")
        if (albumCount > 1) append(" - $albumCount albums")
        if (albumCount == 1) append(" - 1 album")
    }

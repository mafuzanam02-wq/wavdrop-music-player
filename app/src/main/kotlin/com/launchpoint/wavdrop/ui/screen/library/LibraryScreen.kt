package com.launchpoint.wavdrop.ui.screen.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar
import com.launchpoint.wavdrop.ui.permission.AudioPermissionGate
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onSongsClick: () -> Unit,
    onAlbumsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onFoldersClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onSmartCollectionsClick: () -> Unit,
    onHomeClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onInsightsClick: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(
                    nowPlaying = nowPlaying,
                    onOpenNowPlaying = onNowPlayingClick,
                    onTogglePlayPause = playbackVm::togglePlayPause,
                    onPrevious = playbackVm::skipToPrevious,
                    onNext = playbackVm::skipToNext,
                    onToggleShuffle = playbackVm::toggleShuffle,
                    onCycleRepeatMode = playbackVm::cycleRepeatMode,
                    applyNavigationBarsPadding = false,
                )
                PrimaryNavigationBar(
                    selected = PrimaryDestination.LIBRARY,
                    onHomeClick = onHomeClick,
                    onSongsClick = onSongsClick,
                    onLibraryClick = {},
                    onInsightsClick = onInsightsClick,
                )
            }
        },
    ) { innerPadding ->
        AudioPermissionGate(
            onPermissionGranted = viewModel::syncIfNeeded,
            modifier = Modifier.padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item {
                    LibraryIntro(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    LibrarySummaryCard(
                        summary = summary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                item {
                    LibraryNavRow(
                        title = "Songs",
                        subtitle = "Browse every track with search and A-Z jump.",
                        icon = Icons.Default.MusicNote,
                        onClick = onSongsClick,
                    )
                }
                item { SectionDivider() }
                item {
                    LibraryNavRow(
                        title = "Albums",
                        subtitle = "Browse by album.",
                        icon = Icons.Default.Album,
                        onClick = onAlbumsClick,
                    )
                }
                item { SectionDivider() }
                item {
                    LibraryNavRow(
                        title = "Artists",
                        subtitle = "Browse by artist.",
                        icon = Icons.Default.Person,
                        onClick = onArtistsClick,
                    )
                }
                item { SectionDivider() }
                item {
                    LibraryNavRow(
                        title = "Folders",
                        subtitle = "Browse by device folder.",
                        icon = Icons.Default.Folder,
                        onClick = onFoldersClick,
                    )
                }
                item { SectionDivider() }
                item {
                    LibraryNavRow(
                        title = "Playlists",
                        subtitle = "Create and manage local playlists.",
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        onClick = onPlaylistsClick,
                    )
                }
                item { SectionDivider() }
                item {
                    LibraryNavRow(
                        title = "Smart Collections",
                        subtitle = "Automatic mixes from listening history.",
                        icon = Icons.Default.AutoAwesome,
                        onClick = onSmartCollectionsClick,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LibrarySummaryCard(
    summary: LibrarySummaryUiState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        shape = MaterialTheme.shapes.small,
    ) {
        if (summary.isLoading) {
            Text(
                text = "Loading library summary...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                modifier = Modifier.padding(14.dp),
            )
        } else if (summary.isEmpty) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "No music yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Add audio files to see your library totals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryMetric(text = pluralCount(summary.totalSongs, "song", "songs"))
                SummaryMetric(text = pluralCount(summary.totalAlbums, "album", "albums"))
                SummaryMetric(text = pluralCount(summary.totalArtists, "artist", "artists"))
                SummaryMetric(text = formatLibraryDuration(summary.totalDurationMs))
            }
        }
    }
}

@Composable
private fun SummaryMetric(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LibraryIntro(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Column {
                Text(
                    text = "Browse your local music",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Songs, albums, artists, folders, playlists, and smart collections live here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun LibraryNavRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
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
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 76.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

private fun pluralCount(count: Int, singular: String, plural: String): String {
    val label = if (count == 1) singular else plural
    return "${NUMBER_FORMAT.format(count)} $label"
}

private fun formatLibraryDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val totalHours = totalMinutes / 60L
    val days = totalHours / 24L
    val minutes = totalMinutes % 60L

    return when {
        days >= 1L -> {
            val label = if (days == 1L) "day" else "days"
            "$days $label of music"
        }
        totalHours >= 1L -> {
            if (minutes > 0L) "$totalHours hr $minutes min" else "$totalHours hr"
        }
        else -> "$totalMinutes min"
    }
}

private val NUMBER_FORMAT: NumberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())

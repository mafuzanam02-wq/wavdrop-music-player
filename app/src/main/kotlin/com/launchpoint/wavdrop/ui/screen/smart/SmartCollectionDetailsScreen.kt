package com.launchpoint.wavdrop.ui.screen.smart

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.SongRow
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCollectionDetailsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: SmartCollectionDetailsViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val state      by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            text     = viewModel.title,
                            style    = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text  = viewModel.description,
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
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
            MiniPlayer(
                nowPlaying        = nowPlaying,
                onOpenNowPlaying  = onNowPlayingClick,
                onTogglePlayPause = playbackVm::togglePlayPause,
                onPrevious        = playbackVm::skipToPrevious,
                onNext            = playbackVm::skipToNext,
                onToggleShuffle   = playbackVm::toggleShuffle,
                onCycleRepeatMode = playbackVm::cycleRepeatMode,
            )
        },
    ) { innerPadding ->
        if (viewModel.type == SmartCollectionType.MOST_PLAYED) {
            MostPlayedContent(
                state = state,
                onPeriodSelected = viewModel::setMostPlayedPeriod,
                onLimitSelected = viewModel::setMostPlayedDisplayLimit,
                onPlayAll = viewModel::playAll,
                onShufflePlay = viewModel::shufflePlay,
                onSongClick = viewModel::playSong,
                onToggleFavorite = viewModel::toggleFavorite,
                onTrackDetailsClick = onTrackDetailsClick,
                modifier = Modifier.padding(innerPadding),
            )
        } else if (state.songs.isEmpty()) {
            EmptyDetailContent(Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    PlaybackActions(
                        onPlayAll     = viewModel::playAll,
                        onShufflePlay = viewModel::shufflePlay,
                        modifier      = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    HorizontalDivider(
                        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
                items(state.songs, key = { it.id }) { song ->
                    SongRow(
                        song             = song,
                        isCurrent        = song.id == state.currentSongId,
                        isFavorite       = song.id in state.favoriteSongIds,
                        onClick          = { viewModel.playSong(song) },
                        onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                        onOpenDetails    = { onTrackDetailsClick(song.id) },
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
}

private fun playCountLabel(count: Int): String =
    if (count == 1) "1 play" else "$count plays"

@Composable
private fun MostPlayedContent(
    state: SmartCollectionDetailsUiState,
    onPeriodSelected: (MostPlayedPeriod) -> Unit,
    onLimitSelected: (MostPlayedDisplayLimit) -> Unit,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onSongClick: (com.launchpoint.wavdrop.data.model.Song) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            MostPlayedControls(
                selectedPeriod = state.mostPlayedPeriod,
                selectedLimit = state.mostPlayedDisplayLimit,
                onPeriodSelected = onPeriodSelected,
                onLimitSelected = onLimitSelected,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
            )
        }

        if (state.mostPlayedSummaries.isEmpty()) {
            item {
                MostPlayedEmptyRow(period = state.mostPlayedPeriod)
            }
        } else {
            item {
                PlaybackActions(
                    onPlayAll = onPlayAll,
                    onShufflePlay = onShufflePlay,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }

            items(state.mostPlayedSummaries, key = { it.song.id }) { summary ->
                MostPlayedSongRow(
                    summary = summary,
                    isCurrent = summary.song.id == state.currentSongId,
                    isFavorite = summary.song.id in state.favoriteSongIds,
                    onClick = { onSongClick(summary.song) },
                    onToggleFavorite = { onToggleFavorite(summary.song.id) },
                    onOpenDetails = { onTrackDetailsClick(summary.song.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

// ── Filter controls ───────────────────────────────────────────────────────────

@Composable
private fun MostPlayedControls(
    selectedPeriod: MostPlayedPeriod,
    selectedLimit: MostPlayedDisplayLimit,
    onPeriodSelected: (MostPlayedPeriod) -> Unit,
    onLimitSelected: (MostPlayedDisplayLimit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FilterDropdown(
            label = "Period",
            selectedLabel = selectedPeriod.label,
            modifier = Modifier.weight(1f),
        ) { dismiss ->
            MostPlayedPeriod.values().forEach { period ->
                DropdownMenuItem(
                    text = { Text(period.label) },
                    onClick = { onPeriodSelected(period); dismiss() },
                )
            }
        }
        FilterDropdown(
            label = "Show",
            selectedLabel = selectedLimit.label,
            modifier = Modifier.weight(1f),
        ) { dismiss ->
            MostPlayedDisplayLimit.values().forEach { limit ->
                DropdownMenuItem(
                    text = { Text(limit.label) },
                    onClick = { onLimitSelected(limit); dismiss() },
                )
            }
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    selectedLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                content { expanded = false }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun MostPlayedEmptyRow(
    period: MostPlayedPeriod,
    modifier: Modifier = Modifier,
) {
    val message = when (period) {
        MostPlayedPeriod.ALL_TIME -> "No played songs yet."
        MostPlayedPeriod.THIS_MONTH -> "No plays recorded this month yet."
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

// ── Song row ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MostPlayedSongRow(
    summary: SongStatsSummary,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowColor = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
    val accentColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent

    Row(
        modifier = modifier
            .background(rowColor)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onToggleFavorite,
                onLongClick = onOpenDetails,
            )
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(accentColor),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                    )
                }
            }
            Text(
                text = summary.song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = playCountLabel(summary.playCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── Playback actions ──────────────────────────────────────────────────────────

@Composable
private fun PlaybackActions(
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier              = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick  = onPlayAll,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.padding(end = 4.dp))
            Text("Play all")
        }
        FilledTonalButton(
            onClick  = onShufflePlay,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Shuffle, null, modifier = Modifier.padding(end = 4.dp))
            Text("Shuffle")
        }
    }
}

// ── Empty detail ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyDetailContent(modifier: Modifier = Modifier) {
    Box(
        modifier         = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text      = "No songs in this collection.",
            style     = MaterialTheme.typography.bodyLarge,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

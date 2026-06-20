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
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.MostPlayedDisplayLimit
import com.launchpoint.wavdrop.data.model.MostPlayedPeriod
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.shareSong
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.LoadingStateContent
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.SongRowWithOverflow
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel
import com.launchpoint.wavdrop.ui.viewmodel.PlaylistActionsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCollectionDetailsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: SmartCollectionDetailsViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
    playlistVm: PlaylistActionsViewModel = hiltViewModel(),
) {
    val state             by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying        by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val playlists         by playlistVm.playlists.collectAsStateWithLifecycle()
    val allPlaylistSongs  by playlistVm.allPlaylistSongs.collectAsStateWithLifecycle()
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    val snackbarHostState  = remember { SnackbarHostState() }
    val coroutineScope     = rememberCoroutineScope()
    val context            = LocalContext.current

    val onSaveAsPlaylist: () -> Unit = {
        viewModel.saveAsPlaylist { result ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    when (result) {
                        is SaveAsPlaylistResult.Success      -> "Saved \"${result.name}\" · ${result.added} tracks"
                        is SaveAsPlaylistResult.DuplicateName -> "\"${result.name}\" already exists as a playlist"
                        SaveAsPlaylistResult.Empty           -> "No songs to save"
                        SaveAsPlaylistResult.Error           -> "Something went wrong"
                    },
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        if (viewModel.isInvalidType) {
            InvalidCollectionContent(
                onNavigateBack = onNavigateBack,
                modifier       = Modifier.padding(innerPadding),
            )
        } else if (state.isLoading) {
            LoadingStateContent(
                message = "Loading collection...",
                modifier = Modifier.padding(innerPadding),
            )
        } else if (viewModel.type == SmartCollectionType.MOST_PLAYED) {
            MostPlayedContent(
                state               = state,
                onPeriodSelected    = viewModel::setMostPlayedPeriod,
                onLimitSelected     = viewModel::setMostPlayedDisplayLimit,
                onPlayAll           = viewModel::playAll,
                onShufflePlay       = viewModel::shufflePlay,
                onSaveAsPlaylist    = onSaveAsPlaylist,
                onSongClick         = viewModel::playSong,
                onPlayNext          = { song -> viewModel.playNext(song) },
                onAddToQueue        = { song -> viewModel.addToQueue(song) },
                onToggleFavorite    = { songId, isFavorite ->
                    viewModel.toggleFavorite(songId)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            if (isFavorite) "Removed from Favorites" else "Added to Favorites",
                        )
                    }
                },
                onAddToPlaylist     = { song -> addToPlaylistSong = song },
                onTrackDetailsClick = onTrackDetailsClick,
                onShare             = { song ->
                    shareSong(context, song) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Could not share this track")
                        }
                    }
                },
                modifier            = Modifier.padding(innerPadding),
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
                        onPlayAll        = viewModel::playAll,
                        onShufflePlay    = viewModel::shufflePlay,
                        onSaveAsPlaylist = onSaveAsPlaylist,
                        modifier         = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    HorizontalDivider(
                        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
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
                        onShare          = {
                            shareSong(context, song) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Could not share this track")
                                }
                            }
                        },
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
            playlists           = playlists,
            existingPlaylistIds = allPlaylistSongs
                .filter { it.songId == song.id }
                .map { it.playlistId }
                .toSet(),
            onSelectPlaylist    = { playlistId ->
                playlistVm.addSongToPlaylist(song.id, playlistId) { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.singleSongMessage())
                    }
                }
                addToPlaylistSong = null
            },
            onCreateAndAdd      = { name ->
                playlistVm.createPlaylistAndAddSong(name, song.id)
                addToPlaylistSong = null
            },
            onDismiss           = { addToPlaylistSong = null },
        )
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
    onSaveAsPlaylist: () -> Unit,
    onSongClick: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onToggleFavorite: (Long, Boolean) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onShare: (Song) -> Unit,
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
                    onPlayAll        = onPlayAll,
                    onShufflePlay    = onShufflePlay,
                    onSaveAsPlaylist = onSaveAsPlaylist,
                    modifier         = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }

            items(state.mostPlayedSummaries, key = { it.song.id }) { summary ->
                val isFavorite = summary.song.id in state.favoriteSongIds
                MostPlayedSongRow(
                    summary          = summary,
                    isCurrent        = summary.song.id == state.currentSongId,
                    isFavorite       = isFavorite,
                    onClick          = { onSongClick(summary.song) },
                    onPlayNext       = { onPlayNext(summary.song) },
                    onAddToQueue     = { onAddToQueue(summary.song) },
                    onToggleFavorite = { onToggleFavorite(summary.song.id, isFavorite) },
                    onAddToPlaylist  = { onAddToPlaylist(summary.song) },
                    onOpenDetails    = { onTrackDetailsClick(summary.song.id) },
                    onShare          = { onShare(summary.song) },
                    modifier         = Modifier.fillMaxWidth(),
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
        MostPlayedPeriod.ALL_TIME -> "No played songs yet. Play music to rank your most-played tracks."
        MostPlayedPeriod.THIS_MONTH -> "No plays recorded this month. Play music in Wavdrop to fill this view."
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
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenDetails: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowColor    = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
    val accentColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent
    var menuExpanded by remember { mutableStateOf(false) }
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 8.dp else 12.dp
    val artworkSize = if (compact) 44.dp else 48.dp

    Row(
        modifier = modifier
            .background(rowColor)
            .combinedClickable(
                onClick       = onClick,
                onDoubleClick = onToggleFavorite,
                onLongClick   = { menuExpanded = true },
            )
            .padding(start = 16.dp, end = 4.dp, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(accentColor),
        )
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(summary.song.albumId),
            contentDescription = "Album artwork for ${summary.song.album}",
            placeholderIcon = Icons.Default.MusicNote,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(artworkSize),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text     = summary.song.displayTitle,
                    style    = MaterialTheme.typography.titleMedium,
                    color    = if (isCurrent) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCurrent) {
                    Icon(
                        imageVector        = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.primary,
                        modifier           = Modifier.padding(start = 8.dp).size(16.dp),
                    )
                }
            }
            Text(
                text     = summary.song.displayArtist,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text     = playCountLabel(summary.playCount),
            style    = MaterialTheme.typography.labelMedium,
            color    = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector        = Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                )
            }
            DropdownMenu(
                expanded         = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(text = { Text("Play") },             onClick = { menuExpanded = false; onClick() })
                DropdownMenuItem(text = { Text("Play next") },        onClick = { menuExpanded = false; onPlayNext() })
                DropdownMenuItem(text = { Text("Add to queue") },     onClick = { menuExpanded = false; onAddToQueue() })
                DropdownMenuItem(text = { Text("Add to playlist") },  onClick = { menuExpanded = false; onAddToPlaylist() })
                DropdownMenuItem(
                    text    = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                    onClick = { menuExpanded = false; onToggleFavorite() },
                )
                DropdownMenuItem(text = { Text("Track details") },    onClick = { menuExpanded = false; onOpenDetails() })
                DropdownMenuItem(text = { Text("Share") },            onClick = { menuExpanded = false; onShare() })
            }
        }
    }
}

// ── Playback actions ──────────────────────────────────────────────────────────

@Composable
private fun PlaybackActions(
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    modifier: Modifier = Modifier,
    onSaveAsPlaylist: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
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
        if (onSaveAsPlaylist != null) {
            OutlinedButton(
                onClick  = onSaveAsPlaylist,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save as Playlist")
            }
        }
    }
}

// ── Invalid / not-found state ─────────────────────────────────────────────────

@Composable
private fun InvalidCollectionContent(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text      = "Collection not found.",
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onNavigateBack) {
                Text("Go back")
            }
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
            text      = "No songs in this collection yet.\nPlay, favorite, skip, or add music that matches this collection.",
            style     = MaterialTheme.typography.bodyLarge,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

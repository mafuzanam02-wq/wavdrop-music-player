package com.launchpoint.wavdrop.ui.screen.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.LoadingStateContent
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.PlaylistArtworkCollage
import com.launchpoint.wavdrop.ui.components.SearchTopAppBar
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel
import com.launchpoint.wavdrop.ui.viewmodel.PlaylistActionsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailsScreen(
    onNavigateBack: () -> Unit,
    onAddSongsClick: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: PlaylistDetailsViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
    playlistVm: PlaylistActionsViewModel = hiltViewModel(),
) {
    val state             by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying        by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val playlists         by playlistVm.playlists.collectAsStateWithLifecycle()
    var showRename        by remember { mutableStateOf(false) }
    var showDelete        by remember { mutableStateOf(false) }
    var menuExpanded      by remember { mutableStateOf(false) }
    var renameError       by remember { mutableStateOf<String?>(null) }
    var searchMode        by remember { mutableStateOf(false) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    val snackbarHostState  = remember { SnackbarHostState() }
    val coroutineScope     = rememberCoroutineScope()

    val playlistName = state.playlist?.name ?: ""
    val songCount    = state.entries.size

    Scaffold(
        topBar = {
            if (searchMode) {
                SearchTopAppBar(
                    query         = state.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    onClose       = {
                        searchMode = false
                        viewModel.clearSearch()
                    },
                    placeholder   = "Search playlist",
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text     = playlistName.ifBlank { "Playlist" },
                                style    = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (songCount > 0) {
                                Text(
                                    text  = "$songCount songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
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
                    actions = {
                        IconButton(onClick = { searchMode = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search playlist")
                        }
                        IconButton(onClick = onAddSongsClick) {
                            Icon(Icons.Default.Add, contentDescription = "Add songs")
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded         = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text        = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                    onClick     = { menuExpanded = false; showRename = true },
                                )
                                DropdownMenuItem(
                                    text        = { Text("Delete playlist") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick     = { menuExpanded = false; showDelete = true },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor    = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        if (state.isLoading) {
            LoadingStateContent(
                message = "Loading playlist...",
                modifier = Modifier.padding(innerPadding),
            )
        } else if (state.entries.isEmpty()) {
            EmptyContent(
                onAddSongsClick = onAddSongsClick,
                playlistName    = playlistName.ifBlank { "Playlist" },
                artworkUris     = state.artworkUris,
                modifier        = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                item {
                    PlaylistHeader(
                        playlistName = playlistName.ifBlank { "Playlist" },
                        songCount    = songCount,
                        artworkUris  = state.artworkUris,
                    )
                }
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
                if (state.visibleEntries.isEmpty()) {
                    item { NoPlaylistSearchResults() }
                } else {
                    itemsIndexed(state.visibleEntries, key = { _, entry -> "${entry.position}:${entry.songId}" }) { index, entry ->
                        val isFavorite = entry.songId in state.favoriteSongIds
                        PlaylistSongRow(
                            entry            = entry,
                            isFirst          = index == 0,
                            isLast           = index == state.visibleEntries.lastIndex,
                            allowMove        = !state.isSearchActive,
                            isCurrent        = entry.songId == state.currentSongId,
                            isFavorite       = isFavorite,
                            onClick          = { viewModel.playEntry(entry) },
                            onToggleFavorite = {
                                viewModel.toggleFavorite(entry.songId)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (isFavorite) "Removed from Favorites" else "Added to Favorites",
                                    )
                                }
                            },
                            onOpenDetails    = { onTrackDetailsClick(entry.songId) },
                            onPlayNext       = { viewModel.playNext(entry.song) },
                            onAddToQueue     = { viewModel.addToQueue(entry.song) },
                            onAddToPlaylist  = { addToPlaylistSong = entry.song },
                            onRemove         = { viewModel.removeEntry(entry.position) },
                            onMoveUp         = { viewModel.moveEntryUp(entry.position) },
                            onMoveDown       = { viewModel.moveEntryDown(entry.position) },
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

    // ── Rename dialog ─────────────────────────────────────────────────────────
    if (showRename) {
        var nameInput by remember(playlistName) { mutableStateOf(playlistName) }
        AlertDialog(
            onDismissRequest = { showRename = false; renameError = null },
            title = { Text("Rename playlist") },
            text = {
                Column {
                    OutlinedTextField(
                        value         = nameInput,
                        onValueChange = { nameInput = it },
                        label         = { Text("Name") },
                        singleLine    = true,
                        isError       = renameError != null,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    if (renameError != null) {
                        Text(
                            text     = renameError!!,
                            color    = MaterialTheme.colorScheme.error,
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick  = {
                        viewModel.renamePlaylist(nameInput) { result ->
                            when (result) {
                                is PlaylistOperationResult.Success -> {
                                    showRename = false; renameError = null
                                }
                                PlaylistOperationResult.BlankName ->
                                    renameError = "Name cannot be blank."
                                PlaylistOperationResult.DuplicateName ->
                                    renameError = "A playlist with that name already exists."
                            }
                        }
                    },
                    enabled  = nameInput.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false; renameError = null }) { Text("Cancel") }
            },
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────────
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title   = { Text("Delete playlist") },
            text    = { Text("Delete \"$playlistName\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist { onNavigateBack() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlaylistHeader(
    playlistName: String,
    songCount: Int,
    artworkUris: List<String>,
) {
    Column(
        modifier            = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlaylistArtworkCollage(
            artworkUris        = artworkUris,
            contentDescription = "$playlistName artwork",
            modifier           = Modifier.size(132.dp),
        )
        Text(
            text     = playlistName,
            style    = MaterialTheme.typography.titleLarge,
            color    = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text  = "$songCount songs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun NoPlaylistSearchResults() {
    Text(
        text      = "No matching songs in this playlist. Try a different search or add more songs.",
        style     = MaterialTheme.typography.bodyLarge,
        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        textAlign = TextAlign.Center,
        modifier  = Modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
private fun PlaybackActions(
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier            = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick   = onPlayAll,
            modifier  = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.padding(end = 4.dp))
            Text("Play all")
        }
        FilledTonalButton(
            onClick   = onShufflePlay,
            modifier  = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Shuffle, null, modifier = Modifier.padding(end = 4.dp))
            Text("Shuffle")
        }
    }
}

@Composable
private fun EmptyContent(
    onAddSongsClick: () -> Unit,
    playlistName: String,
    artworkUris: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier         = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlaylistArtworkCollage(
                artworkUris        = artworkUris,
                contentDescription = "$playlistName artwork",
                modifier           = Modifier.padding(bottom = 16.dp).size(132.dp),
            )
            Text(
                text      = "No songs in this playlist yet.",
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Text(
                text      = "Add songs from your library to start building this playlist.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(top = 8.dp),
            )
            FilledTonalButton(
                onClick  = onAddSongsClick,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = 4.dp))
                Text("Add songs")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistSongRow(
    entry: PlaylistSongItem,
    isFirst: Boolean,
    isLast: Boolean,
    allowMove: Boolean,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = entry.song
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
                onLongClick   = onOpenDetails,
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
            artworkUri = ArtworkResolver.albumArtworkUri(song.albumId),
            contentDescription = "Album artwork for ${song.album}",
            placeholderIcon = Icons.Default.MusicNote,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(artworkSize),
        )
        SongText(
            song      = song,
            isCurrent = isCurrent,
            modifier  = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector        = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                tint               = if (isFavorite) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier           = Modifier.size(20.dp),
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Playlist song options")
            }
            DropdownMenu(
                expanded         = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text    = { Text("Play next") },
                    onClick = { menuExpanded = false; onPlayNext() },
                )
                DropdownMenuItem(
                    text    = { Text("Add to queue") },
                    onClick = { menuExpanded = false; onAddToQueue() },
                )
                DropdownMenuItem(
                    text    = { Text("Add to playlist") },
                    onClick = { menuExpanded = false; onAddToPlaylist() },
                )
                DropdownMenuItem(
                    text    = { Text("Track details") },
                    onClick = { menuExpanded = false; onOpenDetails() },
                )
                if (allowMove) {
                    DropdownMenuItem(
                        text    = { Text("Move up") },
                        enabled = !isFirst,
                        onClick = { menuExpanded = false; onMoveUp() },
                    )
                    DropdownMenuItem(
                        text    = { Text("Move down") },
                        enabled = !isLast,
                        onClick = { menuExpanded = false; onMoveDown() },
                    )
                }
                DropdownMenuItem(
                    text    = { Text("Remove from playlist") },
                    onClick = { menuExpanded = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun SongText(
    song: Song,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = song.title,
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
            text     = song.artist,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

package com.launchpoint.wavdrop.ui.screen.folders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.shareSong
import com.launchpoint.wavdrop.ui.components.LoadingStateContent
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.SongRowWithOverflow
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel
import com.launchpoint.wavdrop.ui.viewmodel.PlaylistActionsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: FolderDetailsViewModel = hiltViewModel(),
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text     = state.displayName,
                            style    = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text  = "${state.songs.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
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
                message = "Loading folder...",
                modifier = Modifier.padding(innerPadding),
            )
        } else if (state.songs.isEmpty()) {
            Box(
                modifier         = Modifier.padding(innerPadding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text  = "No songs found in this folder",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The folder may be empty, excluded, or no longer readable. Add audio files and rescan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier       = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
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

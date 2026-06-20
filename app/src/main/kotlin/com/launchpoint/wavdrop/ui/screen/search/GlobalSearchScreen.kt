package com.launchpoint.wavdrop.ui.screen.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.GroupedSearchContent
import com.launchpoint.wavdrop.ui.components.SearchTopAppBar
import com.launchpoint.wavdrop.ui.components.SongSearchActions
import com.launchpoint.wavdrop.ui.components.shareSong
import com.launchpoint.wavdrop.ui.viewmodel.PlaylistActionsViewModel
import kotlinx.coroutines.launch

@Composable
fun GlobalSearchRoute(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onFolderClick: (String) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
    playlistVm: PlaylistActionsViewModel = hiltViewModel(),
) {
    val searchQuery     by viewModel.searchQuery.collectAsStateWithLifecycle()
    val allSongs        by viewModel.allSongs.collectAsStateWithLifecycle()
    val nowPlaying      by viewModel.nowPlayingState.collectAsStateWithLifecycle()
    val favoriteSongIds by viewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val playlists        by playlistVm.playlists.collectAsStateWithLifecycle()
    val allPlaylistSongs by playlistVm.allPlaylistSongs.collectAsStateWithLifecycle()

    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }

    BackHandler(onBack = onNavigateBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SearchTopAppBar(
                query         = searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                onClose       = onNavigateBack,
                placeholder   = "Search songs, artists, albums, playlists, folders…",
            )
        },
    ) { innerPadding ->
        GroupedSearchContent(
            songs  = allSongs,
            query  = searchQuery.trim(),
            songActions = SongSearchActions(
                currentSongId    = nowPlaying.song?.id,
                favoriteSongIds  = favoriteSongIds,
                onSongClick      = viewModel::playSearchResult,
                onPlayNext       = viewModel::playNext,
                onAddToQueue     = viewModel::addToQueue,
                onToggleFavorite = { song, wasFavorite ->
                    viewModel.toggleFavorite(song.id)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            if (wasFavorite) "Removed from Favorites" else "Added to Favorites",
                        )
                    }
                },
                onAddToPlaylist  = { song -> addToPlaylistSong = song },
                onTrackDetailsClick = onTrackDetailsClick,
                onFolderClick    = onFolderClick,
                onShare          = { song ->
                    shareSong(context, song) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Could not share this track")
                        }
                    }
                },
            ),
            onAlbumClick  = onAlbumClick,
            onArtistClick = onArtistClick,
            onFolderClick = onFolderClick,
            playlists = playlists,
            onPlaylistClick = onPlaylistClick,
            modifier      = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
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
                playlistVm.createPlaylistAndAddSong(name, song.id) { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.singleSongMessage())
                    }
                }
                addToPlaylistSong = null
            },
            onDismiss           = { addToPlaylistSong = null },
        )
    }
}

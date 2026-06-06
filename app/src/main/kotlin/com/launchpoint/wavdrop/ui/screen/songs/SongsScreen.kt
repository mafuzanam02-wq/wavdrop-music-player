package com.launchpoint.wavdrop.ui.screen.songs

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.search.AlphabetIndex
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.AlphabetSideIndex
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar
import com.launchpoint.wavdrop.ui.components.SearchTopAppBar
import com.launchpoint.wavdrop.ui.components.SongRowWithOverflow
import com.launchpoint.wavdrop.ui.permission.AudioPermissionGate
import com.launchpoint.wavdrop.ui.screen.home.HomeUiState
import com.launchpoint.wavdrop.ui.screen.home.HomeViewModel
import com.launchpoint.wavdrop.ui.viewmodel.PlaylistActionsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(
    onNavigateBack: () -> Unit,
    onHomeClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onFolderClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    playlistVm: PlaylistActionsViewModel = hiltViewModel(),
) {
    val uiState          by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying       by viewModel.nowPlayingState.collectAsStateWithLifecycle()
    val favoriteSongIds  by viewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val searchQuery      by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isRefreshing     by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val playlists        by playlistVm.playlists.collectAsStateWithLifecycle()
    val allPlaylistSongs by playlistVm.allPlaylistSongs.collectAsStateWithLifecycle()
    var isSearchActive   by remember { mutableStateOf(false) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope    = rememberCoroutineScope()

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                SearchTopAppBar(
                    query         = searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClose       = { isSearchActive = false; viewModel.setSearchQuery("") },
                    placeholder   = "Search songs...",
                )
            } else {
                TopAppBar(
                    title = { Text("Songs") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
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
            androidx.compose.foundation.layout.Column {
                MiniPlayer(
                    nowPlaying          = nowPlaying,
                    onOpenNowPlaying    = onNowPlayingClick,
                    onTogglePlayPause   = viewModel::togglePlayPause,
                    onPrevious          = viewModel::skipToPrevious,
                    onNext              = viewModel::skipToNext,
                    onToggleShuffle     = viewModel::toggleShuffle,
                    onCycleRepeatMode   = viewModel::cycleRepeatMode,
                    applyNavigationBarsPadding = false,
                )
                PrimaryNavigationBar(
                    selected        = PrimaryDestination.SONGS,
                    onHomeClick     = onHomeClick,
                    onSongsClick    = {},
                    onLibraryClick  = onLibraryClick,
                    onSettingsClick = onSettingsClick,
                )
            }
        },
    ) { innerPadding ->
        AudioPermissionGate(
            onPermissionGranted = viewModel::syncIfNeeded,
            modifier = Modifier.padding(innerPadding),
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh    = viewModel::refreshLibrary,
                modifier     = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                when (val state = uiState) {
                    HomeUiState.Loading -> LoadingSongs()
                    HomeUiState.Empty   -> EmptySongs()
                    is HomeUiState.Songs -> SongListContent(
                        songs             = state.songs,
                        showAlphabetIndex = !isSearchActive,
                        currentSongId     = nowPlaying.song?.id,
                        favoriteSongIds   = favoriteSongIds,
                        onSongClick       = viewModel::playSongFromLibraryQueue,
                        onShuffleAll      = viewModel::shuffleAll,
                        onPlayNext        = viewModel::playNext,
                        onAddToQueue      = viewModel::addToQueue,
                        onToggleFavorite  = { song, wasFavorite ->
                            viewModel.toggleFavorite(song.id)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (wasFavorite) "Removed from Favorites" else "Added to Favorites",
                                )
                            }
                        },
                        onAddToPlaylist   = { song -> addToPlaylistSong = song },
                        onTrackDetailsClick = onTrackDetailsClick,
                        onFolderClick     = onFolderClick,
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

@Composable
private fun SongListContent(
    songs: List<Song>,
    showAlphabetIndex: Boolean = true,
    currentSongId: Long?,
    favoriteSongIds: Set<Long>,
    onSongClick: (Song) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onToggleFavorite: (Song, Boolean) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateText(
                title = "No matching songs",
                message = "Try a different search, or add audio files that match this query.",
            )
        }
        return
    }
    val listState      = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val songsRef = rememberUpdatedState(songs)
    val currentLetter: Char? by remember {
        derivedStateOf {
            val items   = songsRef.value
            val songIdx = listState.firstVisibleItemIndex - 1
            if (songIdx < 0) null
            else {
                val ch = items.getOrNull(songIdx)?.title?.trim()?.firstOrNull()?.uppercaseChar()
                when {
                    ch == null    -> null
                    ch in 'A'..'Z' -> ch
                    else          -> '#'
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state          = listState,
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 32.dp, bottom = 4.dp),
        ) {
            item(key = "shuffle_header") {
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onShuffleAll) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).padding(end = 2.dp),
                        )
                        Text(
                            text     = "Shuffle ${songs.size} songs",
                            style    = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
            items(songs, key = { it.id }) { song ->
                val isFavorite = song.id in favoriteSongIds
                SongRowWithOverflow(
                    song             = song,
                    isCurrent        = song.id == currentSongId,
                    isFavorite       = isFavorite,
                    onPlay           = { onSongClick(song) },
                    onPlayNext       = { onPlayNext(song) },
                    onAddToQueue     = { onAddToQueue(song) },
                    onToggleFavorite = { onToggleFavorite(song, isFavorite) },
                    onAddToPlaylist  = { onAddToPlaylist(song) },
                    onTrackDetails   = { onTrackDetailsClick(song.id) },
                    onViewFolder     = song.validFolderKey()?.let { key -> { onFolderClick(key) } },
                    modifier         = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
        }
        if (showAlphabetIndex) {
            AlphabetSideIndex(
                activeLetter    = currentLetter,
                listState       = listState,
                autoHide        = true,
                onLetterSelected = { letter ->
                    AlphabetIndex.firstIndexForSongLetter(songs, letter)?.let { index ->
                        coroutineScope.launch { listState.scrollToItem(index + 1) }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private fun Song.validFolderKey(): String? {
    val hasFolderMetadata = !folderPath
        ?.trim()
        ?.trim('/', '\\')
        .isNullOrBlank()
    if (!hasFolderMetadata) return null
    return FolderGrouper.folderKey(this)
        .takeUnless { it == FolderGrouper.UNKNOWN_FOLDER }
        ?.takeIf { it.isNotBlank() }
}

@Composable
private fun LoadingSongs(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = "Loading songs...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptySongs(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyStateText(
            title = "No music found",
            message = "Add audio files to your device, then pull to refresh your library.",
        )
    }
}

@Composable
private fun EmptyStateText(
    title: String,
    message: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

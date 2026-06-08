package com.launchpoint.wavdrop.ui.screen.songs

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.search.AlphabetIndex
import com.launchpoint.wavdrop.data.search.LibrarySearch
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.shareSong
import com.launchpoint.wavdrop.ui.components.AlphabetSideIndex
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
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
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    playlistVm: PlaylistActionsViewModel = hiltViewModel(),
) {
    val uiState          by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying       by viewModel.nowPlayingState.collectAsStateWithLifecycle()
    val favoriteSongIds  by viewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val searchQuery      by viewModel.searchQuery.collectAsStateWithLifecycle()
    val librarySongs     by viewModel.librarySongs.collectAsStateWithLifecycle()
    val isRefreshing     by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val playlists        by playlistVm.playlists.collectAsStateWithLifecycle()
    val allPlaylistSongs by playlistVm.allPlaylistSongs.collectAsStateWithLifecycle()
    var isSearchActive   by remember { mutableStateOf(false) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope    = rememberCoroutineScope()
    val context           = LocalContext.current

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
                    is HomeUiState.Songs -> {
                        val trimmedQuery = searchQuery.trim()
                        val commonSongActions = SongResultActions(
                            currentSongId = nowPlaying.song?.id,
                            favoriteSongIds = favoriteSongIds,
                            onSongClick = viewModel::playSongFromLibraryQueue,
                            onPlayNext = viewModel::playNext,
                            onAddToQueue = viewModel::addToQueue,
                            onToggleFavorite = { song, wasFavorite ->
                                viewModel.toggleFavorite(song.id)
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (wasFavorite) "Removed from Favorites" else "Added to Favorites",
                                    )
                                }
                            },
                            onAddToPlaylist = { song -> addToPlaylistSong = song },
                            onTrackDetailsClick = onTrackDetailsClick,
                            onFolderClick = onFolderClick,
                            onShare = { song ->
                                shareSong(context, song) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Could not share this track")
                                    }
                                }
                            },
                        )
                        if (isSearchActive && trimmedQuery.isNotEmpty()) {
                            GroupedSearchContent(
                                songs = librarySongs,
                                query = trimmedQuery,
                                songActions = commonSongActions,
                                onAlbumClick = onAlbumClick,
                                onArtistClick = onArtistClick,
                            )
                        } else {
                            SongListContent(
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
                                onShare           = { song ->
                                    shareSong(context, song) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Could not share this track")
                                        }
                                    }
                                },
                            )
                        }
                    }
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

private data class SongResultActions(
    val currentSongId: Long?,
    val favoriteSongIds: Set<Long>,
    val onSongClick: (Song) -> Unit,
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onToggleFavorite: (Song, Boolean) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onTrackDetailsClick: (Long) -> Unit,
    val onFolderClick: (String) -> Unit,
    val onShare: (Song) -> Unit,
)

private data class GroupedSearchResults(
    val songs: List<Song>,
    val artists: List<ArtistSummary>,
    val albums: List<AlbumSummary>,
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
}

@Composable
private fun GroupedSearchContent(
    songs: List<Song>,
    query: String,
    songActions: SongResultActions,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val results = remember(songs, query) { buildGroupedSearchResults(songs, query) }
    if (results.isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateText(
                title = "No results",
                message = "Try a song title, artist, or album name.",
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
    ) {
        if (results.songs.isNotEmpty()) {
            item(key = "songs_header") {
                SearchSectionHeader(title = "Songs", count = results.songs.size)
            }
            items(results.songs, key = { "song_${it.id}" }) { song ->
                SearchSongResultRow(
                    song = song,
                    query = query,
                    actions = songActions,
                    modifier = Modifier.fillMaxWidth(),
                )
                SearchDivider()
            }
        }
        if (results.artists.isNotEmpty()) {
            item(key = "artists_header") {
                SearchSectionHeader(title = "Artists", count = results.artists.size)
            }
            items(results.artists, key = { "artist_${it.artistKey}" }) { artist ->
                SearchArtistRow(
                    artist = artist,
                    query = query,
                    onClick = { onArtistClick(artist.artistKey) },
                )
                SearchDivider()
            }
        }
        if (results.albums.isNotEmpty()) {
            item(key = "albums_header") {
                SearchSectionHeader(title = "Albums", count = results.albums.size)
            }
            items(results.albums, key = { "album_${it.albumKey}" }) { album ->
                SearchAlbumRow(
                    album = album,
                    query = query,
                    onClick = { onAlbumClick(album.albumKey) },
                )
                SearchDivider()
            }
        }
    }
}

@Composable
private fun SearchSongResultRow(
    song: Song,
    query: String,
    actions: SongResultActions,
    modifier: Modifier = Modifier,
) {
    val isFavorite = song.id in actions.favoriteSongIds
    val highlightStyle = searchHighlightStyle()
    SongRowWithOverflow(
        song             = song,
        isCurrent        = song.id == actions.currentSongId,
        isFavorite       = isFavorite,
        onPlay           = { actions.onSongClick(song) },
        onPlayNext       = { actions.onPlayNext(song) },
        onAddToQueue     = { actions.onAddToQueue(song) },
        onToggleFavorite = { actions.onToggleFavorite(song, isFavorite) },
        onAddToPlaylist  = { actions.onAddToPlaylist(song) },
        onTrackDetails   = { actions.onTrackDetailsClick(song.id) },
        onViewFolder     = song.validFolderKey()?.let { key -> { actions.onFolderClick(key) } },
        onShare          = { actions.onShare(song) },
        modifier         = modifier,
        highlightedTitle = highlightQuery(song.title, query, highlightStyle),
        highlightedArtist = highlightQuery(song.artist, query, highlightStyle),
    )
}

@Composable
private fun SearchArtistRow(
    artist: ArtistSummary,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val artworkSize = if (compact) 40.dp else 44.dp
    val highlightStyle = searchHighlightStyle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtworkImage(
            artworkUri = artist.artworkUri,
            contentDescription = "Artist image for ${artist.artistKey}",
            placeholderIcon = Icons.Default.Person,
            modifier = Modifier.size(artworkSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightQuery(artist.artistKey, query, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artistSearchMeta(artist),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchAlbumRow(
    album: AlbumSummary,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val artworkSize = if (compact) 48.dp else 52.dp
    val highlightStyle = searchHighlightStyle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(album.albumId),
            contentDescription = "Album artwork for ${album.albumKey}",
            placeholderIcon = Icons.Default.Album,
            modifier = Modifier.size(artworkSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightQuery(album.albumKey, query, highlightStyle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = highlightQuery(album.artist, query, highlightStyle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${album.songCount} songs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "$title ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SearchDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun searchHighlightStyle(): SpanStyle =
    SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )

private fun buildGroupedSearchResults(
    songs: List<Song>,
    query: String,
): GroupedSearchResults {
    val trimmedQuery = query.trim()
    val queryLower = trimmedQuery.lowercase()
    if (queryLower.isEmpty()) {
        return GroupedSearchResults(
            songs = songs,
            artists = emptyList(),
            albums = emptyList(),
        )
    }
    return GroupedSearchResults(
        songs = LibrarySearch.filterSongs(songs, trimmedQuery),
        artists = ArtistGrouper.group(songs)
            .filter { it.artistKey.containsSearch(queryLower) }
            .take(24),
        albums = AlbumGrouper.group(songs)
            .filter { album ->
                album.albumKey.containsSearch(queryLower) ||
                    album.artist.containsSearch(queryLower)
            }
            .take(24),
    )
}

private fun String.containsSearch(queryLower: String): Boolean =
    lowercase().contains(queryLower)

private fun highlightQuery(
    text: String,
    query: String,
    highlightStyle: SpanStyle,
): AnnotatedString {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return AnnotatedString(text)

    val sourceLower = text.lowercase()
    val queryLower = trimmedQuery.lowercase()
    return buildAnnotatedString {
        var currentIndex = 0
        while (currentIndex < text.length) {
            val matchIndex = sourceLower.indexOf(queryLower, startIndex = currentIndex)
            if (matchIndex < 0) {
                append(text.substring(currentIndex))
                break
            }
            append(text.substring(currentIndex, matchIndex))
            withStyle(highlightStyle) {
                append(text.substring(matchIndex, matchIndex + trimmedQuery.length))
            }
            currentIndex = matchIndex + trimmedQuery.length
        }
    }
}

private fun artistSearchMeta(artist: ArtistSummary): String = buildString {
    append("${artist.songCount} songs")
    if (artist.albumCount > 0) append(" - ${artist.albumCount} albums")
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
    onShare: (Song) -> Unit,
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
                    onShare          = { onShare(song) },
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

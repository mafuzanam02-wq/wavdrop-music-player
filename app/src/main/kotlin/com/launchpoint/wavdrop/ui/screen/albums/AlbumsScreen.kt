package com.launchpoint.wavdrop.ui.screen.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.search.AlphabetIndex
import com.launchpoint.wavdrop.ui.components.AlphabetSideIndex
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.SearchTopAppBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    onNavigateBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val state       by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    var isSearchActive by remember { mutableStateOf(false) }

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
                    onClose       = {
                        isSearchActive = false
                        viewModel.setSearchQuery("")
                    },
                    placeholder   = "Search albums…",
                )
            } else {
                TopAppBar(
                    title = { Text("Albums") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector        = Icons.Default.Search,
                                contentDescription = "Search",
                                tint               = MaterialTheme.colorScheme.onSurface,
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
    ) { innerPadding ->
        when (val s = state) {
            AlbumsUiState.Loading  -> LoadingContent(Modifier.padding(innerPadding))
            AlbumsUiState.Empty    -> EmptyContent(Modifier.padding(innerPadding))
            is AlbumsUiState.Ready -> AlbumListContent(
                albums       = s.albums,
                onAlbumClick = onAlbumClick,
                modifier     = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AlbumListContent(
    albums: List<AlbumSummary>,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text  = "No results found.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        return
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state          = listState,
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 32.dp, bottom = 4.dp),
        ) {
            items(albums, key = { it.albumKey }) { album ->
                AlbumRow(album = album, onClick = { onAlbumClick(album.albumKey) })
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
        }
        AlphabetSideIndex(
            onLetterSelected = { letter ->
                AlphabetIndex.firstIndexForAlbumLetter(albums, letter)?.let { index ->
                    coroutineScope.launch { listState.animateScrollToItem(index) }
                }
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun AlbumRow(
    album: AlbumSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(album.albumId),
            contentDescription = "Album artwork for ${album.albumKey}",
            placeholderIcon = Icons.Default.Album,
            modifier = Modifier.size(52.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = album.albumKey,
                style    = MaterialTheme.typography.titleMedium,
                color    = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text     = album.artist,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = "${album.songCount} songs · ${formatTotalDuration(album.totalDurationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = "Loading albums…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = "No albums found",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

private fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    return if (totalMinutes < 60) {
        "$totalMinutes min"
    } else {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        if (m == 0L) "${h}h" else "${h}h ${m}m"
    }
}

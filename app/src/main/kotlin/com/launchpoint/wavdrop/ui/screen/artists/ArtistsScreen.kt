package com.launchpoint.wavdrop.ui.screen.artists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.search.AlphabetIndex
import com.launchpoint.wavdrop.ui.components.AlphabetSideIndex
import com.launchpoint.wavdrop.ui.components.SearchTopAppBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistsScreen(
    onNavigateBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    viewModel: ArtistsViewModel = hiltViewModel(),
) {
    val state          by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery    by viewModel.searchQuery.collectAsStateWithLifecycle()
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
                    placeholder   = "Search artists…",
                )
            } else {
                TopAppBar(
                    title = { Text("Artists") },
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
            ArtistsUiState.Loading  -> LoadingContent(Modifier.padding(innerPadding))
            ArtistsUiState.Empty    -> EmptyContent(Modifier.padding(innerPadding))
            is ArtistsUiState.Ready -> ArtistListContent(
                artists           = s.artists,
                showAlphabetIndex = !isSearchActive,
                onArtistClick     = onArtistClick,
                modifier          = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ArtistListContent(
    artists: List<ArtistSummary>,
    showAlphabetIndex: Boolean = true,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
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

    // No header item — firstVisibleItemIndex maps directly to artists[index].
    val artistsRef = rememberUpdatedState(artists)
    val currentLetter: Char? by remember {
        derivedStateOf {
            val ch = artistsRef.value.getOrNull(listState.firstVisibleItemIndex)
                ?.artistKey?.trim()?.firstOrNull()?.uppercaseChar()
            when {
                ch == null -> null
                ch in 'A'..'Z' -> ch
                else -> '#'
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state          = listState,
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 32.dp, bottom = 4.dp),
        ) {
            items(artists, key = { it.artistKey }) { artist ->
                ArtistRow(artist = artist, onClick = { onArtistClick(artist.artistKey) })
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
        }
        if (showAlphabetIndex) {
            AlphabetSideIndex(
                activeLetter = currentLetter,
                onLetterSelected = { letter ->
                    AlphabetIndex.firstIndexForArtistLetter(artists, letter)?.let { index ->
                        coroutineScope.launch { listState.scrollToItem(index) }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun ArtistRow(
    artist: ArtistSummary,
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
        Box(
            modifier         = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.Person,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = artist.artistKey,
                style    = MaterialTheme.typography.titleMedium,
                color    = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                append("${artist.songCount} songs")
                if (artist.albumCount > 0) append(" · ${artist.albumCount} albums")
                val dur = formatTotalDuration(artist.totalDurationMs)
                if (dur.isNotEmpty()) append(" · $dur")
            }
            Text(
                text  = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = "Loading artists…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = "No artists found",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

private fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    return when {
        totalMinutes == 0L -> ""
        totalMinutes < 60  -> "$totalMinutes min"
        else -> {
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
    }
}

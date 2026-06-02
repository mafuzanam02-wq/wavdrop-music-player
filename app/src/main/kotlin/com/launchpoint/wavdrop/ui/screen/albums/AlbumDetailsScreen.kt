package com.launchpoint.wavdrop.ui.screen.albums

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.SongRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    viewModel: AlbumDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text     = state.albumName,
                            style    = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.artist.isNotBlank()) {
                            Text(
                                text     = state.artist,
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier       = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            item {
                AlbumHeader(state = state)
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

@Composable
private fun AlbumHeader(
    state: AlbumDetailsUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(state.albumId),
            contentDescription = "Album artwork for ${state.albumName}",
            placeholderIcon = Icons.Default.Album,
            modifier = Modifier.size(112.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.albumName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.artist.isNotBlank()) {
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = "${state.songs.size} songs - ${formatTotalDuration(state.totalDurationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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

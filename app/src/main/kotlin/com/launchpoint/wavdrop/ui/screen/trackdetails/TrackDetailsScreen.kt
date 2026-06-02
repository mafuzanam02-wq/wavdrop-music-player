package com.launchpoint.wavdrop.ui.screen.trackdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.lyrics.LyricsResult
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.TrackStats
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.screen.lyrics.LyricsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsScreen(
    onNavigateBack: () -> Unit,
    viewModel: TrackDetailsViewModel = hiltViewModel(),
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
) {
    val uiState           by viewModel.uiState.collectAsStateWithLifecycle()
    val lyricsState       by lyricsViewModel.lyricsState.collectAsStateWithLifecycle()
    val playlists         by viewModel.playlists.collectAsStateWithLifecycle()
    var showAddToPlaylist by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    val ready = uiState as? TrackDetailsUiState.Ready
                    if (ready != null) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector        = if (ready.isFavorite) Icons.Default.Favorite
                                                     else Icons.Default.FavoriteBorder,
                                contentDescription = if (ready.isFavorite) "Unfavorite" else "Favorite",
                                tint               = if (ready.isFavorite) MaterialTheme.colorScheme.primary
                                                     else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            TrackDetailsUiState.Loading  -> LoadingContent(Modifier.padding(innerPadding))
            TrackDetailsUiState.NotFound -> NotFoundContent(Modifier.padding(innerPadding))
            is TrackDetailsUiState.Ready -> ReadyContent(
                song              = state.song,
                stats             = state.stats,
                lyrics            = lyricsState,
                onAddToPlaylist   = { showAddToPlaylist = true },
                modifier          = Modifier.padding(innerPadding),
            )
        }
    }

    if (showAddToPlaylist) {
        AddToPlaylistDialog(
            playlists        = playlists,
            onSelectPlaylist = { id ->
                viewModel.addToPlaylist(id)
                showAddToPlaylist = false
            },
            onCreateAndAdd   = { name ->
                viewModel.createPlaylistAndAdd(name)
                showAddToPlaylist = false
            },
            onDismiss        = { showAddToPlaylist = false },
        )
    }
}

// ── Loading ───────────────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

// ── Not found ─────────────────────────────────────────────────────────────────

@Composable
private fun NotFoundContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text      = "Track not found.",
            style     = MaterialTheme.typography.bodyLarge,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

// ── Ready ─────────────────────────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    song: Song,
    stats: TrackStats?,
    lyrics: LyricsResult,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // ── Track section ────────────────────────────────────────────────────
        SectionHeader("Track")

        DetailRow("Title",    song.title)
        DetailRow("Artist",   song.artist.ifBlank { "Unknown" })
        DetailRow("Album",    song.album.ifBlank { "Unknown" })
        DetailRow("Duration", TrackDetailsFormatters.formatDuration(song.duration))
        if (song.year > 0) DetailRow("Year", song.year.toString())

        SectionDivider()

        // ── Statistics section ───────────────────────────────────────────────
        SectionHeader("Statistics")

        DetailRow("Play count",     (stats?.playCount ?: 0).toString())
        DetailRow("Skip count",     (stats?.skipCount ?: 0).toString())
        DetailRow("Last played",    TrackDetailsFormatters.formatLastPlayed(stats?.lastPlayedAt ?: 0L))
        DetailRow("Total listened", TrackDetailsFormatters.formatListeningTime(stats?.totalListeningTimeMs ?: 0L))

        SectionDivider()

        // ── File section ─────────────────────────────────────────────────────
        SectionHeader("File")

        DetailRow("Song ID",  song.id.toString())
        DetailRow("Album ID", song.albumId.toString())
        UriRow("URI", song.uri)

        SectionDivider()

        // ── Lyrics section ───────────────────────────────────────────────────
        SectionHeader("Lyrics")
        LyricsSection(lyrics)

        SectionDivider()

        // ── Playlists section ────────────────────────────────────────────────
        SectionHeader("Playlists")
        TextButton(
            onClick  = onAddToPlaylist,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("Add to playlist") }

        SectionDivider()

        // ── Coming soon ──────────────────────────────────────────────────────
        SectionHeader("Coming soon")
        PlaceholderRow("Import history")
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(vertical = 4.dp),
        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text       = value,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color      = MaterialTheme.colorScheme.onSurface,
            modifier   = Modifier.weight(0.62f),
        )
    }
}

@Composable
private fun UriRow(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text     = value,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LyricsSection(result: LyricsResult) {
    when (result) {
        LyricsResult.Loading -> {
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        LyricsResult.NotFound -> {
            Text(
                text     = "No lyrics found for this track.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        is LyricsResult.Available -> {
            Text(
                text     = result.text,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        is LyricsResult.Error -> {
            Text(
                text     = "No lyrics found for this track.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PlaceholderRow(label: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = "Coming soon",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        )
    }
}

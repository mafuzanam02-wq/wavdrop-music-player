package com.launchpoint.wavdrop.ui.screen.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.R
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.ui.permission.AudioPermissionStatus
import com.launchpoint.wavdrop.ui.permission.audioPermission
import com.launchpoint.wavdrop.ui.permission.hasAudioPermission
import com.launchpoint.wavdrop.ui.permission.openAppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current

    // ── Permission state ──────────────────────────────────────────────────────
    var permissionStatus by remember {
        mutableStateOf(
            if (context.hasAudioPermission()) AudioPermissionStatus.Granted
            else AudioPermissionStatus.NotRequested,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        permissionStatus = if (isGranted) {
            AudioPermissionStatus.Granted
        } else {
            val activity = context as? androidx.activity.ComponentActivity
            if (activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission)
            ) {
                AudioPermissionStatus.Denied
            } else {
                AudioPermissionStatus.PermanentlyDenied
            }
        }
    }

    LaunchedEffect(permissionStatus) {
        if (permissionStatus == AudioPermissionStatus.Granted) viewModel.syncIfNeeded()
    }

    // ── Playback + library state ──────────────────────────────────────────────
    val uiState     by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying  by viewModel.nowPlayingState.collectAsStateWithLifecycle()

    // ── Layout ────────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NowPlayingBar(
                state            = nowPlaying,
                onTogglePlayPause = viewModel::togglePlayPause,
            )
        },
    ) { innerPadding ->
        when (permissionStatus) {
            AudioPermissionStatus.Granted ->
                LibraryContent(
                    uiState     = uiState,
                    onSongClick = viewModel::playSong,
                    modifier    = Modifier.padding(innerPadding),
                )

            AudioPermissionStatus.NotRequested ->
                PermissionRationaleContent(
                    modifier            = Modifier.padding(innerPadding),
                    onRequestPermission = { permissionLauncher.launch(audioPermission) },
                )

            AudioPermissionStatus.Denied ->
                PermissionDeniedContent(
                    modifier = Modifier.padding(innerPadding),
                    onRetry  = { permissionLauncher.launch(audioPermission) },
                )

            AudioPermissionStatus.PermanentlyDenied ->
                PermissionPermanentlyDeniedContent(
                    modifier       = Modifier.padding(innerPadding),
                    onOpenSettings = { context.openAppSettings() },
                )
        }
    }
}

// ── Now Playing mini-player ───────────────────────────────────────────────────

@Composable
private fun NowPlayingBar(
    state: NowPlayingState,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.song == null) return

    Surface(
        modifier      = modifier.fillMaxWidth(),
        color         = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Album art placeholder
            Box(
                modifier          = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment  = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(22.dp),
                )
            }

            // Title + artist
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text     = state.song.title,
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text     = state.song.artist,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Play / pause
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector        = if (state.isPlaying) Icons.Default.Pause
                                         else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint               = MaterialTheme.colorScheme.onSurface,
                    modifier           = Modifier.size(32.dp),
                )
            }
        }
    }
}

// ── Permission screens ────────────────────────────────────────────────────────

@Composable
private fun PermissionRationaleContent(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredColumn(modifier) {
        Icon(
            imageVector        = Icons.Default.LibraryMusic,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text      = "Access your music",
            style     = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text      = "Wavdrop needs permission to read your audio files so it can build your local library.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRequestPermission) {
            Text("Allow music access")
        }
    }
}

@Composable
private fun PermissionDeniedContent(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredColumn(modifier) {
        Icon(
            imageVector        = Icons.Default.Lock,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.error,
            modifier           = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text      = "Permission required",
            style     = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text      = "Storage access is needed to read your music files. No data leaves your device.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry) {
            Text("Allow music access")
        }
    }
}

@Composable
private fun PermissionPermanentlyDeniedContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredColumn(modifier) {
        Icon(
            imageVector        = Icons.Default.FolderOff,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier           = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text      = "Permission blocked",
            style     = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text      = "You've permanently declined storage access. Open Settings and grant the permission manually.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text("Open Settings")
        }
    }
}

// ── Library content ───────────────────────────────────────────────────────────

@Composable
private fun LibraryContent(
    uiState: HomeUiState,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        HomeUiState.Loading  -> ScanningContent(modifier)
        HomeUiState.Empty    -> EmptyLibraryContent(modifier)
        is HomeUiState.Songs -> SongListContent(uiState.songs, onSongClick, modifier)
    }
}

@Composable
private fun ScanningContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = "Scanning library…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptyLibraryContent(modifier: Modifier = Modifier) {
    CenteredColumn(modifier) {
        Icon(
            imageVector        = Icons.Default.LibraryMusic,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier           = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text      = "No music found",
            style     = MaterialTheme.typography.titleMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text      = "Add audio files to your device to see them here.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@Composable
private fun SongListContent(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier       = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(songs, key = { it.id }) { song ->
            SongRow(song = song, onClick = { onSongClick(song) })
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
            )
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text     = song.title,
            style    = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

// ── Shared layout helper ──────────────────────────────────────────────────────

@Composable
private fun CenteredColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier              = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally,
        content               = { content() },
    )
}

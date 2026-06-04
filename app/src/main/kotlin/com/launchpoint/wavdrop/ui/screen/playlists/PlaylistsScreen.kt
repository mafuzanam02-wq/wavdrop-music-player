package com.launchpoint.wavdrop.ui.screen.playlists

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.ui.components.LoadingStateContent
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.PlaylistArtworkCollage
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onNavigateBack: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: PlaylistsViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val playlists = state.playlists
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget     by remember { mutableStateOf<PlaylistListItem?>(null) }
    var deleteTarget     by remember { mutableStateOf<PlaylistListItem?>(null) }
    var errorMessage     by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
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
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create playlist")
            }
        },
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
                message = "Loading playlists...",
                modifier = Modifier.padding(innerPadding),
            )
        } else if (playlists.isEmpty()) {
            EmptyPlaylistsContent(Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier      = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(playlists, key = { it.playlist.id }) { item ->
                    PlaylistRow(
                        item      = item,
                        onClick   = { onPlaylistClick(item.playlist.id) },
                        onRename  = { renameTarget = item },
                        onDelete  = { deleteTarget = item },
                    )
                    HorizontalDivider(
                        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }

    // ── Create dialog ─────────────────────────────────────────────────────────
    if (showCreateDialog) {
        PlaylistNameDialog(
            title         = "New playlist",
            initialName   = "",
            confirmLabel  = "Create",
            onConfirm     = { name ->
                viewModel.createPlaylist(name) { result ->
                    when (result) {
                        is PlaylistOperationResult.Success -> { showCreateDialog = false; errorMessage = null }
                        PlaylistOperationResult.BlankName  -> errorMessage = "Name cannot be blank."
                        PlaylistOperationResult.DuplicateName -> errorMessage = "A playlist with that name already exists."
                    }
                }
            },
            onDismiss     = { showCreateDialog = false; errorMessage = null },
            errorMessage  = errorMessage,
        )
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────
    renameTarget?.let { target ->
        PlaylistNameDialog(
            title        = "Rename playlist",
            initialName  = target.playlist.name,
            confirmLabel = "Rename",
            onConfirm    = { name ->
                viewModel.renamePlaylist(target.playlist.id, name) { result ->
                    when (result) {
                        is PlaylistOperationResult.Success -> { renameTarget = null; errorMessage = null }
                        PlaylistOperationResult.BlankName  -> errorMessage = "Name cannot be blank."
                        PlaylistOperationResult.DuplicateName -> errorMessage = "A playlist with that name already exists."
                    }
                }
            },
            onDismiss    = { renameTarget = null; errorMessage = null },
            errorMessage = errorMessage,
        )
    }

    // ── Delete confirmation ───────────────────────────────────────────────────
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title   = { Text("Delete playlist") },
            text    = { Text("Delete \"${target.playlist.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(target.playlist.id)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

// ── Playlist row ──────────────────────────────────────────────────────────────

@Composable
private fun PlaylistRow(
    item: PlaylistListItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val playlist = item.playlist
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 9.dp else 12.dp
    val artworkSize = if (compact) 48.dp else 52.dp

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistArtworkCollage(
            artworkUris         = item.artworkUris,
            contentDescription  = "${playlist.name} artwork",
            modifier            = Modifier
                .padding(end = 16.dp)
                .size(artworkSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = playlist.name,
                style    = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text  = "${playlist.songCount} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    text  = formatDate(playlist.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector        = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(
                expanded         = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text          = { Text("Rename") },
                    leadingIcon   = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                    onClick       = { menuExpanded = false; onRename() },
                )
                DropdownMenuItem(
                    text          = { Text("Delete") },
                    leadingIcon   = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick       = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

// ── Shared name dialog ────────────────────────────────────────────────────────

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String?,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name") },
                    singleLine    = true,
                    isError       = errorMessage != null,
                    modifier      = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        text     = errorMessage,
                        color    = MaterialTheme.colorScheme.error,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onConfirm(name) },
                enabled  = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyPlaylistsContent(modifier: Modifier = Modifier) {
    Box(
        modifier         = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text      = "No playlists yet.",
                style     = MaterialTheme.typography.titleMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Text(
                text      = "Tap + to create your first playlist.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))

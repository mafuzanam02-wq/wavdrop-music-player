package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.NowPlayingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: NowPlayingState,
    onDismiss: () -> Unit,
    onJumpToItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onPlayNext: (Int) -> Unit,
    onViewStats: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        QueueSheetContent(
            state = state,
            onJumpToItem = onJumpToItem,
            onRemoveItem = onRemoveItem,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onPlayNext = onPlayNext,
            onViewStats = onViewStats,
        )
    }
}

@Composable
private fun QueueSheetContent(
    state: NowPlayingState,
    onJumpToItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onPlayNext: (Int) -> Unit,
    onViewStats: (Long) -> Unit,
) {
    val currentSong = state.queue.getOrNull(state.currentIndex)
    val upNextSongs = if (state.currentIndex >= 0) {
        state.queue.drop(state.currentIndex + 1)
    } else {
        emptyList()
    }

    LazyColumn {
        item {
            Text(
                text = "Up Next",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        if (currentSong != null) {
            item {
                QueueSectionHeader(label = "Playing now")
            }
            item {
                QueueNowPlayingRow(song = currentSong)
            }
        }

        if (upNextSongs.isNotEmpty()) {
            item {
                QueueSectionHeader(
                    label = "Up next · ${upNextSongs.size}",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            itemsIndexed(
                items = upNextSongs,
                key = { _, song -> song.id },
            ) { index, song ->
                val playbackIndex = state.currentIndex + 1 + index
                val isFirstUpNext = index == 0
                val isLastUpNext = index == upNextSongs.size - 1
                QueueItemRow(
                    song = song,
                    isFirstUpNext = isFirstUpNext,
                    isLastUpNext = isLastUpNext,
                    onJump = { onJumpToItem(playbackIndex) },
                    onMoveUp = { onMoveUp(playbackIndex) },
                    onMoveDown = { onMoveDown(playbackIndex) },
                    onPlayNext = { onPlayNext(playbackIndex) },
                    onRemove = { onRemoveItem(playbackIndex) },
                    onViewStats = { onViewStats(song.id) },
                )
                if (!isLastUpNext) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        } else if (currentSong != null) {
            item {
                Text(
                    text = "Nothing up next",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QueueSectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun QueueNowPlayingRow(song: Song) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
                )
            }
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun QueueItemRow(
    song: Song,
    isFirstUpNext: Boolean,
    isLastUpNext: Boolean,
    onJump: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    onViewStats: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJump)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Queue item actions",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (!isFirstUpNext) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        onClick = { expanded = false; onMoveUp() },
                    )
                }
                if (!isLastUpNext) {
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        onClick = { expanded = false; onMoveDown() },
                    )
                }
                if (!isFirstUpNext) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        onClick = { expanded = false; onPlayNext() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove from queue") },
                    onClick = { expanded = false; onRemove() },
                )
                DropdownMenuItem(
                    text = { Text("View stats") },
                    onClick = { expanded = false; onViewStats() },
                )
            }
        }
    }
}

@Composable
fun QueueHandle(
    upNextCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "Open queue",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (upNextCount > 0) "Up Next · $upNextCount" else "Up Next",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

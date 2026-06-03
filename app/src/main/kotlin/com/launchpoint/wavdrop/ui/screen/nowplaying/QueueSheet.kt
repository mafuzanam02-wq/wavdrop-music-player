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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.LaunchedEffect
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
            onDismiss = onDismiss,
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
    onDismiss: () -> Unit,
    onJumpToItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onPlayNext: (Int) -> Unit,
    onViewStats: (Long) -> Unit,
) {
    val currentIndex = state.currentIndex
    val previousSongs = if (currentIndex > 0) state.queue.take(currentIndex) else emptyList()
    val currentSong = state.queue.getOrNull(currentIndex)
    val upNextSongs = if (currentIndex >= 0) state.queue.drop(currentIndex + 1) else emptyList()

    val listState = rememberLazyListState()

    // Scroll to the "Playing now" section header on open and when the current track changes.
    // LazyColumn item layout (0-based):
    //   0           "Previously played" header   } only when currentIndex > 0
    //   1..ci       previous song rows           }
    //   ci+1 or 0   "Playing now" header         <- scroll target
    //   ci+2 or 1   QueueNowPlayingRow
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            val target = if (currentIndex > 0) currentIndex + 1 else 0
            listState.scrollToItem(target)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        QueueSheetHeader(onDismiss = onDismiss)
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
        // ── Previously played ─────────────────────────────────────────────────
        if (previousSongs.isNotEmpty()) {
            item {
                QueueSectionHeader(label = "Previously played · ${previousSongs.size}")
            }
            itemsIndexed(
                items = previousSongs,
                // key = absolute queue index (0..currentIndex-1), always unique
                key = { index, _ -> index },
            ) { index, song ->
                QueuePreviousItemRow(
                    song = song,
                    onJump = { onJumpToItem(index) },
                    onViewStats = { onViewStats(song.id) },
                )
                if (index < previousSongs.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }

        // ── Playing now ───────────────────────────────────────────────────────
        if (currentSong != null) {
            item {
                QueueSectionHeader(
                    label = "Playing now",
                    modifier = Modifier.padding(top = if (previousSongs.isNotEmpty()) 8.dp else 0.dp),
                )
            }
            item {
                QueueNowPlayingRow(song = currentSong)
            }
        }

        // ── Up next ───────────────────────────────────────────────────────────
        if (upNextSongs.isNotEmpty()) {
            item {
                QueueSectionHeader(
                    label = "Up next · ${upNextSongs.size}",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            itemsIndexed(
                items = upNextSongs,
                // key = absolute queue index (currentIndex+1..), always unique
                key = { index, _ -> currentIndex + 1 + index },
            ) { index, song ->
                val playbackIndex = currentIndex + 1 + index
                val isFirstUpNext = index == 0
                val isLastUpNext = index == upNextSongs.lastIndex
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
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun QueueSheetHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Queue",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close queue",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
        }
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

// ── Playing-now row (highlighted) ─────────────────────────────────────────────

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

// ── Previously-played row (dimmed, limited actions) ───────────────────────────

@Composable
private fun QueuePreviousItemRow(
    song: Song,
    onJump: () -> Unit,
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
        // Spacer matches the 20 dp drag-handle icon width in QueueItemRow for visual alignment
        Spacer(Modifier.size(20.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Previous item actions",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = { expanded = false; onJump() },
                )
                DropdownMenuItem(
                    text = { Text("View stats") },
                    onClick = { expanded = false; onViewStats() },
                )
            }
        }
    }
}

// ── Up-next row (full actions) ────────────────────────────────────────────────

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

// ── Handle shown on Now Playing screen ───────────────────────────────────────

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

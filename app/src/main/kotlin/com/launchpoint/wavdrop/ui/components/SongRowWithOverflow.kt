package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.launchpoint.wavdrop.data.model.Song

/**
 * Song row with a "⋮" overflow menu containing the standard actions.
 *
 * Use this instead of bare [SongRow] wherever overflow actions are needed.
 * [onRemove] and [onViewFolder] are optional and only shown when non-null.
 */
@Composable
fun SongRowWithOverflow(
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onTrackDetails: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onViewFolder: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SongRow(
            song               = song,
            isCurrent          = isCurrent,
            isFavorite         = isFavorite,
            onClick            = onPlay,
            onToggleFavorite   = onToggleFavorite,
            onOpenDetails      = onTrackDetails,
            showFavoriteButton = false,
            onMoreClick        = { expanded = true },
            modifier           = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text    = { Text("Play") },
                onClick = { expanded = false; onPlay() },
            )
            DropdownMenuItem(
                text    = { Text("Play next") },
                onClick = { expanded = false; onPlayNext() },
            )
            DropdownMenuItem(
                text    = { Text("Add to queue") },
                onClick = { expanded = false; onAddToQueue() },
            )
            DropdownMenuItem(
                text    = { Text("Add to playlist") },
                onClick = { expanded = false; onAddToPlaylist() },
            )
            DropdownMenuItem(
                text    = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                onClick = { expanded = false; onToggleFavorite() },
            )
            DropdownMenuItem(
                text    = { Text("Track details") },
                onClick = { expanded = false; onTrackDetails() },
            )
            if (onShare != null) {
                DropdownMenuItem(
                    text    = { Text("Share") },
                    onClick = { expanded = false; onShare() },
                )
            }
            if (onRemove != null) {
                DropdownMenuItem(
                    text    = { Text("Remove") },
                    onClick = { expanded = false; onRemove() },
                )
            }
            if (onViewFolder != null) {
                DropdownMenuItem(
                    text    = { Text("View folder") },
                    onClick = { expanded = false; onViewFolder() },
                )
            }
        }
    }
}

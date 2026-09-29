package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
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
    onAlbumClick: ((String) -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlightedTitle: AnnotatedString? = null,
    highlightedArtist: AnnotatedString? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val albumKey = metadataNavigationKey(song.album, "Unknown Album")
    val artistKey = metadataNavigationKey(song.artist, "Unknown Artist")

    Box(modifier = modifier) {
        SongRow(
            song               = song,
            isCurrent          = isCurrent,
            isFavorite         = isFavorite,
            onClick            = onPlay,
            onToggleFavorite   = onToggleFavorite,
            onOpenDetails      = { expanded = true },
            showFavoriteButton = false,
            onMoreClick        = { expanded = true },
            modifier           = Modifier.fillMaxWidth(),
            highlightedTitle   = highlightedTitle,
            highlightedArtist  = highlightedArtist,
        )
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text    = { Text("Play") },
                onClick = { expanded = false; onPlay() },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            )
            DropdownMenuItem(
                text    = { Text("Play next") },
                onClick = { expanded = false; onPlayNext() },
                leadingIcon = { Icon(Icons.Default.SkipNext, contentDescription = null) },
            )
            DropdownMenuItem(
                text    = { Text("Add to queue") },
                onClick = { expanded = false; onAddToQueue() },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text    = { Text("Add to playlist") },
                onClick = { expanded = false; onAddToPlaylist() },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
            )
            DropdownMenuItem(
                text    = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                onClick = { expanded = false; onToggleFavorite() },
                leadingIcon = {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider()
            if (onArtistClick != null && artistKey != null) {
                DropdownMenuItem(
                    text    = { Text("Go to artist") },
                    onClick = { expanded = false; onArtistClick(artistKey) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                )
            }
            if (onAlbumClick != null && albumKey != null) {
                DropdownMenuItem(
                    text    = { Text("Go to album") },
                    onClick = { expanded = false; onAlbumClick(albumKey) },
                    leadingIcon = { Icon(Icons.Default.Album, contentDescription = null) },
                )
            }
            DropdownMenuItem(
                text    = { Text("Track details") },
                onClick = { expanded = false; onTrackDetails() },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            )
            if (onShare != null || onViewFolder != null || onRemove != null) HorizontalDivider()
            if (onShare != null) {
                DropdownMenuItem(
                    text    = { Text("Share") },
                    onClick = { expanded = false; onShare() },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                )
            }
            if (onViewFolder != null) {
                DropdownMenuItem(
                    text    = { Text("View folder") },
                    onClick = { expanded = false; onViewFolder() },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                )
            }
            if (onRemove != null) {
                DropdownMenuItem(
                    text    = { Text("Remove") },
                    onClick = { expanded = false; onRemove() },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                )
            }
        }
    }
}

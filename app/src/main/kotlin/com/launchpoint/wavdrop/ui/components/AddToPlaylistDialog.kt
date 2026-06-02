package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.data.model.PlaylistSummary

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistSummary>,
    onSelectPlaylist: (Long) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCreateField by remember { mutableStateOf(playlists.isEmpty()) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                if (!showCreateField && playlists.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(playlists) { playlist ->
                            Row(
                                modifier          = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPlaylist(playlist.id) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector        = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier           = Modifier.padding(end = 12.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text  = playlist.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text  = "${playlist.songCount} songs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .clickable { showCreateField = true }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Add,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.primary,
                            modifier           = Modifier.padding(end = 12.dp),
                        )
                        Text(
                            text  = "New playlist",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    if (playlists.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        label         = { Text("Playlist name") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (showCreateField || playlists.isEmpty()) {
                TextButton(
                    onClick  = { if (newName.isNotBlank()) onCreateAndAdd(newName) },
                    enabled  = newName.isNotBlank(),
                ) { Text("Create & add") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

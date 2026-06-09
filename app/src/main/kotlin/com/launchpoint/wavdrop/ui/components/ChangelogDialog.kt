package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val CHANGELOG_ITEMS = listOf(
    "Improved search with grouped results for songs, artists, and albums",
    "Long-press a song to quickly access actions and Track Details",
    "Track details now accessible from the Now Playing menu",
    "Improved backup and restore experience",
    "Backup preferences can now be restored from Wavdrop backups",
    "Improved backup file handling to help avoid duplicate backup files",
    "Improved reliability when importing or restoring listening statistics",
    "Improved import and restore behavior when using the same backup or import file more than once",
    "Various stability, reliability, and usability improvements",
)

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("What's new") },
        text             = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CHANGELOG_ITEMS.forEachIndexed { index, item ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Text(
                        text  = "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}

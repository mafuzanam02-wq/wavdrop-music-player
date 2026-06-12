package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private sealed interface ChangelogEntry {
    data class Section(val title: String) : ChangelogEntry
    data class Item(val text: String)     : ChangelogEntry
}

private val CHANGELOG_ENTRIES: List<ChangelogEntry> = listOf(
    ChangelogEntry.Section("Backup & Restore"),
    ChangelogEntry.Item("Added Backup Verification to check whether your backup can be restored."),
    ChangelogEntry.Item("Improved backup integrity checks to detect damaged files before restore."),
    ChangelogEntry.Item("Listening history, Monthly Reports, Wrapped, playlists, lyrics, favorites, and settings now restore more reliably."),
    ChangelogEntry.Item("Backups now preserve more app preferences and restore safely across reinstalls."),

    ChangelogEntry.Section("Library & Scanning"),
    ChangelogEntry.Item("Safer selected-folder scans: Wavdrop now keeps your library if folder access changes unexpectedly."),
    ChangelogEntry.Item("Improved scanning reliability for larger music libraries."),
    ChangelogEntry.Item("Playlist cleanup during rescans is more reliable."),

    ChangelogEntry.Section("Playback & Queue"),
    ChangelogEntry.Item("Added Play, Shuffle, Play next, and Add to queue actions for albums, artists, and folders."),
    ChangelogEntry.Item("Improved startup session restore so your last queue and position come back more consistently."),

    ChangelogEntry.Section("Search & Matching"),
    ChangelogEntry.Item("Improved search and matching for names with accents, apostrophes, underscores, casing differences, and common filename suffixes."),
    ChangelogEntry.Item("Display names are preserved exactly as your music files provide them."),

    ChangelogEntry.Section("Support & Polish"),
    ChangelogEntry.Item("Support emails now automatically include app version and device details."),
    ChangelogEntry.Item("Obsidian Black is now the default launcher icon."),
    ChangelogEntry.Item("General stability and error-handling improvements."),
)

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("What's new in Beta 3") },
        text             = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CHANGELOG_ENTRIES.forEachIndexed { index, entry ->
                    when (entry) {
                        is ChangelogEntry.Section -> {
                            if (index > 0) Spacer(Modifier.height(12.dp))
                            Text(
                                text     = entry.title,
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        is ChangelogEntry.Item -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text  = "· ${entry.text}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}

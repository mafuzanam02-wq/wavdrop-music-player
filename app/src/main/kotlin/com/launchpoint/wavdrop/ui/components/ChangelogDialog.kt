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
    data class Item(val text: String) : ChangelogEntry
}

private val BETA_7_ENTRIES: List<ChangelogEntry> = listOf(
    ChangelogEntry.Section("Navigation & Discoverability"),
    ChangelogEntry.Item("Smart Collections now appear in Global Search results."),
    ChangelogEntry.Item("Insights now has a Search icon for quick access to Global Search."),
    ChangelogEntry.Item("Settings is now accessible directly from the Insights screen."),
    ChangelogEntry.Item("Opening the app to Now Playing with an empty queue now falls back to Home safely."),

    ChangelogEntry.Section("Polish"),
    ChangelogEntry.Item("Monthly Reports no longer uses technical language in empty states."),
    ChangelogEntry.Item("Metadata separators and overflow menu descriptions are now consistent across all screens."),
    ChangelogEntry.Item("Empty-state button labels and backup screen wording clarified throughout."),
)

private val BETA_5_ENTRIES: List<ChangelogEntry> = listOf(
    ChangelogEntry.Section("Navigation & Insights"),
    ChangelogEntry.Item("Insights is now a main tab with quick listening stats."),
    ChangelogEntry.Item("Wrapped now supports Monthly and Yearly recaps."),
    ChangelogEntry.Item("Wrapped slides now show richer Top 3 rankings and Skip Habits."),

    ChangelogEntry.Section("Search & Discovery"),
    ChangelogEntry.Item("Global Search now finds playlists too."),
    ChangelogEntry.Item("Albums, Artists, and Playlists now have better sorting and discovery tools."),

    ChangelogEntry.Section("Settings"),
    ChangelogEntry.Item("Settings moved to the Home gear, with Wrapped Appearance controls in Settings."),
)

private val BETA_4_ENTRIES: List<ChangelogEntry> = listOf(
    ChangelogEntry.Section("Discover & Browse"),
    ChangelogEntry.Item("Global Search now finds songs, albums, artists, and folders across your library."),
    ChangelogEntry.Item("Folder browsing has improved sorting and search polish."),
    ChangelogEntry.Item("Empty-library guidance now provides clearer next steps and rescan actions."),

    ChangelogEntry.Section("Insights & Sharing"),
    ChangelogEntry.Item("Statistics now includes expanded listening insights, reports, and monthly recaps."),
    ChangelogEntry.Item("Wrapped cards can now be shared as images."),

    ChangelogEntry.Section("Playback & Lyrics"),
    ChangelogEntry.Item("Lyrics are easier to paste, edit, and manage from Now Playing and track details."),
    ChangelogEntry.Item("Sleep Timer now supports custom durations and a Home timer status chip."),
    ChangelogEntry.Item("Playback session restore is more reliable, with safer queue recovery and more accurate position resume."),

    ChangelogEntry.Section("Privacy & Reliability"),
    ChangelogEntry.Item("Clearer privacy and data-ownership information explains what Wavdrop stores locally."),
    ChangelogEntry.Item("Release hardening improves stability, recovery, and edge-case handling throughout the app."),
)

private val BETA_3_1_ENTRIES: List<ChangelogEntry> = listOf(
    ChangelogEntry.Section("Search Playback"),
    ChangelogEntry.Item("Added a Search result tap setting: Replace Queue keeps the existing behavior, while Preserve Queue plays the searched track and then continues the remaining queue."),
    ChangelogEntry.Item("Shuffle/session restore now preserves the effective playback order more reliably after app restarts."),

    ChangelogEntry.Section("Desktop Backup Import"),
    ChangelogEntry.Item("Added support for Wavdrop Desktop backup imports by matching songs with metadata instead of desktop song IDs."),
    ChangelogEntry.Item("Desktop stats merge safely: play counts and listening time use the higher value, last played keeps the latest timestamp, and favorites use true-wins merge."),
    ChangelogEntry.Item("Desktop/shared backup playlists translate song references to local Android song IDs before import."),
    ChangelogEntry.Item("Playlist imports are conservative and non-destructive: existing entries are kept, matched songs are appended, and repeated imports are idempotent."),

    ChangelogEntry.Section("Backup & Restore"),
    ChangelogEntry.Item("Added Backup Verification to check whether your backup can be restored."),
    ChangelogEntry.Item("Improved backup integrity checks to detect damaged files before restore."),
    ChangelogEntry.Item("Listening history, Monthly Reports, Wrapped, playlists, lyrics, favorites, and settings now restore more reliably."),
    ChangelogEntry.Item("Backups now preserve more app preferences and restore safely across reinstalls."),
    ChangelogEntry.Item("Added shared Wavdrop data contract docs for backup, import, export, and migration work."),

    ChangelogEntry.Section("Library & Scanning"),
    ChangelogEntry.Item("Safer selected-folder scans: Wavdrop now keeps your library if folder access changes unexpectedly."),
    ChangelogEntry.Item("Improved scanning reliability for larger music libraries."),
    ChangelogEntry.Item("Playlist cleanup during rescans is more reliable."),

    ChangelogEntry.Section("Playback & Queue"),
    ChangelogEntry.Item("Added Play, Shuffle, Play next, and Add to queue actions for albums, artists, and folders."),
    ChangelogEntry.Item("Improved startup session restore so your last queue and position come back more consistently."),

    ChangelogEntry.Section("Search & Matching"),
    ChangelogEntry.Item("Improved search and matching for names with accents, apostrophes, underscores, casing differences, and common filename suffixes."),
    ChangelogEntry.Item("Display names are preserved exactly as your music files provide them when valid metadata exists."),
    ChangelogEntry.Item("When metadata is missing or unknown, filename-like titles now display cleaner names without modifying audio files, tags, or filenames."),

    ChangelogEntry.Section("Support & Polish"),
    ChangelogEntry.Item("Support emails now automatically include app version and device details."),
    ChangelogEntry.Item("Obsidian Black is now the default launcher icon."),
    ChangelogEntry.Item("The no-lyrics overlay now always offers Search lyrics online as a shortcut to the existing external search action."),
    ChangelogEntry.Item("General stability and error-handling improvements."),
)

private fun fullBeta5Entries(): List<ChangelogEntry> = listOf(
    ChangelogEntry.Item("Moved Insights into the bottom navigation."),
    ChangelogEntry.Item("Moved Settings to the Home gear."),
    ChangelogEntry.Item("Added Insights summary cards for plays, listening time, and streaks."),
    ChangelogEntry.Item("Refreshed the Insights hub with destination cards."),
    ChangelogEntry.Item("Added Monthly and Yearly Wrapped scopes."),
    ChangelogEntry.Item("Updated Wrapped with Top 3 Tracks, Top 3 Artists, Top 3 Albums, and Skip Habits."),
    ChangelogEntry.Item("Moved Wrapped Appearance controls into Settings."),
    ChangelogEntry.Item("Added playlist-name results to Global Search."),
    ChangelogEntry.Item("Improved Global Search normalization for artists and albums."),
    ChangelogEntry.Item("Added search and sorting to Playlists."),
    ChangelogEntry.Item("Added sorting to Albums and Artists."),
    ChangelogEntry.Item("Added Album → Artist navigation from Album Details."),
    ChangelogEntry.Item("Improved search placeholders and Playlist Details search Back behavior."),
    ChangelogEntry.Item("Updated backup verification wording."),
)

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    ChangelogEntriesDialog(
        title = "What's new in Beta 7",
        entries = BETA_7_ENTRIES,
        onDismiss = onDismiss,
    )
}

@Composable
fun FullChangelogDialog(onDismiss: () -> Unit) {
    ChangelogEntriesDialog(
        title = "Wavdrop Changelog",
        entries = buildList {
            add(ChangelogEntry.Section("Beta 7 — Navigation, Discoverability & Polish"))
            addAll(BETA_7_ENTRIES)
            add(ChangelogEntry.Section("Beta 5 — Discovery, Insights, and Wrapped"))
            addAll(fullBeta5Entries())
            add(ChangelogEntry.Section("Beta 4"))
            addAll(BETA_4_ENTRIES)
            add(ChangelogEntry.Section("Beta 3.1"))
            addAll(BETA_3_1_ENTRIES)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ChangelogEntriesDialog(
    title: String,
    entries: List<ChangelogEntry>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                entries.forEachIndexed { index, entry ->
                    when (entry) {
                        is ChangelogEntry.Section -> {
                            if (index > 0) Spacer(Modifier.height(12.dp))
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        is ChangelogEntry.Item -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "- ${entry.text}",
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

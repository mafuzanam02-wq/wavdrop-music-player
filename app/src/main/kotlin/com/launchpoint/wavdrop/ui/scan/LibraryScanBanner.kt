package com.launchpoint.wavdrop.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** User-facing scan-status copy. */
object LibraryScanCopy {
    const val ERROR_TITLE = "Library scan couldn't complete"
    const val WARNING_TITLE = "Library not updated"
    const val RETRY = "Try again"
    const val LIBRARY_SETTINGS = "Library Settings"
    const val DISMISS = "Dismiss"
}

/**
 * Compact status over a still-usable library: a thin progress bar while scanning, and a dismissible warning/error with a
 * retry action. It renders nothing for Idle/Complete, so a successful rescan clears any earlier message.
 */
@Composable
fun LibraryScanStatus(
    state: LibraryScanUiState,
    onRetry: () -> Unit,
    onLibrarySettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LibraryScanUiState.Scanning -> LinearProgressIndicator(modifier = modifier.fillMaxWidth())
        is LibraryScanUiState.Warning -> LibraryScanMessageCard(
            title = LibraryScanCopy.WARNING_TITLE,
            message = state.message,
            onRetry = onRetry,
            onLibrarySettings = onLibrarySettings,
            onDismiss = onDismiss,
            modifier = modifier,
        )
        is LibraryScanUiState.Error -> LibraryScanMessageCard(
            title = LibraryScanCopy.ERROR_TITLE,
            message = state.message,
            onRetry = onRetry,
            onLibrarySettings = onLibrarySettings,
            onDismiss = onDismiss,
            modifier = modifier,
        )
        LibraryScanUiState.Idle, LibraryScanUiState.Complete -> Unit
    }
}

@Composable
private fun LibraryScanMessageCard(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onLibrarySettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(LibraryScanCopy.DISMISS) }
                TextButton(onClick = onLibrarySettings) { Text(LibraryScanCopy.LIBRARY_SETTINGS) }
                TextButton(onClick = onRetry) { Text(LibraryScanCopy.RETRY) }
            }
        }
    }
}
